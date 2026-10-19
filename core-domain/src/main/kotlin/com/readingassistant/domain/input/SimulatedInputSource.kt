package com.readingassistant.domain.input

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Developer simulation input source enabling programmatic triggering of every button interaction.
 */
class SimulatedInputSource : InputController {

    private val _rawEvents = MutableSharedFlow<ButtonEvent>(replay = 1, extraBufferCapacity = 64)
    override val rawEvents: SharedFlow<ButtonEvent> = _rawEvents.asSharedFlow()

    override suspend fun emitEvent(event: ButtonEvent) {
        _rawEvents.emit(event)
    }

    suspend fun pressLeftShort(timestampMs: Long = System.currentTimeMillis()) {
        emitEvent(ButtonEvent(PhysicalButton.LEFT, PressType.SHORT_PRESS, timestampMs))
    }

    suspend fun pressRightShort(timestampMs: Long = System.currentTimeMillis()) {
        emitEvent(ButtonEvent(PhysicalButton.RIGHT, PressType.SHORT_PRESS, timestampMs))
    }

    suspend fun pressLeftDouble(timestampMs: Long = System.currentTimeMillis()) {
        emitEvent(ButtonEvent(PhysicalButton.LEFT, PressType.DOUBLE_PRESS, timestampMs))
    }

    suspend fun pressRightDouble(timestampMs: Long = System.currentTimeMillis()) {
        emitEvent(ButtonEvent(PhysicalButton.RIGHT, PressType.DOUBLE_PRESS, timestampMs))
    }

    suspend fun pressLeftLong(timestampMs: Long = System.currentTimeMillis()) {
        emitEvent(ButtonEvent(PhysicalButton.LEFT, PressType.LONG_PRESS, timestampMs))
    }

    suspend fun pressRightLong(timestampMs: Long = System.currentTimeMillis()) {
        emitEvent(ButtonEvent(PhysicalButton.RIGHT, PressType.LONG_PRESS, timestampMs))
    }
}
