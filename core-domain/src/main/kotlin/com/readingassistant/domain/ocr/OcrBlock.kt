package com.readingassistant.domain.ocr

/**
 * Normalized 2D bounding coordinates (0.0 to 1.0) of recognized text.
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * An individual recognized line of text.
 */
data class OcrLine(
    val text: String,
    val confidence: Float,
    val boundingBox: BoundingBox? = null
)

/**
 * A recognized block or paragraph of text.
 */
data class OcrBlock(
    val text: String,
    val confidence: Float,
    val lines: List<OcrLine> = emptyList(),
    val boundingBox: BoundingBox? = null
)

/**
 * Complete result produced by an OCR extraction pass.
 */
data class OcrResult(
    val fullText: String,
    val confidence: Float,
    val blocks: List<OcrBlock> = emptyList(),
    val uncertainWords: List<String> = emptyList()
) {
    val isHighConfidence: Boolean
        get() = confidence >= 0.70f && uncertainWords.isEmpty()
}
