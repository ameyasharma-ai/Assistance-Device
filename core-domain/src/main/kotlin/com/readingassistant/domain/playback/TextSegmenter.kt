package com.readingassistant.domain.playback

import com.readingassistant.domain.model.DocumentSection
import com.readingassistant.domain.model.SpeechSegment

/**
 * Splits document sections into granular sentence and paragraph segments
 * to enable instant, resilient pause, resume, rewind, and navigation.
 */
object TextSegmenter {

    private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+(?=[A-Z0-9])")

    /**
     * Splits a document section's content into sequential SpeechSegments.
     */
    fun segmentSection(section: DocumentSection, sectionIndex: Int): List<SpeechSegment> {
        val paragraphs = section.content.split("\n\n").filter { it.isNotBlank() }
        val segments = mutableListOf<SpeechSegment>()
        var globalIndex = 0

        for (p in paragraphs) {
            val trimmedParagraph = p.trim()
            val rawSentences = trimmedParagraph.split(SENTENCE_SPLIT_REGEX)
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (rawSentences.isEmpty()) continue

            for ((sIndex, sentence) in rawSentences.withIndex()) {
                val isLastSentenceOfParagraph = (sIndex == rawSentences.lastIndex)
                segments.add(
                    SpeechSegment(
                        segmentIndex = globalIndex++,
                        text = sentence,
                        sectionIndex = sectionIndex,
                        isParagraphBoundary = isLastSentenceOfParagraph
                    )
                )
            }
        }

        // Fallback for non-empty text without sentence punctuation
        if (segments.isEmpty() && section.content.isNotBlank()) {
            segments.add(
                SpeechSegment(
                    segmentIndex = 0,
                    text = section.content.trim(),
                    sectionIndex = sectionIndex,
                    isParagraphBoundary = true
                )
            )
        }

        return segments
    }
}
