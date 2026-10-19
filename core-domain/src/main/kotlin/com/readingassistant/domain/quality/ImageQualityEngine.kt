package com.readingassistant.domain.quality

import com.readingassistant.domain.camera.AnalysisFrame
import com.readingassistant.domain.model.QualityIssue
import com.readingassistant.domain.model.QualityScore
import com.readingassistant.domain.model.ReadinessDecision
import kotlin.math.abs

/**
 * Configuration thresholds for calibrating document readiness decisions.
 */
data class QualityThresholds(
    val minSharpnessScore: Float = 0.50f,
    val minFramingScore: Float = 0.60f,
    val minExposureScore: Float = 0.40f,
    val maxGlareRatio: Float = 0.02f,
    val minStabilityScore: Float = 0.70f,
    val minOverallConfidence: Float = 0.65f,
    val minMeanLuminance: Float = 40.0f,
    val maxMeanLuminance: Float = 230.0f,
    val minLaplacianVariance: Float = 200.0f
)

/**
 * Computer vision engine evaluating frame sharpness, illumination, glare, and stability.
 * Operates purely on raw grayscale pixel buffers without native dependencies for testability.
 */
class ImageQualityEngine(
    private val thresholds: QualityThresholds = QualityThresholds()
) {
    private var previousFrame: AnalysisFrame? = null

    /**
     * Evaluates an analysis frame and returns a structured ReadinessDecision.
     */
    fun evaluate(frame: AnalysisFrame): ReadinessDecision {
        val width = frame.width
        val height = frame.height
        val bytes = frame.grayscaleBytes

        if (bytes.isEmpty() || width <= 0 || height <= 0) {
            return ReadinessDecision.NotReady(
                QualityScore(0f, 0f, 0f, 1f, 0f),
                QualityIssue.INSUFFICIENT_CONFIDENCE,
                "No camera frame data received"
            )
        }

        // 1. Mean luminance & histogram analysis
        var sum = 0L
        var saturatedPixels = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sum += v
            if (v >= 254) {
                saturatedPixels++
            }
        }
        val meanLuminance = sum.toFloat() / bytes.size
        val glareRatio = saturatedPixels.toFloat() / bytes.size

        // Exposure check
        if (meanLuminance < thresholds.minMeanLuminance) {
            val score = QualityScore(
                sharpness = 0.3f,
                exposure = (meanLuminance / thresholds.minMeanLuminance) * 0.4f,
                framingCompleteness = 0.5f,
                glareRatio = glareRatio,
                stability = 0.9f
            )
            return ReadinessDecision.NotReady(score, QualityIssue.TOO_DARK)
        }

        if (meanLuminance > thresholds.maxMeanLuminance) {
            val score = QualityScore(
                sharpness = 0.2f,
                exposure = 0.95f,
                framingCompleteness = 0.5f,
                glareRatio = glareRatio,
                stability = 0.9f
            )
            return ReadinessDecision.NotReady(score, QualityIssue.TOO_BRIGHT)
        }

        // Glare check
        if (glareRatio > thresholds.maxGlareRatio) {
            val score = QualityScore(
                sharpness = 0.4f,
                exposure = 0.8f,
                framingCompleteness = 0.6f,
                glareRatio = glareRatio,
                stability = 0.9f
            )
            return ReadinessDecision.NotReady(score, QualityIssue.GLARE_DETECTED)
        }

        // 2. Sharpness calculation (Laplacian 3x3 kernel variance approximation)
        val laplacianVariance = computeLaplacianVariance(bytes, width, height)
        val sharpnessNormalized = minOf(1.0f, (laplacianVariance / 800.0f).toFloat())

        if (laplacianVariance < thresholds.minLaplacianVariance) {
            val score = QualityScore(
                sharpness = sharpnessNormalized,
                exposure = calculateExposureScore(meanLuminance),
                framingCompleteness = 0.7f,
                glareRatio = glareRatio,
                stability = 0.85f
            )
            return ReadinessDecision.NotReady(score, QualityIssue.BLURRY)
        }

        // 3. Framing & border completeness check
        val framingScore = computeFramingCompleteness(bytes, width, height)
        if (framingScore < thresholds.minFramingScore) {
            val score = QualityScore(
                sharpness = sharpnessNormalized,
                exposure = calculateExposureScore(meanLuminance),
                framingCompleteness = framingScore,
                glareRatio = glareRatio,
                stability = 0.85f
            )
            return ReadinessDecision.NotReady(score, QualityIssue.PAGE_CROPPED)
        }

        // 4. Stability calculation compared to previous frame
        val stabilityScore = computeStability(frame, previousFrame)
        previousFrame = frame

        if (stabilityScore < thresholds.minStabilityScore) {
            val score = QualityScore(
                sharpness = sharpnessNormalized,
                exposure = calculateExposureScore(meanLuminance),
                framingCompleteness = framingScore,
                glareRatio = glareRatio,
                stability = stabilityScore
            )
            return ReadinessDecision.NotReady(score, QualityIssue.UNSTABLE)
        }

        // All checks satisfied
        val overallScore = QualityScore(
            sharpness = sharpnessNormalized,
            exposure = calculateExposureScore(meanLuminance),
            framingCompleteness = framingScore,
            glareRatio = glareRatio,
            stability = stabilityScore
        )

        return if (overallScore.overallConfidence >= thresholds.minOverallConfidence) {
            ReadinessDecision.Ready(overallScore)
        } else {
            ReadinessDecision.NotReady(overallScore, QualityIssue.INSUFFICIENT_CONFIDENCE)
        }
    }

    private fun computeLaplacianVariance(bytes: ByteArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0

        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        // Subsample by 2 for performance on mobile CPU
        for (y in 1 until height - 1 step 2) {
            for (x in 1 until width - 1 step 2) {
                val center = bytes[y * width + x].toInt() and 0xFF
                val up = bytes[(y - 1) * width + x].toInt() and 0xFF
                val down = bytes[(y + 1) * width + x].toInt() and 0xFF
                val left = bytes[y * width + (x - 1)].toInt() and 0xFF
                val right = bytes[y * width + (x + 1)].toInt() and 0xFF

                val lap = (4 * center) - (up + down + left + right)
                sum += lap
                sumSq += (lap * lap)
                count++
            }
        }

        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    private fun computeFramingCompleteness(bytes: ByteArray, width: Int, height: Int): Float {
        // Inspect margins (top, bottom, left, right 5%) for text cut-off
        val marginX = maxOf(1, width / 20)
        val marginY = maxOf(1, height / 20)

        var borderContrastCount = 0
        val totalBorderSamples = (2 * width) + (2 * height)

        for (x in 0 until width step 4) {
            val topDiff = abs((bytes[x].toInt() and 0xFF) - (bytes[marginY * width + x].toInt() and 0xFF))
            val botDiff = abs((bytes[(height - 1) * width + x].toInt() and 0xFF) - (bytes[(height - 1 - marginY) * width + x].toInt() and 0xFF))
            if (topDiff > 80) borderContrastCount++
            if (botDiff > 80) borderContrastCount++
        }

        val borderCutoffRatio = borderContrastCount.toFloat() / (totalBorderSamples / 4)
        return maxOf(0.0f, minOf(1.0f, 1.0f - (borderCutoffRatio * 2.0f)))
    }

    private fun computeStability(current: AnalysisFrame, prev: AnalysisFrame?): Float {
        if (prev == null || prev.width != current.width || prev.height != current.height) {
            return 1.0f // Initial frame assumed stable
        }
        val curBytes = current.grayscaleBytes
        val prevBytes = prev.grayscaleBytes
        var diffSum = 0L
        val step = 8 // Sample 1 in 8 pixels
        var samples = 0

        for (i in curBytes.indices step step) {
            val d = abs((curBytes[i].toInt() and 0xFF) - (prevBytes[i].toInt() and 0xFF))
            diffSum += d
            samples++
        }

        val avgDiff = diffSum.toFloat() / samples
        return maxOf(0.0f, minOf(1.0f, 1.0f - (avgDiff / 50.0f)))
    }

    private fun calculateExposureScore(meanLuminance: Float): Float {
        // Ideal luminance is ~128 (50% gray text paper)
        val deviation = abs(meanLuminance - 128f)
        return maxOf(0.0f, 1.0f - (deviation / 128f))
    }

    fun reset() {
        previousFrame = null
    }
}
