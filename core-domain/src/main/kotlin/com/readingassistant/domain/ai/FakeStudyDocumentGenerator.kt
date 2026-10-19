package com.readingassistant.domain.ai

import com.readingassistant.domain.model.AssistantError
import com.readingassistant.domain.model.DocumentId
import com.readingassistant.domain.model.DocumentSection
import com.readingassistant.domain.model.ErrorCode
import com.readingassistant.domain.model.GenerationProgress
import com.readingassistant.domain.model.SectionType
import com.readingassistant.domain.model.StudyDocument
import com.readingassistant.domain.model.StudyTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Injection scenarios for the fake AI generator.
 */
enum class FakeAiScenario {
    SUCCESS,
    TIMEOUT,
    RATE_LIMITED,
    MALFORMED_OUTPUT,
    NETWORK_ERROR,
    SERVER_ERROR,
    PARTIAL_FAILURE
}

/**
 * Deterministic fake AI generator for unit tests and laptop-based simulation mode.
 */
class FakeStudyDocumentGenerator : StudyDocumentGenerator {

    var activeScenario: FakeAiScenario = FakeAiScenario.SUCCESS
    var simulatedDelayMs: Long = 100L
    private var isCancelled: Boolean = false

    override suspend fun generateStudyDocument(
        imagePath: String,
        ocrRawText: String?,
        task: StudyTask,
        onProgress: (suspend (GenerationProgress) -> Unit)?
    ): Result<StudyDocument> {
        isCancelled = false

        when (activeScenario) {
            FakeAiScenario.TIMEOUT -> {
                delay(simulatedDelayMs)
                return Result.failure(
                    AssistantError(ErrorCode.AI_TIMEOUT, "AI service request timed out").asException()
                )
            }
            FakeAiScenario.RATE_LIMITED -> {
                delay(50)
                return Result.failure(
                    AssistantError(ErrorCode.AI_RATE_LIMITED, "Rate limit reached").asException()
                )
            }
            FakeAiScenario.NETWORK_ERROR -> {
                delay(50)
                return Result.failure(
                    AssistantError(ErrorCode.NETWORK_OFFLINE, "Network offline").asException()
                )
            }
            FakeAiScenario.SERVER_ERROR -> {
                delay(50)
                return Result.failure(
                    AssistantError(ErrorCode.AI_MALFORMED_RESPONSE, "Internal server error").asException()
                )
            }
            FakeAiScenario.MALFORMED_OUTPUT -> {
                delay(50)
                return Result.failure(
                    AssistantError(ErrorCode.AI_MALFORMED_RESPONSE, "Invalid JSON structure received from model").asException()
                )
            }
            FakeAiScenario.PARTIAL_FAILURE -> {
                onProgress?.invoke(GenerationProgress(5, 1, "Transcription"))
                delay(simulatedDelayMs)
                return Result.failure(
                    AssistantError(ErrorCode.AI_MALFORMED_RESPONSE, "Stream interrupted midway").asException()
                )
            }
            FakeAiScenario.SUCCESS -> {
                val sectionTemplates = listOf(
                    Triple("Source Transcription", SectionType.TRANSCRIPTION,
                        "Chapter 4: Laws of Motion. When an object exerts a force on a second object, the second object exerts an equal and opposite force."),
                    Triple("Key Definitions", SectionType.KEY_DEFINITIONS,
                        "Newton's Third Law: For every action in nature there is an equal and opposite reaction.\nForce: An interaction that changes the motion of an object."),
                    Triple("Concept Explanations", SectionType.EXPLANATION,
                        "Think of jumping off a small boat into water. As your legs push forward onto the dock, your feet exert an equal force backwards on the boat, causing it to drift away."),
                    Triple("Worked Solutions", SectionType.WORKED_SOLUTIONS,
                        "Example 4.1: A 60 kg swimmer pushes against a pool wall with a force of 120 Newtons.\nStep 1: Identify given quantities: Mass = 60 kg, Force = 120 N.\nStep 2: Apply F = m * a. Acceleration = 120 N divided by 60 kg = 2.0 meters per second squared away from the wall."),
                    Triple("Study Summary", SectionType.SUMMARY,
                        "- Action and reaction forces act on different bodies.\n- They occur simultaneously in pairs.\n- They never cancel each other out because they act on separate objects.")
                )

                val generatedSections = mutableListOf<DocumentSection>()
                for ((index, item) in sectionTemplates.withIndex()) {
                    if (isCancelled) {
                        return Result.failure(CancellationException("Generation cancelled by user"))
                    }
                    delay(simulatedDelayMs / sectionTemplates.size)
                    onProgress?.invoke(GenerationProgress(sectionTemplates.size, index + 1, item.first))
                    generatedSections.add(
                        DocumentSection(
                            id = "sec_${index + 1}",
                            title = item.first,
                            type = item.second,
                            content = item.third,
                            sortOrder = index
                        )
                    )
                }

                val doc = StudyDocument(
                    id = DocumentId("doc_${UUID.randomUUID()}"),
                    title = "Physics: Laws of Motion",
                    capturedImagePath = imagePath,
                    ocrRawText = ocrRawText ?: "Chapter 4: Laws of Motion...",
                    sections = generatedSections,
                    createdAtMs = System.currentTimeMillis(),
                    requestedTask = task
                )
                return Result.success(doc)
            }
        }
    }

    override fun cancel() {
        isCancelled = true
    }

    private fun AssistantError.asException(): Exception =
        IllegalStateException("$code: $message")
}
