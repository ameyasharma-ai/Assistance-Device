package com.readingassistant.domain.input

import kotlinx.coroutines.flow.SharedFlow

/**
 * Interface unifying hardware button drivers (BLE/HID) and simulated input sources.
 */
interface InputController {
    /**
     * Observable stream of raw or hardware-filtered button events.
     */
    val rawEvents: SharedFlow<ButtonEvent>

    /**
     * Ingest a button event from hardware adapter or simulated source.
     */
    suspend fun emitEvent(event: ButtonEvent)
}
