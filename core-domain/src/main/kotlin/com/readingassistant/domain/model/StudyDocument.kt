package com.readingassistant.domain.model

/**
 * Unique identifier for a persisted study document.
 */
data class DocumentId(val value: String) {
    override fun toString(): String = value
}

/**
 * Categories of content within a generated study document.
 * This structure strictly segregates original source transcription from AI explanations and solutions.
 */
enum class SectionType(val displayName: String) {
    TRANSCRIPTION("Source Transcription"),
    KEY_DEFINITIONS("Key Definitions"),
    EXPLANATION("Concept Explanations"),
    WORKED_SOLUTIONS("Worked Solutions"),
    SUMMARY("Study Summary"),
    UNCERTAINTIES("Reading Uncertainties")
}

/**
 * Individual logical section within a study document.
 */
data class DocumentSection(
    val id: String,
    val title: String,
    val type: SectionType,
    val content: String,
    val sortOrder: Int
)

/**
 * High-level task requested by the student when scanning.
 */
enum class StudyTask(val userLabel: String) {
    FULL_STUDY_GUIDE("Full Study Guide"),
    EXPLAIN_CONCEPTS("Explain Concepts"),
    SOLVE_PROBLEMS("Solve Visible Problems"),
    SUMMARIZE("Quick Summary")
}

/**
 * Progress indicator during multi-section document generation.
 */
data class GenerationProgress(
    val totalSections: Int,
    val completedSections: Int,
    val currentSectionTitle: String? = null
) {
    val percentComplete: Int
        get() = if (totalSections > 0) ((completedSections.toFloat() / totalSections) * 100).toInt() else 0
}

/**
 * Complete structured study document generated for a scanned page.
 */
data class StudyDocument(
    val id: DocumentId,
    val title: String,
    val capturedImagePath: String,
    val ocrRawText: String? = null,
    val sections: List<DocumentSection>,
    val createdAtMs: Long,
    val requestedTask: StudyTask = StudyTask.FULL_STUDY_GUIDE
) {
    fun getSection(type: SectionType): DocumentSection? = sections.find { it.type == type }
}
