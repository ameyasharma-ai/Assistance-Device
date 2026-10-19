package com.readingassistant.domain.simulator

import com.readingassistant.domain.AssistantOrchestrator
import com.readingassistant.domain.ai.FakeStudyDocumentGenerator
import com.readingassistant.domain.camera.SimulatedCameraScenario
import com.readingassistant.domain.camera.SimulatedDocumentCameraController
import com.readingassistant.domain.feedback.SimulatedFeedbackController
import com.readingassistant.domain.input.SimulatedInputSource
import com.readingassistant.domain.model.ReadinessDecision
import com.readingassistant.domain.model.SectionType
import com.readingassistant.domain.playback.SimulatedPlaybackController
import com.readingassistant.domain.quality.ImageQualityEngine
import com.readingassistant.domain.state.AssistantState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Scanner

/**
 * Interactive Laptop Terminal Simulator.
 * Allows executing and inspecting the full Wearable AI Reading Assistant pipeline
 * directly on a development laptop without needing physical hardware or emulators.
 */
fun main(args: Array<String>) = runBlocking {
    println("===============================================================================")
    println("      WEARABLE AI READING ASSISTANT — LAPTOP CONSOLE SIMULATOR                 ")
    println("===============================================================================")

    val scope = CoroutineScope(Dispatchers.Default)
    val inputSource = SimulatedInputSource()
    val cameraController = SimulatedDocumentCameraController(scope = scope)
    val feedbackController = SimulatedFeedbackController()
    val documentGenerator = FakeStudyDocumentGenerator().apply { simulatedDelayMs = 200L }
    val playbackController = SimulatedPlaybackController(scope = scope).apply { segmentDurationMs = 1200L }
    val qualityEngine = ImageQualityEngine()

    val orchestrator = AssistantOrchestrator(
        inputController = inputSource,
        cameraController = cameraController,
        feedbackController = feedbackController,
        documentGenerator = documentGenerator,
        playbackController = playbackController,
        qualityEngine = qualityEngine,
        scope = scope
    )

    orchestrator.start()

    // Real-time state observers
    orchestrator.state.onEach { state ->
        println("\n>>> [STATE CHANGED] -> ${state.name}")
        when (state) {
            is AssistantState.DocumentReadyForPlayback -> {
                println("\n" + "=".repeat(60))
                println("STUDY DOCUMENT GENERATED: ${state.document.title}")
                println("=".repeat(60))
                for (sec in state.document.sections) {
                    val tag = if (sec.type == SectionType.TRANSCRIPTION) "【VERBATIM SOURCE】" else "【${sec.type}】"
                    println("\n$tag ${sec.title}")
                    println(sec.content)
                }
                println("=".repeat(60) + "\n")
            }
            is AssistantState.Error -> {
                println("!!! ERROR: [${state.error.code}] ${state.error.message}")
            }
            else -> {}
        }
    }.launchIn(scope)

    feedbackController.hapticEvents.onEach { pattern ->
        println("  [TACTILE HAPTIC] Pulse: $pattern")
    }.launchIn(scope)

    feedbackController.spokenEvents.onEach { prompt ->
        println("  [AUDIO SPOKEN CUE] \"${prompt.message}\"")
    }.launchIn(scope)

    playbackController.currentSegment.onEach { segment ->
        if (segment != null) {
            println("  [TTS READING ALOUD] Section ${segment.sectionIndex + 1}, Segment ${segment.segmentIndex + 1}: \"${segment.text}\"")
        }
    }.launchIn(scope)

    val isDemo = args.contains("--demo")

    if (isDemo) {
        runAutomatedWalkthrough(inputSource, cameraController)
    } else {
        println("\nCONTROLS:")
        println("  [L]  Left Foot Tap        (Scan / Cancel / Prev Paragraph)")
        println("  [R]  Right Foot Tap       (Capture / Confirm / Play-Pause)")
        println("  [LL] Left Double Tap      (Rewind 15s)")
        println("  [RR] Right Double Tap     (Repeat Paragraph)")
        println("  [LP] Left Long Press      (Previous Section)")
        println("  [RP] Right Long Press     (Next Section)")
        println("  [1]  Inject Sharp Page    [2] Inject Blurry Page    [3] Inject Cropped Page")
        println("  [4]  Inject Dark Page     [5] Inject Glare Page     [6] Disconnect Camera")
        println("  [auto] Run Auto Demo     [Q] Quit")
        println("-------------------------------------------------------------------------------")

        val scanner = Scanner(System.`in`)
        while (scanner.hasNextLine()) {
            val line = scanner.nextLine().trim().lowercase()
            when (line) {
                "l" -> inputSource.pressLeftShort()
                "r" -> inputSource.pressRightShort()
                "ll" -> inputSource.pressLeftDouble()
                "rr" -> inputSource.pressRightDouble()
                "lp" -> inputSource.pressLeftLong()
                "rp" -> inputSource.pressRightLong()
                "1" -> {
                    cameraController.activeScenario = SimulatedCameraScenario.SHARP_PAGE
                    cameraController.emitTestFrame()
                }
                "2" -> {
                    cameraController.activeScenario = SimulatedCameraScenario.BLURRED_PAGE
                    cameraController.emitTestFrame()
                }
                "3" -> {
                    cameraController.activeScenario = SimulatedCameraScenario.CROPPED_PAGE
                    cameraController.emitTestFrame()
                }
                "4" -> {
                    cameraController.activeScenario = SimulatedCameraScenario.DARK_PAGE
                    cameraController.emitTestFrame()
                }
                "5" -> {
                    cameraController.activeScenario = SimulatedCameraScenario.GLARE_PAGE
                    cameraController.emitTestFrame()
                }
                "6" -> {
                    cameraController.activeScenario = SimulatedCameraScenario.DISCONNECTED
                    cameraController.emitTestFrame()
                }
                "auto" -> runAutomatedWalkthrough(inputSource, cameraController)
                "q" -> break
                else -> println("Unknown command: '$line'. Type 'l', 'r', 'auto', or 'q'.")
            }
        }
    }

    orchestrator.stop()
    scope.cancel()
    println("\nSimulator finished.")
}

private suspend fun runAutomatedWalkthrough(
    inputSource: SimulatedInputSource,
    cameraController: SimulatedDocumentCameraController
) {
    println("\n--- STARTING AUTOMATED SCAN-TO-SPEECH WALKTHROUGH ---")
    delay(500)

    println("\nStep 1: User taps Left Foot to activate scanner...")
    inputSource.pressLeftShort()
    delay(600)

    println("\nStep 2: Camera begins preview. Analyzing textbook page frame...")
    cameraController.activeScenario = SimulatedCameraScenario.SHARP_PAGE
    cameraController.emitTestFrame()
    delay(700)

    println("\nStep 3: Document ready and framed! User taps Right Foot to capture...")
    inputSource.pressRightShort()
    delay(700)

    println("\nStep 4: Image quality validation passed! User taps Right Foot to confirm AI processing...")
    inputSource.pressRightShort()
    delay(1500)

    println("\nStep 5: Study document ready! User taps Right Foot to start auditory playback...")
    inputSource.pressRightShort()
    delay(2800)

    println("\nStep 6: User taps Right Foot to pause playback...")
    inputSource.pressRightShort()
    delay(600)

    println("\nStep 7: User taps Left Foot to step back to previous sentence...")
    inputSource.pressLeftShort()
    delay(600)

    println("\nStep 8: User taps Right Foot to resume playback...")
    inputSource.pressRightShort()
    delay(1800)

    println("\nStep 9: User taps Right Foot to pause. Walkthrough complete!")
    inputSource.pressRightShort()
    delay(400)
    println("--- WALKTHROUGH COMPLETED SUCCESSFULLY ---")
}
