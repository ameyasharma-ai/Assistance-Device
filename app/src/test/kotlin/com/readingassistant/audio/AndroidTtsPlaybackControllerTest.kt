package com.readingassistant.audio

import android.content.Context
import android.content.ContextWrapper
import com.readingassistant.domain.model.DocumentId
import com.readingassistant.domain.model.DocumentSection
import com.readingassistant.domain.model.PlaybackStatus
import com.readingassistant.domain.model.SectionType
import com.readingassistant.domain.model.StudyDocument
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidTtsPlaybackControllerTest {

    private class TestContext : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
        override fun getApplicationContext(): Context = this
    }

    private fun createSampleDocument(): StudyDocument {
        return StudyDocument(
            id = DocumentId("doc_test_1"),
            title = "Test Physics Chapter",
            capturedImagePath = "/tmp/test.jpg",
            sections = listOf(
                DocumentSection(
                    id = "sec_1",
                    title = "Source Transcription",
                    type = SectionType.TRANSCRIPTION,
                    content = "First sentence of transcription. Second sentence follows.",
                    sortOrder = 0
                ),
                DocumentSection(
                    id = "sec_2",
                    title = "Key Definitions",
                    type = SectionType.KEY_DEFINITIONS,
                    content = "Force is mass times acceleration. Velocity is displacement over time.",
                    sortOrder = 1
                )
            ),
            createdAtMs = 1000L
        )
    }

    @Test
    fun loadDocument_segmentsTextAndInitializesPaused() = runTest {
        val controller = AndroidTtsPlaybackController(TestContext(), this)
        val doc = createSampleDocument()

        controller.loadDocument(doc)

        assertEquals(PlaybackStatus.PAUSED, controller.playbackStatus.value)
        assertEquals(0, controller.currentPosition.value.sectionIndex)
        assertEquals(0, controller.currentPosition.value.segmentIndex)
        assertNotNull(controller.currentSegment.value)
        assertEquals("First sentence of transcription.", controller.currentSegment.value?.text)

        controller.shutdown()
    }

    @Test
    fun navigation_advancesAndRewindsParagraphsAndSections() = runTest {
        val controller = AndroidTtsPlaybackController(TestContext(), this)
        val doc = createSampleDocument()

        controller.loadDocument(doc)

        // Advance paragraph/sentence
        controller.nextParagraph()
        assertEquals(1, controller.currentPosition.value.segmentIndex)
        assertEquals("Second sentence follows.", controller.currentSegment.value?.text)

        // Step back
        controller.previousParagraph()
        assertEquals(0, controller.currentPosition.value.segmentIndex)
        assertEquals("First sentence of transcription.", controller.currentSegment.value?.text)

        // Advance to next section
        controller.nextSection()
        assertEquals(1, controller.currentPosition.value.sectionIndex)
        assertEquals(0, controller.currentPosition.value.segmentIndex)
        assertEquals("Force is mass times acceleration.", controller.currentSegment.value?.text)

        // Step back to previous section
        controller.previousSection()
        assertEquals(0, controller.currentPosition.value.sectionIndex)

        controller.shutdown()
    }
}
