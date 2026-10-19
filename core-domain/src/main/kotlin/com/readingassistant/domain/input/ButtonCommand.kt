package com.readingassistant.domain.input

/**
 * High-level intent derived from physical button interactions in the current assistant state.
 */
sealed interface ButtonCommand {
    // Scanner commands
    data object StartScan : ButtonCommand
    data object CancelScan : ButtonCommand
    data object CaptureStill : ButtonCommand
    data object ConfirmProcessing : ButtonCommand
    data object RetakeCapture : ButtonCommand

    // Playback navigation commands
    data object TogglePlayPause : ButtonCommand
    data object NextParagraph : ButtonCommand
    data object PreviousParagraph : ButtonCommand
    data object RepeatParagraph : ButtonCommand
    data object NextSection : ButtonCommand
    data object PreviousSection : ButtonCommand
    data class Rewind(val seconds: Int = 15) : ButtonCommand

    // Error recovery
    data object DismissError : ButtonCommand

    // Ignored / No-op
    data object None : ButtonCommand
}
