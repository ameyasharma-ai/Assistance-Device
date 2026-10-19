package com.readingassistant.domain.model

/**
 * Standard tactile vibration patterns used to convey operational state to non-sighted users.
 * Specified as durations (in milliseconds) of alternating [vibrate, pause, vibrate, ...].
 */
enum class TactilePattern(val waveformTimingsMs: LongArray, val repeat: Int = -1) {
    // Single subtle buzz when scanner is initialized
    SCANNER_ACTIVATED(longArrayOf(0, 100)),

    // Distinct double buzz when page quality is verified and ready for capture
    DOCUMENT_READY(longArrayOf(0, 120, 80, 120)),

    // Sharp single pulse on shutter capture
    CAPTURED(longArrayOf(0, 200)),

    // Gentle pulse when cloud processing commences
    PROCESSING_STARTED(longArrayOf(0, 80)),

    // Triumphant triple pulse on document ready for playback
    PROCESSING_COMPLETED(longArrayOf(0, 150, 100, 150, 100, 250)),

    // Gentle tick for paragraph/section navigation
    NAVIGATION_TICK(longArrayOf(0, 40)),

    // Double short warning pulse for recoverable issue (e.g. slight blur)
    RECOVERABLE_WARNING(longArrayOf(0, 80, 100, 80)),

    // Long urgent vibration for fatal or unrecoverable error
    FATAL_ERROR(longArrayOf(0, 500, 150, 500)),

    // Subdued triple tick for low battery advisory
    LOW_BATTERY(longArrayOf(0, 60, 60, 60, 60, 60))
}

/**
 * Priority for spoken audio prompts.
 */
enum class PromptPriority {
    IMMEDIATE_INTERRUPT,
    QUEUED_AFTER_CURRENT,
    LOW_PRIORITY_BACKGROUND
}

/**
 * Accessible audio announcement queued for earphone playback.
 */
data class SpokenPrompt(
    val id: String,
    val message: String,
    val priority: PromptPriority = PromptPriority.IMMEDIATE_INTERRUPT
)
