package com.readingassistant.domain.ocr

import com.readingassistant.domain.model.AssistantError
import com.readingassistant.domain.model.ErrorCode

/**
 * Test corpus document types for OCR calibration.
 */
enum class OcrCorpusType {
    STANDARD_TEXTBOOK,
    MULTI_COLUMN,
    MATHEMATICAL_EQUATIONS,
    DATA_TABLE,
    DIAGRAM_WITH_LABELS,
    DEGRADED_TEXT,
    UNREADABLE
}

/**
 * Deterministic fake OCR engine supplying realistic textbook corpus extractions,
 * highlighting known OCR limitations with mathematical symbols and tables.
 */
class FakeOcrEngine : OcrEngine {

    var corpusType: OcrCorpusType = OcrCorpusType.STANDARD_TEXTBOOK

    override suspend fun extractText(imagePath: String): Result<OcrResult> {
        return when (corpusType) {
            OcrCorpusType.STANDARD_TEXTBOOK -> {
                val text = "Chapter 4: Laws of Motion\nWhen an object exerts a force on a second object, the second object exerts an equal and opposite force."
                val block = OcrBlock(
                    text = text,
                    confidence = 0.96f,
                    lines = listOf(
                        OcrLine("Chapter 4: Laws of Motion", 0.98f, BoundingBox(0.1f, 0.1f, 0.9f, 0.2f)),
                        OcrLine("When an object exerts a force on a second object, the second object exerts an equal and opposite force.", 0.95f, BoundingBox(0.1f, 0.25f, 0.9f, 0.45f))
                    )
                )
                Result.success(
                    OcrResult(
                        fullText = text,
                        confidence = 0.96f,
                        blocks = listOf(block),
                        uncertainWords = emptyList()
                    )
                )
            }

            OcrCorpusType.MULTI_COLUMN -> {
                val col1 = "Column 1: Friction opposes relative motion between contacting surfaces in mechanics."
                val col2 = "Column 2: Static friction prevents sliding until maximum tractive threshold is reached."
                Result.success(
                    OcrResult(
                        fullText = "$col1\n\n$col2",
                        confidence = 0.91f,
                        blocks = listOf(
                            OcrBlock(col1, 0.92f, listOf(OcrLine(col1, 0.92f, BoundingBox(0.05f, 0.1f, 0.48f, 0.9f)))),
                            OcrBlock(col2, 0.90f, listOf(OcrLine(col2, 0.90f, BoundingBox(0.52f, 0.1f, 0.95f, 0.9f))))
                        ),
                        uncertainWords = emptyList()
                    )
                )
            }

            OcrCorpusType.MATHEMATICAL_EQUATIONS -> {
                // Known OCR limitation: Greek characters and subscripts often yield lower confidence
                val text = "F_net = m * a\nIntegrate: \u222B F dt = \u0394p"
                Result.success(
                    OcrResult(
                        fullText = text,
                        confidence = 0.68f, // Below high-confidence threshold
                        blocks = listOf(OcrBlock(text, 0.68f)),
                        uncertainWords = listOf("\u222B", "\u0394p")
                    )
                )
            }

            OcrCorpusType.DATA_TABLE -> {
                val tableText = "Material | Friction Coefficient\nWood on Wood | 0.4\nSteel on Steel | 0.6\nTeflon on Steel | 0.04"
                Result.success(
                    OcrResult(
                        fullText = tableText,
                        confidence = 0.85f,
                        blocks = listOf(OcrBlock(tableText, 0.85f)),
                        uncertainWords = emptyList()
                    )
                )
            }

            OcrCorpusType.DIAGRAM_WITH_LABELS -> {
                val labels = "Figure 4.2: Free Body Diagram\nLabels: Normal Force (N), Gravitational Force (mg), Applied Force (F), Friction (f)"
                Result.success(
                    OcrResult(
                        fullText = labels,
                        confidence = 0.82f,
                        blocks = listOf(OcrBlock(labels, 0.82f)),
                        uncertainWords = emptyList()
                    )
                )
            }

            OcrCorpusType.DEGRADED_TEXT -> {
                val degraded = "Inertia is the resi...nce of any phys...al object."
                Result.success(
                    OcrResult(
                        fullText = degraded,
                        confidence = 0.45f,
                        blocks = listOf(OcrBlock(degraded, 0.45f)),
                        uncertainWords = listOf("resi...nce", "phys...al")
                    )
                )
            }

            OcrCorpusType.UNREADABLE -> {
                Result.failure(
                    AssistantError(ErrorCode.OCR_FAILED, "OCR engine could not find any readable text")
                        .asException()
                )
            }
        }
    }

    private fun AssistantError.asException(): Exception =
        IllegalStateException("$code: $message")
}
