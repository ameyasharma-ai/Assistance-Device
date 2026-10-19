package com.readingassistant.domain.ocr

/**
 * Universal OCR engine contract.
 * Supported by Google ML Kit / Tesseract on Android and FakeOcrEngine for unit testing and simulation.
 */
interface OcrEngine {

    /**
     * Extracts text and structured layout blocks from a local image file.
     * Does NOT modify or overwrite the original source image.
     */
    suspend fun extractText(imagePath: String): Result<OcrResult>
}
