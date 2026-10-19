package com.readingassistant.domain.camera

import com.readingassistant.domain.model.AssistantError
import com.readingassistant.domain.model.ErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Test scenarios that can be injected into the simulated camera controller.
 */
enum class SimulatedCameraScenario {
    SHARP_PAGE,
    BLURRED_PAGE,
    CROPPED_PAGE,
    DARK_PAGE,
    OVEREXPOSED_PAGE,
    GLARE_PAGE,
    DISCONNECTED,
    CAPTURE_FAILURE
}

/**
 * Deterministic fake camera controller for unit tests, end-to-end simulations, and laptop development.
 */
class SimulatedDocumentCameraController(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val captureOutputDir: File? = null
) : DocumentCameraController {

    private val _status = MutableStateFlow(CameraStatus.CLOSED)
    override val status: StateFlow<CameraStatus> = _status.asStateFlow()

    private val _analysisFrames = MutableSharedFlow<AnalysisFrame>(replay = 1, extraBufferCapacity = 16)
    override val analysisFrames: Flow<AnalysisFrame> = _analysisFrames.asSharedFlow()

    var activeScenario: SimulatedCameraScenario = SimulatedCameraScenario.SHARP_PAGE
    var frameIntervalMs: Long = 100L
    var simulatedDelayMs: Long = 0L
    var autoGenerateFrames: Boolean = false

    private var previewJob: Job? = null

    override suspend fun open(): Result<Unit> {
        if (activeScenario == SimulatedCameraScenario.DISCONNECTED) {
            _status.value = CameraStatus.DISCONNECTED
            return Result.failure(
                AssistantError(ErrorCode.CAMERA_UNAVAILABLE, "Simulated camera is disconnected")
                    .asException()
            )
        }
        _status.value = CameraStatus.OPENING
        if (simulatedDelayMs > 0) delay(simulatedDelayMs)
        _status.value = CameraStatus.PREVIEWING
        return Result.success(Unit)
    }

    override suspend fun startPreview(): Result<Unit> {
        if (_status.value == CameraStatus.DISCONNECTED) {
            return Result.failure(
                AssistantError(ErrorCode.CAMERA_DISCONNECTED, "Camera disconnected").asException()
            )
        }
        _status.value = CameraStatus.PREVIEWING
        if (autoGenerateFrames) {
            startFrameGeneration()
        }
        return Result.success(Unit)
    }

    override suspend fun stopPreview(): Result<Unit> {
        previewJob?.cancel()
        previewJob = null
        if (_status.value == CameraStatus.PREVIEWING) {
            _status.value = CameraStatus.OPENING
        }
        return Result.success(Unit)
    }

    override suspend fun captureStill(): Result<String> {
        if (activeScenario == SimulatedCameraScenario.CAPTURE_FAILURE) {
            return Result.failure(
                AssistantError(ErrorCode.CAPTURE_FAILED, "Simulated shutter failure").asException()
            )
        }
        if (_status.value == CameraStatus.DISCONNECTED || _status.value == CameraStatus.CLOSED) {
            return Result.failure(
                AssistantError(ErrorCode.CAMERA_UNAVAILABLE, "Camera not ready for capture").asException()
            )
        }

        _status.value = CameraStatus.CAPTURING
        if (simulatedDelayMs > 0) delay(simulatedDelayMs)

        // Write a synthetic JPEG file to persistent dir if configured, or return simulated path
        val timestamp = System.currentTimeMillis()
        val targetPath = if (captureOutputDir != null) {
            captureOutputDir.mkdirs()
            val file = File(captureOutputDir, "scan_$timestamp.jpg")
            if (!file.exists()) {
                file.writeBytes(synthesizeJpegBytes())
            }
            file.absolutePath
        } else {
            "/tmp/simulated_scans/scan_$timestamp.jpg"
        }

        _status.value = CameraStatus.PREVIEWING
        return Result.success(targetPath)
    }

    override suspend fun close() {
        stopPreview()
        _status.value = CameraStatus.CLOSED
    }

    /**
     * Programmatically emit a single frame conforming to the active scenario.
     */
    suspend fun emitTestFrame(scenario: SimulatedCameraScenario = activeScenario) {
        val frame = generateFrameForScenario(scenario)
        _analysisFrames.emit(frame)
    }

    /**
     * Synchronously generates an AnalysisFrame conforming to the given scenario without flow dispatch.
     */
    fun emitTestFrameSync(scenario: SimulatedCameraScenario = activeScenario): AnalysisFrame {
        return generateFrameForScenario(scenario)
    }

    private fun startFrameGeneration() {
        previewJob?.cancel()
        previewJob = scope.launch {
            while (isActive && _status.value == CameraStatus.PREVIEWING) {
                if (activeScenario == SimulatedCameraScenario.DISCONNECTED) {
                    _status.value = CameraStatus.DISCONNECTED
                    break
                }
                emitTestFrame(activeScenario)
                delay(frameIntervalMs)
            }
        }
    }

    private fun generateFrameForScenario(scenario: SimulatedCameraScenario): AnalysisFrame {
        val width = 160
        val height = 120
        val bytes = ByteArray(width * height)

        when (scenario) {
            SimulatedCameraScenario.SHARP_PAGE -> {
                // High contrast alternating line patterns with clean page margins
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val inMargin = (x < 12 || x > width - 12 || y < 12 || y > height - 12)
                        val isTextLine = (y % 10 in 3..6)
                        val isChar = (x % 6 in 1..4)
                        bytes[y * width + x] = if (!inMargin && isTextLine && isChar) 20.toByte() else 230.toByte()
                    }
                }
            }
            SimulatedCameraScenario.BLURRED_PAGE -> {
                // Uniform washed-out gray gradient (zero sharp edges)
                for (i in bytes.indices) {
                    bytes[i] = (128 + (i % 5)).toByte()
                }
            }
            SimulatedCameraScenario.CROPPED_PAGE -> {
                // Text abruptly cut off on margins
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val isBorder = (x < 5 || y < 5)
                        bytes[y * width + x] = if (isBorder) 10.toByte() else 220.toByte()
                    }
                }
            }
            SimulatedCameraScenario.DARK_PAGE -> {
                // Deep dark (mean intensity ~ 25)
                for (i in bytes.indices) {
                    bytes[i] = (20 + (i % 10)).toByte()
                }
            }
            SimulatedCameraScenario.OVEREXPOSED_PAGE -> {
                // Bleached white (mean intensity ~ 248)
                for (i in bytes.indices) {
                    bytes[i] = (245 + (i % 10)).toByte()
                }
            }
            SimulatedCameraScenario.GLARE_PAGE -> {
                // Intense circular hotspot in center
                val cx = width / 2
                val cy = height / 2
                val radiusSq = 25 * 25
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val distSq = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                        bytes[y * width + x] = if (distSq < radiusSq) 255.toByte() else 180.toByte()
                    }
                }
            }
            SimulatedCameraScenario.DISCONNECTED,
            SimulatedCameraScenario.CAPTURE_FAILURE -> {
                // Black blank frame
                bytes.fill(0)
            }
        }

        return AnalysisFrame(width, height, bytes)
    }

    private fun synthesizeJpegBytes(): ByteArray {
        // Minimal JPEG header + synthetic body
        return byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00.toByte(), 0x10.toByte(), 0x4A.toByte(), 0x46.toByte(),
            0x49.toByte(), 0x46.toByte(), 0x00.toByte(), 0x01.toByte(),
            0xFF.toByte(), 0xD9.toByte()
        )
    }

    private fun AssistantError.asException(): Exception =
        IllegalStateException("$code: $message")
}
