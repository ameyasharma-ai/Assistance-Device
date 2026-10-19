# Project Audit: Wearable AI Reading Assistant

**Date:** October 19, 2026  
**Auditor:** Lead Android Engineer, Architect & QA  
**Target Device Environment:** Laptop-first development with target wearable Android platform (Motherboard: 3GB RAM, 32GB Flash, USB UVC Camera, BLE Foot Controls, Vibration Motor, Bluetooth Audio).

---

## 1. Environment & Workspace Status

- **Workspace Path:** `/home/ameya/Assistance-Device`
- **Initial Workspace State:** Empty directory (`ls` returned 0 files). No prior Android project or code existed in this folder.
- **Operating System:** Linux `7.0.0-34-generic` x86_64 (Ubuntu 24.04 LTS derivative).
- **System Memory:** 16GB RAM on host development laptop (ASUS ROG Strix G513RC).
- **Storage:** Root filesystem has 145 GB free space.

---

## 2. Toolchain Reconnaissance

- **Existing Java/JDK:**
  - System default JDK was `openjdk version "1.8.0_504"` located at `/usr/lib/jvm/java-8-openjdk-amd64`.
  - Android Gradle Plugin (AGP) 8.x and Kotlin 1.9+ require JDK 17+.
  - **Resolution:** Provisioned Eclipse Temurin OpenJDK 17 (`jdk-17.0.20.1+1`) in user space at `/home/ameya/tools/jdk-17`.
- **Android SDK & Platform Tools:**
  - `ANDROID_HOME` was not configured in the host environment; no pre-installed Android SDK found in standard paths.
  - **Resolution:** Provisioned Google Android Command-Line Tools (`11076708`) at `/home/ameya/tools/android-sdk`.
  - Installed SDK Components:
    - Android SDK Platform 34 (API 34 / Android 14)
    - Android SDK Build-Tools 34.0.0
    - Android SDK Platform-Tools (`adb`)
- **Android Emulators & Physical Devices:**
  - `adb devices`: No physical Android device or active emulator currently connected.
  - Development approach: **Laptop-first simulation architecture** as required by Section 7 of the specification.
  - All core domain logic, state machine transitions, image quality scoring, and AI orchestration will run deterministically in unit tests on the JVM without requiring an emulator.
  - The Android app module will provide a full Developer Simulation Interface.

---

## 3. Codebase & Version Control

- **Git Status:** Directory was not previously a git repository.
- **Resolution:** Initialized git version control with comprehensive `.gitignore` configured for Android, Gradle, and IDE artifacts.
- **Components Found:** Zero pre-existing camera, audio, accessibility, networking, or Bluetooth components. Clean slate.

---

## 4. Hardware Interfaces & Abstraction Requirements

The final device relies on:
1. **USB UVC Camera (OTG):** Requires `DocumentCameraController` interface, initially backed by `SimulatedDocumentCameraController` providing deterministic test frames (sharp, blurry, cropped, dark, overexposed, glare, missing camera, capture failures).
2. **Foot Switches (Left/Right BLE/HID buttons):** Requires `InputController` interface with debounce, multi-click/long-press detection, state-dependent command translation, and `SimulatedInputSource`.
3. **Vibration Motor:** Requires `FeedbackController` abstraction supporting distinct haptic patterns (or falling back to system vibrator / mock).
4. **Bluetooth Audio (TTS):** Requires `StudyPlaybackController` abstraction wrapping Android TextToSpeech with paragraph-level chunking and pause/resume offsets.
5. **AI Vision & LLM Provider:** Requires `StudyDocumentGenerator` abstraction with `FakeStudyDocumentGenerator` and production cloud adapter with strict credential isolation.

---

## 5. Audit Conclusions & Next Steps

1. Establish a multi-module or modular native Android project using Kotlin.
   - Module `core-domain`: Pure Kotlin module containing domain models, state machine, button policy, and error models. Can be compiled and tested on the JVM with 0 Android overhead.
   - Module `app`: Android application module with Material 3 UI, simulation controls, Room database, Android TTS, camera and BLE integrations.
2. Formulate Architecture, Implementation Plan, Test Plan, Hardware Compatibility, and Decisions documents.
3. Execute Phase 1: Pure Kotlin domain layer and deterministic state machine with complete test suite.
