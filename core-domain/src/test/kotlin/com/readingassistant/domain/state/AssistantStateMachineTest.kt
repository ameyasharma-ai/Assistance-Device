package com.readingassistant.domain.state

import com.readingassistant.domain.model.AssistantError
import com.readingassistant.domain.model.CaptureValidationResult
import com.readingassistant.domain.model.DocumentId
import com.readingassistant.domain.model.DocumentSection
import com.readingassistant.domain.model.ErrorCode
import com.readingassistant.domain.model.GenerationProgress
import com.readingassistant.domain.model.QualityIssue
import com.readingassistant.domain.model.QualityScore
import com.readingassistant.domain.model.ReadingPosition
import com.readingassistant.domain.model.ReadinessDecision
import com.readingassistant.domain.model.RetakeRecommendation
import com.readingassistant.domain.model.SectionType
import com.readingassistant.domain.model.SessionId
import com.readingassistant.domain.model.StudyDocument
import com.readingassistant.domain.model.TactilePattern
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantStateMachineTest {

    private lateinit var stateMachine: AssistantStateMachine
    private val testSessionId = SessionId("test-session-123")
    private val actionsList = mutableListOf<AssistantAction>()

    private val sampleQualityScore = QualityScore(
        sharpness = 0.9f,
        exposure = 0.85f,
        framingCompleteness = 0.95f,
        glareRatio = 0.05f,
        stability = 0.99f
    )

    private val sampleDocument = StudyDocument(
        id = DocumentId("doc-456"),
        title = "Physics Chapter 3",
        capturedImagePath = "/data/scans/page1.jpg",
        sections = listOf(
            DocumentSection("s1", "Transcription", SectionType.TRANSCRIPTION, "Newton's Laws", 0),
            DocumentSection("s2", "Summary", SectionType.SUMMARY, "Three laws of motion", 1)
        ),
        createdAtMs = 1700000000L
    )

    @Before
    fun setUp() {
        stateMachine = AssistantStateMachine(
            initialState = AssistantState.Idle,
            sessionIdGenerator = { testSessionId }
        )
        actionsList.clear()
    }

    @Test
    fun `initial state is Idle`() = runTest {
        assertEquals(AssistantState.Idle, stateMachine.state.value)
    }

    @Test
    fun `full happy path from Idle through Scan to Playback and Pause`() = runTest(UnconfinedTestDispatcher()) {
        val collectJob = launch {
            stateMachine.actions.toList(actionsList)
        }

        // 1. Idle -> StartScanRequested -> ScannerStarting
        stateMachine.dispatch(AssistantEvent.StartScanRequested)
        val s1 = stateMachine.state.value as AssistantState.ScannerStarting
        assertEquals(testSessionId, s1.sessionId)
        assertTrue(actionsList.any { it is AssistantAction.StartCamera })
        assertTrue(actionsList.any { it == AssistantAction.EmitHaptic(TactilePattern.SCANNER_ACTIVATED) })

        // 2. ScannerStarting -> CameraStarted -> Scanning
        stateMachine.dispatch(AssistantEvent.CameraStarted(testSessionId))
        val s2 = stateMachine.state.value as AssistantState.Scanning
        assertEquals(testSessionId, s2.sessionId)

        // 3. Scanning -> FrameAnalyzed(NotReady) -> remains Scanning (no vibration)
        actionsList.clear()
        val notReady = ReadinessDecision.NotReady(sampleQualityScore, QualityIssue.BLURRY)
        stateMachine.dispatch(AssistantEvent.FrameAnalyzed(notReady))
        assertTrue(stateMachine.state.value is AssistantState.Scanning)
        assertTrue(actionsList.none { it is AssistantAction.EmitHaptic })

        // 4. Scanning -> FrameAnalyzed(Ready) -> DocumentReady (triggers readiness vibration once)
        val ready = ReadinessDecision.Ready(sampleQualityScore)
        stateMachine.dispatch(AssistantEvent.FrameAnalyzed(ready))
        val s4 = stateMachine.state.value as AssistantState.DocumentReady
        assertEquals(testSessionId, s4.sessionId)
        assertTrue(actionsList.any { it == AssistantAction.EmitHaptic(TactilePattern.DOCUMENT_READY) })

        // Subsequent ready frame does NOT repeat readiness vibration
        actionsList.clear()
        stateMachine.dispatch(AssistantEvent.FrameAnalyzed(ready))
        assertTrue(actionsList.none { it == AssistantAction.EmitHaptic(TactilePattern.DOCUMENT_READY) })

        // 5. DocumentReady -> CaptureRequested -> Capturing
        stateMachine.dispatch(AssistantEvent.CaptureRequested)
        val s5 = stateMachine.state.value as AssistantState.Capturing
        assertEquals(testSessionId, s5.sessionId)
        assertTrue(actionsList.any { it == AssistantAction.EmitHaptic(TactilePattern.CAPTURED) })
        assertTrue(actionsList.any { it is AssistantAction.TriggerCapture })

        // 6. Capturing -> CaptureSucceeded -> auto advances to CaptureValidation
        actionsList.clear()
        stateMachine.dispatch(AssistantEvent.CaptureSucceeded("/path/image.jpg"))
        val s6 = stateMachine.state.value as AssistantState.CaptureValidation
        assertEquals("/path/image.jpg", s6.imagePath)
        assertTrue(actionsList.any { it is AssistantAction.StopCamera })
        assertTrue(actionsList.any { it is AssistantAction.ValidateCapture })

        // 7. CaptureValidation -> ValidationPassed -> Processing
        actionsList.clear()
        stateMachine.dispatch(AssistantEvent.ValidationPassed)
        val s7 = stateMachine.state.value as AssistantState.Processing
        assertEquals("/path/image.jpg", s7.imagePath)
        assertTrue(actionsList.any { it == AssistantAction.EmitHaptic(TactilePattern.PROCESSING_STARTED) })
        assertTrue(actionsList.any { it is AssistantAction.StartAiProcessing })

        // 8. Processing progress updates do not leave Processing state
        stateMachine.dispatch(AssistantEvent.ProcessingProgressUpdated(GenerationProgress(4, 2, "Definitions")))
        val s8 = stateMachine.state.value as AssistantState.Processing
        assertEquals(50, s8.progress?.percentComplete)

        // 9. Processing -> ProcessingSucceeded -> DocumentReadyForPlayback
        actionsList.clear()
        stateMachine.dispatch(AssistantEvent.ProcessingSucceeded(sampleDocument))
        val s9 = stateMachine.state.value as AssistantState.DocumentReadyForPlayback
        assertEquals(sampleDocument, s9.document)
        assertTrue(actionsList.any { it == AssistantAction.EmitHaptic(TactilePattern.PROCESSING_COMPLETED) })
        assertTrue(actionsList.any { it is AssistantAction.PersistDocument })

        // 10. DocumentReadyForPlayback -> PlayRequested -> Playing
        actionsList.clear()
        stateMachine.dispatch(AssistantEvent.PlayRequested)
        val s10 = stateMachine.state.value as AssistantState.Playing
        assertEquals(sampleDocument, s10.document)
        assertTrue(actionsList.any { it is AssistantAction.StartPlayback })

        // 11. Playing -> PauseRequested -> Paused
        actionsList.clear()
        stateMachine.dispatch(AssistantEvent.PauseRequested)
        val s11 = stateMachine.state.value as AssistantState.Paused
        assertEquals(sampleDocument, s11.document)
        assertTrue(actionsList.any { it is AssistantAction.PausePlayback })

        // 12. Paused -> PlayRequested -> Playing resumed
        actionsList.clear()
        stateMachine.dispatch(AssistantEvent.PlayRequested)
        assertTrue(stateMachine.state.value is AssistantState.Playing)
        assertTrue(actionsList.any { it is AssistantAction.StartPlayback })

        collectJob.cancel()
    }

    @Test
    fun `quality degradation from DocumentReady drops back to Scanning and re-arms`() = runTest(UnconfinedTestDispatcher()) {
        val collectJob = launch { stateMachine.actions.toList(actionsList) }

        stateMachine.dispatch(AssistantEvent.StartScanRequested)
        stateMachine.dispatch(AssistantEvent.CameraStarted(testSessionId))
        stateMachine.dispatch(AssistantEvent.FrameAnalyzed(ReadinessDecision.Ready(sampleQualityScore)))
        assertTrue(stateMachine.state.value is AssistantState.DocumentReady)

        // Degrade frame
        val blurry = ReadinessDecision.NotReady(sampleQualityScore, QualityIssue.BLURRY)
        stateMachine.dispatch(AssistantEvent.FrameAnalyzed(blurry))
        assertTrue(stateMachine.state.value is AssistantState.Scanning)

        // Re-readiness triggers vibration again!
        actionsList.clear()
        stateMachine.dispatch(AssistantEvent.FrameAnalyzed(ReadinessDecision.Ready(sampleQualityScore)))
        assertTrue(stateMachine.state.value is AssistantState.DocumentReady)
        assertTrue(actionsList.any { it == AssistantAction.EmitHaptic(TactilePattern.DOCUMENT_READY) })

        collectJob.cancel()
    }

    @Test
    fun `capture validation with sub-optimal quality enters AwaitingConfirmation and allows Retake`() = runTest(UnconfinedTestDispatcher()) {
        val collectJob = launch { stateMachine.actions.toList(actionsList) }

        stateMachine.dispatch(AssistantEvent.StartScanRequested)
        stateMachine.dispatch(AssistantEvent.CameraStarted(testSessionId))
        stateMachine.dispatch(AssistantEvent.CaptureRequested)
        stateMachine.dispatch(AssistantEvent.CaptureSucceeded("/path/suboptimal.jpg"))

        // Flag validation issue
        val validation = CaptureValidationResult.NeedsConfirmation(
            sampleQualityScore,
            RetakeRecommendation.RETAKE_BLUR,
            "Image lacks text sharpness"
        )
        stateMachine.dispatch(AssistantEvent.ValidationNeedsConfirmation(validation))
        assertTrue(stateMachine.state.value is AssistantState.AwaitingConfirmation)

        // Retake requested -> restarts camera with new session
        actionsList.clear()
        stateMachine.dispatch(AssistantEvent.RetakeRequested)
        assertTrue(stateMachine.state.value is AssistantState.ScannerStarting)
        assertTrue(actionsList.any { it is AssistantAction.StartCamera })

        collectJob.cancel()
    }

    @Test
    fun `capture validation with sub-optimal quality allows user to Confirm and proceed anyway`() = runTest(UnconfinedTestDispatcher()) {
        val collectJob = launch { stateMachine.actions.toList(actionsList) }

        stateMachine.dispatch(AssistantEvent.StartScanRequested)
        stateMachine.dispatch(AssistantEvent.CameraStarted(testSessionId))
        stateMachine.dispatch(AssistantEvent.CaptureRequested)
        stateMachine.dispatch(AssistantEvent.CaptureSucceeded("/path/dim.jpg"))

        val validation = CaptureValidationResult.NeedsConfirmation(
            sampleQualityScore,
            RetakeRecommendation.RETAKE_LIGHTING,
            "Lighting is dim"
        )
        stateMachine.dispatch(AssistantEvent.ValidationNeedsConfirmation(validation))
        assertEquals("AWAITING_CONFIRMATION", stateMachine.state.value.name)

        // User overrides and confirms processing
        stateMachine.dispatch(AssistantEvent.ConfirmProcessingRequested)
        assertTrue(stateMachine.state.value is AssistantState.Processing)
        assertTrue(actionsList.any { it is AssistantAction.StartAiProcessing })

        collectJob.cancel()
    }

    @Test
    fun `cancellation during scanning stops camera and returns to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val collectJob = launch { stateMachine.actions.toList(actionsList) }

        stateMachine.dispatch(AssistantEvent.StartScanRequested)
        stateMachine.dispatch(AssistantEvent.CameraStarted(testSessionId))
        assertTrue(stateMachine.state.value is AssistantState.Scanning)

        stateMachine.dispatch(AssistantEvent.CancelScanRequested)
        assertEquals(AssistantState.Idle, stateMachine.state.value)
        assertTrue(actionsList.any { it is AssistantAction.StopCamera })

        collectJob.cancel()
    }

    @Test
    fun `cancellation during processing cancels AI job and returns to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val collectJob = launch { stateMachine.actions.toList(actionsList) }

        stateMachine.dispatch(AssistantEvent.StartScanRequested)
        stateMachine.dispatch(AssistantEvent.CameraStarted(testSessionId))
        stateMachine.dispatch(AssistantEvent.CaptureRequested)
        stateMachine.dispatch(AssistantEvent.CaptureSucceeded("/path/img.jpg"))
        stateMachine.dispatch(AssistantEvent.ValidationPassed)
        assertTrue(stateMachine.state.value is AssistantState.Processing)

        // User cancels long processing
        stateMachine.dispatch(AssistantEvent.CancelScanRequested)
        assertEquals(AssistantState.Idle, stateMachine.state.value)
        assertTrue(actionsList.any { it is AssistantAction.CancelAiProcessing })

        collectJob.cancel()
    }

    @Test
    fun `invalid events in current state do not crash or alter state`() = runTest {
        // In Idle, PlayRequested or CaptureRequested is invalid
        stateMachine.dispatch(AssistantEvent.PlayRequested)
        assertEquals(AssistantState.Idle, stateMachine.state.value)

        stateMachine.dispatch(AssistantEvent.CaptureRequested)
        assertEquals(AssistantState.Idle, stateMachine.state.value)

        stateMachine.dispatch(AssistantEvent.ConfirmProcessingRequested)
        assertEquals(AssistantState.Idle, stateMachine.state.value)
    }

    @Test
    fun `error state preserves previous state and recovers to safe state upon dismissal`() = runTest(UnconfinedTestDispatcher()) {
        val collectJob = launch { stateMachine.actions.toList(actionsList) }

        // Start scanning
        stateMachine.dispatch(AssistantEvent.StartScanRequested)
        stateMachine.dispatch(AssistantEvent.CameraStarted(testSessionId))

        // Hardware error occurs
        val error = AssistantError(ErrorCode.CAMERA_DISCONNECTED, "Camera was disconnected")
        stateMachine.dispatch(AssistantEvent.CameraFailed(error))

        val errorState = stateMachine.state.value as AssistantState.Error
        assertEquals(error, errorState.error)
        assertTrue(errorState.previousState is AssistantState.Scanning)

        // Dismiss error returns to Idle safely
        stateMachine.dispatch(AssistantEvent.DismissErrorRequested)
        assertEquals(AssistantState.Idle, stateMachine.state.value)

        collectJob.cancel()
    }

    @Test
    fun `paragraph and section navigation updates reading position and emits haptic ticks`() = runTest(UnconfinedTestDispatcher()) {
        val collectJob = launch { stateMachine.actions.toList(actionsList) }

        // Enter playing state
        stateMachine.dispatch(AssistantEvent.StartScanRequested)
        stateMachine.dispatch(AssistantEvent.CameraStarted(testSessionId))
        stateMachine.dispatch(AssistantEvent.CaptureRequested)
        stateMachine.dispatch(AssistantEvent.CaptureSucceeded("/path/img.jpg"))
        stateMachine.dispatch(AssistantEvent.ValidationPassed)
        stateMachine.dispatch(AssistantEvent.ProcessingSucceeded(sampleDocument))
        stateMachine.dispatch(AssistantEvent.PlayRequested)

        val playing1 = stateMachine.state.value as AssistantState.Playing
        assertEquals(0, playing1.position.segmentIndex)

        // Next paragraph
        actionsList.clear()
        stateMachine.dispatch(AssistantEvent.NextParagraphRequested)
        val playing2 = stateMachine.state.value as AssistantState.Playing
        assertEquals(1, playing2.position.segmentIndex)
        assertTrue(actionsList.any { it == AssistantAction.EmitHaptic(TactilePattern.NAVIGATION_TICK) })
        assertTrue(actionsList.any { it is AssistantAction.SeekPlayback })

        // Previous paragraph
        actionsList.clear()
        stateMachine.dispatch(AssistantEvent.PreviousParagraphRequested)
        val playing3 = stateMachine.state.value as AssistantState.Playing
        assertEquals(0, playing3.position.segmentIndex)

        // Next section
        stateMachine.dispatch(AssistantEvent.NextSectionRequested)
        val playing4 = stateMachine.state.value as AssistantState.Playing
        assertEquals(1, playing4.position.sectionIndex)
        assertEquals(0, playing4.position.segmentIndex)

        collectJob.cancel()
    }
}
