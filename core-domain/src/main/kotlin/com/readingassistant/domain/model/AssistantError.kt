package com.readingassistant.domain.model

/**
 * Standard error codes across hardware, vision, AI, and storage layers.
 */
enum class ErrorCode(val spokenMessage: String) {
    // Camera
    CAMERA_UNAVAILABLE("Camera is not detected or unavailable"),
    CAMERA_DISCONNECTED("Camera was disconnected"),
    CAPTURE_FAILED("Failed to capture image"),
    
    // Quality & OCR
    IMAGE_UNREADABLE("Image quality is insufficient to read"),
    OCR_FAILED("Text extraction encountered an error"),
    
    // AI & Network
    NETWORK_OFFLINE("Internet connection is offline"),
    AI_TIMEOUT("AI service request timed out"),
    AI_MALFORMED_RESPONSE("Invalid response received from AI service"),
    AI_RATE_LIMITED("AI service is busy; please try again shortly"),
    AI_AUTH_FAILED("Authentication error connecting to AI service"),
    
    // Storage & System
    STORAGE_FULL("Internal storage is full"),
    DATABASE_ERROR("Failed to access local document database"),
    
    // Playback
    TTS_INITIALIZATION_FAILED("Speech engine initialization failed"),
    AUDIO_FOCUS_LOST("Audio playback was interrupted")
}

/**
 * Categorization of whether an error can be retried automatically or requires user action.
 */
enum class ErrorRecoverability {
    AUTOMATIC_RETRY,
    USER_ACTION_REQUIRED,
    FATAL
}

/**
 * Typed domain error model for the assistant.
 */
data class AssistantError(
    val code: ErrorCode,
    val message: String = code.spokenMessage,
    val recoverability: ErrorRecoverability = ErrorRecoverability.USER_ACTION_REQUIRED,
    val cause: Throwable? = null
) {
    override fun toString(): String = "AssistantError(code=$code, message='$message', recoverability=$recoverability)"
}
