package dev.pocket.android;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import dev.pocket.control.ControlException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import static dev.pocket.control.ControlException.Code.*;

/** SDK-only system integrations. Host owns authorization, operation IDs, and permissions UI. */
public final class AndroidSystemActions {
    private final Context context;
    public AndroidSystemActions(Context context) { this.context=context.getApplicationContext(); }
    public record Contact(long contactId,String name,String number) {}
    public record Calendar(long id,String name,boolean writable) {}
    public record Outcome(String status,String reference,String message) {}

    public void requirePermission(String permission) throws ControlException {
        if(context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) {
            throw new ControlException(PERMISSION_REQUIRED,permission);
        }
    }

    public List<Contact> findContacts(String query,int limit) throws ControlException {
        requirePermission(Manifest.permission.READ_CONTACTS);
        if(query==null || query.trim().isEmpty() || query.length()>200 || limit<1 || limit>100) {
            throw new ControlException(INVALID_ARGUMENT,"Contact query 1..200 characters; limit 1..100");
        }
        Uri uri=Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,Uri.encode(query));
        String[] columns={ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER};
        Map<String,Contact> unique=new LinkedHashMap<>();
        try(Cursor c=context.getContentResolver().query(uri,columns,null,null,null)) {
            if(c!=null) while(c.moveToNext() && unique.size()<limit) {
                Contact contact=new Contact(c.getLong(0),c.getString(1),c.getString(2));
                try {unique.put(contact.contactId()+":"+normalizePhone(contact.number()),contact);}
                catch(ControlException unsupportedNumber) { /* Skip unsupported service codes/extensions. */ }
            }
        }
        return new ArrayList<>(unique.values());
    }

    public Contact resolveContact(String name) throws ControlException {
        List<Contact> all=findContacts(name,100);
        if(all.size()>=100) throw new ControlException(AMBIGUOUS_TARGET,"Contact search was truncated; select an explicit number");
        List<Contact> exact=all.stream().filter(c->c.name()!=null && c.name().equalsIgnoreCase(name)).collect(java.util.stream.Collectors.toList());
        List<Contact> candidates=exact.isEmpty()?all:exact;
        if(candidates.size()!=1) throw new ControlException(candidates.isEmpty()?ACTION_REJECTED:AMBIGUOUS_TARGET,
            "Resolve contact and phone-number ambiguity before placing a call");
        return candidates.get(0);
    }

    public Outcome callContact(String name,PhoneAccountHandle account,boolean speaker) throws ControlException {
        Contact contact=resolveContact(name);
        return callNumber(contact.number(),account,speaker);
    }

    // This library deliberately leaves uses-permission declarations to the embedding app.
    // Runtime permission is checked here and revocation during dispatch is handled below.
    @android.annotation.SuppressLint("MissingPermission")
    public Outcome callNumber(String number,PhoneAccountHandle account,boolean speaker) throws ControlException {
        requirePermission(Manifest.permission.CALL_PHONE);
        String normalized=normalizePhone(number);
        String feature=android.os.Build.VERSION.SDK_INT>=33?PackageManager.FEATURE_TELEPHONY_CALLING:PackageManager.FEATURE_TELEPHONY;
        if(!context.getPackageManager().hasSystemFeature(feature)) {
            throw new ControlException(UNSUPPORTED,"Device has no telephony calling feature");
        }
        TelecomManager telecom=context.getSystemService(TelecomManager.class);
        if(telecom==null) throw new ControlException(NOT_READY,"Telecom service unavailable");
        Bundle extras=new Bundle();
        if(account!=null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,account);
        extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE,speaker);
        try { telecom.placeCall(Uri.fromParts("tel",normalized,null),extras); }
        catch(SecurityException e) { throw new ControlException(PERMISSION_REQUIRED,"Android denied phone-call permission"); }
        return new Outcome("requested",normalized,"System call requested; observe call state before reporting connection");
    }

    public static String normalizePhone(String value) throws ControlException {
        if(value==null || !value.matches("\\+?[0-9][0-9 ()\\-]{2,24}")) {
            throw new ControlException(INVALID_ARGUMENT,"An ordinary phone number is required");
        }
        return value.replaceAll("[ ()\\-]","");
    }

    public Outcome setAlarm(int hour,int minute,String label) throws ControlException {
        if(hour<0 || hour>23 || minute<0 || minute>59) throw new ControlException(INVALID_ARGUMENT,"Invalid alarm time");
        return launchClock(new Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR,hour).putExtra(AlarmClock.EXTRA_MINUTES,minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE,label==null?"":label).putExtra(AlarmClock.EXTRA_SKIP_UI,true));
    }

    public Outcome startTimer(int seconds,String label) throws ControlException {
        if(seconds<1 || seconds>86400) throw new ControlException(INVALID_ARGUMENT,"Timer length 1..86400 seconds");
        return launchClock(new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH,seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE,label==null?"":label).putExtra(AlarmClock.EXTRA_SKIP_UI,true));
    }

    private Outcome launchClock(Intent intent) throws ControlException {
        requirePermission("com.android.alarm.permission.SET_ALARM");
        if(intent.resolveActivity(context.getPackageManager())==null) throw new ControlException(UNSUPPORTED,"No clock intent handler");
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch(android.content.ActivityNotFoundException e) {throw new ControlException(UNSUPPORTED,"Clock handler disappeared");}
        // Clock intents have no universal completion receipt. Do not fabricate created/verified.
        return new Outcome("requested",intent.getAction(),"Skip-UI requested; OEM launch restrictions and clock completion require verification");
    }

    public List<Calendar> calendars() throws ControlException {
        requirePermission(Manifest.permission.READ_CALENDAR);
        List<Calendar> result=new ArrayList<>();
        String[] columns={CalendarContract.Calendars._ID,CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL};
        try(Cursor c=context.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,columns,null,null,null)) {
            if(c!=null) while(c.moveToNext()) result.add(new Calendar(c.getLong(0),c.getString(1),
                c.getInt(2)>=CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR));
        }
        return result;
    }

    public Outcome createEvent(long calendarId,String title,long startMillis,long endMillis,String timezone) throws ControlException {
        requirePermission(Manifest.permission.WRITE_CALENDAR);
        requirePermission(Manifest.permission.READ_CALENDAR);
        if(calendarId<0 || title==null || title.trim().isEmpty() || title.length()>500 || endMillis<=startMillis) {
            throw new ControlException(INVALID_ARGUMENT,"Valid calendar, title, and increasing start/end required");
        }
        if(timezone==null || !List.of(TimeZone.getAvailableIDs()).contains(timezone)) {
            throw new ControlException(INVALID_ARGUMENT,"Recognized event time zone required");
        }
        if(calendars().stream().noneMatch(c->c.id()==calendarId && c.writable())) throw new ControlException(INVALID_ARGUMENT,"Choose an existing writable calendar");
        ContentValues values=new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID,calendarId);values.put(CalendarContract.Events.TITLE,title);
        values.put(CalendarContract.Events.DTSTART,startMillis);values.put(CalendarContract.Events.DTEND,endMillis);
        values.put(CalendarContract.Events.EVENT_TIMEZONE,timezone);
        // Match ordinary calendar editors: optional empty text fields need not be SQL NULL.
        values.put(CalendarContract.Events.DESCRIPTION,"");values.put(CalendarContract.Events.EVENT_LOCATION,"");
        Uri uri=context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI,values);
        if(uri==null) throw new ControlException(RESULT_UNKNOWN,"Provider returned no event URI; inspect before repeating");
        try(Cursor c=context.getContentResolver().query(uri,new String[]{CalendarContract.Events._ID},null,null,null)) {
            if(c==null || !c.moveToFirst()) return new Outcome("requested",uri.toString(),"Event URI returned; read-back unavailable");
            return new Outcome("verified",uri.toString(),"Event persisted and read back from Calendar Provider");
        } catch(RuntimeException e) {
            // The insert already completed. A failed read-back must never invite a duplicate insert.
            return new Outcome("requested",uri.toString(),"Event URI returned; read-back failed, inspect this URI before retrying");
        }
    }
}
