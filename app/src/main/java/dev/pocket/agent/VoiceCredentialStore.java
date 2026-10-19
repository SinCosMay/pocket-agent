package dev.pocket.agent;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Separate Android Keystore envelope for voice credentials, independent of the model provider. */
final class VoiceCredentialStore {
    private static final String ALIAS = "pocket-agent-deepgram-v1";
    private final Context context;
    private final AtomicFile credentialFile;
    VoiceCredentialStore(Context context) {
        this.context = context.getApplicationContext();
        this.credentialFile=new AtomicFile(new File(this.context.getNoBackupFilesDir(),"voice-credential.json"));
    }
    private String savedEnvelope() {
        try {
            if(credentialFile.getBaseFile().isFile())
                return new String(credentialFile.readFully(),StandardCharsets.UTF_8);
        } catch(Exception error) {throw new IllegalStateException("Protected voice credential is unavailable");}
        String legacy=context.getSharedPreferences("voice-credentials",Context.MODE_PRIVATE).getString("secret",null);
        if(legacy!=null) {
            try {writeEnvelope(legacy);context.getSharedPreferences("voice-credentials",Context.MODE_PRIVATE).edit().remove("secret").commit();}
            catch(Exception ignored) {}
        }
        return legacy;
    }
    private void writeEnvelope(String value) throws Exception {
        FileOutputStream output=null;
        try {
            output=credentialFile.startWrite();
            output.write(value.getBytes(StandardCharsets.UTF_8));
            credentialFile.finishWrite(output);
        } catch(Exception failure) {
            if(output!=null)credentialFile.failWrite(output);
            throw failure;
        }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    synchronized boolean configured() { return !read().isEmpty(); }

    synchronized String read() {
        String saved = savedEnvelope();
        if (saved == null) return "";
        try {
            JSONObject envelope = new JSONObject(saved);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Protected voice credential is unavailable");
        }
    }

    synchronized void save(String apiKey) throws Exception {
        String value = apiKey == null ? "" : apiKey.trim();
        if (value.isEmpty() || value.length() > 512 || value.contains("\n") || value.contains("\r"))
            throw new IllegalArgumentException("Enter a valid Deepgram API key");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        JSONObject envelope = new JSONObject()
            .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
        writeEnvelope(envelope.toString());
        context.getSharedPreferences("voice-credentials",Context.MODE_PRIVATE).edit().remove("secret").commit();
    }
}
