# Validation record

This is a preview implementation. Build, engine compatibility, functional behavior and measurements are separate checks. This report will be updated with exact Actions artifact hashes and Fedora emulator results before release.

## Source and environment

New project: https://github.com/ru-aish/pocket-agent. Work is on `feat/android-agent`; the original Pocket MCP working tree and application are separate. Source/dependency pins are in `runtime/SOURCE_PINS.json` and `runtime/package-lock.json`.

APKs compile/sign only on GitHub Actions. Local Fedora runs use explicit `emulator-5580`, API35 x86_64, a read-only copy of the existing `senp_api35` AVD. No physical phone is selected. Real-provider tests use the user's requested `gpt-6-luna` through the loopback-only Codex-auth inference relay. On-phone Pi executes all tools; the relay performs no agent or phone operations.

## Preliminary evidence

Sixteen Linux tests pass: real Pi scripted tool loop, streaming abort, main responsiveness and completion delivery, receipt-aware follow-up, recursive shared-capacity joins, interruption restoration, distinct stop/cancel controls, recursive grant enforcement deterministic fixture postconditions, bounded pre-dispatch stale-screen replanning, unknown-outcome refusal, image retention bounds, provider error visibility, and optional tool-schema wire contracts. These use a native-shaped bridge and do not prove Android execution.

Seven local relay protocol/security tests pass. A real Luna request through the actual pinned Pi adapter invoked one harmless read-only tool with validated arguments, accepted its structured result, and completed. This establishes account/provider protocol compatibility, separately from APK execution.

The trimmed runtime is approximately 602 KB raw / 158 KB gzip. The bundle report identifies emitted code contributions. These JavaScript sizes exclude the Node native engine and are not APK or RAM numbers. The application excludes ngrok, MCP loopback transports, desktop coding SDK/TUI, package discovery, voice and local-model assets.

## ARM64 emulator attempt

The Actions workflow produces ARM64 and x86_64 signed APKs. This host's Android Emulator 37.1.11 rejects ARM64 images (`QEMU2 emulator does not support arm64 CPU architecture`). An isolated direct aarch64-engine attempt with the official API35 ARM64 default image (checksum verified) failed before guest boot because the emulator attempted PCI audio devices on a board without PCI. Disabling the first audio device led to a second PCI audio initialization failure. No ARM64 APK or Javet execution is claimed from these attempts. The existing x86 emulator was not modified.

ARM64 device execution remains required before treating the ARM64 artifact as validated. This is a host emulator limitation, not evidence that the APK works or fails on an ARM64 phone.

## Remaining release limits

Native standalone ChatGPT-account login is unfinished: no supported mobile OAuth registration/redirect has been established. Independent direct HTTPS API-key inference is implemented; the Codex relay is an emulator development aid and requires the PC for inference authentication.

Android may terminate or restrict the user-started service. Activity recreation and process-death interruption must be validated on the distributed APK. There is no guarantee of immortal execution, and no permanent wake lock or idle model polling.

A same-engine/device stock headless Pi9 memory/spawn comparison, long-duration resource growth run, idle network/wakeup profiling and physical-phone battery/OEM background observation remain unmeasured. Do not infer performance savings from disabled settings or JavaScript bundle size. History and worker caps reject further work rather than silently dropping saved results; the minimal UI does not yet rotate an exhausted main database.
