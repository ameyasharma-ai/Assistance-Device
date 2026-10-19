package com.readingassistant.domain.input

import com.readingassistant.domain.state.AssistantState

/**
 * Central policy engine that maps physical button events into semantic commands
 * based on the active AssistantState, enforcing debounce and duplicate suppression.
 */
class InputPolicy(
    private val debounceWindowMs: Long = 150L
) {
    private var lastLeftPressTimestampMs: Long = 0L
    private var lastRightPressTimestampMs: Long = 0L

    /**
     * Translates a raw ButtonEvent into a semantic ButtonCommand, or ButtonCommand.None
     * if debounced, duplicate, or invalid in the current state.
     */
    @Synchronized
    fun evaluate(event: ButtonEvent, currentState: AssistantState): ButtonCommand {
        // Debounce check per physical button
        val now = event.timestampMs
        val lastTimestamp = when (event.button) {
            PhysicalButton.LEFT -> lastLeftPressTimestampMs
            PhysicalButton.RIGHT -> lastRightPressTimestampMs
        }

        if (now - lastTimestamp < debounceWindowMs) {
            // Discard bounce within window
            return ButtonCommand.None
        }

        // Record timestamp
        when (event.button) {
            PhysicalButton.LEFT -> lastLeftPressTimestampMs = now
            PhysicalButton.RIGHT -> lastRightPressTimestampMs = now
        }

        return mapEventToCommand(event, currentState)
    }

    private fun mapEventToCommand(event: ButtonEvent, state: AssistantState): ButtonCommand {
        return when (state) {
            is AssistantState.Idle -> {
                when {
                    event.button == PhysicalButton.LEFT && event.type == PressType.SHORT_PRESS -> {
                        ButtonCommand.StartScan
                    }
                    else -> ButtonCommand.None
                }
            }

            is AssistantState.ScannerStarting,
            is AssistantState.Scanning,
            is AssistantState.DocumentReady -> {
                when {
                    event.button == PhysicalButton.LEFT && event.type == PressType.SHORT_PRESS -> {
                        ButtonCommand.CancelScan
                    }
                    event.button == PhysicalButton.RIGHT && event.type == PressType.SHORT_PRESS -> {
                        ButtonCommand.CaptureStill
                    }
                    else -> ButtonCommand.None
                }
            }

            is AssistantState.Capturing,
            is AssistantState.Captured,
            is AssistantState.CaptureValidation -> {
                // Ignore button presses during capture acquisition and internal validation
                ButtonCommand.None
            }

            is AssistantState.AwaitingConfirmation -> {
                when {
                    event.button == PhysicalButton.LEFT && event.type == PressType.SHORT_PRESS -> {
                        ButtonCommand.RetakeCapture
                    }
                    event.button == PhysicalButton.RIGHT && event.type == PressType.SHORT_PRESS -> {
                        ButtonCommand.ConfirmProcessing
                    }
                    else -> ButtonCommand.None
                }
            }

            is AssistantState.Processing -> {
                when {
                    event.button == PhysicalButton.LEFT && event.type == PressType.LONG_PRESS -> {
                        ButtonCommand.CancelScan
                    }
                    // Reject clicks to prevent duplicate network submissions
                    else -> ButtonCommand.None
                }
            }

            is AssistantState.DocumentReadyForPlayback,
            is AssistantState.Playing,
            is AssistantState.Paused -> {
                when (event.button) {
                    PhysicalButton.RIGHT -> when (event.type) {
                        PressType.SHORT_PRESS -> ButtonCommand.TogglePlayPause
                        PressType.DOUBLE_PRESS -> ButtonCommand.NextParagraph
                        PressType.LONG_PRESS -> ButtonCommand.NextSection
                    }
                    PhysicalButton.LEFT -> when (event.type) {
                        PressType.SHORT_PRESS -> ButtonCommand.PreviousParagraph
                        PressType.DOUBLE_PRESS -> ButtonCommand.Rewind(15)
                        PressType.LONG_PRESS -> ButtonCommand.PreviousSection
                    }
                }
            }

            is AssistantState.Error -> {
                // Any single press dismisses error
                if (event.type == PressType.SHORT_PRESS) {
                    ButtonCommand.DismissError
                } else {
                    ButtonCommand.None
                }
            }
        }
    }

    /**
     * Resets debounce timestamps (useful in unit testing).
     */
    @Synchronized
    fun resetDebounce() {
        lastLeftPressTimestampMs = 0L
        lastRightPressTimestampMs = 0L
    }
}
