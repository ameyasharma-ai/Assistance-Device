# Test Execution Results

This document records the actual test executions, commands run, test counts, passing/failing status, and verification metrics.

---

## Baseline Verification (Phase 0)

- **Date:** October 19, 2026
- **JDK:** OpenJDK 17.0.20.1 (Eclipse Adoptium)
- **Android SDK:** API Level 34 (Android 14)
- **Gradle Version:** Gradle 8.7
- **Verification Commands Executed:**
  - `java -version` -> PASSED (Temurin 17.0.20.1+1)
  - `sdkmanager --list_installed` -> PASSED (platforms;android-34, build-tools;34.0.0, platform-tools 37.0.1)
  - `gradle -v` -> PASSED (Gradle 8.7 running on JVM 17.0.20.1)
  - `./gradlew test` -> PASSED (Baseline project compilation and tasks clean)

---

## Phase 1 Test Results: Domain Models & State Machine

- **Command Executed:** `./gradlew :core-domain:test --info`
- **Execution Date:** October 19, 2026
- **Test Summary:**
  - Total Tests: 23
  - Failures: 0
  - Errors: 0
  - Skipped: 0
  - Result: **PASSED (100% success rate)**

### Detailed Test Suites:

1. `com.readingassistant.domain.state.AssistantStateMachineTest` (10 tests, 0 failures):
   - `initial state is Idle` -> PASSED
   - `full happy path from Idle through Scan to Playback and Pause` -> PASSED
   - `quality degradation from DocumentReady drops back to Scanning and re-arms` -> PASSED
   - `capture validation with sub-optimal quality enters AwaitingConfirmation and allows Retake` -> PASSED
   - `capture validation with sub-optimal quality allows user to Confirm and proceed anyway` -> PASSED
   - `cancellation during scanning stops camera and returns to Idle` -> PASSED
   - `cancellation during processing cancels AI job and returns to Idle` -> PASSED
   - `invalid events in current state do not crash or alter state` -> PASSED
   - `error state preserves previous state and recovers to safe state upon dismissal` -> PASSED
   - `paragraph and section navigation updates reading position and emits haptic ticks` -> PASSED

2. `com.readingassistant.domain.input.InputPolicyTest` (9 tests, 0 failures):
   - `debounce discards rapid duplicate clicks within window on same button` -> PASSED
   - `different physical buttons have independent debounce timers` -> PASSED
   - `idle state only accepts left short press` -> PASSED
   - `scanning state maps left to cancel and right to capture` -> PASSED
   - `capturing and validation states ignore all button inputs` -> PASSED
   - `awaiting confirmation maps left to retake and right to confirm` -> PASSED
   - `processing state rejects clicks and only permits left long press for cancellation` -> PASSED
   - `playback states support full navigation controls` -> PASSED
   - `error state allows any short click to dismiss error` -> PASSED

3. `com.readingassistant.domain.model.DomainModelsTest` (4 tests, 0 failures):
   - `quality score calculates weighted overall confidence correctly` -> PASSED
   - `generation progress calculates percentage accurately and handles zero total` -> PASSED
   - `study document correctly finds section by SectionType` -> PASSED
   - `assistant error properties are accessible and preserve recoverability` -> PASSED
