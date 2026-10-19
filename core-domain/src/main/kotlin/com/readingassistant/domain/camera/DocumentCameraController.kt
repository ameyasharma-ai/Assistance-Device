package com.readingassistant.domain.camera

import com.readingassistant.domain.model.AssistantError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Universal document camera controller interface.
 * Hardware drivers (USB UVC, CameraX) and simulated camera sources implement this contract.
 */
interface DocumentCameraController {

    val status: StateFlow<CameraStatus>

    /**
     * Stream of downsampled analysis frames for live quality scoring.
     */
    val analysisFrames: Flow<AnalysisFrame>

    /**
     * Open camera and prepare resources.
     */
    suspend fun open(): Result<Unit>

    /**
     * Start live preview stream producing analysis frames.
     */
    suspend fun startPreview(): Result<Unit>

    /**
     * Stop preview stream and put sensor in low-power mode.
     */
    suspend fun stopPreview(): Result<Unit>

    /**
     * Capture a full-resolution still image to persistent disk storage.
     * Returns the absolute file path of the captured raw image.
     */
    suspend fun captureStill(): Result<String>

    /**
     * Release all USB, camera, and buffer resources immediately.
     */
    suspend fun close()
}
