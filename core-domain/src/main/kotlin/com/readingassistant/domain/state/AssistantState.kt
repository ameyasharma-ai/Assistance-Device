package com.readingassistant.domain.state

import com.readingassistant.domain.model.AssistantError
import com.readingassistant.domain.model.CaptureValidationResult
import com.readingassistant.domain.model.GenerationProgress
import com.readingassistant.domain.model.ReadingPosition
import com.readingassistant.domain.model.ReadinessDecision
import com.readingassistant.domain.model.SessionId
import com.readingassistant.domain.model.StudyDocument

/**
 * Deterministic states representing the complete lifecycle of the Assistant.
 */
sealed interface AssistantState {
    val name: String

    /**
     * Standby state; low power consumption, camera and TTS idle.
     */
    data object Idle : AssistantState {
        override val name: String = "IDLE"
    }

    /**
     * Camera initialization and peripheral setup in progress.
     */
    data class ScannerStarting(
        val sessionId: SessionId
    ) : AssistantState {
        override val name: String = "SCANNER_STARTING"
    }

    /**
     * Actively analyzing live preview frames for document framing, focus, and lighting.
     */
    data class Scanning(
        val sessionId: SessionId,
        val latestQuality: ReadinessDecision? = null
    ) : AssistantState {
        override val name: String = "SCANNING"
    }

    /**
     * Document readiness criteria fully satisfied; user signaled via haptic cue to capture.
     */
    data class DocumentReady(
        val sessionId: SessionId,
        val readiness: ReadinessDecision.Ready
    ) : AssistantState {
        override val name: String = "DOCUMENT_READY"
    }

    /**
     * Camera shutter triggered; high-resolution image acquisition in progress.
     */
    data class Capturing(
        val sessionId: SessionId
    ) : AssistantState {
        override val name: String = "CAPTURING"
    }

    /**
     * Still image successfully written to private app storage.
     */
    data class Captured(
        val sessionId: SessionId,
        val imagePath: String
    ) : AssistantState {
        override val name: String = "CAPTURED"
    }

    /**
     * Validating quality of the captured full-resolution still image.
     */
    data class CaptureValidation(
        val sessionId: SessionId,
        val imagePath: String
    ) : AssistantState {
        override val name: String = "CAPTURE_VALIDATION"
    }

    /**
     * Suboptimal capture detected; awaiting user decision to retake or proceed.
     */
    data class AwaitingConfirmation(
        val sessionId: SessionId,
        val imagePath: String,
        val validation: CaptureValidationResult.NeedsConfirmation
    ) : AssistantState {
        override val name: String = "AWAITING_CONFIRMATION"
    }

    /**
     * Cloud OCR / LLM vision inference running; streaming structured study sections.
     */
    data class Processing(
        val sessionId: SessionId,
        val imagePath: String,
        val progress: GenerationProgress? = null
    ) : AssistantState {
        override val name: String = "PROCESSING"
    }

    /**
     * Study document validated, persisted, and ready for speech playback.
     */
    data class DocumentReadyForPlayback(
        val document: StudyDocument
    ) : AssistantState {
        override val name: String = "DOCUMENT_READY_FOR_PLAYBACK"
    }

    /**
     * Active speech playback of study document content.
     */
    data class Playing(
        val document: StudyDocument,
        val position: ReadingPosition
    ) : AssistantState {
        override val name: String = "PLAYING"
    }

    /**
     * Playback paused at exact paragraph and sentence boundary.
     */
    data class Paused(
        val document: StudyDocument,
        val position: ReadingPosition
    ) : AssistantState {
        override val name: String = "PAUSED"
    }

    /**
     * Recoverable or fatal error state with reference to previous state for recovery.
     */
    data class Error(
        val error: AssistantError,
        val previousState: AssistantState
    ) : AssistantState {
        override val name: String = "ERROR"
    }
}
