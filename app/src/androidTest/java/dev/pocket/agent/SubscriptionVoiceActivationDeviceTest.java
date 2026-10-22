package dev.pocket.agent;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.webrtc.PeerConnectionFactory;
import static org.junit.Assert.*;

/** Disposable-emulator regressions for the power-button subscription voice path. */
@RunWith(AndroidJUnit4.class)
public final class SubscriptionVoiceActivationDeviceTest {
    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static String base64(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static JSONObject fakeAccount(String refresh) throws Exception {
        String header = base64(new JSONObject().put("alg", "RS256").toString());
        JSONObject claims = new JSONObject()
                .put("https://api.openai.com/auth",
                        new JSONObject().put("chatgpt_account_id", "emulator-voice-test"))
                .put("exp", System.currentTimeMillis() / 1000L + 7200L);
        return new JSONObject().put("accessToken", header + "." + base64(claims.toString()) + ".c2ln")
                .put("refreshToken", refresh).put("accountId", "emulator-voice-test")
                .put("expiresAt", System.currentTimeMillis() + 3_600_000L);
    }

    @Test public void voiceLoginSurvivesChatProviderSwitchAndRotation() throws Exception {
        Context ctx = context();
        SharedPreferences protectedPrefs = ctx.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE);
        String prior = protectedPrefs.getString("encrypted", null);
        try {
            assertTrue(protectedPrefs.edit().remove("encrypted").commit());
            CredentialStore store = new CredentialStore(ctx);
            store.saveVoiceCodex(fakeAccount("voice-refresh-a"));
            store.save(new JSONObject().put("authMode", "scripted")
                    .put("model", "scripted").put("endpoint", "https://api.openai.com/v1"));
            assertEquals("scripted", store.get().getString("authMode"));
            assertFalse(store.get().has("subscriptionVoiceAccount"));
            assertEquals("voice-refresh-a", store.getVoiceCodex().getString("refreshToken"));
            store.updateVoiceCodex(fakeAccount("voice-refresh-b")
                    .put("oldRefreshToken", "voice-refresh-a"));
            assertEquals("voice-refresh-b", store.getVoiceCodex().getString("refreshToken"));
            assertEquals("scripted", store.get().getString("authMode"));
            store.signOutVoiceCodex();
            assertEquals(0, store.getVoiceCodex().length());
        } finally {
            SharedPreferences.Editor restore = protectedPrefs.edit();
            if (prior == null) restore.remove("encrypted"); else restore.putString("encrypted", prior);
            assertTrue(restore.commit());
        }
    }

    @Test public void failureClassificationNeverPrintsRemoteErrorText() {
        assertEquals("ChatGPT rejected voice authentication",
                SubscriptionVoiceFailureStore.safeReason("Subscription call rejected (HTTP 401)"));
        assertEquals("Native voice audio failed to initialize",
                SubscriptionVoiceFailureStore.safeReason("Unable to initialize native audio"));
        assertEquals("Subscription voice setup failed",
                SubscriptionVoiceFailureStore.safeReason("token=do-not-log-this"));
    }

    @Test public void missingLoginWakeLeavesPersistentActionableFailure() throws Exception {
        Context ctx = context();
        SharedPreferences protectedPrefs = ctx.getSharedPreferences("provider-secrets", Context.MODE_PRIVATE);
        String prior = protectedPrefs.getString("encrypted", null);
        try {
            assertTrue(protectedPrefs.edit().remove("encrypted").commit());
            SubscriptionVoiceFailureStore.ready(ctx);
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(ctx.getPackageName(), Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33)
                InstrumentationRegistry.getInstrumentation().getUiAutomation()
                        .grantRuntimePermission(ctx.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
            Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                    new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                ctx.startForegroundService(new Intent(ctx, SubscriptionVoiceService.class)
                        .setAction(SubscriptionVoiceService.ACTION_WAKE));
                boolean found = false;
                long until = SystemClock.elapsedRealtime() + 12_000L;
                while (SystemClock.elapsedRealtime() < until) {
                    if (SubscriptionVoiceFailureStore.lastStatus(ctx)
                            .contains("ChatGPT voice sign-in required")) {
                        found = true;
                        break;
                    }
                    SystemClock.sleep(100);
                }
                assertTrue("Wake failure must remain visible after service teardown: "
                        + SubscriptionVoiceFailureStore.lastStatus(ctx), found);
            } finally {
                activity.finish();
            }
        } finally {
            ctx.stopService(new Intent(ctx, SubscriptionVoiceService.class));
            SharedPreferences.Editor restore = protectedPrefs.edit();
            if (prior == null) restore.remove("encrypted"); else restore.putString("encrypted", prior);
            assertTrue(restore.commit());
            SubscriptionVoiceFailureStore.ready(ctx);
            ctx.getSystemService(NotificationManager.class).cancel(35);
        }
    }

    @Test public void nativeWebRtcJniLoadsInMinifiedRelease() {
        // Loads org.webrtc's JNI mappings without a network call or microphone.
        // A historical R8/JNI_OnLoad SIGABRT would abort this emulator test process.
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(context()).createInitializationOptions());
    }
}
