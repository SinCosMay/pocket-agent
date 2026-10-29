#!/usr/bin/env bash
set -euo pipefail
serial="${ANDROID_SERIAL:-$(adb get-serialno)}"
[[ "$serial" == emulator-* ]] || { echo 'An isolated emulator is required'; exit 1; }
package=dev.pocket.agent.uipreview
evidence=app/build/outputs/mobile-ui-integration-evidence
mkdir -p "$evidence"
adb -s "$serial" install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$serial" shell settings put global hidden_api_policy 1
failed=0
for test in NativeUiDeviceTest ConversationHistoryDeviceTest ProviderProfilesDeviceTest CodexAuthDeviceTest SubscriptionVoiceActivationDeviceTest VoiceInvitationDeviceTest 'LifecycleDeviceTest#mainThreadPromptAndDebugControlsWorkOnDevice'; do
  adb -s "$serial" shell am force-stop "$package"
  adb -s "$serial" shell pm grant "$package" android.permission.RECORD_AUDIO
  adb -s "$serial" shell pm grant "$package" android.permission.POST_NOTIFICATIONS
  log="$evidence/${test//#/_}.txt"
  if ! adb -s "$serial" shell am instrument -w -r -e class "dev.pocket.agent.$test" "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$log"; then failed=1; fi
  if ! grep -Eq 'OK \([0-9]+ tests?\)' "$log" || grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$log"; then failed=1; fi
  if [[ "$test" == NativeUiDeviceTest ]]; then
    adb -s "$serial" pull "/sdcard/Android/data/$package/files/ui-qa" "$evidence/screenshots" || failed=1
  fi
done
adb -s "$serial" shell am force-stop "$package"
exit "$failed"
