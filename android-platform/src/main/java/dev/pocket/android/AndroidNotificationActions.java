package dev.pocket.android;

import android.app.Notification;
import android.os.Parcelable;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import dev.pocket.control.ControlException;
import java.util.ArrayList;
import java.util.List;
import static dev.pocket.control.ControlException.Code.*;

/** Uses app-provided reply capabilities. No private WhatsApp/Telegram protocol or database access. */
public final class AndroidNotificationActions {
    private final NotificationListenerService listener;
    public AndroidNotificationActions(NotificationListenerService connectedListener) { listener=connectedListener; }
    public record Reply(String notificationKey,long postedAt,String packageName,int actionIndex,String label,String title,String conversationTitle,Boolean groupConversation) {}
    public record Message(String sender,String text,long timestamp) {}
    public record Entry(String notificationKey,long postedAt,String packageName,String title,String text,
                        String expandedText,String conversationTitle,Boolean groupConversation,List<Message> messages) {}

    public record Page(List<Entry> entries,boolean hasMore) {}

    /** Active notifications only; offsets are best-effort because notifications can change between reads. */
    public List<Entry> read(String packageName,int limit) throws ControlException {
        return readPage(packageName,0,limit).entries();
    }

    public Page readPage(String packageName,int offset,int limit) throws ControlException {
        if(limit<1 || limit>50 || offset<0 ||
            (packageName!=null && (!packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") || packageName.length()>200)))
            throw new ControlException(INVALID_ARGUMENT,"Optional package name, nonnegative offset and limit 1..50 required");
        List<Entry> result=new ArrayList<>();
        StatusBarNotification[] items=active();
        java.util.Arrays.sort(items,(a,b)->Long.compare(b.getPostTime(),a.getPostTime()));
        int skipped=0;
        for(StatusBarNotification item:items) {
            if(packageName!=null && !packageName.equals(item.getPackageName()))continue;
            try {
                Notification n=item.getNotification();
                if(n==null || n.extras==null)continue;
                Bundle extras=n.extras;
                List<Message> messages=new ArrayList<>();
                try {
                    Parcelable[] bundled=extras.getParcelableArray(Notification.EXTRA_MESSAGES);
                    if(bundled!=null) {
                        List<Notification.MessagingStyle.Message> parsed=Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundled);
                        for(int i=Math.max(0,parsed.size()-10);i<parsed.size();i++) {
                            Notification.MessagingStyle.Message message=parsed.get(i);
                            messages.add(new Message(clipped(message.getSender(),100),clipped(message.getText(),500),message.getTimestamp()));
                        }
                    }
                }catch(RuntimeException ignored) { /* A malformed message history need not hide valid metadata. */ }
                Entry entry=new Entry(item.getKey(),item.getPostTime(),item.getPackageName(),
                    clipped(extras.getCharSequence(Notification.EXTRA_TITLE)),
                    clipped(extras.getCharSequence(Notification.EXTRA_TEXT)),
                    clipped(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)),
                    clipped(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)),
                    extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)?extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION):null,
                    messages);
                if(skipped<offset){skipped++;continue;}
                if(result.size()==limit)return new Page(result,true);
                result.add(entry);
            }catch(RuntimeException ignored) { /* Skip one malformed notification without discarding the rest. */ }
        }
        return new Page(result,false);
    }

    public List<Reply> replies() throws ControlException {
        List<Reply> result=new ArrayList<>();
        for(StatusBarNotification item:active()) {
            Notification notification=item.getNotification();
            CharSequence title=notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence conversation=notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            Boolean group=notification.extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)?notification.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION):null;
            Notification.Action[] actions=item.getNotification().actions;
            if(actions==null) continue;
            for(int i=0;i<actions.length;i++) {
                RemoteInput[] inputs=actions[i].getRemoteInputs();
                if(inputs!=null && inputs.length==1 && inputs[0].getAllowFreeFormInput() && actions[i].actionIntent!=null) {
                    result.add(new Reply(item.getKey(),item.getPostTime(),item.getPackageName(),i,String.valueOf(actions[i].title),clipped(title),clipped(conversation),group));
                }
            }
        }
        return result;
    }
    private static String clipped(CharSequence value) {return clipped(value,1000);}
    private static String clipped(CharSequence value,int max) {String s=value==null?"":value.toString();return s.length()>max?s.substring(0,max):s;}

    public AndroidSystemActions.Outcome reply(String key,long postedAt,int actionIndex,String text) throws ControlException {
        if(text==null || text.trim().isEmpty() || text.length()>20000) throw new ControlException(INVALID_ARGUMENT,"Reply text required");
        for(StatusBarNotification item:active()) {
            if(!item.getKey().equals(key)) continue;
            if(item.getPostTime()!=postedAt) throw new ControlException(STALE_OBSERVATION,"Notification changed; retrieve the current reply capability");
            Notification.Action[] actions=item.getNotification().actions;
            if(actions==null || actionIndex<0 || actionIndex>=actions.length) throw new ControlException(INVALID_ARGUMENT,"Reply action unavailable");
            Notification.Action action=actions[actionIndex];RemoteInput[] inputs=action.getRemoteInputs();
            if(inputs==null || inputs.length!=1 || !inputs[0].getAllowFreeFormInput() || action.actionIntent==null) {
                throw new ControlException(UNSUPPORTED,"Selected action is not a supported free-form reply");
            }
            Bundle values=new Bundle();values.putCharSequence(inputs[0].getResultKey(),text);
            Intent result=new Intent();RemoteInput.addResultsToIntent(inputs,result,values);
            RemoteInput.setResultsSource(result,RemoteInput.SOURCE_FREE_FORM_INPUT);
            try { action.actionIntent.send(listener,0,result); }
            catch(PendingIntent.CanceledException e) {throw new ControlException(STALE_OBSERVATION,"Reply capability expired");}
            return new AndroidSystemActions.Outcome("requested",key,"Reply action sent to owning app; delivery is not yet verified");
        }
        throw new ControlException(STALE_OBSERVATION,"Notification is no longer active");
    }

    private StatusBarNotification[] active() throws ControlException {
        if(listener==null) throw new ControlException(NOT_READY,"An authorized, connected notification listener is required");
        try {
            StatusBarNotification[] items=listener.getActiveNotifications();
            if(items==null) throw new ControlException(NOT_READY,"Notification listener not connected");
            return items;
        } catch(SecurityException e) {throw new ControlException(PERMISSION_REQUIRED,"Notification access required");}
    }
}
