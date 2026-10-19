package com.readingassistant.haptics

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import com.readingassistant.domain.feedback.FeedbackController
import com.readingassistant.domain.model.PromptPriority
import com.readingassistant.domain.model.SpokenPrompt
import com.readingassistant.domain.model.TactilePattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Production Android implementation of [FeedbackController].
 * Interfaces with hardware haptic actuators via [Vibrator] / [VibrationEffect]
 * and synthesizes auditory cues via [TextToSpeech] and [ToneGenerator]
 * to ensure all spoken cues and earcons play clearly through active audio output
 * (Bluetooth earbuds or laptop speakers).
 */
class AndroidFeedbackController(
    private val context: Context,
    private val speechDelegate: ((SpokenPrompt) -> Unit)? = null
) : FeedbackController, TextToSpeech.OnInitListener {

    private val tag = "AndroidFeedbackCtrl"

    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private val pendingPrompts = mutableListOf<String>()

    private val toneGenerator: ToneGenerator? by lazy {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 85)
        } catch (e: Exception) {
            Log.w(tag, "Failed to create ToneGenerator", e)
            null
        }
    }

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to obtain system Vibrator service", e)
            null
        }
    }

    init {
        try {
            textToSpeech = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.w(tag, "Failed to initialize TextToSpeech for feedback", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val tts = textToSpeech ?: return
            val res = tts.setLanguage(Locale.US)
            if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts.setAudioAttributes(attrs)
                isTtsReady = true
                Log.i(tag, "Feedback TextToSpeech ready on STREAM_MUSIC")

                synchronized(pendingPrompts) {
                    for (msg in pendingPrompts) {
                        speakText(msg, flush = false)
                    }
                    pendingPrompts.clear()
                }
            } else {
                Log.w(tag, "US English TTS language not supported in FeedbackController")
            }
        } else {
            Log.w(tag, "Feedback TextToSpeech init failed with status: $status")
        }
    }

    // Observable inspection flows for Developer UI and test assertions
    private val _lastTactilePattern = MutableStateFlow<TactilePattern?>(null)
    val lastTactilePattern: StateFlow<TactilePattern?> = _lastTactilePattern.asStateFlow()

    private val _lastSpokenPrompt = MutableStateFlow<SpokenPrompt?>(null)
    val lastSpokenPrompt: StateFlow<SpokenPrompt?> = _lastSpokenPrompt.asStateFlow()

    override suspend fun emitHaptic(pattern: TactilePattern) {
        _lastTactilePattern.value = pattern
        Log.d(tag, "Emitting tactile pattern: $pattern")

        // Play earcon tone through STREAM_MUSIC
        playEarcon(pattern)

        val vib = vibrator
        if (vib == null || !vib.hasVibrator()) {
            Log.d(tag, "Vibrator unavailable or hardware missing; simulated pattern logged: $pattern")
            return
        }

        try {
            val timings = pattern.waveformTimingsMs
            val amplitudes = getAmplitudesForPattern(pattern)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vib.hasAmplitudeControl() && amplitudes != null && amplitudes.size == timings.size) {
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                } else {
                    VibrationEffect.createWaveform(timings, -1)
                }
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(timings, -1)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error triggering vibration for pattern $pattern", e)
        }
    }

    private fun playEarcon(pattern: TactilePattern) {
        try {
            when (pattern) {
                TactilePattern.NAVIGATION_TICK -> toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
                TactilePattern.DOCUMENT_READY -> toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 80)
                TactilePattern.CAPTURED -> toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 100)
                TactilePattern.PROCESSING_COMPLETED -> toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                TactilePattern.FATAL_ERROR -> toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 200)
                TactilePattern.RECOVERABLE_WARNING -> toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 100)
                else -> toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
            }
        } catch (e: Exception) {
            Log.w(tag, "ToneGenerator earcon failed", e)
        }
    }

    override suspend fun speak(prompt: SpokenPrompt) {
        _lastSpokenPrompt.value = prompt
        Log.i(tag, "Speech prompt announced: ${prompt.message} (Priority=${prompt.priority})")
        speechDelegate?.invoke(prompt)

        val isFlush = prompt.priority == PromptPriority.IMMEDIATE_INTERRUPT
        speakText(prompt.message, flush = isFlush)
    }

    private fun speakText(text: String, flush: Boolean) {
        if (isTtsReady && textToSpeech != null) {
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val utteranceId = "prompt_${System.currentTimeMillis()}"
            val params = android.os.Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            textToSpeech?.speak(text, mode, params, utteranceId)
        } else {
            synchronized(pendingPrompts) {
                if (pendingPrompts.size < 5) {
                    pendingPrompts.add(text)
                }
            }
        }
    }

    override suspend fun stopHaptics() {
        try {
            vibrator?.cancel()
            textToSpeech?.stop()
        } catch (e: Exception) {
            Log.w(tag, "Error stopping feedback", e)
        }
    }

    fun shutdown() {
        try {
            vibrator?.cancel()
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            isTtsReady = false
            toneGenerator?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error during shutdown", e)
        }
    }

    private fun getAmplitudesForPattern(pattern: TactilePattern): IntArray? {
        return when (pattern) {
            TactilePattern.SCANNER_ACTIVATED -> intArrayOf(0, 180)
            TactilePattern.DOCUMENT_READY -> intArrayOf(0, 200, 0, 255)
            TactilePattern.CAPTURED -> intArrayOf(0, 255)
            TactilePattern.PROCESSING_STARTED -> intArrayOf(0, 120)
            TactilePattern.PROCESSING_COMPLETED -> intArrayOf(0, 180, 0, 200, 0, 255)
            TactilePattern.NAVIGATION_TICK -> intArrayOf(0, 80)
            TactilePattern.RECOVERABLE_WARNING -> intArrayOf(0, 180, 0, 180)
            TactilePattern.FATAL_ERROR -> intArrayOf(0, 255, 0, 255)
            TactilePattern.LOW_BATTERY -> intArrayOf(0, 100, 0, 100, 0, 100)
        }
    }
}

