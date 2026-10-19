package com.readingassistant.domain.feedback

import com.readingassistant.domain.model.SpokenPrompt
import com.readingassistant.domain.model.TactilePattern

/**
 * Universal feedback interface coordinating haptic pulses and speech cues for visually impaired users.
 */
interface FeedbackController {

    /**
     * Emit a structured tactile vibration pattern.
     */
    suspend fun emitHaptic(pattern: TactilePattern)

    /**
     * Announce an accessible speech prompt over Bluetooth earphones or speaker.
     */
    suspend fun speak(prompt: SpokenPrompt)

    /**
     * Stop active vibrations immediately.
     */
    suspend fun stopHaptics()
}
