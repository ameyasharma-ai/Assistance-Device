# Architectural & Engineering Decisions (ADR)

## ADR 001: Modular Architecture with Pure Kotlin `core-domain`
- **Context:** The application logic (FSM, input policy, document models, study prompts, quality scoring contracts) must be verifiable on a development laptop without physical hardware, emulators, or Android framework overhead.
- **Decision:** Split the project into a pure Kotlin JVM library module (`core-domain`) and an Android application module (`app`).
- **Consequences:**
  - Positive: Unit tests in `core-domain` execute in milliseconds on the host JVM.
  - Positive: Zero risk of accidental coupling to Android SDK components (`Context`, `Handler`, `Looper`) in domain business logic.
  - Positive: Clean separation of concerns strictly enforces dependency inversion.
  - Tradeoff: Shared data models between Android UI and domain must be passed cleanly or mapped.

## ADR 002: Formal Finite State Machine with Centralized Input Policy
- **Context:** Shoe-mounted buttons trigger different actions depending on whether the user is scanning a page, reviewing a capture, or listening to generated study text. Scattering `if-else` button checks across activities leads to race conditions, double triggers, and unpredictable state.
- **Decision:** All raw button inputs are routed into a centralized `InputPolicy`. The policy debounces signals (150ms window) and converts physical actions into typed `AssistantEvent`s dispatched to a single deterministic `AssistantStateMachine`.
- **Consequences:**
  - Positive: Impossible for illegal transitions to occur silently.
  - Positive: 100% testable state coverage with fast unit tests.
  - Positive: Duplicate/rapid button presses are filtered out before reaching business logic.

## ADR 003: Single-Trigger Haptic Readiness with Hysteresis
- **Context:** When the user points the wearable camera at a page, the quality engine evaluates framing and sharpness on every frame (15-30 fps). Continuous vibration on every ready frame is disorienting and drains battery.
- **Decision:** Implement a state transition notification policy. When the state transitions from `SCANNING` to `DOCUMENT_READY`, a single distinct vibration pattern is emitted. Re-announcement only occurs if the image degrades back to `SCANNING` and subsequently recovers, or after an explicit timeout.
- **Consequences:**
  - Positive: Tactile feedback is distinct, unambiguous, and pleasant for the user.

## ADR 004: Segregated Structured Study Document Schema
- **Context:** Visually impaired students need clear separation between the exact literal text on the textbook page and the AI's commentary, explanations, and worked solutions.
- **Decision:** Study documents are parsed and persisted as structured multi-section models (`sourceTranscription`, `keyDefinitions`, `explanations`, `workedSolutions`, `studySummary`, `uncertainties`) rather than a monolithic markdown string.
- **Consequences:**
  - Positive: Audio playback can navigate between sections cleanly.
  - Positive: Transcription errors or model hallucinations in explanations do not corrupt the record of the primary source text.

## ADR 005: Paragraph-Aware Speech Segmentation
- **Context:** Android TextToSpeech cannot natively pause or rewind arbitrary byte streams without restarting the utterance from the beginning. Large single utterances cause long TTS preparation lag.
- **Decision:** Split documents into structured, sequential `SpeechSegment`s (paragraphs and sentences). The playback controller maintains a pointer to `(sectionIndex, segmentIndex, characterOffset)`.
- **Consequences:**
  - Positive: Pause, resume, next paragraph, previous paragraph, and repeat paragraph operate instantaneously and reliably.
  - Positive: Reading position survives app restarts and process recreation.
