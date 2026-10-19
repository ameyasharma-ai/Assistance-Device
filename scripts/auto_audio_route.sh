#!/usr/bin/env bash
# auto_audio_route.sh
# Automatically manages PipeWire audio routing between Bluetooth Earbuds and Laptop Speakers.

SPEAKER_SINK="alsa_output.pci-0000_05_00.6.analog-stereo"

echo "Starting Auto Audio Router daemon for Reading Assistant..."

while true; do
    # Check for connected bluetooth audio sink (e.g. AirPods, Bluetooth earbuds)
    BT_SINK=$(pactl list short sinks 2>/dev/null | grep "bluez_output" | awk '{print $2}' | head -n 1)

    if [ -n "$BT_SINK" ]; then
        TARGET_SINK="$BT_SINK"
        ACTIVE_DEVICE="BLUETOOTH_EARBUD ($BT_SINK)"
    else
        TARGET_SINK="$SPEAKER_SINK"
        ACTIVE_DEVICE="LAPTOP_SPEAKER ($SPEAKER_SINK)"
    fi

    # Set default sink if different
    CURRENT_DEFAULT=$(pactl get-default-sink 2>/dev/null)
    if [ "$CURRENT_DEFAULT" != "$TARGET_SINK" ]; then
        pactl set-default-sink "$TARGET_SINK" 2>/dev/null
        echo "[Router] Switched default sink to: $ACTIVE_DEVICE"
    fi

    # Ensure target sink is unmuted and at 100% volume
    pactl set-sink-mute "$TARGET_SINK" 0 2>/dev/null
    pactl set-sink-volume "$TARGET_SINK" 100% 2>/dev/null

    # Also unmute ALSA hardware controls if target is laptop speakers
    if [ "$TARGET_SINK" = "$SPEAKER_SINK" ]; then
        amixer -c 1 sset Master 100% unmute >/dev/null 2>&1
        amixer -c 1 sset Speaker 100% unmute >/dev/null 2>&1
    fi

    # Ensure all QEMU / Android emulator audio streams are routed to TARGET_SINK
    TARGET_ID=$(pactl list short sinks 2>/dev/null | grep "$TARGET_SINK" | awk '{print $1}')
    if [ -n "$TARGET_ID" ]; then
        for INPUT_ID in $(pactl list short sink-inputs 2>/dev/null | grep "qemu-system-x86_64" | awk '{print $1}'); do
            CUR_SINK=$(pactl list sink-inputs 2>/dev/null | awk -v id="$INPUT_ID" '
                $0 ~ "Sink Input #" id { found=1 }
                found && $1 == "Sink:" { print $2; exit }
            ')
            if [ "$CUR_SINK" != "$TARGET_ID" ] && [ -n "$CUR_SINK" ]; then
                pactl move-sink-input "$INPUT_ID" "$TARGET_SINK" 2>/dev/null
                echo "[Router] Moved QEMU stream #$INPUT_ID to $TARGET_SINK"
            fi
        done
    fi

    sleep 1
done
