package com.readingassistant.domain

import com.readingassistant.domain.ai.StudyDocumentGenerator
import com.readingassistant.domain.camera.CameraStatus
import com.readingassistant.domain.camera.DocumentCameraController
import com.readingassistant.domain.feedback.FeedbackController
import com.readingassistant.domain.input.ButtonCommand
import com.readingassistant.domain.input.ButtonEvent
import com.readingassistant.domain.input.InputController
import com.readingassistant.domain.input.InputPolicy
import com.readingassistant.domain.model.AssistantError
import com.readingassistant.domain.model.CaptureValidationResult
import com.readingassistant.domain.model.ErrorCode
import com.readingassistant.domain.model.QualityScore
import com.readingassistant.domain.model.ReadinessDecision
import com.readingassistant.domain.playback.StudyPlaybackController
import com.readingassistant.domain.state.AssistantAction
import com.readingassistant.domain.state.AssistantEvent
import com.readingassistant.domain.state.AssistantState
import com.readingassistant.domain.state.AssistantStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Central system orchestrator linking the State Machine, Input Policy, Camera Controller,
 * AI Generator, Playback Engine, and Feedback Actuators into a unified reactive workflow.
 */
class AssistantOrchestrator(
    val stateMachine: AssistantStateMachine = AssistantStateMachine(),
    val inputPolicy: InputPolicy = InputPolicy(),
    val inputController: InputController,
    val cameraController: DocumentCameraController,
    val feedbackController: FeedbackController,
    val documentGenerator: StudyDocumentGenerator,
    val playbackController: StudyPlaybackController,
    val qualityEngine: com.readingassistant.domain.quality.ImageQualityEngine = com.readingassistant.domain.quality.ImageQualityEngine(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    val state: StateFlow<AssistantState> = stateMachine.state

    private var orchestratorJob: Job? = null

    /**
     * Start the reactive pipeline binding user input and peripheral actions.
     */
    fun start() {
        if (orchestratorJob != null) return

        val compositeJob = Job(scope.coroutineContext[Job])
        orchestratorJob = compositeJob
        val orchestratorScope = CoroutineScope(scope.coroutineContext + compositeJob)

        inputController.rawEvents.onEach { event ->
            dispatchButtonEvent(event)
        }.launchIn(orchestratorScope)

        stateMachine.actions.onEach { action ->
            handleAssistantAction(action)
        }.launchIn(orchestratorScope)

        cameraController.analysisFrames.onEach { frame ->
            processFrame(frame)
        }.launchIn(orchestratorScope)
    }

    suspend fun processFrame(frame: com.readingassistant.domain.camera.AnalysisFrame) {
        val currentState = stateMachine.state.value
        if (currentState is AssistantState.Scanning || currentState is AssistantState.DocumentReady) {
            val decision = evaluateFrameReadiness(frame)
            stateMachine.dispatch(AssistantEvent.FrameAnalyzed(decision))
        }
    }

    /**
     * Stop and release active background collectors.
     */
    fun stop() {
        orchestratorJob?.cancel()
        orchestratorJob = null
    }

    suspend fun dispatchButtonEvent(event: ButtonEvent) {
        val cmd = inputPolicy.evaluate(event, stateMachine.state.value)
        handleButtonCommand(cmd)
    }

    private suspend fun handleButtonCommand(command: ButtonCommand) {
        when (command) {
            is ButtonCommand.StartScan -> stateMachine.dispatch(AssistantEvent.StartScanRequested)
            is ButtonCommand.CancelScan -> stateMachine.dispatch(AssistantEvent.CancelScanRequested)
            is ButtonCommand.CaptureStill -> stateMachine.dispatch(AssistantEvent.CaptureRequested)
            is ButtonCommand.ConfirmProcessing -> stateMachine.dispatch(AssistantEvent.ConfirmProcessingRequested)
            is ButtonCommand.RetakeCapture -> stateMachine.dispatch(AssistantEvent.RetakeRequested)
            is ButtonCommand.TogglePlayPause -> {
                when (stateMachine.state.value) {
                    is AssistantState.Playing -> stateMachine.dispatch(AssistantEvent.PauseRequested)
                    is AssistantState.Paused -> stateMachine.dispatch(AssistantEvent.PlayRequested)
                    is AssistantState.DocumentReadyForPlayback -> stateMachine.dispatch(AssistantEvent.PlayRequested)
                    else -> { /* Ignore */ }
                }
            }
            is ButtonCommand.NextParagraph -> stateMachine.dispatch(AssistantEvent.NextParagraphRequested)
            is ButtonCommand.PreviousParagraph -> stateMachine.dispatch(AssistantEvent.PreviousParagraphRequested)
            is ButtonCommand.RepeatParagraph -> stateMachine.dispatch(AssistantEvent.RepeatParagraphRequested)
            is ButtonCommand.NextSection -> stateMachine.dispatch(AssistantEvent.NextSectionRequested)
            is ButtonCommand.PreviousSection -> stateMachine.dispatch(AssistantEvent.PreviousSectionRequested)
            is ButtonCommand.Rewind -> stateMachine.dispatch(AssistantEvent.RewindRequested(command.seconds))
            is ButtonCommand.DismissError -> stateMachine.dispatch(AssistantEvent.DismissErrorRequested)
            is ButtonCommand.None -> { /* Debounced or suppressed */ }
        }
    }

    private suspend fun handleAssistantAction(action: AssistantAction) {
        when (action) {
            is AssistantAction.StartCamera -> {
                val openResult = cameraController.open()
                if (openResult.isSuccess) {
                    val previewResult = cameraController.startPreview()
                    if (previewResult.isSuccess) {
                        stateMachine.dispatch(AssistantEvent.CameraStarted(action.sessionId))
                    } else {
                        val error = AssistantError(ErrorCode.CAMERA_UNAVAILABLE, "Failed to start camera preview")
                        stateMachine.dispatch(AssistantEvent.CameraFailed(error))
                    }
                } else {
                    val error = AssistantError(ErrorCode.CAMERA_UNAVAILABLE, "Unable to access camera")
                    stateMachine.dispatch(AssistantEvent.CameraFailed(error))
                }
            }

            is AssistantAction.StopCamera -> {
                cameraController.stopPreview()
                cameraController.close()
            }

            is AssistantAction.TriggerCapture -> {
                val captureResult = cameraController.captureStill()
                captureResult.fold(
                    onSuccess = { path ->
                        stateMachine.dispatch(AssistantEvent.CaptureSucceeded(path))
                    },
                    onFailure = { ex ->
                        val error = AssistantError(ErrorCode.CAPTURE_FAILED, ex.message ?: "Capture failed")
                        stateMachine.dispatch(AssistantEvent.CaptureFailed(error))
                    }
                )
            }

            is AssistantAction.ValidateCapture -> {
                // In production, ImageQualityEngine runs on the captured still
                // By default in simulation, pass validation
                stateMachine.dispatch(AssistantEvent.ValidationPassed)
            }

            is AssistantAction.StartAiProcessing -> {
                val result = documentGenerator.generateStudyDocument(
                    imagePath = action.imagePath,
                    onProgress = { progress ->
                        stateMachine.dispatch(AssistantEvent.ProcessingProgressUpdated(progress))
                    }
                )
                result.fold(
                    onSuccess = { document ->
                        stateMachine.dispatch(AssistantEvent.ProcessingSucceeded(document))
                    },
                    onFailure = { ex ->
                        val error = AssistantError(
                            code = ErrorCode.AI_MALFORMED_RESPONSE,
                            message = ex.message ?: "AI generation encountered an error"
                        )
                        stateMachine.dispatch(AssistantEvent.ProcessingFailed(error))
                    }
                )
            }

            is AssistantAction.CancelAiProcessing -> {
                documentGenerator.cancel()
            }

            is AssistantAction.EmitHaptic -> {
                feedbackController.emitHaptic(action.pattern)
            }

            is AssistantAction.SpeakPrompt -> {
                feedbackController.speak(action.prompt)
            }

            is AssistantAction.StartPlayback -> {
                playbackController.loadDocument(action.document, action.position)
                playbackController.play()
            }

            is AssistantAction.PausePlayback -> {
                playbackController.pause()
            }

            is AssistantAction.SeekPlayback -> {
                // Update active reading position
            }

            is AssistantAction.PersistDocument -> {
                // Persistent repository hook
            }

            is AssistantAction.PersistReadingPosition -> {
                // Persistent repository hook
            }
        }
    }

    /**
     * Evaluates raw frame bytes to produce a ReadinessDecision.
     */
    fun evaluateFrameReadiness(frame: com.readingassistant.domain.camera.AnalysisFrame): ReadinessDecision {
        return qualityEngine.evaluate(frame)
    }
}
