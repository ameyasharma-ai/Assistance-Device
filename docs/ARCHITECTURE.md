# System Architecture: Wearable AI Reading Assistant

## 1. Architectural Principles

1. **Hardware Independence & Testability:**
   All core business logic, state machines, quality assessment algorithms, and AI orchestrations are strictly isolated from Android framework classes and hardware drivers. Every external hardware sensor/actuator is accessed via a typed interface with companion deterministic simulators.
2. **Deterministic State Progression:**
   The assistant's operation is governed by a formal, deterministic finite state machine (FSM). Input signals from the foot controls do not directly trigger side effects; they are processed by an `InputPolicy` that translates debounced physical events into legal state transitions.
3. **Fail-Safe & Accessible by Default:**
   Designed for blind and visually impaired users. Every state transition has paired multimodal feedback (distinct tactile vibration patterns and non-blocking spoken audio cues). The application must never enter an unrecoverable silent hang.
4. **Constrained Hardware Footprint:**
   Target board constraint is 3 GB RAM and 32 GB flash. Memory allocation is bounded: analysis frames are downsampled and immediately recycled; full-resolution images are written to private flash storage and freed from memory; AI responses are streamed and parsed into modular section entities.

---

## 2. Layered Module Structure

The project is structured into clean modules:

```
Assistance-Device/
├── core-domain/                  # Pure Kotlin module (No Android framework dependencies)
│   ├── src/main/kotlin/
│   │   └── com/readingassistant/domain/
│   │       ├── state/            # FSM: States, Events, Actions, Transitions, StateMachine
│   │       ├── input/            # InputController, ButtonEvent, ButtonAction, InputPolicy
│   │       ├── camera/           # DocumentCameraController, CameraStatus, FrameData
│   │       ├── quality/          # ImageQualityEngine, QualityScore, ReadinessDecision
│   │       ├── ocr/              # OcrEngine, OcrResult, BoundingBox
│   │       ├── ai/               # StudyDocumentGenerator, StudyTask, PromptTemplates
│   │       ├── model/            # StudyDocument, Section, Session, ReadingPosition
│   │       ├── playback/         # StudyPlaybackController, PlaybackState, Segment
│   │       ├── feedback/         # FeedbackController, TactilePattern, SpokenPrompt
│   │       └── error/            # AssistantError, ErrorCode, ErrorRecovery
│   └── src/test/kotlin/          # Fast, 100% deterministic JVM unit tests
│
├── app/                          # Android Application module
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/com/readingassistant/
│   │   │   ├── di/               # Service Locator / Dependency Container
│   │   │   ├── service/          # Foreground Service for persistent assistant lifecycle
│   │   │   ├── camera/           # UVC Camera & CameraX implementations
│   │   │   ├── bluetooth/        # BLE GATT Foot Button & HID adapter
│   │   │   ├── audio/            # Android TextToSpeech & Bluetooth SCO/A2DP manager
│   │   │   ├── haptics/          # Android Vibrator & GPIO vibration driver
│   │   │   ├── data/             # Room Database, DAOs, FileStorageManager
│   │   │   ├── ai/               # Cloud Vision/LLM HTTP client (Gemini/OpenAI compatible)
│   │   │   └── ui/               # Accessible UI & Developer Simulation Activity
│   │   └── res/                  # Accessible layouts, strings, semantic descriptions
│   └── src/test/ & src/androidTest/
```

---

## 3. Core Subsystems & Interfaces

### 3.1 Input Controller & Foot Controls
- **Physical Controls:** 2 shoe-mounted buttons (Left / Right).
- **Event Types:** Short Press, Double Press, Long Press (>800ms).
- **Debounce & Filtering:** 150ms hardware debounce window; duplicate event suppression.
- **Context-Sensitive Input Policy:**
  - *When Scanning:* Left button cancels/re-starts; Right button captures.
  - *When In Review / Awaiting Confirmation:* Left button retakes; Right button confirms & submits.
  - *When In Playback:* Left short press: Previous paragraph; Left double press: Rewind 15s; Left long press: Previous section. Right short press: Play/Pause; Right double press: Next paragraph; Right long press: Next section.

### 3.2 Application State Machine (FSM)
States:
1. `IDLE`: Assistant standing by, low power.
2. `SCANNER_STARTING`: Initializing camera pipeline and quality engine.
3. `SCANNING`: Live analysis of textbook framing, sharpness, lighting, stability.
4. `DOCUMENT_READY`: Frame meets readiness criteria (triggers readiness vibration once).
5. `CAPTURING`: Triggering still capture at full resolution.
6. `CAPTURED`: Full-resolution image acquired.
7. `CAPTURE_VALIDATION`: Analyzing captured still image for final clarity.
8. `AWAITING_CONFIRMATION`: Quality advisory presented (if suboptimal) or manual confirmation pending.
9. `PROCESSING`: Submitting to OCR and Cloud Vision/LLM; polling/streaming structured sections.
10. `DOCUMENT_READY_FOR_PLAYBACK`: Study document persisted, sections validated, announcement queued.
11. `PLAYING`: Text-to-speech active, reading current paragraph.
12. `PAUSED`: Playback halted at exact paragraph/sentence boundary.
13. `ERROR`: Structured error with recovery options.

### 3.3 Quality Engine (Computer Vision)
- Signals Evaluated:
  - Text-region sharpness (Laplacian variance on detected document region).
  - Page boundary & perspective completeness.
  - Exposure & contrast histogram (clipping check for under/over-exposure).
  - Glare detection (high-intensity specular reflection clusters).
  - Inter-frame stability (optical flow / frame differencing).
- Output: `ReadinessDecision` with numeric scores and localized reasons (`READY`, `BLURRY`, `PAGE_CROPPED`, `TOO_DARK`, `TOO_BRIGHT`, `UNSTABLE`, `GLARE_DETECTED`).
- Notification Policy: Hysteresis-gated tactile alert (triggers once upon entering `DOCUMENT_READY`, re-arms only if state degrades and recovers).

### 3.4 AI Processing & Structured Study Documents
- Contract: Structured JSON response conforming to `StudyDocument` domain model:
  - `sourceTranscription`: Exact reading of original textbook page.
  - `keyDefinitions`: Extracted terms and accessible definitions.
  - `explanations`: Concept breakdowns in conversational, student-friendly tone.
  - `workedSolutions`: Step-by-step problem solutions preserving units and equations.
  - `studySummary`: Quick revision bullet points.
  - `uncertainties`: Explicit flags for unreadable or ambiguous text.
- Separation of Source vs. Generated: Crucial distinction preserved for assistive accuracy.

### 3.5 Playback & Audio Pipeline
- Paragraph-Aware Segmentation: Documents are split into sequential sentences/paragraphs (`SpeechSegment`).
- Granular Navigation: Allows jumping forward/back by paragraph, repeating current paragraph, or jumping sections.
- Resumable State: `ReadingPosition` (documentId, sectionIndex, segmentIndex, characterOffset) persisted transactionally in Room DB.

---

## 4. Hardware Simulation Architecture (Dev Mode)

To allow full laptop-based development and continuous automated verification before physical hardware integration:
- `SimulatedDocumentCameraController`: Emits configurable image fixtures (sharp, blurred, cropped, dark, glare, camera disconnect).
- `SimulatedInputSource`: Injectable left/right button clicks, double clicks, and long presses.
- `SimulatedFeedbackController`: Logs tactile vibration waveforms and speech prompts without hardware vibrator.
- `FakeStudyDocumentGenerator`: Deterministic mock LLM responses with configurable delay, errors, partial generation, and schema validation.
