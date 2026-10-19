package com.readingassistant.camera

import com.readingassistant.domain.camera.AnalysisFrame
import com.readingassistant.domain.camera.CameraStatus
import com.readingassistant.domain.camera.DocumentCameraController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Dynamic delegating controller allowing hot-swapping between
 * real hardware camera (laptop webcam in emulator) and synthetic simulation frames
 * without tearing down the orchestrator or state machine.
 */
class DelegatingDocumentCameraController(
    initialDelegate: DocumentCameraController,
    private val scope: CoroutineScope
) : DocumentCameraController {

    private val _status = MutableStateFlow(initialDelegate.status.value)
    override val status: StateFlow<CameraStatus> = _status.asStateFlow()

    private val _analysisFrames = MutableSharedFlow<AnalysisFrame>(extraBufferCapacity = 5)
    override val analysisFrames: SharedFlow<AnalysisFrame> = _analysisFrames.asSharedFlow()

    private var statusJob: Job? = null
    private var framesJob: Job? = null

    var activeDelegate: DocumentCameraController = initialDelegate
        set(newDelegate) {
            field = newDelegate
            bindDelegate(newDelegate)
        }

    init {
        bindDelegate(initialDelegate)
    }

    private fun bindDelegate(delegate: DocumentCameraController) {
        statusJob?.cancel()
        framesJob?.cancel()

        statusJob = delegate.status.onEach { newStatus ->
            _status.value = newStatus
        }.launchIn(scope)

        framesJob = delegate.analysisFrames.onEach { frame ->
            _analysisFrames.tryEmit(frame)
        }.launchIn(scope)
    }

    override suspend fun open(): Result<Unit> = activeDelegate.open()

    override suspend fun startPreview(): Result<Unit> = activeDelegate.startPreview()

    override suspend fun stopPreview(): Result<Unit> = activeDelegate.stopPreview()

    override suspend fun captureStill(): Result<String> = activeDelegate.captureStill()

    override suspend fun close() = activeDelegate.close()
}
