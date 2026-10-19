package com.readingassistant.domain.model

/**
 * Granular quality metrics calculated from a camera analysis frame or still capture.
 * All normalized scores range between 0.0f (lowest quality) to 1.0f (highest quality).
 */
data class QualityScore(
    val sharpness: Float,
    val exposure: Float,
    val framingCompleteness: Float,
    val glareRatio: Float,
    val stability: Float
) {
    val overallConfidence: Float
        get() = (sharpness * 0.35f) +
                (framingCompleteness * 0.25f) +
                (exposure * 0.15f) +
                ((1.0f - glareRatio) * 0.15f) +
                (stability * 0.10f)
}

/**
 * Discrete quality issues that prevent a frame from being considered capture-ready.
 */
enum class QualityIssue(val readableMessage: String) {
    BLURRY("Page is blurry or camera is moving"),
    PAGE_CROPPED("Page edges are cut off; move camera higher"),
    TOO_DARK("Lighting is too dark; increase illumination"),
    TOO_BRIGHT("Lighting is too bright or washed out"),
    UNSTABLE("Device is shaking; hold steady"),
    GLARE_DETECTED("Glare or reflection obscures the text"),
    INSUFFICIENT_CONFIDENCE("Document not detected clearly")
}

/**
 * Decision reached by the Image Quality Engine for a given frame.
 */
sealed interface ReadinessDecision {
    val score: QualityScore

    data class Ready(
        override val score: QualityScore
    ) : ReadinessDecision

    data class NotReady(
        override val score: QualityScore,
        val primaryIssue: QualityIssue,
        val userGuidance: String = primaryIssue.readableMessage
    ) : ReadinessDecision
}
