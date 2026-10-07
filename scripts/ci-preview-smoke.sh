#!/usr/bin/env bash
# Install only on the dedicated, disposable CI emulator created by the workflow.
set -euo pipefail
mkdir -p build/ci/reports
adb install -r build/ci/release/app-preview.apk
adb shell am start -W -n io.readx.app/.MainActivity | tee build/ci/reports/preview-launch.txt
grep -q '^Status: ok' build/ci/reports/preview-launch.txt
adb shell pidof io.readx.app
adb logcat -b crash -d > build/ci/reports/preview-crash.txt
if grep -q 'Process: io.readx.app' build/ci/reports/preview-crash.txt; then
  echo 'ReadX crashed during the minified Preview launch'
  exit 1
fi
