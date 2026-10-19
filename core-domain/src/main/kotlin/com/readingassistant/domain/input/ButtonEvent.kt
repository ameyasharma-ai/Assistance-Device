package com.readingassistant.domain.input

/**
 * Physical button controls available on the wearable shoes.
 */
enum class PhysicalButton {
    LEFT,
    RIGHT
}

/**
 * Duration-based classification of physical button presses.
 */
enum class PressType {
    SHORT_PRESS,
    DOUBLE_PRESS,
    LONG_PRESS
}

/**
 * Raw or debounced button event originating from shoe controls or developer simulator.
 */
data class ButtonEvent(
    val button: PhysicalButton,
    val type: PressType,
    val timestampMs: Long = System.currentTimeMillis()
)
