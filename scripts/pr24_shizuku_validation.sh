#!/usr/bin/env bash
set -euo pipefail

curl -fL --retry 3 -o /tmp/shizuku.apk https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk
adb install -r /tmp/shizuku.apk

release_apk=app/build/outputs/apk/release/app-x86_64-release.apk
test_apk="$(find app/build/outputs/apk/androidTest/release -name '*.apk' -type f -print -quit)"
fixture_apk="$(find fixture/build/outputs/apk/debug -name '*.apk' -type f -print -quit)"
test -f "$release_apk"
test -f "$test_apk"
test -f "$fixture_apk"

adb install -r "$release_apk"
adb install -r "$test_apk"
adb install -r "$fixture_apk"
adb shell settings put secure enabled_accessibility_services dev.pocket.agent/.AgentAccessibility
adb shell settings put secure accessibility_enabled 1
adb shell pm grant dev.pocket.agent moe.shizuku.manager.permission.API_V23
adb shell dumpsys package dev.pocket.agent | grep -F 'moe.shizuku.manager.permission.API_V23: granted=true'

unzip -p /tmp/shizuku.apk lib/x86_64/libshizuku.so > /tmp/shizuku_starter
adb push /tmp/shizuku_starter /data/local/tmp/shizuku_starter >/dev/null
adb shell chmod 755 /data/local/tmp/shizuku_starter
adb shell /data/local/tmp/shizuku_starter
sleep 2
adb shell 'ps -A | grep -q shizuku_server'

for case in \
  rapidScreenshotsRetryReadThrottleWithoutLosingObservation \
  delayedAccessibilityTextVerificationCoversExactUnicodeClearAndBatch
do
  output="$(adb shell am instrument -w -r -e class "dev.pocket.agent.RuntimeDeviceTest#$case" dev.pocket.agent.test/androidx.test.runner.AndroidJUnitRunner)"
  printf '%s\n' "$output"
  printf '%s\n' "$output" | grep -F 'OK ('
done
