#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

export JAVA_HOME="/home/ameya/tools/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="/home/ameya/tools/android-sdk"

echo "=========================================================="
echo " Starting Interactive Foot-Pedal Terminal Console..."
echo "=========================================================="
./gradlew :core-domain:run -q --console=plain
