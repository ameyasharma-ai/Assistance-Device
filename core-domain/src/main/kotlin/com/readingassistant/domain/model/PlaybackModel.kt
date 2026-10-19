package com.readingassistant.domain.model

/**
 * An individual speakable segment (sentence or paragraph) extracted from a study section.
 */
data class SpeechSegment(
    val segmentIndex: Int,
    val text: String,
    val sectionIndex: Int,
    val isParagraphBoundary: Boolean = false
)

/**
 * Persisted playback progress allowing seamless resumption across sessions and app restarts.
 */
data class ReadingPosition(
    val documentId: DocumentId,
    val sectionIndex: Int = 0,
    val segmentIndex: Int = 0,
    val characterOffset: Int = 0,
    val lastUpdatedMs: Long = System.currentTimeMillis()
)

/**
 * Active playback status of the study audio engine.
 */
enum class PlaybackStatus {
    STOPPED,
    PLAYING,
    PAUSED,
    BUFFERING
}

/**
 * User-configurable speech rates.
 */
enum class PlaybackSpeed(val multiplier: Float) {
    VERY_SLOW(0.75f),
    NORMAL(1.0f),
    FAST(1.25f),
    VERY_FAST(1.5f),
    DOUBLE(2.0f)
}
