#!/usr/bin/env bash
set -euo pipefail

cd android
./gradlew lintDebug --no-daemon
./gradlew assembleDebug --no-daemon
