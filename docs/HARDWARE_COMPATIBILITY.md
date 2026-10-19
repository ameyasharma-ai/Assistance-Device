# Hardware Compatibility & Abstraction Specifications

## 1. Target Hardware Profile

The target wearable device comprises:
- **Processor & Architecture:** Android Motherboard (ARM64-v8a / 64-bit ARM).
- **RAM:** 3 GB LPDDR4x.
- **Internal Storage:** 32 GB eMMC.
- **Operating System:** Android 11+ (API Level 30 through 34).
- **USB Host / OTG:** High-Speed USB 2.0 or SuperSpeed USB 3.0 supporting USB Video Class (UVC 1.1/1.5).
- **Bluetooth:** Dual-mode Bluetooth 5.0+ supporting BLE (GATT server/client) and Classic Audio (A2DP / HSP / HFP).
- **Haptic Actuator:** ERM (Eccentric Rotating Mass) or LRA (Linear Resonant Actuator) driven via Android Vibrator HAL or dedicated GPIO driver.

---

## 2. Hardware Interfaces & Abstraction Layers

To isolate application logic from physical hardware availability, all peripheral communication is conducted through clean abstraction interfaces:

### 2.1 Camera Subsystem (`DocumentCameraController`)
- **Real Implementation:** `UvcDocumentCameraController` (via Android USB Host API / libuvc) and fallback `CameraXDocumentCameraController`.
- **Simulation Implementation:** `SimulatedDocumentCameraController`.
  - Can supply synthetic test bitmaps (sharp, blurry, cropped, dark, overexposed, glare).
  - Can inject hardware disconnection, framing latency, and frame dropping.

### 2.2 Foot Switch Subsystem (`InputController`)
- **Real Implementation:**
  - `BleGattFootButtonController`: Connects to custom BLE foot switches advertising custom button service and characteristic notifications.
  - `BluetoothHidFootPedalAdapter`: Connects to standard Bluetooth HID pedal controllers sending keypress / media key events.
- **Simulation Implementation:** `SimulatedInputSource`.
  - Exposes programmatic `triggerLeftClick()`, `triggerRightClick()`, `triggerLeftDoubleClick()`, `triggerRightLongPress()`, etc.

### 2.3 Vibration / Haptic Subsystem (`FeedbackController`)
- **Real Implementation:** `AndroidVibratorController` utilizing `VibrationEffect` with custom waveform timings and amplitude control.
- **Simulation Implementation:** `SimulatedFeedbackController`.
  - Records tactile events into an observable state flow and developer log with pattern name, millisecond duration, and intensity.

### 2.4 Audio Output Subsystem (`StudyPlaybackController`)
- **Real Implementation:** `AndroidTtsPlaybackController` bound to Bluetooth audio routing (A2DP / SCO) with `AudioManager` audio focus management.
- **Simulation Implementation:** `SimulatedPlaybackEngine`.
  - Deterministically advances through sentence segments with configurable simulated speech rates, pause tokens, and progress callbacks.

---

## 3. Power and Memory Management Constraints

- **3 GB RAM Budgeting:**
  - App heap limit: Typically 256MB to 512MB on 3GB devices (`largeHeap=true` in manifest).
  - Preview analysis frames: Fixed resolution (e.g., 640x480 or 960x720) in YUV/NV21 or downsampled RGB; allocated once into a reusable buffer pool.
  - Still captures: Captured at native sensor resolution (e.g., 8MP/12MP), saved directly to disk, and processed using memory-mapped streams or downscaled regions to prevent `OutOfMemoryError`.
- **USB Power Consumption:**
  - UVC camera drawing >500mA from USB OTG requires camera sleep/standby mode when the assistant is not in `SCANNING` or `CAPTURING` states.
  - Camera is immediately released when transitioning to `PROCESSING`, `DOCUMENT_READY_FOR_PLAYBACK`, `PLAYING`, or `IDLE`.
