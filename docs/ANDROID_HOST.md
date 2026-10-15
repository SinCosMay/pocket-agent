# Android Node host

`RuntimeHost` owns one Javet `NodeRuntime` for all Pi sessions. The pinned artifact is `com.caoccao.javet:javet-node-android:6.0.2` (ARM64 and x86_64). The runtime entry is `assets/runtime.cjs`, produced by the runtime bundle build in GitHub Actions, copied to app-private files and loaded with Node CommonJS `require`. No desktop process or WebView executes agent code.

Javet's [Android installation guide](https://www.caoccao.com/Javet/tutorial/basic/installation.html), [event-loop guidance](https://www.caoccao.com/Javet/reference/tips/best_practices.html), and [6.0.2 NodeRuntime source](https://github.com/caoccao/Javet/blob/6.0.2/src/main/java/com/caoccao/javet/interop/NodeRuntime.java) are the integration references. A `HandlerThread` serializes every V8 entry and native callback. `await(RunNoWait)` drains ready Node work once per 16 ms while runtime activity is reported; it does not block the owning thread in `RunOnce`, so new user messages and aborts can enter promptly. No periodic event pump runs while idle. This is an active-work event pump, not worker/model status polling. There is no wake lock. Android Doze may delay networking and timers; service lifetime is not guaranteed.

The application uses a user-started, non-exported `specialUse` foreground service with an ongoing notification and Stop all action. Activity destruction removes its observer only. Stop response dispatches `stop_main`; worker Cancel dispatches `cancel_worker`; Stop all terminates the service/runtime. The service does not automatically restart following process death or boot. Distribution through a store may require review of the foreground service/accessibility use case.

## Native bridge

The single bound object exposes annotated functions only:

- `PocketNative.call(method, argsJson)` returns JSON `{ok,result}` or `{ok:false,error:{code,message}}`.
- `PocketNative.emit(eventJson)` accepts state, activity, ready, error and log events from the bundled trusted runtime.
- `tool` calls `NativeTools.execute(name,args,owner)`; `store.load` and `store.save` use app-private SQLite; `credentials.get` returns provider settings to trusted Node code.

No raw Java/JVM reflection converter, remote server, exported tool broadcast, or credential-setting JS bridge is installed. Calls run synchronously on the Node thread. Native handlers must remain bounded: a long handler delays all logical sessions until it returns.

Native UI dispatch uses `PocketAgent.dispatch(JSON.stringify({action,...}))`. Events are delivered through in-process observers on Android's main thread. SQLite stores the opaque runtime checkpoint separately from the rendered UI snapshot, plus up to 100 diagnostic readiness/error/gate records; it never stores secret provider configuration. Native startup marks only UI task status interrupted, and the runtime restores its checkpoint with unresolved generations explicitly interrupted. Operation records remain separate and side effects are never automatically replayed. The runtime preserves pending completion/collection records in its checkpoint.

## Configuration and credentials

The native settings screen stores endpoint, model, auth mode and API/relay bearer inside an AES-GCM encrypted envelope using a non-exportable Android Keystore key. App backups and device transfer are excluded. Secret values do not enter Intents or UI events. An empty key field retains the existing key; switching providers should replace it explicitly. API mode accepts HTTPS endpoints; Codex relay mode accepts only `http://10.0.2.2:port` when Android reports an emulator. Native account login on standalone Android is not implemented; see AUTH.md.

Network security XML permits Android platform HTTP only to `10.0.2.2`. Node's native OpenSSL/socket implementation does **not** inherit Android's network security policy. Therefore provider endpoint validation is enforced in native configuration and must also remain enforced in the JS provider adapter. The local compatibility gate uses Node's loopback server internally. No credentials or test endpoints are bundled.

## On-device compatibility and resource gate

`RuntimeHost.runCompatibilityGate()` is an in-process instrumentation hook, with no externally exported entry point. It executes `node-gate.cjs` inside the actual Android Node runtime and reports Node/V8 versions, `crypto.webcrypto.subtle` SHA-256, UUID generation, streaming fetch across two timed chunks, `AbortController` cancellation after streaming begins, and Node memory usage. The test fails on absent APIs, buffered transport, wrong hash or failed cancellation. Android instrumentation must await the returned future and assert `ok:true`; compilation or running the script in desktop Node is not Android proof.

`AgentService.peekHost()` exposes the current host to package instrumentation. `dispatch(JSONObject)` and `memoryStats()` return futures. Resource collection should combine `process.memoryUsage()` with `adb shell dumpsys meminfo dev.pocket.agent`, idle CPU/wakeup observations, fixed-context scripted worker cycles, and exact APK provenance. No footprint or battery improvement is claimed before measurements. Build APKs only in GitHub Actions; emulator execution belongs on the user's local Fedora machine.
