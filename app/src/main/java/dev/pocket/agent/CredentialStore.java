package dev.pocket.agent;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Secrets never enter Intents, state snapshots, logs, backups, or APK assets. */
public final class CredentialStore {
    private static final String ALIAS = "pocket-agent-provider-v1";
    private final Context context;
    public CredentialStore(Context context) { this.context = context.getApplicationContext(); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public synchronized JSONObject get() throws Exception {
        String saved = context.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE).getString("encrypted", null);
        if (saved == null) return new JSONObject().put("authMode", "scripted").put("endpoint", "https://api.openai.com/v1").put("model", "gpt-4.1-mini");
        JSONObject envelope = new JSONObject(saved);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), StandardCharsets.UTF_8));
    }
    public synchronized void save(JSONObject settings) throws Exception {
        validateEndpoint(settings.optString("endpoint"), settings.optString("authMode"));
        JSONObject allowed = new JSONObject();
        for (String field : new String[]{"endpoint", "model", "apiKey", "authMode"}) allowed.put(field, settings.optString(field));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] data = cipher.doFinal(allowed.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject envelope = new JSONObject().put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).put("data", Base64.encodeToString(data, Base64.NO_WRAP));
        if (!context.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE).edit().putString("encrypted", envelope.toString()).commit())
            throw new IllegalStateException("Could not save provider configuration");
    }
    public static void validateEndpoint(String endpoint, String authMode) throws Exception {
        if (authMode.equals("scripted")) return;
        if (!authMode.equals("openai") && !authMode.equals("api-key") && !authMode.equals("codex") && !authMode.equals("codexRelay") && !authMode.equals("codex-relay")) throw new IllegalArgumentException("Unknown authentication mode");
        URI uri = new URI(endpoint);
        if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null || uri.getHost() == null)
            throw new IllegalArgumentException("Endpoint must be an API base URL without credentials, query or fragment");
        boolean relay = authMode.equals("codex") || authMode.equals("codexRelay") || authMode.equals("codex-relay");
        if (relay) {
            if (!isEmulator() || !"http".equals(uri.getScheme()) || !"10.0.2.2".equals(uri.getHost()) || uri.getPort() < 1)
                throw new IllegalArgumentException("Codex development relay requires an emulator and http://10.0.2.2:port/v1");
        } else if (!"https".equals(uri.getScheme())) throw new IllegalArgumentException("API connections require HTTPS");
    }
    public static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for") || Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu");
    }
}
