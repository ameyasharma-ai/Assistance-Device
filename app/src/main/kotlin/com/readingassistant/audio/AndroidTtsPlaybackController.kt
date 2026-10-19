package com.readingassistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.readingassistant.domain.model.DocumentId
import com.readingassistant.domain.model.PlaybackSpeed
import com.readingassistant.domain.model.PlaybackStatus
import com.readingassistant.domain.model.ReadingPosition
import com.readingassistant.domain.model.SpeechSegment
import com.readingassistant.domain.model.StudyDocument
import com.readingassistant.domain.playback.StudyPlaybackController
import com.readingassistant.domain.playback.TextSegmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Production Android Auditory Playback Controller.
 * Leverages [TextToSpeech] with segmented speech boundaries and audio focus arbitration.
 * Automatically falls back to deterministic time progression when running on testing environments
 * or devices without TTS language engines installed.
 */
class AndroidTtsPlaybackController(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) : StudyPlaybackController, TextToSpeech.OnInitListener {

    private val tag = "AndroidTtsCtrl"

    private val _playbackStatus = MutableStateFlow(PlaybackStatus.STOPPED)
    override val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus.asStateFlow()

    private val _currentPosition = MutableStateFlow(ReadingPosition(DocumentId("empty")))
    override val currentPosition: StateFlow<ReadingPosition> = _currentPosition.asStateFlow()

    private val _currentSegment = MutableStateFlow<SpeechSegment?>(null)
    override val currentSegment: StateFlow<SpeechSegment?> = _currentSegment.asStateFlow()

    private var activeDocument: StudyDocument? = null
    private var segmentedSections: List<List<SpeechSegment>> = emptyList()

    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private var fallbackTimerJob: Job? = null
    private var currentSpeed: PlaybackSpeed = PlaybackSpeed.NORMAL

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        try {
            textToSpeech = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.w(tag, "Failed to initialize Android TextToSpeech engine; will use simulation fallback", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val tts = textToSpeech ?: return
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(tag, "US English TTS language not supported or missing data")
            } else {
                isTtsInitialized = true
                tts.setSpeechRate(currentSpeed.multiplier)
                setupUtteranceListener(tts)
                Log.i(tag, "Android TTS engine initialized successfully")
            }
        } else {
            Log.w(tag, "TextToSpeech init failed with status: $status")
        }
    }

    private fun setupUtteranceListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _playbackStatus.value = PlaybackStatus.PLAYING
            }

            override fun onDone(utteranceId: String?) {
                scope.launch {
                    if (_playbackStatus.value == PlaybackStatus.PLAYING) {
                        if (!advanceToNextSegment()) {
                            _playbackStatus.value = PlaybackStatus.STOPPED
                            abandonAudioFocus()
                        } else {
                            speakCurrentSegment()
                        }
                    }
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                Log.w(tag, "TTS utterance error: $utteranceId")
                scope.launch {
                    if (_playbackStatus.value == PlaybackStatus.PLAYING) {
                        advanceToNextSegment()
                    }
                }
            }
        })
    }

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
        if (activeDocument == null || segmentedSections.isEmpty()) return

        requestAudioFocus()
        _playbackStatus.value = PlaybackStatus.PLAYING

        if (isTtsInitialized && textToSpeech != null) {
            speakCurrentSegment()
        } else {
            // Fallback for test / headless runtime
            startFallbackSimulationLoop()
        }
    }

    private fun speakCurrentSegment() {
        val segment = _currentSegment.value ?: return
        val tts = textToSpeech ?: return

        val utteranceId = "seg_${segment.sectionIndex}_${segment.segmentIndex}"
        val params = android.os.Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        tts.speak(segment.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun startFallbackSimulationLoop() {
        fallbackTimerJob?.cancel()
        fallbackTimerJob = scope.launch {
            while (isActive && _playbackStatus.value == PlaybackStatus.PLAYING) {
                val delayTime = (1500L / currentSpeed.multiplier).toLong()
                delay(delayTime)
                if (!advanceToNextSegment()) {
                    _playbackStatus.value = PlaybackStatus.STOPPED
                    abandonAudioFocus()
                    break
                }
            }
        }
    }

    override suspend fun pause() {
        fallbackTimerJob?.cancel()
        fallbackTimerJob = null
        textToSpeech?.stop()
        if (_playbackStatus.value == PlaybackStatus.PLAYING) {
            _playbackStatus.value = PlaybackStatus.PAUSED
        }
        abandonAudioFocus()
    }

    override suspend fun stop() {
        fallbackTimerJob?.cancel()
        fallbackTimerJob = null
        textToSpeech?.stop()
        _playbackStatus.value = PlaybackStatus.STOPPED
        abandonAudioFocus()
    }

    override suspend fun nextParagraph() {
        val currentSec = _currentPosition.value.sectionIndex
        val currentSeg = _currentPosition.value.segmentIndex
        if (currentSec in segmentedSections.indices) {
            val segments = segmentedSections[currentSec]
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
        val currentSec = _currentPosition.value.sectionIndex
        val currentSeg = _currentPosition.value.segmentIndex
        val targetSeg = maxOf(0, currentSeg - 2)
        seekTo(currentSec, targetSeg)
    }

    override fun setPlaybackSpeed(speed: PlaybackSpeed) {
        currentSpeed = speed
        if (isTtsInitialized) {
            textToSpeech?.setSpeechRate(speed.multiplier)
        }
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

        if (_playbackStatus.value == PlaybackStatus.PLAYING) {
            if (isTtsInitialized && textToSpeech != null) {
                speakCurrentSegment()
            }
        }
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
        if (activeDocument == null) return false
        val currentSec = _currentPosition.value.sectionIndex
        val currentSeg = _currentPosition.value.segmentIndex

        if (currentSec !in segmentedSections.indices) return false
        val segments = segmentedSections[currentSec]

        return if (currentSeg + 1 < segments.size) {
            seekTo(currentSec, currentSeg + 1)
            true
        } else if (currentSec + 1 < segmentedSections.size) {
            seekTo(currentSec + 1, 0)
            true
        } else {
            false
        }
    }

    private fun requestAudioFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .build()
                audioFocusRequest = request
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.w(tag, "Audio focus request failed", e)
        }
    }

    private fun abandonAudioFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(tag, "Audio focus release failed", e)
        }
    }

    fun shutdown() {
        fallbackTimerJob?.cancel()
        fallbackTimerJob = null
        _playbackStatus.value = PlaybackStatus.STOPPED
        abandonAudioFocus()
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            isTtsInitialized = false
        } catch (e: Exception) {
            Log.w(tag, "Error shutting down TTS", e)
        }
    }
}
