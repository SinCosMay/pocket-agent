# Pocket Agent

An Android text assistant that delegates phone work to background agents while you keep talking to the main conversation. Pi and the adapted `@pi9/subagent` runtime run inside the app; workers call native Android tools directly.

**Preview:** [v0.1.1-preview.1](https://github.com/ru-aish/pocket-agent/releases/tag/v0.1.1-preview.1) adds direct Codex account sign-in and secure login-cache import. The signed x86_64 APK passed direct Luna delegation with the relay stopped. ARM64 execution remains unverified. See the [account validation record](docs/CODEX_AUTH_VALIDATION.md).

## Install and connect

Requires **Android 12 or later**. This is a separate app, `dev.pocket.agent`, and does not replace Pocket MCP.

1. Download a signed APK from a published release or the `pocket-agent-release` artifact of a successful Actions run. `Pocket-Agent-x86_64.apk` targets the emulator; `Pocket-Agent-arm64.apk` targets ARM64 phones. ARM64 execution is still unverified. A universal APK is also built.
2. Open **Settings** and choose a provider. Choose **Codex account** to sign in with your existing ChatGPT account or import your Codex `auth.json`. No API key or endpoint setup is needed. Choose a model available to your account; `gpt-6-luna` is used for emulator testing. **API key (HTTPS)** is also available for a separate API account.
3. For UI observation and interaction, enable **Pocket Agent phone tools** under Accessibility settings, start Shizuku (via its own app), then select **Enable Shizuku virtual displays** under Pocket Agent Settings and authorize access. UI workers run on shell-backed virtual displays instead of the physical screen, including when that screen is off (subject to Android/OEM restrictions). **Choose phone permissions** lets you grant contacts, calls/phone status, SMS, calendar, and runtime notifications separately. Notification access is needed for notification-reading tools. Direct APIs for SMS, calls and similar actions do **not** require Shizuku or a display.
4. Send a message. **Send** starts the user-visible foreground service, with an ongoing notification. For example: “Delegate checking the current screen to a phone-operator worker, and report what it sees.”

**Stop response** stops main's current response. Open a task to inspect, message, steer, or cancel that worker. Closing its view leaves its work running. **Stop all**, also available in the notification, stops the service and all work.

Settings also offers **Scripted model (local validation)**, which uses no cloud inference and is intended for the included fixture tests. It is not a general conversational model. Credentials are encrypted with Android Keystore; replace the saved key explicitly when switching providers. See [model connections](docs/AUTH.md).

## Codex account testing on an emulator

Use an existing local Codex login directly in the emulator:

```sh
python3 scripts/emulator_test.py --serial emulator-5580 --artifacts artifacts/BUILD --fresh --codex-account
```

This imports credentials privately into Android's encrypted store for the test,
uses direct Luna inference, deletes the transient file, and clears emulator
credentials afterward. It does not change the PC's Codex cache. Phone tools and
all agent scheduling execute locally in the APK. See [AUTH.md](docs/AUTH.md) for
device sign-in, secure import and the optional legacy development relay.

## How it runs

One app-owned Javet Node engine hosts main and every logical worker. Pi9 supplies the in-process scheduler and retained worker conversations. Direct native tools share a FIFO pool of three on-demand Shizuku virtual displays (fourth UI worker waits without failing), bounded batching, and a mutation ledger. Worker completion reaches main through a persisted event inbox rather than repeated model polling. App-private SQLite stores transcripts and task checkpoints separately from protected credentials.

Android can restrict or terminate the user-started service. Interrupted work is preserved as interrupted; side effects are not automatically replayed. Interactive device-code approval, ARM64 execution, physical-phone battery/OEM behavior, and comparative resource measurements remain outstanding. Build success is not full runtime validation.

## Development

**Compile and sign APKs only in GitHub Actions.** The workflow builds release/test APKs, runs runtime tests and lint, and packages hashes and provenance. Emulator/device execution is a separate local step; use an explicit emulator serial, as described in the validation documentation.

- [Runtime architecture, upstream pins, and limits](docs/RUNTIME.md)
- [Android host, lifecycle, permissions, and instrumentation](docs/ANDROID_HOST.md)
- [Authentication and relay setup](docs/AUTH.md)
- [Validation evidence and remaining limits](docs/VALIDATION.md)
- [Original implementation requirements](docs/IMPLEMENTATION_HANDOFF.md)
