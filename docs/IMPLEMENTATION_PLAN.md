# Implementation Plan & Checklist

This plan defines the incremental delivery milestones across all project phases. Each phase requires verification against its acceptance criteria before proceeding.

---

## Master Checklist

- [x] **Phase 0: Project Audit and Engineering Foundation**
  - [x] Inspect environment, toolchain, and host dependencies.
  - [x] Provision standalone JDK 17 (`/home/ameya/tools/jdk-17`) and Android SDK API 34 (`/home/ameya/tools/android-sdk`).
  - [x] Establish Gradle wrapper and build baseline.
  - [x] Initialize Git version control and `.gitignore`.
  - [x] Author comprehensive project specifications in `docs/`.
  - [x] Acceptance Gate: Baseline project builds and tests execute with 0 failures.

- [ ] **Phase 1: Domain Models and State Machine**
  - [ ] Implement pure Kotlin domain models:
    - [ ] Scan session & capture models (`ScanSession`, `ImageQualityResult`, `ReadinessDecision`).
    - [ ] AI processing models (`StudyDocument`, `DocumentSection`, `StudyTask`, `SectionType`).
    - [ ] Playback & navigation models (`PlaybackState`, `ReadingPosition`, `SpeechSegment`).
    - [ ] Error models (`AssistantError`, `ErrorCode`, `Recoverability`).
  - [ ] Implement Input Policy and Button Event definitions (`ButtonEvent`, `ButtonAction`, `InputPolicy`).
  - [ ] Implement deterministic Finite State Machine (`AssistantStateMachine`, legal transitions, guard conditions, side-effect actions).
  - [ ] Unit tests for 100% of major transitions, invalid transitions, duplicate debounce, and cancellation.
  - [ ] Acceptance Gate: Unit test suite for State Machine & Input Policy executes and passes.

- [ ] **Phase 2: Simulation and Developer Interface**
  - [ ] Implement fake/simulated adapters (`SimulatedDocumentCameraController`, `SimulatedInputSource`, `SimulatedFeedbackController`, `FakeStudyDocumentGenerator`, `SimulatedSpeechEngine`).
  - [ ] Build Developer Simulation UI with real-time state inspection, event history log, and manual stimulus injection buttons.
  - [ ] End-to-end simulation tests exercising domain logic.
  - [ ] Acceptance Gate: Full scan-to-speech lifecycle can be simulated end-to-end on laptop.

- [ ] **Phase 3: Camera and Image Quality Engine**
  - [ ] Define `DocumentCameraController` interface.
  - [ ] Implement `ImageQualityEngine` assessing sharpness, framing, exposure, glare, and stability.
  - [ ] Implement test image fixture loading (sharp, blurry, cropped, dark, overexposed, glare).
  - [ ] Implement hysteresis and single-trigger readiness notification policy.
  - [ ] Unit & integration tests for image quality classifications.
  - [ ] Acceptance Gate: Fixture images correctly scored and classified without false triggers.

- [ ] **Phase 4: Document Preprocessing and OCR**
  - [ ] Implement image preprocessing pipeline (validation, downscaling, contrast enhancement, unsharp mask).
  - [ ] Implement replaceable OCR abstraction with fallback mechanisms.
  - [ ] Persist extracted text and OCR confidence metadata alongside original captures.
  - [ ] Acceptance Gate: Text extraction verified on sample test corpus; original images unmodified.

- [ ] **Phase 5: AI Integration and Study Document Generation**
  - [ ] Implement `StudyDocumentGenerator` interface and JSON schema validation.
  - [ ] Construct versioned system prompt enforcing transcription vs. explanation distinction.
  - [ ] Add network retry policies, exponential backoff, cancellation tokens, and timeouts.
  - [ ] Implement persistent storage for partial/resumed generation jobs.
  - [ ] Acceptance Gate: Mock provider tests verify success, timeout, cancellation, malformed response, and error recovery.

- [ ] **Phase 6: Playback and Tactile Feedback**
  - [ ] Implement segmented paragraph-aware speech player with sentence splitting.
  - [ ] Implement navigation: play, pause, resume, repeat paragraph, rewind, previous/next paragraph, and section jumping.
  - [ ] Implement `FeedbackController` with distinct vibration cadence patterns.
  - [ ] Audio interruption handling and state restoration after process death.
  - [ ] Acceptance Gate: Playback controls accurately navigate long multi-paragraph documents and persist reading position.

- [ ] **Phase 7: Android System Integration**
  - [ ] Assemble Room database and repository layer.
  - [ ] Implement Android foreground service for lifecycle persistence.
  - [ ] Configure TalkBack accessibility labels, accessibility action delegates, and high-contrast styling.
  - [ ] Acceptance Gate: Application launches, runs without memory leaks, TalkBack navigates seamlessly.

- [ ] **Phase 8: Hardware Adapter Implementation**
  - [ ] USB UVC camera driver integration via Android USB host APIs.
  - [ ] BLE GATT and HID foot button controller integration.
  - [ ] System vibrator and external motor GPIO interface.
  - [ ] Acceptance Gate: Live hardware drivers tested or cleanly bypassed with graceful degradation when disconnected.

- [ ] **Phase 9: Reliability, Security, and Release Readiness**
  - [ ] End-to-end automated regression testing.
  - [ ] Memory stress tests and low-memory survival checks.
  - [ ] Security audit: Zero credentials committed, sensitive image retention policies verified.
  - [ ] Final user documentation and reproduction guides.
  - [ ] Acceptance Gate: All required tests pass; zero critical bugs; reproducible build verified.
