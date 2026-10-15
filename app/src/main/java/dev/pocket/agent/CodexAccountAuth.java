package dev.pocket.agent;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Native user-initiated device flow; constants mirror the pinned Pi Codex OAuth adapter. */
public final class CodexAccountAuth implements AutoCloseable {
    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String AUTH = "https://auth.openai.com";
    public static final String VERIFICATION_URI = AUTH + "/codex/device";
    private static final String CLAIM = "https://api.openai.com/auth";
    private static final int MAX_DOCUMENT_BYTES = 262144;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile HttpsURLConnection connection;
    private volatile Future<?> task;
    private volatile long deadline;
    public interface Listener {
        void onDeviceCode(String userCode);
        void onSuccess(JSONObject credentials);
        void onFailure(String safeMessage);
    }
    public synchronized void signIn(Listener listener) {
        if (task != null) throw new IllegalStateException("Sign-in is already running");
        cancelled.set(false); deadline = SystemClock.elapsedRealtime() + 15 * 60 * 1000L;
        task = executor.submit(() -> {
            try {
                Response start = post("/api/accounts/deviceauth/usercode", new JSONObject().put("client_id", CLIENT_ID).toString(), false);
                if (start.status == 404) throw new SafeFailure("Device sign-in is unavailable for this account. Import an existing auth.json instead.");
                if (start.status != 200) throw new SafeFailure("Device sign-in could not start. Check your connection and account settings.");
                JSONObject device = start.json;
                String id = token(device.optString("device_auth_id")), code = device.optString("user_code");
                if (!code.matches("[A-Za-z0-9-]{3,64}")) throw new SafeFailure("The sign-in service returned an invalid device code.");
                Object rawInterval = device.opt("interval");
                double seconds;
                try { seconds = rawInterval instanceof Number ? ((Number) rawInterval).doubleValue() : Double.parseDouble(String.valueOf(rawInterval)); }
                catch (Exception invalid) { throw new SafeFailure("The sign-in service returned an invalid polling interval."); }
                if (!Double.isFinite(seconds) || seconds < 0 || seconds > 900) throw new SafeFailure("The sign-in service returned an invalid polling interval.");
                long interval = Math.max(1000, (long) (seconds * 1000));
                main.post(() -> { if (!cancelled.get()) listener.onDeviceCode(code); });
                while (true) {
                    check();
                    Response poll = post("/api/accounts/deviceauth/token", new JSONObject().put("device_auth_id", id).put("user_code", code).toString(), false);
                    if (poll.status == 200) {
                        String authorization = token(poll.json.optString("authorization_code")), verifier = token(poll.json.optString("code_verifier"));
                        String body = form("grant_type", "authorization_code") + "&" + form("client_id", CLIENT_ID) + "&" + form("code", authorization)
                                + "&" + form("code_verifier", verifier) + "&" + form("redirect_uri", AUTH + "/deviceauth/callback");
                        Response exchange = post("/oauth/token", body, true);
                        if (exchange.status != 200) throw new SafeFailure("Account authorization could not be completed. Start sign-in again.");
                        Object expires = exchange.json.opt("expires_in");
                        if (!(expires instanceof Number) || !Double.isFinite(((Number) expires).doubleValue()) || ((Number) expires).doubleValue() <= 0 || ((Number) expires).doubleValue() > 366 * 86400L)
                            throw new SafeFailure("The sign-in service returned an invalid token expiry.");
                        JSONObject values = new JSONObject().put("accessToken", exchange.json.optString("access_token")).put("refreshToken", exchange.json.optString("refresh_token"))
                                .put("expiresAt", System.currentTimeMillis() + ((Number) expires).longValue() * 1000);
                        if (exchange.json.has("id_token")) values.put("idToken", exchange.json.getString("id_token"));
                        JSONObject account = validateCredentials(values, true); check();
                        main.post(() -> { if (!cancelled.get()) listener.onSuccess(account); }); return;
                    }
                    String error = errorCode(poll.json);
                    if (error.equals("slow_down")) interval = Math.min(900000, interval + 5000);
                    else if (poll.status != 403 && poll.status != 404 && !error.equals("deviceauth_authorization_pending"))
                        throw new SafeFailure("Device sign-in was rejected or expired. Start sign-in again.");
                    sleep(Math.min(interval, Math.max(1, deadline - SystemClock.elapsedRealtime())));
                }
            } catch (Exception error) {
                String message = cancelled.get() ? "Sign-in cancelled" : error instanceof SafeFailure ? error.getMessage() : "Account sign-in could not be completed. Check your connection or import auth.json.";
                main.post(() -> { if (!cancelled.get()) listener.onFailure(message); });
            }
        });
    }
    private void check() throws SafeFailure {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new SafeFailure("Sign-in cancelled");
        if (SystemClock.elapsedRealtime() >= deadline) throw new SafeFailure("Device sign-in expired after 15 minutes. Start sign-in again.");
    }
    private void sleep(long millis) throws Exception { check(); Thread.sleep(millis); check(); }
    private static String form(String name, String value) throws Exception { return URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8"); }
    private static String errorCode(JSONObject json) {
        Object error = json.opt("error");
        return error instanceof JSONObject ? ((JSONObject) error).optString("code") : error instanceof String ? (String) error : "";
    }
    private static final class Response {
        final int status; final JSONObject json;
        Response(int status, JSONObject json) { this.status = status; this.json = json; }
    }
    private Response post(String path, String body, boolean form) throws Exception {
        check(); HttpsURLConnection request = (HttpsURLConnection) new URL(AUTH + path).openConnection(); connection = request;
        try {
            request.setInstanceFollowRedirects(false); request.setRequestMethod("POST"); request.setConnectTimeout(15000); request.setReadTimeout(15000);
            request.setRequestProperty("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json");
            request.setRequestProperty("Accept", "application/json"); request.setDoOutput(true);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); request.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = request.getOutputStream()) { output.write(bytes); }
            int status = request.getResponseCode(); check();
            InputStream stream = status >= 200 && status < 300 ? request.getInputStream() : request.getErrorStream();
            JSONObject json = new JSONObject();
            if (stream != null) try (InputStream input = stream) {
                ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) != -1) { check(); if (result.size() + n > MAX_DOCUMENT_BYTES) throw new SafeFailure("The sign-in response exceeded the size limit."); result.write(buffer, 0, n); }
                try { json = new JSONObject(result.toString("UTF-8")); } catch (Exception invalid) { if (status >= 200 && status < 300) throw new SafeFailure("The sign-in service returned an invalid response."); }
            }
            return new Response(status, json);
        } finally { request.disconnect(); if (connection == request) connection = null; }
    }
    public void cancel() {
        cancelled.set(true); Future<?> current = task; if (current != null) current.cancel(true);
        HttpsURLConnection currentConnection = connection; if (currentConnection != null) currentConnection.disconnect();
    }
    @Override public void close() { cancel(); executor.shutdownNow(); }
    private static final class SafeFailure extends Exception { SafeFailure(String message) { super(message); } }

    /** User-selected SAF stream only. No filesystem paths, persistable grants, or plaintext copies. */
    public static JSONObject readImport(InputStream input) throws Exception {
        if (input == null) throw new IllegalArgumentException("Unable to open the selected account file");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) { if (bytes.size() + n > MAX_DOCUMENT_BYTES) throw new IllegalArgumentException("Account file exceeds 256 KB"); bytes.write(buffer, 0, n); }
            return parseImport(new JSONObject(bytes.toString("UTF-8")));
        } catch (IllegalArgumentException safe) { throw safe; }
        catch (Exception invalid) { throw new IllegalArgumentException("Select a valid Codex auth.json containing account tokens"); }
    }
    public static JSONObject parseImport(JSONObject document) throws Exception {
        JSONObject source = document.optJSONObject("tokens");
        if (source == null) source = document.optJSONObject("openai-codex");
        if (source == null) source = document;
        JSONObject values = new JSONObject()
                .put("accessToken", source.optString("accessToken", source.optString("access_token", source.optString("access"))))
                .put("refreshToken", source.optString("refreshToken", source.optString("refresh_token", source.optString("refresh"))));
        String account = source.optString("accountId", source.optString("account_id")); if (!account.isEmpty()) values.put("accountId", account);
        if (source.has("expiresAt")) values.put("expiresAt", source.get("expiresAt"));
        else if (source.has("expires")) values.put("expiresAt", source.get("expires"));
        String id = source.optString("idToken", source.optString("id_token")); if (!id.isEmpty()) values.put("idToken", id);
        return validateCredentials(values, false);
    }
    private static String token(String value) {
        if (value == null || value.isEmpty() || value.length() > 65536 || !value.equals(value.trim()) || java.util.regex.Pattern.compile("[\\x00-\\x20\\x7f]").matcher(value).find())
            throw new IllegalArgumentException("Account tokens are missing or malformed");
        return value;
    }
    private static JSONObject jwt(String value) throws Exception {
        token(value); String[] parts = value.split("\\.", -1);
        if (parts.length != 3 || !parts[0].matches("[A-Za-z0-9_-]+") || !parts[1].matches("[A-Za-z0-9_-]+") || !parts[2].matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("The account access token must be a JWT");
        try {
            JSONObject header = new JSONObject(new String(Base64.decode(parts[0], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
            String alg = header.optString("alg"); if (alg.isEmpty() || alg.equalsIgnoreCase("none")) throw new IllegalArgumentException();
            return new JSONObject(new String(Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
        } catch (Exception invalid) { throw new IllegalArgumentException("The account JWT is malformed"); }
    }
    /** Structural validation only; the provider authenticates the token and its signature. */
    public static JSONObject validateCredentials(JSONObject values, boolean requireFresh) throws Exception {
        String access = token(values.optString("accessToken")), refresh = token(values.optString("refreshToken"));
        if (access.length() > 32768 || refresh.length() > 16384) throw new IllegalArgumentException("Account tokens exceed the supported size limit");
        JSONObject payload = jwt(access);
        JSONObject auth = payload.optJSONObject(CLAIM); String claimed = auth == null ? "" : auth.optString("chatgpt_account_id");
        if (!claimed.matches("[A-Za-z0-9._:-]{1,200}")) throw new IllegalArgumentException("The access token has no Codex account claim");
        String account = values.optString("accountId", claimed);
        if (!account.equals(claimed)) throw new IllegalArgumentException("The account ID does not match its access token");
        Object exp = payload.opt("exp");
        if (!(exp instanceof Number) || !Double.isFinite(((Number) exp).doubleValue()) || ((Number) exp).doubleValue() <= 0 || ((Number) exp).doubleValue() > 9000000000000L || ((Number) exp).doubleValue() != Math.floor(((Number) exp).doubleValue()))
            throw new IllegalArgumentException("The access token has no valid expiry");
        long expires = ((Number) exp).longValue() * 1000;
        if (values.has("expiresAt")) {
            Object supplied = values.get("expiresAt");
            if (!(supplied instanceof Number) || !Double.isFinite(((Number) supplied).doubleValue()) || ((Number) supplied).doubleValue() <= 0 || ((Number) supplied).doubleValue() > 9000000000000000L || ((Number) supplied).doubleValue() != Math.floor(((Number) supplied).doubleValue()))
                throw new IllegalArgumentException("Account expiresAt must be a timestamp in milliseconds");
            expires = Math.min(expires, ((Number) supplied).longValue());
        }
        if (requireFresh && expires <= System.currentTimeMillis()) throw new IllegalArgumentException("The returned account token is already expired");
        JSONObject result = new JSONObject().put("accessToken", access).put("refreshToken", refresh).put("accountId", account).put("expiresAt", expires);
        if (values.has("idToken") && !values.optString("idToken").isEmpty()) { jwt(values.getString("idToken")); result.put("idToken", values.getString("idToken")); }
        return result;
    }
}
