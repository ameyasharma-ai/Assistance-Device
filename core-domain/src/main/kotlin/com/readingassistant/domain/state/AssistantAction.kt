package com.readingassistant.domain.state

import com.readingassistant.domain.model.ReadingPosition
import com.readingassistant.domain.model.SessionId
import com.readingassistant.domain.model.SpokenPrompt
import com.readingassistant.domain.model.StudyDocument
import com.readingassistant.domain.model.TactilePattern

/**
 * Side-effect actions produced by the state machine to be performed by peripheral adapters.
 */
sealed interface AssistantAction {

    // Camera actions
    data class StartCamera(val sessionId: SessionId) : AssistantAction
    data object StopCamera : AssistantAction
    data class TriggerCapture(val sessionId: SessionId) : AssistantAction
    data class ValidateCapture(val sessionId: SessionId, val imagePath: String) : AssistantAction

    // AI actions
    data class StartAiProcessing(val sessionId: SessionId, val imagePath: String) : AssistantAction
    data class CancelAiProcessing(val sessionId: SessionId) : AssistantAction

    // Multimodal feedback actions
    data class EmitHaptic(val pattern: TactilePattern) : AssistantAction
    data class SpeakPrompt(val prompt: SpokenPrompt) : AssistantAction

    // Playback actions
    data class StartPlayback(val document: StudyDocument, val position: ReadingPosition) : AssistantAction
    data object PausePlayback : AssistantAction
    data class SeekPlayback(val position: ReadingPosition) : AssistantAction

    // Persistence actions
    data class PersistDocument(val document: StudyDocument) : AssistantAction
    data class PersistReadingPosition(val position: ReadingPosition) : AssistantAction
}
