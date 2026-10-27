package dev.pocket.agent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import java.util.UUID;
import org.json.JSONObject;

/** A silent, persisted invitation. Posting it never connects audio or starts capture. */
final class VoiceConversationInvitation {
    static final String CHANNEL = "codex_voice_invitations";
    static final int NOTIFICATION_ID = 38;
    static final String ACCEPT = "dev.pocket.agent.ACCEPT_CODEX_VOICE";
    static final String DECLINE = "dev.pocket.agent.DECLINE_CODEX_VOICE";
    static final String TEXT = "text", OFFER = "offer";
    static final long EXPIRY_MS = 10 * 60_000L;
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("voice_response_preferences", Context.MODE_PRIVATE);
    }
    static String mode(Context context) {
        String value = prefs(context).getString("mode", OFFER);
        return TEXT.equals(value) ? TEXT : OFFER;
    }
    static synchronized void setMode(Context context, String value) {
        if (!TEXT.equals(value) && !OFFER.equals(value))
            throw new IllegalArgumentException("Unknown response preference");
        if (!prefs(context).edit().putString("mode", value).commit())
            throw new IllegalStateException("Response preference was not saved");
        if (TEXT.equals(value)) cancel(context, "preference_changed");
    }
    static JSONObject preferences(Context context) {
        JSONObject invitation = pending(context);
        return Json.obj("mode", mode(context), "voiceActive", SubscriptionVoiceService.isActive(),
            "invitationStatus", invitation.optString("status"), "threadId", invitation.optString("threadId"));
    }
    static JSONObject pending(Context context) {
        try { return new JSONObject(prefs(context).getString("invitation", "{}")); }
        catch (Exception invalid) { return new JSONObject(); }
    }
    private static void save(Context context, JSONObject request) {
        if (!prefs(context).edit().putString("invitation", request.toString()).commit())
            throw new IllegalStateException("Voice invitation was not saved");
    }
    private static JSONObject result(String status) {
        return Json.obj("status", status, "note", "Continue in chat unless the user accepts voice. A request is not acceptance or delivery.");
    }
    static String currentThread(Context context) {
        try (StateStore store = new StateStore(context)) {
            JSONObject durable = store.loadDurable();
            if (durable.optBoolean("freshMainRequested")) return "";
            return durable.optString("mainThreadId", store.load().optString("mainThreadId"));
        }
    }
    static synchronized JSONObject request(Context context, JSONObject args) throws Exception {
        if (SubscriptionVoiceService.isActive()) return result("already_active");
        if (TEXT.equals(mode(context))) return result("text_only");
        if (AgentService.isStopping()) return result("runtime_stopping");
        String thread = args.optString("threadId"), message = args.optString("message").trim();
        if (!thread.matches("[a-f0-9-]{36}") || !thread.equals(currentThread(context)))
            return result("thread_changed");
        if (message.isEmpty() || message.length() > 300) throw new IllegalArgumentException("Invalid invitation message");
        JSONObject previous = pending(context);
        long now = System.currentTimeMillis();
        if (thread.equals(previous.optString("threadId"))) {
            if ("pending".equals(previous.optString("status")) && now < previous.optLong("expiresAt"))
                return result("pending").put("id", previous.optString("id"));
            if ("declined".equals(previous.optString("status")) && now - previous.optLong("updatedAt") < EXPIRY_MS)
                return result("declined");
        }
        if (new CredentialStore(context).getVoiceCodex().optString("accountId").isEmpty())
            return result("voice_sign_in_required");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED))
            return result("notifications_disabled");
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Voice conversation invitations", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); channel.enableVibration(false); channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        NotificationChannel actualChannel = manager.getNotificationChannel(CHANNEL);
        if (actualChannel == null || actualChannel.getImportance() == NotificationManager.IMPORTANCE_NONE)
            return result("notifications_disabled");
        String id = UUID.randomUUID().toString();
        JSONObject invitation = Json.obj("id", id, "threadId", thread, "message", message,
            "status", "pending", "createdAt", now, "updatedAt", now, "expiresAt", now + EXPIRY_MS);
        save(context, invitation);
        try { manager.notify(NOTIFICATION_ID, notification(context, invitation)); }
        catch (RuntimeException denied) {
            save(context, invitation.put("status", "failed"));
            return result("notification_failed");
        }
        record(context, invitation);
        return result("pending").put("id", id);
    }
    static Notification notification(Context context, JSONObject request) {
        String id = request.optString("id");
        Intent accept = new Intent(context, MainActivity.class).setAction(ACCEPT)
            .setData(Uri.parse("pocket-agent://voice-invitation/" + id + "/accept"))
            .putExtra("invitationId", id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent decline = new Intent(context, Receiver.class).setAction(DECLINE)
            .setData(Uri.parse("pocket-agent://voice-invitation/" + id + "/decline")).putExtra("invitationId", id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent acceptAction = PendingIntent.getActivity(context, 38, accept, flags);
        PendingIntent declineAction = PendingIntent.getBroadcast(context, 38, decline, flags);
        PendingIntent open = PendingIntent.getActivity(context, 39, new Intent(context, MainActivity.class), flags);
        return new Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Pocket Agent would like to talk").setContentText(request.optString("message"))
            .setStyle(new Notification.BigTextStyle().bigText(request.optString("message")))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setSilent(true).setOnlyAlertOnce(true)
            .setContentIntent(open).setDeleteIntent(declineAction).setTimeoutAfter(EXPIRY_MS)
            .addAction(new Notification.Action.Builder(null, "Accept", acceptAction).build())
            .addAction(new Notification.Action.Builder(null, "Decline", declineAction).build()).build();
    }
    static synchronized boolean canAccept(Context context, String id) {
        try {
            JSONObject request = pending(context);
            return id != null && id.equals(request.optString("id")) && "pending".equals(request.optString("status"))
                && !TEXT.equals(mode(context)) && System.currentTimeMillis() < request.optLong("expiresAt")
                && request.optString("threadId").equals(currentThread(context)) && !AgentService.isStopping();
        } catch (RuntimeException unavailable) { return false; }
    }
    static synchronized boolean accept(Context context, String id) {
        if (!canAccept(context, id)) return false;
        try { finish(context, "accepted"); return true; }
        catch (RuntimeException unavailable) { Log.w("PocketVoice", "Voice invitation acceptance could not be saved"); return false; }
    }
    static synchronized void decline(Context context, String id) {
        JSONObject request = pending(context);
        if (id != null && id.equals(request.optString("id")) && "pending".equals(request.optString("status")))
            finish(context, System.currentTimeMillis() < request.optLong("expiresAt") ? "declined" : "expired");
    }
    static synchronized void cancel(Context context, String reason) {
        try { if ("pending".equals(pending(context).optString("status"))) finish(context, reason); }
        catch (RuntimeException unavailable) { Log.w("PocketVoice", "Voice invitation cancellation could not be saved"); }
    }
    private static void finish(Context context, String status) {
        try {
            JSONObject request = pending(context).put("status", status).put("updatedAt", System.currentTimeMillis());
            save(context, request);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(NOTIFICATION_ID);
            record(context, request);
        } catch (Exception failure) { throw new IllegalStateException("Voice invitation update failed", failure); }
    }
    private static void record(Context context, JSONObject request) {
        try (StateStore store = new StateStore(context)) {
            store.record(Json.obj("type", "voice_invitation", "id", request.optString("id"),
                "status", request.optString("status"), "threadId", request.optString("threadId")));
        } catch (RuntimeException unavailable) { Log.w("PocketVoice", "Voice invitation status could not be saved"); }
    }
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            try { if (intent != null && DECLINE.equals(intent.getAction())) decline(context, intent.getStringExtra("invitationId")); }
            catch (RuntimeException unavailable) { Log.w("PocketVoice", "Voice invitation decline could not be saved"); }
        }
    }
}
