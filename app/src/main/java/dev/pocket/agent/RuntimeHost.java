package dev.pocket.agent;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.enums.V8AwaitMode;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.NodeRuntime;
import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.node.modules.NodeModuleModule;
import com.caoccao.javet.values.reference.V8ValueObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.json.JSONObject;

/** One isolate and one owning thread for main and every logical worker. */
public final class RuntimeHost implements AutoCloseable {
    private final Context context;
    private final StateStore store;
    private final MemoryStore memory;
    private final CredentialStore credentials;
    private final Consumer<JSONObject> listener;
    private final HandlerThread thread = new HandlerThread("PocketNode");
    private final Handler handler;
    private NativeTools tools;
    private NodeRuntime runtime;
    private boolean active, pumpScheduled, closed;
    private CompletableFuture<JSONObject> gate;
    private final java.util.Map<String, CompletableFuture<JSONObject>> debugPending = new java.util.HashMap<>();
    private final Runnable pump = this::pumpEvents;
    private void pumpEvents() {
        pumpScheduled = false;
        if (closed || runtime == null) return;
        try { runtime.await(V8AwaitMode.RunNoWait); }
        catch (Throwable error) { fail("Node event processing failed", error); active = false; }
        schedulePump();
    }
    public RuntimeHost(Context context, Consumer<JSONObject> listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        store = new StateStore(this.context); memory = new MemoryStore(this.context); credentials = new CredentialStore(this.context);
        thread.start(); handler = new Handler(thread.getLooper());
    }
    public CompletableFuture<Void> start() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        handler.post(() -> {
            try {
                store.markInterrupted(); tools = new NativeTools(context);
                runtime = V8Host.getNodeI18nInstance().createV8Runtime();
                runtime.getNodeModule(NodeModuleModule.class).setRequireRootDirectory(context.getFilesDir());
                try (V8ValueObject bridge = runtime.createV8ValueObject()) {
                    bridge.bind(new Bridge()); runtime.getGlobalObject().set("PocketNative", bridge);
                }
                File bundle = new File(context.getFilesDir(), "runtime.cjs");
                try (InputStream input = context.getAssets().open("runtime.cjs"); FileOutputStream output = new FileOutputStream(bundle)) {
                    byte[] buffer = new byte[32768]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                // CommonJS preserves full Node built-ins; never evaluate this as a WebView script.
                runtime.getExecutor("process.on('unhandledRejection',()=>PocketNative.emit(JSON.stringify({type:'error',error:'Unhandled runtime rejection'})));require(" + JSONObject.quote(bundle.getAbsolutePath()) + ");").executeVoid();
                runtime.await(V8AwaitMode.RunNoWait); schedulePump(); result.complete(null);
            } catch (Throwable error) { fail("Runtime startup failed", error); result.completeExceptionally(error); }
        }); return result;
    }
    public CompletableFuture<JSONObject> dispatch(JSONObject command) {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        final String serialized = command.toString();
        handler.post(() -> {
            if (closed || runtime == null) { result.completeExceptionally(new IllegalStateException("Runtime is not running")); return; }
            try {
                String response = runtime.getExecutor("(()=>{const value=PocketAgent.dispatch(" + JSONObject.quote(serialized) + ");return typeof value==='string'?value:JSON.stringify(value)})()").executeString();
                runtime.await(V8AwaitMode.RunNoWait); schedulePump();
                result.complete(response == null ? new JSONObject().put("ok", true) : new JSONObject(response));
            } catch (Throwable error) { fail("Agent command failed", error); result.completeExceptionally(error); }
        }); return result;
    }
    private void schedulePump() {
        if (!closed && !pumpScheduled && (active || gate != null || !debugPending.isEmpty())) { pumpScheduled = true; handler.postDelayed(pump, 16); }
    }
    private void emit(JSONObject event) {
        String type = event.optString("type");
        if (type.equals("dev_tool_result")) {
            CompletableFuture<JSONObject> pending = debugPending.remove(event.optString("requestId"));
            if (pending != null) pending.complete(event.optJSONObject("result"));
            return;
        }
        if (type.equals("state")) {
            JSONObject state = event.optJSONObject("state");
            if (state != null) { store.save(state); active = state.optBoolean("active", state.optBoolean("busy")); }
        } else if (type.equals("activity")) active = event.optBoolean("active");
        else if (type.equals("gate")) {
            store.record(event);
            if (gate != null) { gate.complete(event); gate = null; }
        } else if (type.equals("ready") || type.equals("error") || (BuildConfig.DEVELOPMENT_TOOLS && type.equals("log"))) store.record(event);
        listener.accept(event); schedulePump();
    }
    private void fail(String message, Throwable error) {
        // Deliberately do not log exception messages: providers may include secrets in URLs/errors.
        try {
            JSONObject event = new JSONObject().put("type", "error").put("error", message).put("kind", error.getClass().getSimpleName());
            if (error instanceof JavetException) event.put("javetCode", ((JavetException) error).getError().getCode());
            if(message.equals("Runtime startup failed")) {
                String detail=String.valueOf(error.getMessage());
                JSONObject protectedValues=credentials.get();
                for(String key:new String[]{"apiKey","accessToken","refreshToken","idToken","accountId"}) {
                    String secret=protectedValues.optString(key);
                    if(!secret.isEmpty())detail=detail.replace(secret,"[redacted]");
                }
                event.put("startupDiagnostic",detail.substring(0,Math.min(detail.length(),800)));
            }
            emit(event);
        }
        catch (Exception ignored) { }
    }
    public final class Bridge {
        @V8Function public String call(String method, String argsJson) {
            try {
                if (argsJson == null || argsJson.length() > 2_000_000) throw new IllegalArgumentException("Native arguments exceed limit");
                JSONObject args = new JSONObject(argsJson); Object result;
                switch (method) {
                    case "tool": result = tools.execute(args.getString("name"), args.optJSONObject("args") == null ? new JSONObject() : args.getJSONObject("args"), args.optString("owner", "main")); break;
                    case "memory": result = memory.run(args.getJSONObject("request"), args.getString("owner")); break;
                    case "store.load": result = store.loadDurable(); break;
                    case "store.save": store.saveDurable(args.getJSONObject("state")); result = true; break;
                    case "credentials.get": result = credentials.get(); break;
                    case "credentials.updateCodex": result = credentials.updateCodex(args); break;
                    default: throw new IllegalArgumentException("Unknown native method");
                }
                return new JSONObject().put("ok", true).put("result", result).toString();
            } catch (Throwable error) {
                try { return new JSONObject().put("ok", false).put("error", new JSONObject().put("code", error.getClass().getSimpleName()).put("message", "Native call failed: " + method)).toString(); }
                catch (Exception ignored) { return "{\"ok\":false,\"error\":{\"code\":\"native_error\"}}"; }
            }
        }
        @V8Function public void emit(String eventJson) {
            try {
                if (eventJson == null || eventJson.length() > 8_000_000) throw new IllegalArgumentException("Event exceeds limit");
                RuntimeHost.this.emit(new JSONObject(eventJson));
            } catch (Exception error) { fail("Invalid runtime event", error); }
        }
    }
    CompletableFuture<org.json.JSONArray> developmentToolList() {
        CompletableFuture<org.json.JSONArray> future = new CompletableFuture<>();
        handler.post(() -> {
            if (!BuildConfig.DEVELOPMENT_TOOLS || closed || runtime == null) {
                future.completeExceptionally(new IllegalStateException("Development bridge unavailable")); return;
            }
            try { future.complete(new org.json.JSONArray(runtime.getExecutor("JSON.stringify(PocketAgent.debugList())").executeString())); }
            catch (Throwable error) { future.completeExceptionally(error); }
        });
        return future;
    }
    CompletableFuture<JSONObject> developmentToolCall(String name, JSONObject args) {
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        handler.post(() -> {
            if (!BuildConfig.DEVELOPMENT_TOOLS || closed || runtime == null) {
                future.completeExceptionally(new IllegalStateException("Development bridge unavailable")); return;
            }
            String id = java.util.UUID.randomUUID().toString();
            debugPending.put(id, future);
            try {
                String command = "PocketAgent.debugCall(" + JSONObject.quote(id) + "," + JSONObject.quote(name) + "," + "JSON.parse(" + JSONObject.quote(args.toString()) + ")" + ")";
                runtime.getExecutor(command).executeVoid();
                runtime.await(V8AwaitMode.RunNoWait);
                schedulePump();
                handler.postDelayed(() -> {
                    if (debugPending.remove(id) != null) future.completeExceptionally(new java.util.concurrent.TimeoutException("Tool execution outcome unknown; inspect state before retry"));
                }, 35000);
            } catch (Throwable error) { debugPending.remove(id); future.completeExceptionally(error); }
        });
        return future;
    }
    /** Local instrumentation only: no exported Intent/broadcast/tool endpoint. */
    public CompletableFuture<JSONObject> runCompatibilityGate() {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        handler.post(() -> {
            if (gate != null || closed || runtime == null) { result.completeExceptionally(new IllegalStateException("Gate unavailable")); return; }
            gate = result;
            try (InputStream input = context.getAssets().open("node-gate.cjs")) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                runtime.getExecutor(bytes.toString("UTF-8")).executeVoid(); runtime.await(V8AwaitMode.RunNoWait); schedulePump();
                handler.postDelayed(() -> { if (gate == result) { gate = null; result.completeExceptionally(new IllegalStateException("Node gate timed out")); } }, 20000);
            } catch (Throwable error) { gate = null; result.completeExceptionally(error); }
        }); return result;
    }
    public CompletableFuture<JSONObject> memoryStats() {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        handler.post(() -> { try { result.complete(new JSONObject(runtime.getExecutor("JSON.stringify(process.memoryUsage())").executeString())); } catch (Throwable e) { result.completeExceptionally(e); } }); return result;
    }
    @Override public void close() {
        handler.post(() -> {
            if (closed) return; closed = true; handler.removeCallbacksAndMessages(null);
            try {
                if (runtime != null) {
                    try { runtime.getExecutor("PocketAgent.dispatch('{\"action\":\"stop_all\"}')").executeVoid(); runtime.await(V8AwaitMode.RunNoWait); } catch (Throwable ignored) { }
                    runtime.setStopping(true); runtime.close(); runtime = null;
                }
                if(tools!=null){tools.close();tools=null;}
                store.markInterrupted();
            } catch (Throwable error) { fail("Runtime shutdown failed", error); }
            finally { for (CompletableFuture<JSONObject> pending : debugPending.values()) pending.completeExceptionally(new IllegalStateException("Service stopped")); debugPending.clear(); if (gate != null) gate.completeExceptionally(new IllegalStateException("Service stopped")); memory.close(); store.close(); thread.quitSafely(); }
        });
    }
}
