package com.readingassistant.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.readingassistant.domain.feedback.FeedbackController
import com.readingassistant.domain.model.SpokenPrompt
import com.readingassistant.domain.model.TactilePattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Production Android implementation of [FeedbackController].
 * Interfaces with hardware haptic actuators via [Vibrator] / [VibrationEffect]
 * with graceful fallback for simulated environments and devices lacking haptics.
 */
class AndroidFeedbackController(
    private val context: Context,
    private val speechDelegate: ((SpokenPrompt) -> Unit)? = null
) : FeedbackController {

    private val tag = "AndroidFeedbackCtrl"

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

    // Observable inspection flows for Developer UI and test assertions
    private val _lastTactilePattern = MutableStateFlow<TactilePattern?>(null)
    val lastTactilePattern: StateFlow<TactilePattern?> = _lastTactilePattern.asStateFlow()

    private val _lastSpokenPrompt = MutableStateFlow<SpokenPrompt?>(null)
    val lastSpokenPrompt: StateFlow<SpokenPrompt?> = _lastSpokenPrompt.asStateFlow()

    override suspend fun emitHaptic(pattern: TactilePattern) {
        _lastTactilePattern.value = pattern
        Log.d(tag, "Emitting tactile pattern: $pattern")

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

    override suspend fun speak(prompt: SpokenPrompt) {
        _lastSpokenPrompt.value = prompt
        Log.i(tag, "Speech prompt announced: ${prompt.message} (Priority=${prompt.priority})")
        speechDelegate?.invoke(prompt)
    }

    override suspend fun stopHaptics() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.w(tag, "Error stopping haptics", e)
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
