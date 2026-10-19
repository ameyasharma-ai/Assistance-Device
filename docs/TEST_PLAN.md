# Test Plan & Verification Strategy

## 1. Testing Philosophy

1. **Deterministic Verification:**
   All domain logic, state machines, input policies, and AI orchestration must be 100% testable via JVM unit tests without relying on network connectivity, physical hardware, or Android emulators.
2. **Zero False Assertions:**
   Tests will never mock away the actual logic being verified. We do not weaken assertions or disable tests to force a build to pass.
3. **Layered Verification Pyramid:**
   - **Unit Tests:** Fast, isolated tests for state transitions, button debounce, quality calculations, JSON parsing, and segment navigation.
   - **Integration Tests:** Repository persistence, state-machine-to-audio-pipeline coordination, mock AI streaming, and failure recovery.
   - **Simulation Tests:** Automated script driving the full end-to-end user journey through fake hardware adapters.

---

## 2. Test Suites by Phase

### Phase 1: State Machine & Domain Logic
- `AssistantStateMachineTest`:
  - Verify every valid forward transition:
    - `IDLE` -> `SCANNER_STARTING` -> `SCANNING` -> `DOCUMENT_READY` -> `CAPTURING` -> `CAPTURED` -> `CAPTURE_VALIDATION` -> `AWAITING_CONFIRMATION` -> `PROCESSING` -> `DOCUMENT_READY_FOR_PLAYBACK` -> `PLAYING` -> `PAUSED` -> `IDLE`.
  - Verify invalid transition rejection without crash or corrupt state.
  - Verify cancellation from `SCANNING`, `CAPTURING`, and `PROCESSING`.
  - Verify recovery transitions from `ERROR` state.
- `InputPolicyTest`:
  - Left / Right button click, double click, long press mappings across different application states.
  - Debounce filtering (events within debounce window discarded).
  - Rapid alternating clicks and duplicate input suppression.

### Phase 3: Camera & Image Quality Engine
- `ImageQualityEngineTest`:
  - Sharp page fixture -> yields `ReadinessDecision.READY`.
  - Blurry page fixture -> yields `ReadinessDecision.NOT_READY` with reason `BLURRY`.
  - Cropped page fixture -> yields `ReadinessDecision.NOT_READY` with reason `PAGE_CROPPED`.
  - Dark page fixture -> yields reason `TOO_DARK`.
  - Overexposed page fixture -> yields reason `TOO_BRIGHT`.
  - Glare fixture -> yields reason `GLARE_DETECTED`.
  - Stability calculation across consecutive frames.
  - Hysteresis test: readiness notification fires only once on initial readiness, does not fire repeatedly on every frame.

### Phase 4: OCR & Document Preprocessing
- `OcrEngineTest`:
  - Test printed text extraction accuracy against reference transcript.
  - Verify confidence threshold reporting.
  - Multi-column and table handling without crash.
  - Verification that original raw captured image file is preserved unmodified on disk.

### Phase 5: AI Orchestration & Schema Validation
- `StudyDocumentGeneratorTest`:
  - Successful JSON response parsing into typed `StudyDocument`.
  - Timeout and retry behavior with exponential backoff.
  - Malformed JSON handling: gracefully returns structured error without crashing.
  - Prompt construction verification (source transcription segregated from explanations).
  - Interrupted job resumption: incomplete jobs can resume without duplicate sections.

### Phase 6: Playback & Audio Controller
- `StudyPlaybackControllerTest`:
  - Paragraph segmentation: splits multi-paragraph document into sequential `SpeechSegment`s.
  - Play, pause, resume at exact paragraph/sentence offset.
  - Paragraph navigation: Next paragraph, previous paragraph, repeat current paragraph.
  - Section jumping: Next section, previous section.
  - Reading position persistence and restoration.
  - Audio interruption simulation (simulating incoming phone call or Bluetooth disconnect).

### Phase 7 & 8: System & Simulation Integration
- `EndToEndSimulationTest`:
  - Full scripted journey from Left-foot scan trigger to speech playback and paragraph navigation using fake adapters.
  - Injection of camera disconnect during scan.
  - Injection of API failure and user retake flow.
