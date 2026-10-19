package com.readingassistant.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.readingassistant.domain.ai.StudyDocumentGenerator
import com.readingassistant.domain.ai.StudyPromptTemplate
import com.readingassistant.domain.model.AssistantError
import com.readingassistant.domain.model.DocumentId
import com.readingassistant.domain.model.DocumentSection
import com.readingassistant.domain.model.ErrorCode
import com.readingassistant.domain.model.ErrorRecoverability
import com.readingassistant.domain.model.GenerationProgress
import com.readingassistant.domain.model.SectionType
import com.readingassistant.domain.model.StudyDocument
import com.readingassistant.domain.model.StudyTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/**
 * Production implementation of [StudyDocumentGenerator] powered by Google Gemini Vision API.
 * Uses the free, ultra-low-latency Gemini Flash-Lite multimodal model with built-in
 * rate-limiting (15 RPM free tier compliance) and automatic exponential backoff retries.
 *
 * @param apiKey Google AI Studio API key (configured in .env / BuildConfig)
 * @param model Gemini model identifier (default: "gemini-flash-lite-latest")
 * @param connectTimeoutMs HTTP connection timeout in milliseconds (default: 30000ms)
 * @param readTimeoutMs HTTP read timeout in milliseconds (default: 60000ms)
 */
class GeminiStudyDocumentGenerator(
    private val apiKey: String,
    private val model: String = "gemini-flash-lite-latest",
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val connectTimeoutMs: Int = 30000,
    private val readTimeoutMs: Int = 60000
) : StudyDocumentGenerator {

    private val TAG = "GeminiStudyGen"

    @Volatile
    private var isCancelled = false
    private var activeConnection: HttpURLConnection? = null

    // Rate Limiting Mutex & Timestamp (Respects Google AI Studio Free Tier 15 RPM limit)
    private val rateLimitMutex = Mutex()
    private var lastRequestTimeMs = 0L
    private val MIN_REQUEST_INTERVAL_MS = 4000L // 4 seconds spacing guarantees <= 15 requests/min

    override suspend fun generateStudyDocument(
        imagePath: String,
        ocrRawText: String?,
        task: StudyTask,
        onProgress: (suspend (GenerationProgress) -> Unit)?
    ): Result<StudyDocument> = withContext(Dispatchers.IO) {
        isCancelled = false

        if (apiKey.isBlank() || apiKey.startsWith("your_")) {
            return@withContext Result.failure(
                AssistantError(
                    code = ErrorCode.AI_AUTH_FAILED,
                    message = "Gemini API key is not configured. Please add your GEMINI_API_KEY in the .env file.",
                    recoverability = ErrorRecoverability.USER_ACTION_REQUIRED
                ).asException()
            )
        }

        // 1. Enforce 15 RPM Free Tier Rate-Limit Spacing
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLast = now - lastRequestTimeMs
            if (timeSinceLast < MIN_REQUEST_INTERVAL_MS && lastRequestTimeMs > 0L) {
                val waitMs = MIN_REQUEST_INTERVAL_MS - timeSinceLast
                val waitSecs = (waitMs + 999) / 1000
                Log.i(TAG, "Rate-limit guard: waiting ${waitSecs}s for 15 RPM free tier spacing...")
                onProgress?.invoke(GenerationProgress(totalSections = 5, completedSections = 1, currentSectionTitle = "Respecting API free tier limit: waiting ${waitSecs}s..."))
                delay(waitMs)
            }
            lastRequestTimeMs = System.currentTimeMillis()
        }

        try {
            // 2. Read and optimize image (downsamples large pictures to ensure fast payload delivery)
            onProgress?.invoke(GenerationProgress(totalSections = 5, completedSections = 1, currentSectionTitle = "Optimizing page image..."))
            val imageBase64 = encodeImageToBase64(imagePath)

            // 3. Build Gemini API Request Payload with TTS System Instructions
            val systemInstruction = StudyPromptTemplate.buildSystemPrompt(task)
            val requestBodyJson = buildRequestBody(systemInstruction, imageBase64, ocrRawText)
            val postBytes = requestBodyJson.toString().toByteArray(Charsets.UTF_8)
            Log.d(TAG, "Prepared Gemini request payload: ${postBytes.size} bytes using model $model")

            val targetUrl = URL("$baseUrl/models/$model:generateContent?key=$apiKey")

            // 4. Execute with Automatic Exponential Backoff Retries for Timeouts & 429s
            val maxAttempts = 3
            var attempt = 0
            var lastError: Exception? = null

            while (attempt < maxAttempts) {
                attempt++
                if (isCancelled) {
                    return@withContext Result.failure(CancellationException("Generation cancelled by user"))
                }

                val attemptTitle = if (attempt > 1) "Analyzing with Gemini Flash-Lite (Attempt $attempt of $maxAttempts)..." else "Analyzing with Gemini Flash-Lite..."
                onProgress?.invoke(GenerationProgress(totalSections = 5, completedSections = 2, currentSectionTitle = attemptTitle))

                val result = executeSingleRequest(targetUrl, postBytes, imagePath, ocrRawText, task, onProgress)
                if (result.isSuccess) {
                    return@withContext result
                } else {
                    val ex = result.exceptionOrNull() as? Exception ?: Exception(result.exceptionOrNull())
                    lastError = ex

                    val isRateLimit = ex.message?.contains("429") == true || ex.message?.contains("RATE_LIMITED") == true || ex.message?.contains("RESOURCE_EXHAUSTED") == true
                    val isTimeout = ex is SocketTimeoutException || ex.message?.contains("AI_TIMEOUT") == true || ex.message?.contains("timed out") == true

                    if ((isRateLimit || isTimeout) && attempt < maxAttempts) {
                        val backoffMs = attempt * 4000L
                        Log.w(TAG, "Transient error on attempt $attempt ($ex). Backing off for ${backoffMs}ms before retry...")
                        onProgress?.invoke(GenerationProgress(totalSections = 5, completedSections = 2, currentSectionTitle = "Rate limit/timeout. Backing off ${backoffMs / 1000}s..."))
                        delay(backoffMs)
                        continue
                    }
                    return@withContext Result.failure(ex)
                }
            }

            Result.failure(lastError ?: AssistantError(ErrorCode.AI_TIMEOUT, "Gemini API request timed out").asException())

        } catch (e: CancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during generation", e)
            Result.failure(
                AssistantError(
                    code = ErrorCode.AI_MALFORMED_RESPONSE,
                    message = "Failed to process Gemini response: ${e.message}",
                    cause = e
                ).asException()
            )
        }
    }

    private suspend fun executeSingleRequest(
        targetUrl: URL,
        postBytes: ByteArray,
        imagePath: String,
        ocrRawText: String?,
        task: StudyTask,
        onProgress: (suspend (GenerationProgress) -> Unit)?
    ): Result<StudyDocument> {
        var connection: HttpsURLConnection? = null
        return try {
            connection = (targetUrl.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(postBytes.size)
            }
            activeConnection = connection

            // Send payload bytes directly
            connection.outputStream.use { os ->
                os.write(postBytes)
                os.flush()
            }

            if (isCancelled) {
                connection.disconnect()
                return Result.failure(CancellationException("Generation cancelled by user"))
            }

            // Inspect Response Status
            val statusCode = connection.responseCode
            Log.d(TAG, "Gemini API response HTTP status: $statusCode")

            val responseString = if (statusCode in 200..299) {
                BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            } else {
                val errorStream = connection.errorStream
                val errorText = errorStream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() } } ?: ""
                Log.e(TAG, "Gemini API HTTP $statusCode: $errorText")

                return when (statusCode) {
                    401, 403 -> Result.failure(
                        AssistantError(
                            code = ErrorCode.AI_AUTH_FAILED,
                            message = "Gemini API authentication failed (HTTP $statusCode). Verify your GEMINI_API_KEY in .env."
                        ).asException()
                    )
                    429 -> Result.failure(
                        AssistantError(
                            code = ErrorCode.AI_RATE_LIMITED,
                            message = "Gemini API rate limit exceeded (HTTP 429). Free tier limit: 15 requests/minute."
                        ).asException()
                    )
                    else -> Result.failure(
                        AssistantError(
                            code = ErrorCode.AI_MALFORMED_RESPONSE,
                            message = "Gemini API error (HTTP $statusCode): $errorText"
                        ).asException()
                    )
                }
            }

            onProgress?.invoke(GenerationProgress(totalSections = 5, completedSections = 4, currentSectionTitle = "Structuring Accessible Audio Guide..."))

            // Parse Gemini Structured Response
            val parsedDoc = parseGeminiResponse(responseString, imagePath, ocrRawText, task, onProgress)
            Result.success(parsedDoc)

        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Socket timeout reaching Gemini API", e)
            Result.failure(
                AssistantError(
                    code = ErrorCode.AI_TIMEOUT,
                    message = "Gemini API request timed out.",
                    cause = e
                ).asException()
            )
        } catch (e: UnknownHostException) {
            Log.w(TAG, "Unknown host reaching Gemini API", e)
            Result.failure(
                AssistantError(
                    code = ErrorCode.NETWORK_OFFLINE,
                    message = "Internet connection is offline. Unable to reach Gemini API.",
                    cause = e
                ).asException()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in executeSingleRequest", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
            activeConnection = null
        }
    }

    override fun cancel() {
        isCancelled = true
        activeConnection?.disconnect()
    }

    private fun buildRequestBody(systemPrompt: String, imageBase64: String, ocrRawText: String?): JSONObject {
        val root = JSONObject()

        // System Instruction
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().put("text", systemPrompt))
            })
        }
        root.put("system_instruction", systemInstruction)

        // Contents
        val contentParts = JSONArray()

        val promptUserText = if (ocrRawText.isNullOrBlank()) {
            "Analyze this textbook page photograph. Transcribe the text verbatim and generate the complete structured study guide JSON."
        } else {
            "Analyze this textbook page photograph. On-device preliminary OCR text detected: \"$ocrRawText\". Transcribe the text verbatim and generate the complete structured study guide JSON."
        }
        contentParts.put(JSONObject().put("text", promptUserText))

        if (imageBase64.isNotBlank()) {
            val inlineData = JSONObject().apply {
                put("mime_type", "image/jpeg")
                put("data", imageBase64)
            }
            contentParts.put(JSONObject().put("inline_data", inlineData))
        }

        root.put("contents", JSONArray().apply {
            put(JSONObject().put("parts", contentParts))
        })

        // Generation Config with strict JSON output
        val generationConfig = JSONObject().apply {
            put("response_mime_type", "application/json")
            put("temperature", 0.2)
        }
        root.put("generationConfig", generationConfig)

        return root
    }

    private fun encodeImageToBase64(imagePath: String): String {
        val file = File(imagePath)
        if (!file.exists() || !file.isFile || file.length() == 0L) {
            return ""
        }

        // If image is larger than 600KB, downsample/compress to ensure quick upload
        val bytes = if (file.length() > 600_000) {
            try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
                
                // Calculate sample size to keep within 1600x1200
                var sampleSize = 1
                while (options.outWidth / sampleSize > 1600 || options.outHeight / sampleSize > 1600) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                if (bitmap != null) {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    out.toByteArray()
                } else {
                    file.readBytes()
                }
            } catch (_: Exception) {
                file.readBytes()
            }
        } else {
            file.readBytes()
        }

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private suspend fun parseGeminiResponse(
        rawResponse: String,
        imagePath: String,
        ocrRawText: String?,
        task: StudyTask,
        onProgress: (suspend (GenerationProgress) -> Unit)?
    ): StudyDocument {
        val jsonRoot = JSONObject(rawResponse)
        val candidates = jsonRoot.optJSONArray("candidates")
            ?: throw IllegalStateException("Gemini response missing 'candidates' array")

        if (candidates.length() == 0) {
            throw IllegalStateException("Gemini returned empty candidates")
        }

        val firstCandidate = candidates.getJSONObject(0)
        val content = firstCandidate.optJSONObject("content")
            ?: throw IllegalStateException("Candidate missing 'content'")
        val parts = content.optJSONArray("parts")
            ?: throw IllegalStateException("Candidate content missing 'parts'")

        if (parts.length() == 0) {
            throw IllegalStateException("Candidate parts array is empty")
        }

        var textContent = parts.getJSONObject(0).optString("text", "").trim()

        // Strip markdown code fences if present
        if (textContent.startsWith("```json")) {
            textContent = textContent.removePrefix("```json").trim()
        }
        if (textContent.startsWith("```")) {
            textContent = textContent.removePrefix("```").trim()
        }
        if (textContent.endsWith("```")) {
            textContent = textContent.removeSuffix("```").trim()
        }

        val parsedSections = mutableListOf<DocumentSection>()
        var title = "Scanned Study Material"

        try {
            val docJson = JSONObject(textContent)
            title = docJson.optString("title", "Question & Answer Guide")

            // Case A: "sections" is a JSONArray
            val sectionsArray = docJson.optJSONArray("sections")
            // Case B: "questions" is a JSONArray
            val questionsArray = docJson.optJSONArray("questions")

            val targetArray = sectionsArray ?: questionsArray

            if (targetArray != null && targetArray.length() > 0) {
                for (i in 0 until targetArray.length()) {
                    val secObj = targetArray.getJSONObject(i)
                    val q = secObj.optString("question").ifBlank { secObj.optString("q") }
                    val a = secObj.optString("answer").ifBlank { secObj.optString("a") }

                    val secTitle = secObj.optString("title")
                        .ifBlank { secObj.optString("section_title") }
                        .ifBlank { "Question %02d".format(i + 1) }

                    val secTypeStr = secObj.optString("type", "WORKED_SOLUTIONS").uppercase()
                    val sectionType = try {
                        SectionType.valueOf(secTypeStr)
                    } catch (_: IllegalArgumentException) {
                        SectionType.WORKED_SOLUTIONS
                    }

                    val secContent = if (q.isNotBlank() && a.isNotBlank()) {
                        val qClean = if (q.startsWith("Question", ignoreCase = true)) q else "Question %02d:\n%s".format(i + 1, q)
                        val aClean = if (a.startsWith("Answer", ignoreCase = true)) a else "Answer %02d:\n%s".format(i + 1, a)
                        "$qClean\n\n$aClean"
                    } else {
                        secObj.optString("content").ifBlank { secObj.optString("text", "") }
                    }

                    parsedSections.add(
                        DocumentSection(
                            id = "sec_${UUID.randomUUID()}",
                            title = secTitle,
                            type = sectionType,
                            content = secContent,
                            sortOrder = i
                        )
                    )
                    onProgress?.invoke(GenerationProgress(totalSections = targetArray.length(), completedSections = i + 1, currentSectionTitle = secTitle))
                }
            } else {
                // Case C: "sections" is a JSONObject
                val sectionsObj = docJson.optJSONObject("sections")
                if (sectionsObj != null && sectionsObj.length() > 0) {
                    val keys = sectionsObj.keys()
                    var idx = 0
                    val totalKeys = sectionsObj.length()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val rawVal = sectionsObj.opt(key)
                        val secContent = when (rawVal) {
                            is JSONObject -> rawVal.optString("content").ifBlank { rawVal.optString("text", "") }
                            else -> rawVal?.toString() ?: ""
                        }
                        val formattedTitle = key.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
                        val sectionType = try {
                            SectionType.valueOf(key.uppercase())
                        } catch (_: IllegalArgumentException) {
                            SectionType.WORKED_SOLUTIONS
                        }

                        parsedSections.add(
                            DocumentSection(
                                id = "sec_${UUID.randomUUID()}",
                                title = formattedTitle,
                                type = sectionType,
                                content = secContent,
                                sortOrder = idx
                            )
                        )
                        idx++
                        onProgress?.invoke(GenerationProgress(totalSections = totalKeys, completedSections = idx, currentSectionTitle = formattedTitle))
                    }
                }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Response text was not strictly structured JSON: $textContent")
        }

        // Fallback: If no structured sections could be extracted from JSON, try splitting plain text by Question markers
        if (parsedSections.isEmpty() && textContent.isNotBlank()) {
            val qRegex = Regex("(?i)(?=(?:Question|Q)\\s*\\d+[:\\s])")
            val chunks = textContent.split(qRegex).map { it.trim() }.filter { it.isNotBlank() }
            if (chunks.size > 1) {
                chunks.forEachIndexed { i, chunk ->
                    parsedSections.add(
                        DocumentSection(
                            id = "sec_${UUID.randomUUID()}",
                            title = "Question %02d".format(i + 1),
                            type = SectionType.WORKED_SOLUTIONS,
                            content = chunk,
                            sortOrder = i
                        )
                    )
                }
            } else {
                parsedSections.add(
                    DocumentSection(
                        id = "sec_${UUID.randomUUID()}",
                        title = "Question 01",
                        type = SectionType.WORKED_SOLUTIONS,
                        content = textContent,
                        sortOrder = 0
                    )
                )
            }
        }

        // Final safety fallback
        if (parsedSections.isEmpty()) {
            parsedSections.add(
                DocumentSection(
                    id = "sec_${UUID.randomUUID()}",
                    title = "Question 01",
                    type = SectionType.WORKED_SOLUTIONS,
                    content = "No questions detected on this page. Please align textbook and capture again.",
                    sortOrder = 0
                )
            )
        }

        return StudyDocument(
            id = DocumentId("gemini_${UUID.randomUUID()}"),
            title = title,
            capturedImagePath = imagePath,
            ocrRawText = ocrRawText,
            sections = parsedSections,
            createdAtMs = System.currentTimeMillis(),
            requestedTask = task
        )
    }

    private fun AssistantError.asException(): Exception =
        IllegalStateException("$code: $message")
}
