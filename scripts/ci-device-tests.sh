#!/usr/bin/env bash
# The emulator action runs each input line in a separate sh; invoke this file once.
set -euo pipefail

collect_qa() {
  mkdir -p build/ci/device-qa
  if adb shell test -d /sdcard/Android/data/io.readx.app/files/qa; then
    adb pull /sdcard/Android/data/io.readx.app/files/qa build/ci/device-qa > build/ci/device-qa/pull.log 2>&1 || echo 'QA screenshot collection failed'
  fi
}
trap collect_qa EXIT
./gradlew --no-daemon --stacktrace connectedDebugAndroidTest -PbundledOcr=false -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
