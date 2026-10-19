package com.readingassistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.readingassistant.BuildConfig
import com.readingassistant.R
import com.readingassistant.ai.GeminiStudyDocumentGenerator
import com.readingassistant.audio.AndroidTtsPlaybackController
import com.readingassistant.camera.AndroidCamera2Controller
import com.readingassistant.camera.DelegatingDocumentCameraController
import com.readingassistant.databinding.ActivityMainBinding
import com.readingassistant.domain.AssistantOrchestrator
import com.readingassistant.domain.ai.FakeStudyDocumentGenerator
import com.readingassistant.domain.ai.StudyDocumentGenerator
import com.readingassistant.domain.camera.SimulatedCameraScenario
import com.readingassistant.domain.camera.SimulatedDocumentCameraController
import com.readingassistant.domain.input.SimulatedInputSource
import com.readingassistant.domain.model.ReadinessDecision
import com.readingassistant.domain.model.SectionType
import com.readingassistant.domain.model.StudyDocument
import com.readingassistant.domain.quality.ImageQualityEngine
import com.readingassistant.domain.state.AssistantState
import com.readingassistant.haptics.AndroidFeedbackController
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import com.readingassistant.domain.model.PromptPriority
import com.readingassistant.domain.model.SpokenPrompt
import com.readingassistant.domain.model.TactilePattern
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val simulatedCameraController = SimulatedDocumentCameraController()
    private lateinit var webcamCameraController: AndroidCamera2Controller
    private lateinit var delegatingCameraController: DelegatingDocumentCameraController

    private lateinit var feedbackController: AndroidFeedbackController
    private lateinit var documentGenerator: StudyDocumentGenerator
    private lateinit var playbackController: AndroidTtsPlaybackController
    private val qualityEngine = ImageQualityEngine()

    private lateinit var orchestrator: AssistantOrchestrator
    private var isUsingWebcam = false

    private var currentDocument: StudyDocument? = null

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            enableWebcamSource()
        } else {
            enableSimulationSource()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val geminiKey = BuildConfig.GEMINI_API_KEY
        val geminiModel = BuildConfig.GEMINI_MODEL

        val hasLiveKey = geminiKey.isNotBlank() && !geminiKey.startsWith("your_")
        if (hasLiveKey) {
            documentGenerator = GeminiStudyDocumentGenerator(
                apiKey = geminiKey,
                model = geminiModel
            )
            binding.tvAiModelBadge.text = "AI Engine: $geminiModel [LIVE]"
            binding.tvAiModelBadge.setTextColor(Color.parseColor("#00E676")) // Green
        } else {
            documentGenerator = FakeStudyDocumentGenerator()
            binding.tvAiModelBadge.text = "AI Engine: Simulated Offline [Add GEMINI_API_KEY in .env]"
            binding.tvAiModelBadge.setTextColor(Color.parseColor("#FFD600")) // Amber
        }

        webcamCameraController = AndroidCamera2Controller(this)
        delegatingCameraController = DelegatingDocumentCameraController(
            initialDelegate = simulatedCameraController,
            scope = lifecycleScope
        )

        feedbackController = AndroidFeedbackController(this)
        playbackController = AndroidTtsPlaybackController(this)

        orchestrator = AssistantOrchestrator(
            inputController = inputSource,
            cameraController = delegatingCameraController,
            feedbackController = feedbackController,
            documentGenerator = documentGenerator,
            playbackController = playbackController,
            qualityEngine = qualityEngine,
            scope = lifecycleScope
        )

        orchestrator.start()

        setupPedalControls()
        setupVisionSimulationControls()
        setupCameraSourceControls()
        setupLivePreviewTexture()
        setupBluetoothControls()
        observeSystemState()

        // Check if camera permission is already granted, and default to webcam if available
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            enableWebcamSource()
        } else {
            enableSimulationSource()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        orchestrator.stop()
        feedbackController.shutdown()
        playbackController.shutdown()
    }

    private fun setupLivePreviewTexture() {
        binding.viewFinderTexture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                val surface = Surface(surfaceTexture)
                webcamCameraController.previewSurface = surface
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                webcamCameraController.previewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
        }
    }

    private fun setupCameraSourceControls() {
        binding.btnToggleCameraSource.setOnClickListener {
            if (isUsingWebcam) {
                enableSimulationSource()
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    enableWebcamSource()
                } else {
                    requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }

    private fun enableWebcamSource() {
        isUsingWebcam = true
        delegatingCameraController.activeDelegate = webcamCameraController
        binding.tvCameraSourceBadge.text = "Source: Laptop Webcam (webcam0)"
        binding.tvCameraSourceBadge.setTextColor(Color.parseColor("#00E676")) // Green
        binding.btnToggleCameraSource.text = "Switch to Simulation"
        binding.viewFinderTexture.visibility = View.VISIBLE
        binding.ivSimulationPreview.visibility = View.GONE
        binding.tvPreviewFps.text = "LIVE WEBCAM PREVIEW"
        binding.tvPreviewFps.setTextColor(Color.parseColor("#00E676"))
        binding.tvFramingOverlayTag.text = "ALIGN TEXTBOOK IN WEBCAM"
        binding.tvFramingOverlayTag.setTextColor(Color.parseColor("#00F0FF"))
        if (binding.viewFinderTexture.isAvailable) {
            webcamCameraController.previewSurface = Surface(binding.viewFinderTexture.surfaceTexture)
        }
    }

    private fun enableSimulationSource() {
        isUsingWebcam = false
        delegatingCameraController.activeDelegate = simulatedCameraController
        binding.tvCameraSourceBadge.text = "Source: Simulation Mode (synthetic frames)"
        binding.tvCameraSourceBadge.setTextColor(Color.parseColor("#FFD600")) // Amber
        binding.btnToggleCameraSource.text = "Switch to Webcam"
        binding.viewFinderTexture.visibility = View.GONE
        binding.ivSimulationPreview.visibility = View.VISIBLE
        binding.tvPreviewFps.text = "SYNTHETIC SIMULATION"
        binding.tvPreviewFps.setTextColor(Color.parseColor("#FFD600"))
        binding.tvFramingOverlayTag.text = "SIMULATED FRAME ACTIVE"
        binding.tvFramingOverlayTag.setTextColor(Color.parseColor("#00F0FF"))
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

        binding.btnScanNextPage.setOnClickListener {
            lifecycleScope.launch { inputSource.pressLeftShort() }
        }
    }

    private fun setupBluetoothControls() {
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        val isBtOn = btAdapter?.isEnabled == true
        if (isBtOn) {
            binding.tvBtRouteBadge.text = "ROUTED VIA HOST AUDIO / BLUETOOTH"
            binding.tvBtRouteBadge.setTextColor(Color.parseColor("#00E676"))
        } else {
            binding.tvBtRouteBadge.text = "HOST AUDIO ACTIVE (PIPEWIRE)"
            binding.tvBtRouteBadge.setTextColor(Color.parseColor("#FFD600"))
        }

        binding.btnTestBluetoothAudio.setOnClickListener {
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val testCue = "Audio check. Sound is playing through your active output device. If your earbuds are connected to your laptop, you will hear this in your earbuds. If no earbuds are connected, you will hear this through your laptop speakers."
            lifecycleScope.launch {
                feedbackController.emitHaptic(TactilePattern.NAVIGATION_TICK)
                feedbackController.speak(
                    SpokenPrompt(
                        id = "bt_test_${System.currentTimeMillis()}",
                        message = testCue,
                        priority = PromptPriority.IMMEDIATE_INTERRUPT
                    )
                )
            }
            binding.tvBtStatusMessage.text = "✓ Auditory test cue playing at $timeStr (Earbud / Speaker stream)"
            binding.tvBtStatusMessage.setTextColor(Color.parseColor("#00E676"))
        }

        binding.btnOpenBtSettings.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                startActivity(intent)
            } catch (_: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
                startActivity(fallbackIntent)
            }
        }
    }

    private fun setupVisionSimulationControls() {
        binding.btnSimSharp.setOnClickListener {
            enableSimulationSource()
            simulatedCameraController.activeScenario = SimulatedCameraScenario.SHARP_PAGE
            binding.tvFramingOverlayTag.text = "SYNTHETIC: SHARP PAGE"
            lifecycleScope.launch { simulatedCameraController.emitTestFrame() }
        }
        binding.btnSimBlur.setOnClickListener {
            enableSimulationSource()
            simulatedCameraController.activeScenario = SimulatedCameraScenario.BLURRED_PAGE
            binding.tvFramingOverlayTag.text = "SYNTHETIC: BLURRED PAGE"
            lifecycleScope.launch { simulatedCameraController.emitTestFrame() }
        }
        binding.btnSimCropped.setOnClickListener {
            enableSimulationSource()
            simulatedCameraController.activeScenario = SimulatedCameraScenario.CROPPED_PAGE
            binding.tvFramingOverlayTag.text = "SYNTHETIC: CROPPED PAGE"
            lifecycleScope.launch { simulatedCameraController.emitTestFrame() }
        }
        binding.btnSimDark.setOnClickListener {
            enableSimulationSource()
            simulatedCameraController.activeScenario = SimulatedCameraScenario.DARK_PAGE
            binding.tvFramingOverlayTag.text = "SYNTHETIC: DARK PAGE"
            lifecycleScope.launch { simulatedCameraController.emitTestFrame() }
        }
        binding.btnSimGlare.setOnClickListener {
            enableSimulationSource()
            simulatedCameraController.activeScenario = SimulatedCameraScenario.GLARE_PAGE
            binding.tvFramingOverlayTag.text = "SYNTHETIC: GLARE DETECTED"
            lifecycleScope.launch { simulatedCameraController.emitTestFrame() }
        }
        binding.btnSimDisconnect.setOnClickListener {
            enableSimulationSource()
            simulatedCameraController.activeScenario = SimulatedCameraScenario.DISCONNECTED
            binding.tvFramingOverlayTag.text = "SYNTHETIC: CAMERA DISCONNECTED"
            lifecycleScope.launch { simulatedCameraController.emitTestFrame() }
        }
    }

    private fun observeSystemState() {
        // Observe State Machine transitions
        orchestrator.state.onEach { state ->
            renderAssistantState(state)
        }.launchIn(lifecycleScope)

        // Observe Camera Frames for Quality Inspection
        delegatingCameraController.analysisFrames.onEach { frame ->
            val decision = qualityEngine.evaluate(frame)
            when (decision) {
                is ReadinessDecision.Ready -> {
                    val score = decision.score
                    binding.tvQualityMetrics.text =
                        "Readiness: READY | Sharpness: ${"%.1f".format(score.sharpness * 100)} | " +
                        "Glare: ${"%.1f".format(score.glareRatio * 100)}% | Exposure: ${"%.1f".format(score.exposure * 100)} | Issues: NONE"
                    binding.tvFramingOverlayTag.text = "PAGE READY • TAP RIGHT FOOT TO CAPTURE"
                    binding.tvFramingOverlayTag.setTextColor(Color.parseColor("#00E676")) // Green
                }
                is ReadinessDecision.NotReady -> {
                    val score = decision.score
                    binding.tvQualityMetrics.text =
                        "Readiness: NOT READY | Sharpness: ${"%.1f".format(score.sharpness * 100)} | " +
                        "Glare: ${"%.1f".format(score.glareRatio * 100)}% | Issue: ${decision.primaryIssue.readableMessage}"
                    binding.tvFramingOverlayTag.text = decision.primaryIssue.readableMessage.uppercase()
                    binding.tvFramingOverlayTag.setTextColor(Color.parseColor("#FFD600")) // Amber
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
                    "Study document ready! Tap Right Foot to listen, or Left Foot to scan next page.",
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
                    "Playback paused. Tap Right Foot to resume, or Left Foot to scan next page.",
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

        when (state) {
            is AssistantState.ScannerStarting, is AssistantState.Scanning -> {
                binding.viewFinderTexture.visibility = View.VISIBLE
                binding.ivSimulationPreview.visibility = View.GONE
                if (binding.viewFinderTexture.isAvailable && binding.viewFinderTexture.surfaceTexture != null) {
                    webcamCameraController.previewSurface = Surface(binding.viewFinderTexture.surfaceTexture)
                }
            }
            is AssistantState.Capturing -> {
                binding.tvFramingOverlayTag.text = "CAPTURING STILL..."
                binding.tvFramingOverlayTag.setTextColor(Color.parseColor("#00F0FF"))
            }
            is AssistantState.Processing -> {
                binding.tvFramingOverlayTag.text = "AI GENERATING STUDY GUIDE..."
                binding.tvFramingOverlayTag.setTextColor(Color.parseColor("#E040FB"))
            }
            is AssistantState.DocumentReadyForPlayback -> {
                binding.tvFramingOverlayTag.text = "READY • TAP RIGHT FOOT TO PLAY • LEFT TO SCAN NEXT PAGE"
                binding.tvFramingOverlayTag.setTextColor(Color.parseColor("#00E676"))
            }
            is AssistantState.Playing -> {
                binding.tvFramingOverlayTag.text = "AUDIO PLAYBACK ACTIVE"
                binding.tvFramingOverlayTag.setTextColor(Color.parseColor("#00E676"))
            }
            is AssistantState.Paused -> {
                binding.tvFramingOverlayTag.text = "PAUSED • TAP LEFT FOOT TO SCAN NEXT PAGE"
                binding.tvFramingOverlayTag.setTextColor(Color.parseColor("#FFD600"))
            }
            else -> {}
        }
    }

    private fun displayStudyDocument(doc: StudyDocument) {
        if (currentDocument?.id == doc.id) return
        currentDocument = doc

        binding.tvDocumentTitle.text = "Document: ${doc.title}"
        val builder = SpannableStringBuilder()

        for (sec in doc.sections) {
            val headerColor = when (sec.type) {
                SectionType.TRANSCRIPTION -> "#33B5E5"
                SectionType.KEY_DEFINITIONS -> "#00C853"
                SectionType.EXPLANATION -> "#FFAB00"
                SectionType.WORKED_SOLUTIONS -> "#00E676" // Vibrant Green
                SectionType.SUMMARY -> "#E040FB"
                SectionType.UNCERTAINTIES -> "#FF6D00"
            }

            val headerText = if (sec.title.startsWith("Question", ignoreCase = true)) {
                "【${sec.title.uppercase()}】"
            } else {
                "【${sec.type.displayName}】 ${sec.title}"
            }

            val startHeader = builder.length
            builder.append("$headerText\n")
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_L -> {
                    lifecycleScope.launch { inputSource.pressLeftShort() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_SPACE -> {
                    lifecycleScope.launch { inputSource.pressRightShort() }
                    return true
                }
                KeyEvent.KEYCODE_ESCAPE -> {
                    lifecycleScope.launch { inputSource.pressLeftShort() }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_L -> {
                lifecycleScope.launch { inputSource.pressLeftShort() }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_SPACE -> {
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
