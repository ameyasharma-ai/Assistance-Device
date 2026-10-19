package com.readingassistant.domain.camera

import kotlinx.coroutines.flow.StateFlow

/**
 * Operating status of the document camera peripheral.
 */
enum class CameraStatus {
    CLOSED,
    OPENING,
    PREVIEWING,
    CAPTURING,
    DISCONNECTED,
    ERROR
}

/**
 * Lightweight analysis frame supplied to the quality engine.
 * Decoupled from Android Bitmap/Surface classes for hardware independence.
 */
data class AnalysisFrame(
    val width: Int,
    val height: Int,
    val grayscaleBytes: ByteArray,
    val timestampMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AnalysisFrame
        if (width != other.width) return false
        if (height != other.height) return false
        if (!grayscaleBytes.contentEquals(other.grayscaleBytes)) return false
        return timestampMs == other.timestampMs
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + grayscaleBytes.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}
