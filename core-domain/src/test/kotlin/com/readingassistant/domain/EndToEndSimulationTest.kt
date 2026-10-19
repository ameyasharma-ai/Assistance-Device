package com.readingassistant.domain

import com.readingassistant.domain.ai.FakeAiScenario
import com.readingassistant.domain.ai.FakeStudyDocumentGenerator
import com.readingassistant.domain.camera.CameraStatus
import com.readingassistant.domain.camera.SimulatedCameraScenario
import com.readingassistant.domain.camera.SimulatedDocumentCameraController
import com.readingassistant.domain.feedback.SimulatedFeedbackController
import com.readingassistant.domain.input.ButtonEvent
import com.readingassistant.domain.input.InputPolicy
import com.readingassistant.domain.input.PhysicalButton
import com.readingassistant.domain.input.PressType
import com.readingassistant.domain.input.SimulatedInputSource
import com.readingassistant.domain.model.ErrorCode
import com.readingassistant.domain.model.PlaybackStatus
import com.readingassistant.domain.model.TactilePattern
import com.readingassistant.domain.playback.SimulatedPlaybackController
import com.readingassistant.domain.state.AssistantState
import com.readingassistant.domain.state.AssistantStateMachine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EndToEndSimulationTest {

    @Test
    fun `full end-to-end scan-to-speech journey using simulated adapters`() = runTest(UnconfinedTestDispatcher()) {
        val inputSource = SimulatedInputSource()
        val cameraController = SimulatedDocumentCameraController(backgroundScope)
        cameraController.autoGenerateFrames = false
        val feedbackController = SimulatedFeedbackController()
        val documentGenerator = FakeStudyDocumentGenerator()
        documentGenerator.simulatedDelayMs = 0L
        val playbackController = SimulatedPlaybackController(backgroundScope)
        playbackController.segmentDurationMs = 500L
        val stateMachine = AssistantStateMachine()

        val orchestrator = AssistantOrchestrator(
            stateMachine = stateMachine,
            inputPolicy = InputPolicy(debounceWindowMs = 0L),
            inputController = inputSource,
            cameraController = cameraController,
            feedbackController = feedbackController,
            documentGenerator = documentGenerator,
            playbackController = playbackController,
            scope = backgroundScope
        )
        orchestrator.start()

        // 1. Initial State is Idle
        assertEquals(AssistantState.Idle, orchestrator.state.value)

        // 2. User presses left foot button to start scanning
        orchestrator.dispatchButtonEvent(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, 1000L))
        assertTrue("Expected Scanning, but was ${orchestrator.state.value}", orchestrator.state.value is AssistantState.Scanning)
        assertEquals(CameraStatus.PREVIEWING, cameraController.status.value)
        assertTrue(feedbackController.recordedHaptics.contains(TactilePattern.SCANNER_ACTIVATED))

        // 3. Camera analyzes a blurry frame -> remains Scanning, no readiness vibration
        feedbackController.clearHistory()
        orchestrator.processFrame(cameraController.emitTestFrameSync(SimulatedCameraScenario.BLURRED_PAGE))
        assertTrue(orchestrator.state.value is AssistantState.Scanning)
        assertTrue(feedbackController.recordedHaptics.isEmpty())

        // 4. Camera analyzes a sharp page -> satisfies quality criteria -> transitions to DocumentReady
        orchestrator.processFrame(cameraController.emitTestFrameSync(SimulatedCameraScenario.SHARP_PAGE))
        assertTrue(orchestrator.state.value is AssistantState.DocumentReady)
        assertTrue(feedbackController.recordedHaptics.contains(TactilePattern.DOCUMENT_READY))

        // 5. User presses right foot button to capture
        orchestrator.dispatchButtonEvent(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, 2000L))

        // Verifies Shutter & Processing Started haptics
        assertTrue(feedbackController.recordedHaptics.contains(TactilePattern.CAPTURED))
        assertTrue(feedbackController.recordedHaptics.contains(TactilePattern.PROCESSING_STARTED))

        // Verifies document is generated and persisted -> enters DocumentReadyForPlayback
        val readyState = orchestrator.state.value as AssistantState.DocumentReadyForPlayback
        assertNotNull(readyState.document)
        assertEquals("Physics: Laws of Motion", readyState.document.title)
        assertTrue(feedbackController.recordedHaptics.contains(TactilePattern.PROCESSING_COMPLETED))

        // 6. User presses right foot button to start listening
        orchestrator.dispatchButtonEvent(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, 3000L))
        assertTrue(orchestrator.state.value is AssistantState.Playing)
        assertEquals(PlaybackStatus.PLAYING, playbackController.playbackStatus.value)

        // 7. User presses right foot button to pause
        orchestrator.dispatchButtonEvent(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, 4000L))
        assertTrue(orchestrator.state.value is AssistantState.Paused)
        assertEquals(PlaybackStatus.PAUSED, playbackController.playbackStatus.value)

        // 8. User presses right foot button to resume playback
        orchestrator.dispatchButtonEvent(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, 5000L))
        assertTrue(orchestrator.state.value is AssistantState.Playing)
        assertEquals(PlaybackStatus.PLAYING, playbackController.playbackStatus.value)

        orchestrator.stop()
    }

    @Test
    fun `camera disconnection during scan triggers error state and recovers safely`() = runTest(UnconfinedTestDispatcher()) {
        val inputSource = SimulatedInputSource()
        val cameraController = SimulatedDocumentCameraController(backgroundScope)
        cameraController.autoGenerateFrames = false
        cameraController.activeScenario = SimulatedCameraScenario.DISCONNECTED
        val feedbackController = SimulatedFeedbackController()
        val documentGenerator = FakeStudyDocumentGenerator()
        val playbackController = SimulatedPlaybackController(backgroundScope)
        val stateMachine = AssistantStateMachine()

        val orchestrator = AssistantOrchestrator(
            stateMachine = stateMachine,
            inputPolicy = InputPolicy(debounceWindowMs = 0L),
            inputController = inputSource,
            cameraController = cameraController,
            feedbackController = feedbackController,
            documentGenerator = documentGenerator,
            playbackController = playbackController,
            scope = backgroundScope
        )
        orchestrator.start()

        // User attempts to scan with missing camera
        orchestrator.dispatchButtonEvent(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, 1000L))

        // Enters Error state
        val errorState = orchestrator.state.value as AssistantState.Error
        assertEquals(ErrorCode.CAMERA_UNAVAILABLE, errorState.error.code)
        assertTrue(feedbackController.recordedHaptics.contains(TactilePattern.FATAL_ERROR))

        // Dismiss error with button click -> safely returns to Idle
        orchestrator.dispatchButtonEvent(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, 2000L))
        assertEquals(AssistantState.Idle, orchestrator.state.value)

        orchestrator.stop()
    }

    @Test
    fun `AI failure enters error state with audible prompt`() = runTest(UnconfinedTestDispatcher()) {
        val inputSource = SimulatedInputSource()
        val cameraController = SimulatedDocumentCameraController(backgroundScope)
        cameraController.autoGenerateFrames = false
        val feedbackController = SimulatedFeedbackController()
        val documentGenerator = FakeStudyDocumentGenerator()
        documentGenerator.activeScenario = FakeAiScenario.TIMEOUT
        documentGenerator.simulatedDelayMs = 0L
        val playbackController = SimulatedPlaybackController(backgroundScope)
        val stateMachine = AssistantStateMachine()

        val orchestrator = AssistantOrchestrator(
            stateMachine = stateMachine,
            inputPolicy = InputPolicy(debounceWindowMs = 0L),
            inputController = inputSource,
            cameraController = cameraController,
            feedbackController = feedbackController,
            documentGenerator = documentGenerator,
            playbackController = playbackController,
            scope = backgroundScope
        )
        orchestrator.start()

        orchestrator.dispatchButtonEvent(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, 1000L))
        orchestrator.processFrame(cameraController.emitTestFrameSync(SimulatedCameraScenario.SHARP_PAGE))
        orchestrator.dispatchButtonEvent(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, 2000L))

        // Enters Error state after AI timeout
        val errorState = orchestrator.state.value as AssistantState.Error
        assertEquals(ErrorCode.AI_MALFORMED_RESPONSE, errorState.error.code)
        assertTrue(feedbackController.recordedHaptics.contains(TactilePattern.FATAL_ERROR))

        orchestrator.stop()
    }
}
