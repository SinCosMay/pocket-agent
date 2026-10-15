# Pocket Agent

An Android text assistant that delegates phone work to background agents while you keep talking to the main conversation. Pi and the adapted `@pi9/subagent` runtime run inside the app; workers call native Android tools directly.

**Preview:** the planned first prerelease is `v0.1.0-preview.1`. Publication and full device QA are not claimed here. Check [Releases](https://github.com/ru-aish/pocket-agent/releases) for published downloads and [Actions](https://github.com/ru-aish/pocket-agent/actions/workflows/apk.yml) for successful build artifacts. See the [validation record](docs/VALIDATION.md) for evidence and outstanding checks.

## Install and connect

Requires **Android 12 or later**. This is a separate app, `dev.pocket.agent`, and does not replace Pocket MCP.

1. Download a signed APK from a published release or the `pocket-agent-release` artifact of a successful Actions run. `Pocket-Agent-x86_64.apk` targets the emulator; `Pocket-Agent-arm64.apk` targets ARM64 phones. ARM64 execution is still unverified. A universal APK is also built.
2. Open **Settings** and choose a provider. For independent operation, choose **API key (HTTPS)**, enter a Responses-compatible HTTPS endpoint, a model available to your account, and its API key. OpenAI's base URL is `https://api.openai.com/v1`. A ChatGPT subscription does not substitute for an API key.
3. For screen observation and interaction, open **Accessibility settings** and enable **Pocket Agent phone tools**. **Choose phone permissions** lets you request contacts, calls/phone status, SMS, calendar, and runtime notifications individually. **Notification access settings** is separate and needed only for notification-reading tools. Unavailable permissions are reported through tool errors.
4. Send a message. **Send** starts the user-visible foreground service, with an ongoing notification. For example: “Delegate checking the current screen to a phone-operator worker, and report what it sees.”

**Stop response** stops main's current response. Open a task to inspect, message, steer, or cancel that worker. Closing its view leaves its work running. **Stop all**, also available in the notification, stops the service and all work.

Settings also offers **Scripted model (local validation)**, which uses no cloud inference and is intended for the included fixture tests. It is not a general conversational model. Credentials are encrypted with Android Keystore; replace the saved key explicitly when switching providers. See [model connections](docs/AUTH.md).

## Codex account testing on an emulator

The development relay uses an existing host Codex login for inference only. Run it on the emulator host:

```sh
python3 scripts/codex_relay.py --port 8787 --model gpt-6-luna
```

Requires Python `aiohttp` and a signed-in local Codex account. Choose **Codex emulator relay (development)** in the app, set endpoint `http://10.0.2.2:8787/v1` and model `gpt-6-luna`, and provision the private relay bearer following [AUTH.md](docs/AUTH.md). Keep account access/refresh tokens on the host. The relay binds only to `127.0.0.1`; do not publish or tunnel it.

This mode requires the PC for inference authentication. **Standalone ChatGPT-account login is unfinished.** Independent operation uses the direct HTTPS API-key path, with no desktop companion, Termux, ngrok, or MCP loopback server.

## How it runs

One app-owned Javet Node engine hosts main and every logical worker. Pi9 supplies the in-process scheduler and retained worker conversations. Direct native tools share screen leases, bounded batching, and a mutation ledger. Worker completion reaches main through a persisted event inbox rather than repeated model polling. App-private SQLite stores transcripts and task checkpoints separately from protected credentials.

Android can restrict or terminate the user-started service. Interrupted work is preserved as interrupted; side effects are not automatically replayed. Native account login, ARM64 execution, physical-phone battery/OEM behavior, and comparative resource measurements remain outstanding. Build success is not full runtime validation.

## Development

**Compile and sign APKs only in GitHub Actions.** The workflow builds release/test APKs, runs runtime tests and lint, and packages hashes and provenance. Emulator/device execution is a separate local step; use an explicit emulator serial, as described in the validation documentation.

- [Runtime architecture, upstream pins, and limits](docs/RUNTIME.md)
- [Android host, lifecycle, permissions, and instrumentation](docs/ANDROID_HOST.md)
- [Authentication and relay setup](docs/AUTH.md)
- [Validation evidence and remaining limits](docs/VALIDATION.md)
- [Original implementation requirements](docs/IMPLEMENTATION_HANDOFF.md)
