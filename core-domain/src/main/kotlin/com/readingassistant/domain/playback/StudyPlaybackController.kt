package com.readingassistant.domain.playback

import com.readingassistant.domain.model.PlaybackSpeed
import com.readingassistant.domain.model.PlaybackStatus
import com.readingassistant.domain.model.ReadingPosition
import com.readingassistant.domain.model.SpeechSegment
import com.readingassistant.domain.model.StudyDocument
import kotlinx.coroutines.flow.StateFlow

/**
 * High-level playback controller interface for segmented auditory study.
 */
interface StudyPlaybackController {

    val playbackStatus: StateFlow<PlaybackStatus>
    val currentPosition: StateFlow<ReadingPosition>
    val currentSegment: StateFlow<SpeechSegment?>

    /**
     * Load document into player and cue at specified position.
     */
    suspend fun loadDocument(document: StudyDocument, initialPosition: ReadingPosition? = null)

    /**
     * Start or resume speech from current position.
     */
    suspend fun play()

    /**
     * Pause speech immediately, saving exact segment and offset.
     */
    suspend fun pause()

    /**
     * Stop speech and release audio resources.
     */
    suspend fun stop()

    /**
     * Jump forward to the start of the next paragraph or sentence.
     */
    suspend fun nextParagraph()

    /**
     * Jump backward to the start of the previous paragraph.
     */
    suspend fun previousParagraph()

    /**
     * Replay the currently active paragraph from its beginning.
     */
    suspend fun repeatParagraph()

    /**
     * Jump forward to the next logical study section.
     */
    suspend fun nextSection()

    /**
     * Jump backward to the previous logical study section.
     */
    suspend fun previousSection()

    /**
     * Rewind by a fixed duration or segments.
     */
    suspend fun rewind(seconds: Int = 15)

    /**
     * Adjust speech playback rate.
     */
    fun setPlaybackSpeed(speed: PlaybackSpeed)
}
