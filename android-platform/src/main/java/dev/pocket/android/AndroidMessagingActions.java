package dev.pocket.android;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import dev.pocket.control.ControlException;
import java.util.ArrayList;
import static dev.pocket.control.ControlException.Code.*;

/** SMS execution only. Host supplies receipt PendingIntents and persistent operation deduplication. */
public final class AndroidMessagingActions {
    private final Context context;
    public AndroidMessagingActions(Context context) { this.context=context.getApplicationContext(); }

    public int parts(String message,int subscriptionId) throws ControlException {
        return manager(subscriptionId).divideMessage(validText(message)).size();
    }

    public AndroidSystemActions.Outcome sendSms(String number,String message,int subscriptionId,
            ArrayList<PendingIntent> sent,ArrayList<PendingIntent> delivered) throws ControlException {
        new AndroidSystemActions(context).requirePermission(Manifest.permission.SEND_SMS);
        String phone=AndroidSystemActions.normalizePhone(number);
        SmsManager manager=manager(subscriptionId);
        ArrayList<String> parts=manager.divideMessage(validText(message));
        if(sent==null || delivered==null || sent.size()!=parts.size() || delivered.size()!=parts.size()
            || sent.contains(null) || delivered.contains(null)) {
            throw new ControlException(INVALID_ARGUMENT,"A sent and delivery callback is required for every SMS part");
        }
        if(parts.size()==1) manager.sendTextMessage(phone,null,parts.get(0),sent.get(0),delivered.get(0));
        else manager.sendMultipartTextMessage(phone,null,parts,sent,delivered);
        return new AndroidSystemActions.Outcome("requested",phone,"SMS submitted; use receipts to report sent/delivered, never repeat blindly");
    }

    private SmsManager manager(int requested) throws ControlException {
        int subscription=requested>=0?requested:SubscriptionManager.getDefaultSmsSubscriptionId();
        if(!SubscriptionManager.isValidSubscriptionId(subscription)) throw new ControlException(INVALID_ARGUMENT,"Choose an SMS SIM explicitly or configure a default");
        SmsManager manager=context.getSystemService(SmsManager.class);
        if(manager==null) throw new ControlException(UNSUPPORTED,"SMS service unavailable");
        return manager.createForSubscriptionId(subscription);
    }
    private static String validText(String value) throws ControlException {
        if(value==null || value.isEmpty() || value.length()>20000) throw new ControlException(INVALID_ARGUMENT,"SMS text 1..20000 characters");
        return value;
    }
}
