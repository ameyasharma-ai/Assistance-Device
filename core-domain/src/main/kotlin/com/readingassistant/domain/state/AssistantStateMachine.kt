package com.readingassistant.domain.state

import com.readingassistant.domain.model.ReadingPosition
import com.readingassistant.domain.model.ReadinessDecision
import com.readingassistant.domain.model.SessionId
import com.readingassistant.domain.model.SpokenPrompt
import com.readingassistant.domain.model.TactilePattern
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Thread-safe deterministic finite state machine governing the Wearable Assistant.
 */
class AssistantStateMachine(
    initialState: AssistantState = AssistantState.Idle,
    private val sessionIdGenerator: () -> SessionId = { SessionId(UUID.randomUUID().toString()) }
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _actions = MutableSharedFlow<AssistantAction>(replay = 1, extraBufferCapacity = 64)
    val actions: SharedFlow<AssistantAction> = _actions.asSharedFlow()

    /**
     * Dispatch an event to trigger a deterministic state transition.
     * Invalid events in the current state are safely ignored without crashing or corrupting state.
     */
    suspend fun dispatch(event: AssistantEvent) = mutex.withLock {
        val current = _state.value

        // Global fatal error handling
        if (event is AssistantEvent.FatalErrorOccurred) {
            transitionTo(AssistantState.Error(event.error, current))
            emit(AssistantAction.StopCamera)
            emit(AssistantAction.EmitHaptic(TactilePattern.FATAL_ERROR))
            emit(AssistantAction.SpeakPrompt(SpokenPrompt("fatal", event.error.message)))
            return@withLock
        }

        when (current) {
            is AssistantState.Idle -> handleIdle(event)
            is AssistantState.ScannerStarting -> handleScannerStarting(current, event)
            is AssistantState.Scanning -> handleScanning(current, event)
            is AssistantState.DocumentReady -> handleDocumentReady(current, event)
            is AssistantState.Capturing -> handleCapturing(current, event)
            is AssistantState.Captured -> handleCaptured(current, event)
            is AssistantState.CaptureValidation -> handleCaptureValidation(current, event)
            is AssistantState.AwaitingConfirmation -> handleAwaitingConfirmation(current, event)
            is AssistantState.Processing -> handleProcessing(current, event)
            is AssistantState.DocumentReadyForPlayback -> handleDocumentReadyForPlayback(current, event)
            is AssistantState.Playing -> handlePlaying(current, event)
            is AssistantState.Paused -> handlePaused(current, event)
            is AssistantState.Error -> handleError(current, event)
        }
    }

    private suspend fun handleIdle(event: AssistantEvent) {
        when (event) {
            is AssistantEvent.StartScanRequested -> {
                val sessionId = sessionIdGenerator()
                transitionTo(AssistantState.ScannerStarting(sessionId))
                emit(AssistantAction.StartCamera(sessionId))
                emit(AssistantAction.EmitHaptic(TactilePattern.SCANNER_ACTIVATED))
                emit(AssistantAction.SpeakPrompt(SpokenPrompt("scan_start", "Scanner activated")))
            }
            else -> { /* Ignore invalid event in Idle */ }
        }
    }

    private suspend fun handleScannerStarting(current: AssistantState.ScannerStarting, event: AssistantEvent) {
        when (event) {
            is AssistantEvent.CameraStarted -> {
                if (event.sessionId == current.sessionId) {
                    transitionTo(AssistantState.Scanning(current.sessionId))
                }
            }
            is AssistantEvent.CameraFailed -> {
                transitionTo(AssistantState.Error(event.error, current))
                emit(AssistantAction.EmitHaptic(TactilePattern.FATAL_ERROR))
                emit(AssistantAction.SpeakPrompt(SpokenPrompt("cam_fail", event.error.message)))
            }
            is AssistantEvent.CancelScanRequested -> {
                transitionTo(AssistantState.Idle)
                emit(AssistantAction.StopCamera)
            }
            else -> { /* Ignore */ }
        }
    }

    private suspend fun handleScanning(current: AssistantState.Scanning, event: AssistantEvent) {
        when (event) {
            is AssistantEvent.FrameAnalyzed -> {
                when (val decision = event.decision) {
                    is ReadinessDecision.Ready -> {
                        transitionTo(AssistantState.DocumentReady(current.sessionId, decision))
                        emit(AssistantAction.EmitHaptic(TactilePattern.DOCUMENT_READY))
                        emit(AssistantAction.SpeakPrompt(SpokenPrompt("doc_ready", "Page ready to capture")))
                    }
                    is ReadinessDecision.NotReady -> {
                        // Update latest metrics without vibration
                        _state.value = current.copy(latestQuality = decision)
                    }
                }
            }
            is AssistantEvent.CaptureRequested -> {
                transitionTo(AssistantState.Capturing(current.sessionId))
                emit(AssistantAction.TriggerCapture(current.sessionId))
                emit(AssistantAction.EmitHaptic(TactilePattern.CAPTURED))
            }
            is AssistantEvent.CancelScanRequested -> {
                transitionTo(AssistantState.Idle)
                emit(AssistantAction.StopCamera)
            }
            is AssistantEvent.CameraFailed -> {
                transitionTo(AssistantState.Error(event.error, current))
                emit(AssistantAction.EmitHaptic(TactilePattern.FATAL_ERROR))
                emit(AssistantAction.SpeakPrompt(SpokenPrompt("cam_fail", event.error.message)))
            }
            else -> { /* Ignore */ }
        }
    }

    private suspend fun handleDocumentReady(current: AssistantState.DocumentReady, event: AssistantEvent) {
        when (event) {
            is AssistantEvent.FrameAnalyzed -> {
                when (val decision = event.decision) {
                    is ReadinessDecision.NotReady -> {
                        // Quality degraded; re-arm readiness check without vibration
                        transitionTo(AssistantState.Scanning(current.sessionId, decision))
                    }
                    is ReadinessDecision.Ready -> {
                        // Keep in DocumentReady, DO NOT vibrate again
                        _state.value = current.copy(readiness = decision)
                    }
                }
            }
            is AssistantEvent.CaptureRequested -> {
                transitionTo(AssistantState.Capturing(current.sessionId))
                emit(AssistantAction.TriggerCapture(current.sessionId))
                emit(AssistantAction.EmitHaptic(TactilePattern.CAPTURED))
            }
            is AssistantEvent.CancelScanRequested -> {
                transitionTo(AssistantState.Idle)
                emit(AssistantAction.StopCamera)
            }
            is AssistantEvent.CameraFailed -> {
                transitionTo(AssistantState.Error(event.error, current))
                emit(AssistantAction.EmitHaptic(TactilePattern.FATAL_ERROR))
                emit(AssistantAction.SpeakPrompt(SpokenPrompt("cam_fail", event.error.message)))
            }
            else -> { /* Ignore */ }
        }
    }

    private suspend fun handleCapturing(current: AssistantState.Capturing, event: AssistantEvent) {
        when (event) {
            is AssistantEvent.CaptureSucceeded -> {
                transitionTo(AssistantState.Captured(current.sessionId, event.imagePath))
                // Free camera immediately to conserve power and memory
                emit(AssistantAction.StopCamera)
                // Auto-advance to validation
                transitionTo(AssistantState.CaptureValidation(current.sessionId, event.imagePath))
                emit(AssistantAction.ValidateCapture(current.sessionId, event.imagePath))
            }
            is AssistantEvent.CaptureFailed -> {
                transitionTo(AssistantState.Error(event.error, current))
                emit(AssistantAction.EmitHaptic(TactilePattern.RECOVERABLE_WARNING))
                emit(AssistantAction.SpeakPrompt(SpokenPrompt("cap_fail", "Capture failed. Please try again.")))
            }
            else -> { /* Ignore button presses during capture */ }
        }
    }

    private suspend fun handleCaptured(current: AssistantState.Captured, event: AssistantEvent) {
        // Automatic state transition occurs via handleCapturing; handle fallback
        transitionTo(AssistantState.CaptureValidation(current.sessionId, current.imagePath))
        emit(AssistantAction.ValidateCapture(current.sessionId, current.imagePath))
    }

    private suspend fun handleCaptureValidation(current: AssistantState.CaptureValidation, event: AssistantEvent) {
        when (event) {
            is AssistantEvent.ValidationPassed -> {
                transitionTo(AssistantState.Processing(current.sessionId, current.imagePath))
                emit(AssistantAction.EmitHaptic(TactilePattern.PROCESSING_STARTED))
                emit(AssistantAction.SpeakPrompt(SpokenPrompt("proc_start", "Processing textbook page")))
                emit(AssistantAction.StartAiProcessing(current.sessionId, current.imagePath))
            }
            is AssistantEvent.ValidationNeedsConfirmation -> {
                transitionTo(AssistantState.AwaitingConfirmation(current.sessionId, current.imagePath, event.validation))
                emit(AssistantAction.EmitHaptic(TactilePattern.RECOVERABLE_WARNING))
                emit(AssistantAction.SpeakPrompt(SpokenPrompt("confirm_req", event.validation.recommendation.spokenPrompt)))
            }
            else -> { /* Ignore inputs during image analysis */ }
        }
    }

    private suspend fun handleAwaitingConfirmation(current: AssistantState.AwaitingConfirmation, event: AssistantEvent) {
        when (event) {
            is AssistantEvent.ConfirmProcessingRequested -> {
                transitionTo(AssistantState.Processing(current.sessionId, current.imagePath))
                emit(AssistantAction.EmitHaptic(TactilePattern.PROCESSING_STARTED))
                emit(AssistantAction.SpeakPrompt(SpokenPrompt("proc_start", "Processing textbook page")))
                emit(AssistantAction.StartAiProcessing(current.sessionId, current.imagePath))
            }
            is AssistantEvent.RetakeRequested -> {
                val newSessionId = sessionIdGenerator()
                transitionTo(AssistantState.ScannerStarting(newSessionId))
                emit(AssistantAction.StartCamera(newSessionId))
                emit(AssistantAction.SpeakPrompt(SpokenPrompt("retake", "Retaking photo. Scanner starting.")))
            }
            is AssistantEvent.CancelScanRequested -> {
                transitionTo(AssistantState.Idle)
            }
            else -> { /* Ignore */ }
        }
    }

    private suspend fun handleProcessing(current: AssistantState.Processing, event: AssistantEvent) {
        when (event) {
            is AssistantEvent.ProcessingProgressUpdated -> {
                _state.value = current.copy(progress = event.progress)
            }
            is AssistantEvent.ProcessingSucceeded -> {
                transitionTo(AssistantState.DocumentReadyForPlayback(event.document))
                emit(AssistantAction.EmitHaptic(TactilePattern.PROCESSING_COMPLETED))
                emit(AssistantAction.SpeakPrompt(SpokenPrompt("proc_done", "Study guide ready. Press right foot to listen.")))
                emit(AssistantAction.PersistDocument(event.document))
            }
            is AssistantEvent.ProcessingFailed -> {
                transitionTo(AssistantState.Error(event.error, current))
                emit(AssistantAction.EmitHaptic(TactilePattern.FATAL_ERROR))
                emit(AssistantAction.SpeakPrompt(SpokenPrompt("proc_fail", event.error.message)))
            }
            is AssistantEvent.CancelScanRequested -> {
                transitionTo(AssistantState.Idle)
                emit(AssistantAction.CancelAiProcessing(current.sessionId))
                emit(AssistantAction.SpeakPrompt(SpokenPrompt("proc_cancel", "Processing cancelled")))
            }
            else -> { /* Reject duplicate capture/processing requests */ }
        }
    }

    private suspend fun handleDocumentReadyForPlayback(current: AssistantState.DocumentReadyForPlayback, event: AssistantEvent) {
        when (event) {
            is AssistantEvent.PlayRequested -> {
                val position = ReadingPosition(current.document.id)
                transitionTo(AssistantState.Playing(current.document, position))
                emit(AssistantAction.StartPlayback(current.document, position))
            }
            is AssistantEvent.StartScanRequested -> {
                val sessionId = sessionIdGenerator()
                transitionTo(AssistantState.ScannerStarting(sessionId))
                emit(AssistantAction.StartCamera(sessionId))
                emit(AssistantAction.EmitHaptic(TactilePattern.SCANNER_ACTIVATED))
            }
            else -> { /* Ignore */ }
        }
    }

    private suspend fun handlePlaying(current: AssistantState.Playing, event: AssistantEvent) {
        when (event) {
            is AssistantEvent.PauseRequested -> {
                transitionTo(AssistantState.Paused(current.document, current.position))
                emit(AssistantAction.PausePlayback)
            }
            is AssistantEvent.PlaybackPositionUpdated -> {
                _state.value = current.copy(position = event.position)
                emit(AssistantAction.PersistReadingPosition(event.position))
            }
            is AssistantEvent.NextParagraphRequested -> {
                val nextPos = current.position.copy(
                    segmentIndex = current.position.segmentIndex + 1,
                    characterOffset = 0
                )
                transitionTo(current.copy(position = nextPos))
                emit(AssistantAction.EmitHaptic(TactilePattern.NAVIGATION_TICK))
                emit(AssistantAction.SeekPlayback(nextPos))
            }
            is AssistantEvent.PreviousParagraphRequested -> {
                val prevPos = current.position.copy(
                    segmentIndex = maxOf(0, current.position.segmentIndex - 1),
                    characterOffset = 0
                )
                transitionTo(current.copy(position = prevPos))
                emit(AssistantAction.EmitHaptic(TactilePattern.NAVIGATION_TICK))
                emit(AssistantAction.SeekPlayback(prevPos))
            }
            is AssistantEvent.RepeatParagraphRequested -> {
                val repeatPos = current.position.copy(characterOffset = 0)
                emit(AssistantAction.EmitHaptic(TactilePattern.NAVIGATION_TICK))
                emit(AssistantAction.SeekPlayback(repeatPos))
            }
            is AssistantEvent.NextSectionRequested -> {
                val nextSecPos = current.position.copy(
                    sectionIndex = current.position.sectionIndex + 1,
                    segmentIndex = 0,
                    characterOffset = 0
                )
                transitionTo(current.copy(position = nextSecPos))
                emit(AssistantAction.EmitHaptic(TactilePattern.NAVIGATION_TICK))
                emit(AssistantAction.SeekPlayback(nextSecPos))
            }
            is AssistantEvent.PreviousSectionRequested -> {
                val prevSecPos = current.position.copy(
                    sectionIndex = maxOf(0, current.position.sectionIndex - 1),
                    segmentIndex = 0,
                    characterOffset = 0
                )
                transitionTo(current.copy(position = prevSecPos))
                emit(AssistantAction.EmitHaptic(TactilePattern.NAVIGATION_TICK))
                emit(AssistantAction.SeekPlayback(prevSecPos))
            }
            is AssistantEvent.RewindRequested -> {
                emit(AssistantAction.EmitHaptic(TactilePattern.NAVIGATION_TICK))
                emit(AssistantAction.SeekPlayback(current.position))
            }
            is AssistantEvent.PlaybackCompleted -> {
                transitionTo(AssistantState.DocumentReadyForPlayback(current.document))
            }
            is AssistantEvent.StartScanRequested -> {
                emit(AssistantAction.PausePlayback)
                val sessionId = sessionIdGenerator()
                transitionTo(AssistantState.ScannerStarting(sessionId))
                emit(AssistantAction.StartCamera(sessionId))
            }
            else -> { /* Ignore */ }
        }
    }

    private suspend fun handlePaused(current: AssistantState.Paused, event: AssistantEvent) {
        when (event) {
            is AssistantEvent.PlayRequested -> {
                transitionTo(AssistantState.Playing(current.document, current.position))
                emit(AssistantAction.StartPlayback(current.document, current.position))
            }
            is AssistantEvent.NextParagraphRequested -> {
                val nextPos = current.position.copy(
                    segmentIndex = current.position.segmentIndex + 1,
                    characterOffset = 0
                )
                transitionTo(current.copy(position = nextPos))
                emit(AssistantAction.EmitHaptic(TactilePattern.NAVIGATION_TICK))
                emit(AssistantAction.PersistReadingPosition(nextPos))
            }
            is AssistantEvent.PreviousParagraphRequested -> {
                val prevPos = current.position.copy(
                    segmentIndex = maxOf(0, current.position.segmentIndex - 1),
                    characterOffset = 0
                )
                transitionTo(current.copy(position = prevPos))
                emit(AssistantAction.EmitHaptic(TactilePattern.NAVIGATION_TICK))
                emit(AssistantAction.PersistReadingPosition(prevPos))
            }
            is AssistantEvent.NextSectionRequested -> {
                val nextSecPos = current.position.copy(
                    sectionIndex = current.position.sectionIndex + 1,
                    segmentIndex = 0,
                    characterOffset = 0
                )
                transitionTo(current.copy(position = nextSecPos))
                emit(AssistantAction.EmitHaptic(TactilePattern.NAVIGATION_TICK))
                emit(AssistantAction.PersistReadingPosition(nextSecPos))
            }
            is AssistantEvent.PreviousSectionRequested -> {
                val prevSecPos = current.position.copy(
                    sectionIndex = maxOf(0, current.position.sectionIndex - 1),
                    segmentIndex = 0,
                    characterOffset = 0
                )
                transitionTo(current.copy(position = prevSecPos))
                emit(AssistantAction.EmitHaptic(TactilePattern.NAVIGATION_TICK))
                emit(AssistantAction.PersistReadingPosition(prevSecPos))
            }
            is AssistantEvent.StartScanRequested -> {
                val sessionId = sessionIdGenerator()
                transitionTo(AssistantState.ScannerStarting(sessionId))
                emit(AssistantAction.StartCamera(sessionId))
            }
            else -> { /* Ignore */ }
        }
    }

    private suspend fun handleError(current: AssistantState.Error, event: AssistantEvent) {
        when (event) {
            is AssistantEvent.DismissErrorRequested -> {
                // Recover to safe state based on prior history
                val safeState = when (current.previousState) {
                    is AssistantState.ScannerStarting,
                    is AssistantState.Scanning,
                    is AssistantState.DocumentReady,
                    is AssistantState.Capturing,
                    is AssistantState.Captured,
                    is AssistantState.CaptureValidation,
                    is AssistantState.AwaitingConfirmation,
                    is AssistantState.Processing -> AssistantState.Idle

                    is AssistantState.Playing -> AssistantState.Paused(
                        current.previousState.document,
                        current.previousState.position
                    )
                    is AssistantState.Paused -> current.previousState
                    is AssistantState.DocumentReadyForPlayback -> current.previousState
                    else -> AssistantState.Idle
                }
                transitionTo(safeState)
                emit(AssistantAction.StopCamera)
            }
            else -> { /* Ignore other inputs until error is acknowledged */ }
        }
    }

    private fun transitionTo(newState: AssistantState) {
        _state.value = newState
    }

    private suspend fun emit(action: AssistantAction) {
        _actions.emit(action)
    }
}
