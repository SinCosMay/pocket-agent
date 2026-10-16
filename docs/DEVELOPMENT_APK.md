# Development APK: authorized local MCP and ngrok diagnostics

The signed Android workflow now emits **release** and **development** APKs from one source commit. Development uses the same `dev.pocket.agent` application ID and **distribution signing key** as release, so it can be installed over release and access the same app-private SQLite history. Development includes its own notification-backed server, ngrok JNI, and Settings entry; release does **not** include this server, activity, library, or ngrok native assets. Installing release over development removes the bridge code; stop the bridge first.

## Recorded data

`agent-state.db` already stores the durable Pi runtime checkpoint (`runtime_state`) including `mainMessages`, `workerMessages`, tool transcripts, and the pending completion inbox. The independent `snapshot` row contains UI state. The events table logs startup/errors and, in development, bounded tool-start/end activity (the newest 2,000 entries). Native mutation receipts are separately persisted by `OperationStore`; `phone_operation` queries them. Memory is another private database. **Logs are not full Android logcat, raw Shizuku logs, screenshots from past sessions, or provider transport traces**; images are intentionally omitted from checkpoints. Events and stored transcript are locally retained until DB deletion/uninstall, explicit worker removal, or capacity policies; event diagnostics are a rolling window.

## Start it

1. Install the CI-signed `Pocket-Agent-development-arm64.apk` (or x86_64 for emulator). The CI signs release and development with the same key and verifies both packages.
2. In Pocket Agent, open **Settings → Development MCP and ngrok**. A visible foreground-service notification remains while enabled.
3. Tap **Start USB / localhost server** to bind `127.0.0.1:8767` on the phone.
4. On Fedora, run `adb forward tcp:8767 tcp:8767`. The PC MCP endpoint is `http://127.0.0.1:8767/mcp`. Select the exact ADB device serial if multiple are attached.
5. Tap **Copy MCP bearer token** on phone; use HTTP `Authorization: Bearer <token>` and JSON-RPC 2.0 Streamable HTTP POST. The bearer is different from the ngrok authtoken and must never be committed or logged.
6. For wireless access, first save the **ngrok authtoken** in that page (encrypted by Android Keystore), **stop the local bridge**, then select **Start wireless ngrok bridge**. On ARM64 devices the app loads a development-only ngrok native library from Maven Central, creates an ngrok HTTPS tunnel to its loopback port, and shows the resulting `https://…/mcp` URL. Authenticate with the **same MCP bearer**. Cellular/wifi must be functioning; ngrok plan limits and Android background execution still apply.
7. Use `tools/list` for the exact main model tool schema and diagnostic tools. Read-only `debug_history` supports optional `query`, `conversationId`, `limit`, `offset`. `debug_events` supports query/limit/offset, and `debug_state` returns the last rendered state. Other tools run through the existing main-runtime tool implementations with a dedicated `dev-mcp` screen lease. Direct phone mutations require their usual operation IDs and receipts.
8. **Stop development bridge** when done. To use the release APK, install the release variant with the same signing certificate. If you uninstall instead of replacing, Android deletes the app's private history.

## Security and testing

The bridge is **off by default**, binds only the phone loopback, requires a random 256-bit local MCP bearer for **every** request, rejects HTTP browser Origins, limits JSON bodies to 1 MiB, bounds concurrent work, and rejects requests with unsupported protocol versions. HTTPS/ngrok provides transport encryption; the MCP bearer controls privileged access. The server is not a multi-tenant authorization system; a bearer holder can read old private chats, invoke Android tools, and potentially perform sensitive actions. Do not publish the URL/token in an issue, PR, CI log or screenshot. Rotate the bearer by clearing development credentials if compromise is suspected; a new APK installation does not automatically rotate it.

Validation: `cd runtime && npm ci && npm test && npm run build`; `./gradlew -PdevEmulatorTest :app:assembleDevelopmentAndroidTest`; run `DevBridgeDeviceTest` against the development APK on the same emulator. Verify 401 without the bearer, 403 for browser origins, main-model tool names in `tools/list`, history search, process-restart persistence, release APK absence of dev classes/ngrok, and local `adb forward`. Wireless ngrok requires an ARM64 device; the x86_64 emulator build supports local MCP only. A real ngrok token and device connectivity are needed to verify wireless end-to-end; do not claim that path succeeded based only on compilation.

Production and development builds must be separately tested for Android operation safety. The development bridge is not an invisible service and intentionally does not auto-start at boot.
