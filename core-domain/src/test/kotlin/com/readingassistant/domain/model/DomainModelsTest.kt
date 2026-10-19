package com.readingassistant.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainModelsTest {

    @Test
    fun `quality score calculates weighted overall confidence correctly`() {
        val score = QualityScore(
            sharpness = 1.0f,          // 0.35
            framingCompleteness = 1.0f,// 0.25
            exposure = 1.0f,           // 0.15
            glareRatio = 0.0f,         // (1-0)*0.15 = 0.15
            stability = 1.0f           // 0.10
        )
        // Sum = 0.35 + 0.25 + 0.15 + 0.15 + 0.10 = 1.0
        assertEquals(1.0f, score.overallConfidence, 0.001f)

        val badScore = QualityScore(
            sharpness = 0.0f,
            framingCompleteness = 0.0f,
            exposure = 0.0f,
            glareRatio = 1.0f,
            stability = 0.0f
        )
        assertEquals(0.0f, badScore.overallConfidence, 0.001f)
    }

    @Test
    fun `generation progress calculates percentage accurately and handles zero total`() {
        val progress = GenerationProgress(totalSections = 5, completedSections = 3, currentSectionTitle = "Solutions")
        assertEquals(60, progress.percentComplete)

        val zeroTotal = GenerationProgress(totalSections = 0, completedSections = 0)
        assertEquals(0, zeroTotal.percentComplete)
    }

    @Test
    fun `study document correctly finds section by SectionType`() {
        val doc = StudyDocument(
            id = DocumentId("doc-99"),
            title = "Chemistry",
            capturedImagePath = "/path/chem.jpg",
            sections = listOf(
                DocumentSection("s1", "Transcription", SectionType.TRANSCRIPTION, "Periodic table", 0),
                DocumentSection("s2", "Worked Solutions", SectionType.WORKED_SOLUTIONS, "Molar mass = 18 g/mol", 1)
            ),
            createdAtMs = 5000L
        )

        assertNotNull(doc.getSection(SectionType.TRANSCRIPTION))
        assertEquals("Periodic table", doc.getSection(SectionType.TRANSCRIPTION)?.content)

        assertNotNull(doc.getSection(SectionType.WORKED_SOLUTIONS))
        assertNull(doc.getSection(SectionType.SUMMARY))
    }

    @Test
    fun `assistant error properties are accessible and preserve recoverability`() {
        val error = AssistantError(
            code = ErrorCode.AI_TIMEOUT,
            message = "Server did not respond",
            recoverability = ErrorRecoverability.AUTOMATIC_RETRY
        )
        assertEquals(ErrorCode.AI_TIMEOUT, error.code)
        assertEquals("Server did not respond", error.message)
        assertEquals(ErrorRecoverability.AUTOMATIC_RETRY, error.recoverability)
        assertTrue(error.toString().contains("AI_TIMEOUT"))
    }
}
