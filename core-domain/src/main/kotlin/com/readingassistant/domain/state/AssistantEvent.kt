package com.readingassistant.domain.state

import com.readingassistant.domain.model.AssistantError
import com.readingassistant.domain.model.CaptureValidationResult
import com.readingassistant.domain.model.GenerationProgress
import com.readingassistant.domain.model.ReadingPosition
import com.readingassistant.domain.model.ReadinessDecision
import com.readingassistant.domain.model.SessionId
import com.readingassistant.domain.model.StudyDocument

/**
 * Events ingested by the AssistantStateMachine to trigger deterministic state transitions.
 */
sealed interface AssistantEvent {

    // User button / command requests
    data object StartScanRequested : AssistantEvent
    data object CancelScanRequested : AssistantEvent
    data object CaptureRequested : AssistantEvent
    data object ConfirmProcessingRequested : AssistantEvent
    data object RetakeRequested : AssistantEvent
    data object PlayRequested : AssistantEvent
    data object PauseRequested : AssistantEvent
    data object NextParagraphRequested : AssistantEvent
    data object PreviousParagraphRequested : AssistantEvent
    data class RewindRequested(val seconds: Int = 15) : AssistantEvent
    data object RepeatParagraphRequested : AssistantEvent
    data object NextSectionRequested : AssistantEvent
    data object PreviousSectionRequested : AssistantEvent
    data object DismissErrorRequested : AssistantEvent

    // Hardware & subsystem response events
    data class CameraStarted(val sessionId: SessionId) : AssistantEvent
    data class CameraFailed(val error: AssistantError) : AssistantEvent
    data class FrameAnalyzed(val decision: ReadinessDecision) : AssistantEvent
    data class CaptureSucceeded(val imagePath: String) : AssistantEvent
    data class CaptureFailed(val error: AssistantError) : AssistantEvent
    data object ValidationPassed : AssistantEvent
    data class ValidationNeedsConfirmation(val validation: CaptureValidationResult.NeedsConfirmation) : AssistantEvent
    data class ProcessingProgressUpdated(val progress: GenerationProgress) : AssistantEvent
    data class ProcessingSucceeded(val document: StudyDocument) : AssistantEvent
    data class ProcessingFailed(val error: AssistantError) : AssistantEvent
    data class PlaybackPositionUpdated(val position: ReadingPosition) : AssistantEvent
    data object PlaybackCompleted : AssistantEvent
    data class FatalErrorOccurred(val error: AssistantError) : AssistantEvent
}
