package com.readingassistant.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.readingassistant.domain.camera.AnalysisFrame
import com.readingassistant.domain.camera.CameraStatus
import com.readingassistant.domain.camera.DocumentCameraController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Android Camera2 implementation of [DocumentCameraController].
 * Binds directly to the device's back camera (mapped to laptop's webcam in the emulator)
 * to stream downsampled frames for quality inspection and capture high-resolution stills for Gemini Vision.
 */
class AndroidCamera2Controller(
    private val context: Context,
    var previewSurface: Surface? = null
) : DocumentCameraController {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val _status = MutableStateFlow(CameraStatus.CLOSED)
    override val status: StateFlow<CameraStatus> = _status.asStateFlow()

    private val _analysisFrames = MutableSharedFlow<AnalysisFrame>(extraBufferCapacity = 2)
    override val analysisFrames: SharedFlow<AnalysisFrame> = _analysisFrames.asSharedFlow()

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var previewReader: ImageReader? = null
    private var stillReader: ImageReader? = null

    private val isCapturing = AtomicBoolean(false)
    private var stillCaptureContinuation: ((Result<String>) -> Unit)? = null

    private val mutex = Mutex()

    private fun startBackgroundThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("CameraBackground").apply {
                start()
                cameraHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(500)
        } catch (_: InterruptedException) {}
        cameraThread = null
        cameraHandler = null
    }

    @SuppressLint("MissingPermission")
    override suspend fun open(): Result<Unit> = mutex.withLock {
        try {
            startBackgroundThread()
            _status.value = CameraStatus.OPENING

            val cameraId = findBackCameraId() ?: return Result.failure(
                IllegalStateException("No back-facing camera or webcam found on device")
            )

            val openedDevice = suspendCancellableCoroutine<CameraDevice?> { cont ->
                try {
                    cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            if (cont.isActive) cont.resume(camera)
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            _status.value = CameraStatus.DISCONNECTED
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            _status.value = CameraStatus.ERROR
                            if (cont.isActive) cont.resume(null)
                        }
                    }, cameraHandler)
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }

            if (openedDevice == null) {
                _status.value = CameraStatus.ERROR
                return Result.failure(IllegalStateException("Failed to open camera hardware"))
            }

            cameraDevice = openedDevice
            return Result.success(Unit)
        } catch (e: Exception) {
            _status.value = CameraStatus.ERROR
            return Result.failure(e)
        }
    }

    override suspend fun startPreview(): Result<Unit> = mutex.withLock {
        val device = cameraDevice ?: return Result.failure(IllegalStateException("Camera not opened"))

        try {
            // 1. Preview Reader for Quality Analysis (e.g. 640x480)
            previewReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val frame = extractAnalysisFrame(image)
                        _analysisFrames.tryEmit(frame)
                    } finally {
                        image.close()
                    }
                }, cameraHandler)
            }

            // 2. Still Capture Reader for Full-Resolution Document Photo
            stillReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val file = saveJpegImage(image)
                        stillCaptureContinuation?.invoke(Result.success(file.absolutePath))
                        stillCaptureContinuation = null
                    } catch (e: Exception) {
                        stillCaptureContinuation?.invoke(Result.failure(e))
                        stillCaptureContinuation = null
                    } finally {
                        image.close()
                        isCapturing.set(false)
                    }
                }, cameraHandler)
            }

            val session = suspendCancellableCoroutine<CameraCaptureSession?> { cont ->
                val surfaces = mutableListOf<Surface>(previewReader!!.surface, stillReader!!.surface)
                previewSurface?.let { if (it.isValid) surfaces.add(it) }

                device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(s)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(null)
                    }
                }, cameraHandler)
            }

            if (session == null) {
                return Result.failure(IllegalStateException("Failed to configure camera capture session"))
            }

            captureSession = session

            // Start repeating preview request
            val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewReader!!.surface)
                previewSurface?.let { if (it.isValid) addTarget(it) }
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }

            session.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler)
            _status.value = CameraStatus.PREVIEWING
            return Result.success(Unit)

        } catch (e: Exception) {
            _status.value = CameraStatus.ERROR
            return Result.failure(e)
        }
    }

    override suspend fun stopPreview(): Result<Unit> = mutex.withLock {
        try {
            try {
                captureSession?.stopRepeating()
            } catch (_: Exception) {}
            try {
                captureSession?.close()
            } catch (_: Exception) {}
            captureSession = null

            previewReader?.close()
            previewReader = null

            stillReader?.close()
            stillReader = null

            _status.value = CameraStatus.OPENING
            return Result.success(Unit)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    override suspend fun captureStill(): Result<String> {
        val session = captureSession ?: return Result.failure(IllegalStateException("Camera preview not running"))
        val device = cameraDevice ?: return Result.failure(IllegalStateException("Camera device not open"))
        val sReader = stillReader ?: return Result.failure(IllegalStateException("Still reader not ready"))

        if (!isCapturing.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("Capture already in progress"))
        }

        return suspendCancellableCoroutine { cont ->
            stillCaptureContinuation = { result ->
                if (cont.isActive) cont.resume(result)
            }

            try {
                val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(sReader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }
                session.capture(captureBuilder.build(), null, cameraHandler)
            } catch (e: Exception) {
                isCapturing.set(false)
                stillCaptureContinuation = null
                if (cont.isActive) cont.resume(Result.failure(e))
            }
        }
    }

    override suspend fun close() {
        mutex.withLock {
            try {
                captureSession?.close()
                captureSession = null

                cameraDevice?.close()
                cameraDevice = null

                previewReader?.close()
                previewReader = null

                stillReader?.close()
                stillReader = null

                stopBackgroundThread()
                _status.value = CameraStatus.CLOSED
            } catch (_: Exception) {}
        }
    }

    private fun findBackCameraId(): String? {
        val ids = cameraManager.cameraIdList
        for (id in ids) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return ids.firstOrNull()
    }

    private fun extractAnalysisFrame(image: Image): AnalysisFrame {
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val yBytes = ByteArray(buffer.remaining())
        buffer.get(yBytes)
        return AnalysisFrame(
            width = image.width,
            height = image.height,
            grayscaleBytes = yBytes
        )
    }

    private fun saveJpegImage(image: Image): File {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val outputFile = File(context.cacheDir, "webcam_capture_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outputFile).use { fos ->
            fos.write(bytes)
            fos.flush()
        }
        return outputFile
    }
}
