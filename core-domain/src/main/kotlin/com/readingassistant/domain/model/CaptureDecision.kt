package com.readingassistant.domain.model

/**
 * Recommendations given when a captured still image is suboptimal.
 */
enum class RetakeRecommendation(val spokenPrompt: String) {
    RETAKE_BLUR("Image is blurry. Recommended to retake."),
    RETAKE_LIGHTING("Lighting is uneven or glaring. Recommended to retake."),
    RETAKE_FRAMING("Text appears cropped at the margins. Recommended to retake."),
    PROCEED_ANYWAY("Quality is acceptable to proceed.")
}

/**
 * Result of validating a full-resolution captured still image before submitting to AI.
 */
sealed interface CaptureValidationResult {
    val score: QualityScore

    data class Valid(
        override val score: QualityScore
    ) : CaptureValidationResult

    data class NeedsConfirmation(
        override val score: QualityScore,
        val recommendation: RetakeRecommendation,
        val explanation: String
    ) : CaptureValidationResult
}
