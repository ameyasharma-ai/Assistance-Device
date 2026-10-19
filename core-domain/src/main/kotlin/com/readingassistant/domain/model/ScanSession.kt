package com.readingassistant.domain.model

/**
 * Unique identifier for a scanning session.
 */
data class SessionId(val value: String) {
    override fun toString(): String = value
}

/**
 * Domain model representing an active or completed scan session.
 */
data class ScanSession(
    val id: SessionId,
    val startedAtMs: Long,
    val rawImagePath: String? = null,
    val processedImagePath: String? = null,
    val ocrText: String? = null,
    val status: SessionStatus = SessionStatus.INITIALIZED
)

enum class SessionStatus {
    INITIALIZED,
    SCANNING,
    CAPTURED,
    VALIDATED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}
