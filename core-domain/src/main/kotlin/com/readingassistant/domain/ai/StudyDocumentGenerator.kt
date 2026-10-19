package com.readingassistant.domain.ai

import com.readingassistant.domain.model.GenerationProgress
import com.readingassistant.domain.model.StudyDocument
import com.readingassistant.domain.model.StudyTask

/**
 * Interface orchestrating vision-capable LLM generation of structured study materials.
 */
interface StudyDocumentGenerator {

    /**
     * Submit image and optional OCR text to generate a complete structured study document.
     * Reports incremental section progress via [onProgress].
     */
    suspend fun generateStudyDocument(
        imagePath: String,
        ocrRawText: String? = null,
        task: StudyTask = StudyTask.FULL_STUDY_GUIDE,
        onProgress: (suspend (GenerationProgress) -> Unit)? = null
    ): Result<StudyDocument>

    /**
     * Cancel any active in-flight generation requests.
     */
    fun cancel()
}
