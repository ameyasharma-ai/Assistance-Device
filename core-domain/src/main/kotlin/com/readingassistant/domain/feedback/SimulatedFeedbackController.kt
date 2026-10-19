package com.readingassistant.domain.feedback

import com.readingassistant.domain.model.SpokenPrompt
import com.readingassistant.domain.model.TactilePattern
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Collections

/**
 * Developer simulation feedback controller recording all tactile and audio cues for verification.
 */
class SimulatedFeedbackController : FeedbackController {

    private val _hapticEvents = MutableSharedFlow<TactilePattern>(extraBufferCapacity = 64)
    val hapticEvents: SharedFlow<TactilePattern> = _hapticEvents.asSharedFlow()

    private val _spokenEvents = MutableSharedFlow<SpokenPrompt>(extraBufferCapacity = 64)
    val spokenEvents: SharedFlow<SpokenPrompt> = _spokenEvents.asSharedFlow()

    val recordedHaptics: MutableList<TactilePattern> = Collections.synchronizedList(mutableListOf())
    val recordedPrompts: MutableList<SpokenPrompt> = Collections.synchronizedList(mutableListOf())

    override suspend fun emitHaptic(pattern: TactilePattern) {
        recordedHaptics.add(pattern)
        _hapticEvents.emit(pattern)
    }

    override suspend fun speak(prompt: SpokenPrompt) {
        recordedPrompts.add(prompt)
        _spokenEvents.emit(prompt)
    }

    override suspend fun stopHaptics() {
        // No-op for simulation
    }

    fun clearHistory() {
        recordedHaptics.clear()
        recordedPrompts.clear()
    }
}
