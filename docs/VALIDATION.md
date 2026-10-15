# Preview.1 baseline validation — 2026-10-15

This historical record covers the first preview. Direct Codex account support is validated separately in [CODEX_AUTH_VALIDATION.md](CODEX_AUTH_VALIDATION.md).

This is a working preview, with **ARM64 execution and native standalone account login still open**. It is not a stable release or a completed resource-optimization benchmark. [Machine-readable evidence](validation/2026-10-15.json) contains the exact artifact hashes, device assertions and samples.

## Exact build and environment

- Public project: [ru-aish/pocket-agent](https://github.com/ru-aish/pocket-agent). Original Pocket MCP checkout/application remain separate.
- APK source: [`9aa40403a805e6f206714920320803e7cc505a96`](https://github.com/ru-aish/pocket-agent/commit/9aa40403a805e6f206714920320803e7cc505a96). [Signed Actions run 38730703363](https://github.com/ru-aish/pocket-agent/actions/runs/38730703363) passed runtime tests, relay tests, release/test/fixture builds and Android lint. All APK compilation/signing took place in Actions; CI ran no emulator.
- Local device: explicit `emulator-5580`, Android API35, x86_64, read-only `senp_api35` AVD on Fedora. No connected physical phone was selected.
- Real inference: **`gpt-6-luna`**, through the loopback-only host Codex-auth development relay. Pi, Pi9 and all phone tools executed in the APK. Account tokens stayed on the PC; the relay executed no tools.
- Engine verified inside Android: Node `26.10.0`, V8 `14.6.202.34-node.34`, Javet Node I18n 6.0.2. Pi 1.2.0 and Pi9 0.12.0 pins/notices are recorded in [RUNTIME.md](RUNTIME.md).
- Signing certificate SHA-256: `26867ed4a2b5b7d021576a78b4789dcb161ec540d062934528dfeea54987ed92`.

## Checks on the distributed x86_64 APK

Seventeen runtime tests and seven relay protocol/security tests pass. They cover actual Pi tool-loop execution, optional Responses tool arguments, cancellation, event delivery, recursive capacity/grants, stop controls, persistence, image bounds, and refusal to replay unknown outcomes. The mutation-contract regression rejects seven-character and 49-character nonces, punctuation and non-ASCII characters through actual Pi validation before native dispatch; historical receipt inspection stays separate. These Linux checks are separate from the following eight passing Android cases:

| Android case | Result |
| --- | --- |
| `RuntimeDeviceTest#nodeEmbeddingStreamingAndCancellation` | Pass |
| `RuntimeDeviceTest#directNativeFixtureAndMutationLedger` | Pass |
| `AsyncAgentDeviceTest#delegatedFixtureKeepsMainResponsiveAndCompletesOnce` | Pass |
| `LifecycleDeviceTest#activityRecreationKeepsWorker` | Pass |
| `LifecycleDeviceTest#reportFootprint` | Pass |
| `LifecycleDeviceTest#prepareProcessDeath` | Pass |
| `LifecycleDeviceTest#verifyProcessDeath` | Pass |
| `LiveProviderDeviceTest#liveCodexDelegationAndFixture` | Pass |

The Android Node gate verified streaming Fetch across two timed reads, cancellation after streaming began, UUID and WebCrypto SHA-256. Direct native tests verified typing/clicking the synthetic fixture, duplicate-ID caching, screen-owner contention, permission errors, whole-batch validation and partial-batch retry without an extra click. Stale actions can be replanned only after an explicit pre-dispatch receipt; unknown outcomes stop.

The scripted background worker completed `STATUS_CLICKED:PocketWorks COUNT:1` while main answered another message. Completion woke main once, and direct follow-up retained worker identity with a new generation. The real Luna worker separately observed `STATUS_CLICKED:LiveCodex COUNT:1`; main answered 2+2 while that worker was active. No real calls, SMS or third-party messages were sent.

Activity recreation retained the same runtime/worker. After an external force-stop, a new process restored the same worker ID/generation as interrupted, preserved the main transcript prefix and left the mutation ledger unchanged. The unresolved ledger sentinel was synthetic and never dispatched a side effect; this does not prove carrier/provider recovery.

Manual UI verification checked normal chat, readable system bars, idle status, message scrolling, settings and retained task views. Android may still terminate or restrict the service; these tests do not establish indefinite/OEM background survival.

## Measured footprint

| Quantity | Observed value |
| --- | --- |
| ARM64 signed APK, packaging only | 37,331,651 bytes (35.60 MiB) |
| x86_64 signed APK, executed in emulator | 38,595,616 bytes (36.81 MiB) |
| JavaScript bundle | 604,718 bytes raw / 158,658 bytes gzip |
| Installed x86_64 native library | 105,686,368 bytes |
| App-private data at sampled checkpoint | 1,066,288 bytes; credentials are separately encrypted |
| PSS, 2 → 7 retained workers | 95,766 → 118,389 KiB |
| Node-reported process RSS, same checkpoints | 225,538,048 → 250,642,432 bytes |
| JS heap used, same checkpoints | 10,538,568 → 12,925,920 bytes |
| CPU over one idle instrumentation second | 202 ms / 1001 ms wall |
| Normal app in background, no instrumentation | 10 ms CPU / 30.18 s wall; RSS 236640 kB → 236640 kB |

The disk components above exclude OS dex-optimization/cache sizes and are not a complete installed-space total. The normal CPU sample has 10 ms clock-tick resolution on this emulator.

Five completed, retained scripted workers took 1266, 1517, 1546, 1730, 1714 ms per whole job. These include main dispatch, the scripted delay, tool execution and completion delivery; they are **not worker-spawn latency**. PSS/RSS samples vary with allocator/GC/Android activity and include host overhead. Five retained histories are intentionally additional state, not a leak test.

The same i18n library shrank under standard NDK stripping from 175,732,208 to 105,686,368 bytes on x86_64 and from 175,481,608 to 103,523,504 on ARM64. This is a file-size reduction, not a RAM saving. The APK contains no ngrok, MCP loopback server, desktop coding/TUI SDK, node_modules tree, account credentials or signing key. The emitted bundle report separates parsed inputs from bytes actually emitted.

## Reproduce

Run from the new repository. The fresh suite clears **only this new app's** emulator data and reinstalls its isolated fixture. Keep the account relay private.

```sh
python3 scripts/download_artifacts.py --run 38730703363 --output artifacts/9aa4040
python3 scripts/codex_relay.py --model gpt-6-luna
# In another terminal, with that relay running:
python3 scripts/emulator_test.py --serial emulator-5580 --artifacts artifacts/9aa4040 --fresh --live
# For a separate idle sample, start the app normally, send a scripted message,
# wait for Ready, put it in the background, then:
python3 scripts/profile_idle.py --serial emulator-5580 --seconds 30 --output artifacts/9aa4040/normal-idle.json
```

## Remaining acceptance limits

- **ARM64 runtime gate is incomplete.** Host Emulator 37.1.11 refuses ARM64 guests. An isolated, checksummed official API35 ARM64 image also failed before boot when directly invoking the bundled aarch64 engine (PCI audio initialization). The signed ARM64 APK is supplied as an unverified preview; x86 tests do not prove it executes on a phone.
- **Standalone ChatGPT-account sign-in is unfinished.** No supported mobile client registration/redirect has been established. The Codex relay is a development connection requiring the PC for inference authentication. The independent direct HTTPS API-key adapter is implemented; no production API key was supplied for a separate paid-endpoint acceptance run. See [AUTH.md](AUTH.md).
- A same-engine/ABI stock headless Pi9 comparison, spawn p50/p95, extended create/finish/remove soak, idle wakeup/network profiling and physical-phone battery/OEM observations remain unmeasured. No percentage memory/battery savings or unbounded-execution guarantee is claimed.
- Native handlers execute synchronously on the shared Node owner thread; a slow native call can temporarily delay other conversations. The tested workflows remain responsive, but there is no independent per-conversation native executor.
- Histories and worker generations are bounded; exhaustion rejects work while retaining results. The minimal UI does not rotate an exhausted main database. Permission/carrier/OEM-specific actions and real SMS/calls remain outside these synthetic tests.
