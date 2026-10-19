# Known Limitations & Deferred Integrations

As required by the engineering specifications, all simulated layers and deferred hardware dependencies are explicitly documented.

---

## 1. Hardware Integration Status
- **Target Android Motherboard:** Not physically connected during laptop development phase. Target environment assumed: 3 GB RAM, 32 GB flash storage, ARM64-v8a.
- **USB UVC Camera:** Physical UVC camera hardware integration deferred to Phase 8. Currently abstracted through `DocumentCameraController` and verified with `SimulatedDocumentCameraController` using synthetic test image fixtures.
- **Shoe-Mounted Buttons (BLE/HID):** Physical BLE hardware not present on development laptop. Input events are generated via `SimulatedInputSource` and the Developer Simulation UI.
- **Physical Haptic Motor:** Tested via software feedback logging and Android system vibrator simulation (`SimulatedFeedbackController`).
- **Bluetooth Earphones:** Tested using Android TextToSpeech audio routing abstraction and automated segment progression.

## 2. Platform & Network Limitations
- **Offline / Flight Mode:** Without network access, OCR can operate locally if local engine is configured; Cloud LLM study document generation requires Internet access. Offline behavior enters a structured `AssistantError` state with a spoken advisory to the user.
- **Large Image Memory:** Loading multi-megapixel images directly into Android `Bitmap` can exhaust the 3GB system RAM. The application strictly downsamples analysis frames and streams disk storage.
