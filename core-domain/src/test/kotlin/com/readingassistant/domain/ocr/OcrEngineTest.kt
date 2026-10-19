package com.readingassistant.domain.ocr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OcrEngineTest {

    private lateinit var ocrEngine: FakeOcrEngine

    @Before
    fun setUp() {
        ocrEngine = FakeOcrEngine()
    }

    @Test
    fun `standard textbook text extracts with high confidence`() = runTest {
        ocrEngine.corpusType = OcrCorpusType.STANDARD_TEXTBOOK
        val result = ocrEngine.extractText("/data/scans/page1.jpg")

        assertTrue(result.isSuccess)
        val ocr = result.getOrThrow()
        assertTrue(ocr.isHighConfidence)
        assertTrue(ocr.fullText.contains("Laws of Motion"))
        assertEquals(1, ocr.blocks.size)
    }

    @Test
    fun `multi-column layout produces separate logical blocks`() = runTest {
        ocrEngine.corpusType = OcrCorpusType.MULTI_COLUMN
        val result = ocrEngine.extractText("/data/scans/two_column.jpg")

        assertTrue(result.isSuccess)
        val ocr = result.getOrThrow()
        assertEquals(2, ocr.blocks.size)
        assertTrue(ocr.blocks[0].text.contains("Column 1"))
        assertTrue(ocr.blocks[1].text.contains("Column 2"))
    }

    @Test
    fun `mathematical equations corpus documents known OCR limitation and flags uncertain symbols`() = runTest {
        ocrEngine.corpusType = OcrCorpusType.MATHEMATICAL_EQUATIONS
        val result = ocrEngine.extractText("/data/scans/math.jpg")

        assertTrue(result.isSuccess)
        val ocr = result.getOrThrow()
        assertFalse("Math symbols should lower confidence", ocr.isHighConfidence)
        assertTrue(ocr.uncertainWords.isNotEmpty())
        assertTrue(ocr.uncertainWords.contains("\u222B")) // Integral symbol
    }

    @Test
    fun `degraded text reports uncertain words`() = runTest {
        ocrEngine.corpusType = OcrCorpusType.DEGRADED_TEXT
        val result = ocrEngine.extractText("/data/scans/degraded.jpg")

        assertTrue(result.isSuccess)
        val ocr = result.getOrThrow()
        assertEquals(2, ocr.uncertainWords.size)
        assertTrue(ocr.confidence < 0.50f)
    }

    @Test
    fun `unreadable page returns structured failure`() = runTest {
        ocrEngine.corpusType = OcrCorpusType.UNREADABLE
        val result = ocrEngine.extractText("/data/scans/blank.jpg")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("OCR_FAILED") == true)
    }
}
