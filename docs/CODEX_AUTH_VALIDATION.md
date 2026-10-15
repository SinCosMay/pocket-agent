# Direct Codex account validation — 2026-10-15

Version **0.1.1**, source [`03c7764`](https://github.com/ru-aish/pocket-agent/commit/03c77648c970ecb5cdf7615e9daca51d19c9c9a9), passed a focused account acceptance run on **emulator-5580**, Android API35 x86_64. [Signed Actions build](https://github.com/ru-aish/pocket-agent/actions/runs/38750427541) compiled and signed all APKs and passed 32 runtime tests, 12 Python tests and Android lint. All APK compilation and signing occurred in Actions; CI ran no emulator. The emulator ran on Fedora after downloading the exact signed artifacts.

## Direct account outcome

The APK imported the existing local Codex login into Android's encrypted credential store and called **gpt-6-luna directly**. The task-owned relay was stopped and port 8787 remained closed. No API key, endpoint configuration, ngrok, desktop inference relay or MCP connection was used. The host Codex auth file remained byte-for-byte unchanged with the same modification time.

Luna dispatched a real background phone worker, which opened the isolated fixture, typed `LiveCodex`, clicked once and observed `STATUS_CLICKED:LiveCodex COUNT:1`. Main answered 2+2 while the worker was active. Pi/Pi9 and all native tools ran inside the APK. The test removed its transient credential file and restored scripted mode afterward.

## Focused checks

| Exact signed APK case | Result |
| --- | --- |
| `CodexAuthDeviceTest#importEncryptAndSignOut` | Pass: import, encrypted preferences and local sign-out |
| `CodexAuthDeviceTest#rotationCompareAndSaveRejectsStaleAndSignedOut` | Pass: durable rotation, stale-write rejection and no sign-out resurrection |
| `LiveProviderDeviceTest#liveCodexDelegationAndFixture` | Pass: direct Luna worker and concurrent main answer |

Runtime checks cover fixed origins, redirects, optional tool schemas, streaming cancellation/bounds, concurrent refresh, durable rotation before inference, continued Node pumping after waiter cancellation and raw/JSON-escaped secret redaction. Python checks cover private provisioning, emulator guards and cleanup. Synthetic credentials verified refresh; this run did not force rotation of the shared real login.

## Artifacts and limits

Signing certificate SHA-256: `26867ed4a2b5b7d021576a78b4789dcb161ec540d062934528dfeea54987ed92`.
JavaScript bundle: 634,596 bytes raw / 168,076 bytes gzip. Exact APK hashes and assertions are in [machine-readable evidence](validation/2026-10-15-codex-account.json).

**ARM64 execution remains unverified.** Device-code sign-in is implemented, but interactive browser approval was not exercised; acceptance used the existing account cache. Imported caches share refresh-token lineage with the originating installation, so a separate device login is preferable for ongoing use. New battery/OEM, lifecycle and resource benchmarks were intentionally not repeated for this auth change; [the earlier preview record](VALIDATION.md) retains those measured baseline checks and limitations.

## Reproduce

```sh
python3 scripts/download_artifacts.py --run 38750427541 --output artifacts/03c7764
python3 scripts/emulator_test.py --serial emulator-5580 --artifacts artifacts/03c7764 --fresh --codex-account --account-auth-tests --case CodexAuthDeviceTest#importEncryptAndSignOut --case CodexAuthDeviceTest#rotationCompareAndSaveRejectsStaleAndSignedOut --case LiveProviderDeviceTest#liveCodexDelegationAndFixture
```
