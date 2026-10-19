package com.readingassistant.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.readingassistant.R
import com.readingassistant.audio.AndroidTtsPlaybackController
import com.readingassistant.databinding.ActivityMainBinding
import com.readingassistant.domain.AssistantOrchestrator
import com.readingassistant.domain.ai.FakeStudyDocumentGenerator
import com.readingassistant.domain.camera.SimulatedCameraScenario
import com.readingassistant.domain.camera.SimulatedDocumentCameraController
import com.readingassistant.domain.input.SimulatedInputSource
import com.readingassistant.domain.model.ReadinessDecision
import com.readingassistant.domain.model.SectionType
import com.readingassistant.domain.model.StudyDocument
import com.readingassistant.domain.quality.ImageQualityEngine
import com.readingassistant.domain.state.AssistantState
import com.readingassistant.haptics.AndroidFeedbackController
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Developer Simulation & Accessibility Activity.
 * Allows laptop and on-device interactive testing of foot pedal inputs,
 * camera framing quality, AI study document generation, and paragraph-aware TTS playback.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val inputSource = SimulatedInputSource()
    private val cameraController = SimulatedDocumentCameraController()
    private lateinit var feedbackController: AndroidFeedbackController
    private val documentGenerator = FakeStudyDocumentGenerator()
    private lateinit var playbackController: AndroidTtsPlaybackController
    private val qualityEngine = ImageQualityEngine()

    private lateinit var orchestrator: AssistantOrchestrator

    private var currentDocument: StudyDocument? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        feedbackController = AndroidFeedbackController(this)
        playbackController = AndroidTtsPlaybackController(this)

        orchestrator = AssistantOrchestrator(
            inputController = inputSource,
            cameraController = cameraController,
            feedbackController = feedbackController,
            documentGenerator = documentGenerator,
            playbackController = playbackController,
            qualityEngine = qualityEngine,
            scope = lifecycleScope
        )

        orchestrator.start()

        setupPedalControls()
        setupVisionSimulationControls()
        observeSystemState()
    }

    override fun onDestroy() {
        super.onDestroy()
        orchestrator.stop()
        playbackController.shutdown()
    }

    private fun setupPedalControls() {
        // Left Foot Pedal
        binding.btnLeftTap.setOnClickListener {
            lifecycleScope.launch { inputSource.pressLeftShort() }
        }
        binding.btnLeftDoubleTap.setOnClickListener {
            lifecycleScope.launch { inputSource.pressLeftDouble() }
        }
        binding.btnLeftLongPress.setOnClickListener {
            lifecycleScope.launch { inputSource.pressLeftLong() }
        }

        // Right Foot Pedal
        binding.btnRightTap.setOnClickListener {
            lifecycleScope.launch { inputSource.pressRightShort() }
        }
        binding.btnRightDoubleTap.setOnClickListener {
            lifecycleScope.launch { inputSource.pressRightDouble() }
        }
        binding.btnRightLongPress.setOnClickListener {
            lifecycleScope.launch { inputSource.pressRightLong() }
        }
    }

    private fun setupVisionSimulationControls() {
        binding.btnSimSharp.setOnClickListener {
            cameraController.activeScenario = SimulatedCameraScenario.SHARP_PAGE
            lifecycleScope.launch { cameraController.emitTestFrame() }
        }
        binding.btnSimBlur.setOnClickListener {
            cameraController.activeScenario = SimulatedCameraScenario.BLURRED_PAGE
            lifecycleScope.launch { cameraController.emitTestFrame() }
        }
        binding.btnSimCropped.setOnClickListener {
            cameraController.activeScenario = SimulatedCameraScenario.CROPPED_PAGE
            lifecycleScope.launch { cameraController.emitTestFrame() }
        }
        binding.btnSimDark.setOnClickListener {
            cameraController.activeScenario = SimulatedCameraScenario.DARK_PAGE
            lifecycleScope.launch { cameraController.emitTestFrame() }
        }
        binding.btnSimGlare.setOnClickListener {
            cameraController.activeScenario = SimulatedCameraScenario.GLARE_PAGE
            lifecycleScope.launch { cameraController.emitTestFrame() }
        }
        binding.btnSimDisconnect.setOnClickListener {
            cameraController.activeScenario = SimulatedCameraScenario.DISCONNECTED
            lifecycleScope.launch { cameraController.emitTestFrame() }
        }
    }

    private fun observeSystemState() {
        // Observe State Machine transitions
        orchestrator.state.onEach { state ->
            renderAssistantState(state)
        }.launchIn(lifecycleScope)

        // Observe Camera Frames for Quality Inspection
        cameraController.analysisFrames.onEach { frame ->
            val decision = qualityEngine.evaluate(frame)
            when (decision) {
                is ReadinessDecision.Ready -> {
                    val score = decision.score
                    binding.tvQualityMetrics.text =
                        "Readiness: READY | Sharpness: ${"%.1f".format(score.sharpness * 100)} | " +
                        "Glare: ${"%.1f".format(score.glareRatio * 100)}% | Exposure: ${"%.1f".format(score.exposure * 100)} | Issues: NONE"
                }
                is ReadinessDecision.NotReady -> {
                    val score = decision.score
                    binding.tvQualityMetrics.text =
                        "Readiness: NOT READY | Sharpness: ${"%.1f".format(score.sharpness * 100)} | " +
                        "Glare: ${"%.1f".format(score.glareRatio * 100)}% | Issue: ${decision.primaryIssue.readableMessage}"
                }
            }
        }.launchIn(lifecycleScope)

        // Observe TTS Spoken Utterance Progression
        playbackController.currentSegment.onEach { segment ->
            if (segment != null) {
                binding.tvActiveSpeech.text = "\"${segment.text}\""
            } else {
                binding.tvActiveSpeech.text = "[Auditory speech stopped]"
            }
        }.launchIn(lifecycleScope)

        // Observe Reading Position & Status
        playbackController.currentPosition.onEach { pos ->
            val status = playbackController.playbackStatus.value
            binding.tvPlaybackProgress.text =
                "Position: Section ${pos.sectionIndex + 1}, Segment ${pos.segmentIndex + 1} | Status: $status"
        }.launchIn(lifecycleScope)

        // Observe Feedback Actuators
        feedbackController.lastTactilePattern.onEach { pattern ->
            if (pattern != null) {
                binding.tvLastHaptic.text = "Tactile Actuator: $pattern (${System.currentTimeMillis() % 100000}ms)"
            }
        }.launchIn(lifecycleScope)

        feedbackController.lastSpokenPrompt.onEach { prompt ->
            if (prompt != null) {
                binding.tvLastPrompt.text = "Spoken Cue: \"${prompt.message}\""
            }
        }.launchIn(lifecycleScope)
    }

    private fun renderAssistantState(state: AssistantState) {
        val (badgeText, badgeColor, description, showProgress) = when (state) {
            is AssistantState.Idle -> Quad(
                "STATE: IDLE",
                ContextCompat.getColor(this, R.color.state_idle),
                "System idle. Tap Left Foot to initiate page scan.",
                false
            )
            is AssistantState.ScannerStarting -> Quad(
                "STATE: STARTING",
                ContextCompat.getColor(this, R.color.state_scanning),
                "Initializing camera hardware and sensors...",
                false
            )
            is AssistantState.Scanning -> Quad(
                "STATE: SCANNING",
                ContextCompat.getColor(this, R.color.state_scanning),
                "Scanning page... Align textbook page in camera view.",
                false
            )
            is AssistantState.DocumentReady -> Quad(
                "STATE: DOC READY",
                ContextCompat.getColor(this, R.color.state_ready),
                "Page framed & sharp! Tap Right Foot to capture still.",
                false
            )
            is AssistantState.Capturing -> Quad(
                "STATE: CAPTURING",
                ContextCompat.getColor(this, R.color.state_scanning),
                "Capturing high-resolution still image...",
                false
            )
            is AssistantState.Captured -> Quad(
                "STATE: CAPTURED",
                ContextCompat.getColor(this, R.color.state_scanning),
                "Still image captured. Validating frame quality...",
                false
            )
            is AssistantState.CaptureValidation -> Quad(
                "STATE: VALIDATING",
                ContextCompat.getColor(this, R.color.state_scanning),
                "Validating still image sharpness and exposure...",
                false
            )
            is AssistantState.AwaitingConfirmation -> Quad(
                "STATE: CONFIRMATION",
                ContextCompat.getColor(this, R.color.state_processing),
                "Suboptimal capture detected (${state.validation.recommendation.spokenPrompt}). Tap Right Foot to proceed or Left to retake.",
                false
            )
            is AssistantState.Processing -> Quad(
                "STATE: PROCESSING",
                ContextCompat.getColor(this, R.color.state_processing),
                "AI generating structured study document...",
                true
            )
            is AssistantState.DocumentReadyForPlayback -> {
                displayStudyDocument(state.document)
                Quad(
                    "STATE: READY TO PLAY",
                    ContextCompat.getColor(this, R.color.state_ready),
                    "Study document ready! Tap Right Foot to start auditory playback.",
                    false
                )
            }
            is AssistantState.Playing -> {
                displayStudyDocument(state.document)
                Quad(
                    "STATE: PLAYING",
                    ContextCompat.getColor(this, R.color.state_playing),
                    "Auditory playback in progress. Tap Right Foot to pause.",
                    false
                )
            }
            is AssistantState.Paused -> {
                displayStudyDocument(state.document)
                Quad(
                    "STATE: PAUSED",
                    ContextCompat.getColor(this, R.color.state_paused),
                    "Playback paused. Tap Right Foot to resume.",
                    false
                )
            }
            is AssistantState.Error -> Quad(
                "STATE: ERROR",
                ContextCompat.getColor(this, R.color.state_error),
                "Error: ${state.error.message}. Tap Right Foot to dismiss.",
                false
            )
        }

        binding.tvStateBadge.text = badgeText
        (binding.tvStateBadge.background as? GradientDrawable)?.setColor(badgeColor)
            ?: binding.tvStateBadge.setBackgroundColor(badgeColor)

        binding.tvStateDescription.text = description
        binding.pbProcessing.visibility = if (showProgress) View.VISIBLE else View.GONE
    }

    private fun displayStudyDocument(doc: StudyDocument) {
        if (currentDocument?.id == doc.id) return
        currentDocument = doc

        binding.tvDocumentTitle.text = "Document: ${doc.title}"
        val builder = SpannableStringBuilder()

        for (sec in doc.sections) {
            val headerColor = when (sec.type) {
                SectionType.TRANSCRIPTION -> "#33B5E5" // Verbatim source
                SectionType.KEY_DEFINITIONS -> "#00C853"
                SectionType.EXPLANATION -> "#FFAB00"
                SectionType.WORKED_SOLUTIONS -> "#FF5252"
                SectionType.SUMMARY -> "#E040FB"
                SectionType.UNCERTAINTIES -> "#FF6D00"
            }

            val prefix = if (sec.type == SectionType.TRANSCRIPTION) "【VERBATIM SOURCE】" else "【${sec.type}】"
            val startHeader = builder.length
            builder.append("$prefix ${sec.title}\n")
            builder.setSpan(
                ForegroundColorSpan(Color.parseColor(headerColor)),
                startHeader,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                startHeader,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            builder.append("${sec.content}\n\n")
        }

        binding.tvDocumentSections.text = builder
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_L -> {
                lifecycleScope.launch { inputSource.pressLeftShort() }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_R -> {
                lifecycleScope.launch { inputSource.pressRightShort() }
                true
            }
            KeyEvent.KEYCODE_SPACE -> {
                lifecycleScope.launch { inputSource.pressRightShort() }
                true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                lifecycleScope.launch { inputSource.pressLeftShort() }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
