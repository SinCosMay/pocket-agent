#!/usr/bin/env bash
set -uo pipefail

run_test() {
  local test_class="$1"
  ./gradlew --no-daemon :app:installDebug -PvoiceEmulatorTest=true || return 1
  adb shell pm grant dev.pocket.agent android.permission.RECORD_AUDIO || return 1
  ./gradlew --no-daemon :app:connectedDebugAndroidTest -PvoiceEmulatorTest=true \
    "-Pandroid.testInstrumentationRunnerArguments.class="
}

result=0
for test_class in \
  dev.pocket.agent.InstantVoiceCaptureDeviceTest \
  dev.pocket.agent.JarvisDetectorDeviceTest \
  dev.pocket.agent.JarvisWakeLifecycleDeviceTest \
  dev.pocket.agent.WakeWordSettingsDeviceTest; do
  run_test "$test_class" || result=1
done

# Gradle can uninstall the app as part of instrumentation teardown.
# If still installed, restore the CI-only microphone permission grant.
if adb shell pm path dev.pocket.agent | grep -q '^package:'; then
  adb shell pm revoke dev.pocket.agent android.permission.RECORD_AUDIO || result=1
fi
exit "$result"
