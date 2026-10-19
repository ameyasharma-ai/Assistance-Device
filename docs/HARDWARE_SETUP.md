# Hardware Setup & Wiring Reference

This guide documents the target wearable hardware specifications, wiring connections, and future integration steps.

---

## 1. Physical Hardware Components

1. **Android Motherboard:**
   - Single-board computer running Android 11+ (API 30-34).
   - Minimum 3 GB RAM, 32 GB eMMC storage.
   - Micro-USB or USB-C OTG port capable of 5V 1A output.
   - Integrated Bluetooth 5.0 LE and Wi-Fi.
2. **Camera Module:**
   - USB Video Class (UVC) compliant sensor (OmniVision / Sony IMX).
   - Autofocus or fixed document focus tuned to 30cm - 45cm focal distance.
   - Wide-angle lens (70° - 85° FOV) with minimal barrel distortion.
   - Mounted on lapel, chest strap, or reading glasses frame.
3. **Foot Switch Module:**
   - Dual momentary foot switches mounted on left and right shoes.
   - Microcontroller (e.g., nRF52840 or ESP32-C3) running BLE GATT peripheral firmware.
   - Left Button: Scan / Cancel / Navigate Previous.
   - Right Button: Capture / Confirm / Play-Pause / Navigate Next.
4. **Vibration Motor (Haptics):**
   - 3V-5V ERM/LRA vibration disc mounted on neck strap or wrist.
   - Connected via GPIO transistor circuit or standard Android vibrator motor pins.
5. **Audio Peripheral:**
   - Standard Bluetooth 5.0 bone-conduction or open-ear earphones.
   - Hands-Free Profile (HFP) or Advanced Audio Distribution Profile (A2DP).

---

## 2. Laptop Development Setup

During laptop development, zero physical hardware is required:
- Build environment uses OpenJDK 17 and Gradle 8.7.
- State machine, input policies, and AI pipelines run on JVM.
- Hardware adapters operate in **Simulation Mode** using deterministic test fixtures.
