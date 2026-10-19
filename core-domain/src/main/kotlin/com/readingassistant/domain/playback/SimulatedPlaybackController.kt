package com.readingassistant.domain.playback

import com.readingassistant.domain.model.DocumentId
import com.readingassistant.domain.model.PlaybackSpeed
import com.readingassistant.domain.model.PlaybackStatus
import com.readingassistant.domain.model.ReadingPosition
import com.readingassistant.domain.model.SpeechSegment
import com.readingassistant.domain.model.StudyDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Deterministic fake playback controller for unit testing, simulation, and laptop development.
 */
class SimulatedPlaybackController(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : StudyPlaybackController {

    private val _playbackStatus = MutableStateFlow(PlaybackStatus.STOPPED)
    override val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus.asStateFlow()

    private val _currentPosition = MutableStateFlow(ReadingPosition(DocumentId("empty")))
    override val currentPosition: StateFlow<ReadingPosition> = _currentPosition.asStateFlow()

    private val _currentSegment = MutableStateFlow<SpeechSegment?>(null)
    override val currentSegment: StateFlow<SpeechSegment?> = _currentSegment.asStateFlow()

    private var activeDocument: StudyDocument? = null
    private var segmentedSections: List<List<SpeechSegment>> = emptyList()
    private var playbackJob: Job? = null
    var segmentDurationMs: Long = 100L

    override suspend fun loadDocument(document: StudyDocument, initialPosition: ReadingPosition?) {
        stop()
        activeDocument = document
        segmentedSections = document.sections.mapIndexed { idx, sec ->
            TextSegmenter.segmentSection(sec, idx)
        }

        val pos = initialPosition ?: ReadingPosition(document.id)
        _currentPosition.value = pos
        updateActiveSegment(pos.sectionIndex, pos.segmentIndex)
        _playbackStatus.value = PlaybackStatus.PAUSED
    }

    override suspend fun play() {
        val doc = activeDocument ?: return
        if (segmentedSections.isEmpty()) return

        _playbackStatus.value = PlaybackStatus.PLAYING
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (isActive && _playbackStatus.value == PlaybackStatus.PLAYING) {
                delay(segmentDurationMs)
                if (!advanceToNextSegment()) {
                    // Reached end of document
                    _playbackStatus.value = PlaybackStatus.STOPPED
                    break
                }
            }
        }
    }

    override suspend fun pause() {
        playbackJob?.cancel()
        playbackJob = null
        if (_playbackStatus.value == PlaybackStatus.PLAYING) {
            _playbackStatus.value = PlaybackStatus.PAUSED
        }
    }

    override suspend fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        _playbackStatus.value = PlaybackStatus.STOPPED
    }

    override suspend fun nextParagraph() {
        val currentSec = _currentPosition.value.sectionIndex
        val currentSeg = _currentPosition.value.segmentIndex
        if (currentSec in segmentedSections.indices) {
            val segments = segmentedSections[currentSec]
            // Find next segment marked as paragraph boundary or next segment
            val nextIdx = minOf(segments.lastIndex, currentSeg + 1)
            seekTo(currentSec, nextIdx)
        }
    }

    override suspend fun previousParagraph() {
        val currentSec = _currentPosition.value.sectionIndex
        val currentSeg = _currentPosition.value.segmentIndex
        val prevIdx = maxOf(0, currentSeg - 1)
        seekTo(currentSec, prevIdx)
    }

    override suspend fun repeatParagraph() {
        val currentSec = _currentPosition.value.sectionIndex
        val currentSeg = _currentPosition.value.segmentIndex
        seekTo(currentSec, currentSeg)
    }

    override suspend fun nextSection() {
        val nextSec = minOf(segmentedSections.lastIndex, _currentPosition.value.sectionIndex + 1)
        seekTo(nextSec, 0)
    }

    override suspend fun previousSection() {
        val prevSec = maxOf(0, _currentPosition.value.sectionIndex - 1)
        seekTo(prevSec, 0)
    }

    override suspend fun rewind(seconds: Int) {
        // In simulated mode, step back by 2 segments
        val currentSec = _currentPosition.value.sectionIndex
        val currentSeg = _currentPosition.value.segmentIndex
        val targetSeg = maxOf(0, currentSeg - 2)
        seekTo(currentSec, targetSeg)
    }

    override fun setPlaybackSpeed(speed: PlaybackSpeed) {
        segmentDurationMs = (100L / speed.multiplier).toLong()
    }

    private fun seekTo(sectionIndex: Int, segmentIndex: Int) {
        val doc = activeDocument ?: return
        val newPos = ReadingPosition(
            documentId = doc.id,
            sectionIndex = sectionIndex,
            segmentIndex = segmentIndex,
            characterOffset = 0
        )
        _currentPosition.value = newPos
        updateActiveSegment(sectionIndex, segmentIndex)
    }

    private fun updateActiveSegment(sectionIndex: Int, segmentIndex: Int) {
        if (sectionIndex in segmentedSections.indices) {
            val section = segmentedSections[sectionIndex]
            if (segmentIndex in section.indices) {
                _currentSegment.value = section[segmentIndex]
                return
            }
        }
        _currentSegment.value = null
    }

    private fun advanceToNextSegment(): Boolean {
        val doc = activeDocument ?: return false
        val currentSec = _currentPosition.value.sectionIndex
        val currentSeg = _currentPosition.value.segmentIndex

        if (currentSec !in segmentedSections.indices) return false
        val segments = segmentedSections[currentSec]

        return if (currentSeg + 1 < segments.size) {
            seekTo(currentSec, currentSeg + 1)
            true
        } else if (currentSec + 1 < segmentedSections.size) {
            // Advance to first segment of next section
            seekTo(currentSec + 1, 0)
            true
        } else {
            false // End of document
        }
    }
}
