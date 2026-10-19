package com.readingassistant.domain.input

import com.readingassistant.domain.model.AssistantError
import com.readingassistant.domain.model.CaptureValidationResult
import com.readingassistant.domain.model.DocumentId
import com.readingassistant.domain.model.ErrorCode
import com.readingassistant.domain.model.QualityScore
import com.readingassistant.domain.model.ReadingPosition
import com.readingassistant.domain.model.RetakeRecommendation
import com.readingassistant.domain.model.SessionId
import com.readingassistant.domain.model.StudyDocument
import com.readingassistant.domain.state.AssistantState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class InputPolicyTest {

    private lateinit var policy: InputPolicy

    private val dummySessionId = SessionId("test-session")
    private val dummyQualityScore = QualityScore(0.8f, 0.8f, 0.8f, 0.1f, 0.9f)
    private val dummyDocument = StudyDocument(
        id = DocumentId("doc-1"),
        title = "Test",
        capturedImagePath = "/test.jpg",
        sections = emptyList(),
        createdAtMs = 1000L
    )

    @Before
    fun setUp() {
        policy = InputPolicy(debounceWindowMs = 150L)
    }

    @Test
    fun `debounce discards rapid duplicate clicks within window on same button`() {
        val state = AssistantState.Idle
        val t0 = 1000L

        // First click allowed
        val cmd1 = policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, t0), state)
        assertEquals(ButtonCommand.StartScan, cmd1)

        // Second click 50ms later rejected by debounce
        val cmd2 = policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, t0 + 50L), state)
        assertEquals(ButtonCommand.None, cmd2)

        // Third click after debounce window passes
        val cmd3 = policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, t0 + 200L), state)
        assertEquals(ButtonCommand.StartScan, cmd3)
    }

    @Test
    fun `different physical buttons have independent debounce timers`() {
        val state = AssistantState.Scanning(dummySessionId)
        val t0 = 1000L

        // Left button press (Cancel)
        val cmdLeft = policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, t0), state)
        assertEquals(ButtonCommand.CancelScan, cmdLeft)

        // Right button press immediately after (Capture) - allowed because different button!
        val cmdRight = policy.evaluate(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, t0 + 20L), state)
        assertEquals(ButtonCommand.CaptureStill, cmdRight)
    }

    @Test
    fun `idle state only accepts left short press`() {
        val state = AssistantState.Idle

        assertEquals(ButtonCommand.StartScan, policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, 1000L), state))
        assertEquals(ButtonCommand.None, policy.evaluate(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, 1200L), state))
        assertEquals(ButtonCommand.None, policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.LONG_PRESS, 1400L), state))
    }

    @Test
    fun `scanning state maps left to cancel and right to capture`() {
        val state = AssistantState.Scanning(dummySessionId)

        assertEquals(ButtonCommand.CancelScan, policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, 1000L), state))
        assertEquals(ButtonCommand.CaptureStill, policy.evaluate(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, 1200L), state))
    }

    @Test
    fun `capturing and validation states ignore all button inputs`() {
        val capturing = AssistantState.Capturing(dummySessionId)
        assertEquals(ButtonCommand.None, policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, 1000L), capturing))
        assertEquals(ButtonCommand.None, policy.evaluate(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, 1200L), capturing))

        val validation = AssistantState.CaptureValidation(dummySessionId, "/test.jpg")
        assertEquals(ButtonCommand.None, policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, 1400L), validation))
        assertEquals(ButtonCommand.None, policy.evaluate(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, 1600L), validation))
    }

    @Test
    fun `awaiting confirmation maps left to retake and right to confirm`() {
        val confirmation = AssistantState.AwaitingConfirmation(
            dummySessionId,
            "/test.jpg",
            CaptureValidationResult.NeedsConfirmation(dummyQualityScore, RetakeRecommendation.RETAKE_BLUR, "Blurry")
        )

        assertEquals(ButtonCommand.RetakeCapture, policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, 1000L), confirmation))
        assertEquals(ButtonCommand.ConfirmProcessing, policy.evaluate(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, 1200L), confirmation))
    }

    @Test
    fun `processing state rejects clicks and only permits left long press for cancellation`() {
        val processing = AssistantState.Processing(dummySessionId, "/test.jpg")

        // Short clicks ignored to prevent duplicate submissions
        assertEquals(ButtonCommand.None, policy.evaluate(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, 1000L), processing))
        assertEquals(ButtonCommand.None, policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, 1200L), processing))

        // Long press left cancels
        assertEquals(ButtonCommand.CancelScan, policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.LONG_PRESS, 1400L), processing))
    }

    @Test
    fun `playback states support full navigation controls`() {
        val playing = AssistantState.Playing(dummyDocument, ReadingPosition(dummyDocument.id))

        // Right button: Play/Pause, Next Paragraph, Next Section
        assertEquals(ButtonCommand.TogglePlayPause, policy.evaluate(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, 1000L), playing))
        assertEquals(ButtonCommand.NextParagraph, policy.evaluate(ButtonEvent(PhysicalButton.RIGHT, PressType.DOUBLE_PRESS, 1200L), playing))
        assertEquals(ButtonCommand.NextSection, policy.evaluate(ButtonEvent(PhysicalButton.RIGHT, PressType.LONG_PRESS, 1400L), playing))

        // Left button: Previous Paragraph, Rewind, Previous Section
        assertEquals(ButtonCommand.PreviousParagraph, policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, 1600L), playing))
        assertEquals(ButtonCommand.Rewind(15), policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.DOUBLE_PRESS, 1800L), playing))
        assertEquals(ButtonCommand.PreviousSection, policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.LONG_PRESS, 2000L), playing))
    }

    @Test
    fun `error state allows any short click to dismiss error`() {
        val errorState = AssistantState.Error(
            AssistantError(ErrorCode.CAMERA_UNAVAILABLE),
            AssistantState.Idle
        )

        assertEquals(ButtonCommand.DismissError, policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, 1000L), errorState))
        assertEquals(ButtonCommand.DismissError, policy.evaluate(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, 1200L), errorState))
        assertEquals(ButtonCommand.None, policy.evaluate(ButtonEvent(PhysicalButton.LEFT, PressType.LONG_PRESS, 1400L), errorState))
    }
}
