package dev.pocket.agent;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** Main-looper-owned communication route, released with the voice session. */
final class SubscriptionVoiceAudioRoute implements AutoCloseable {
    static final class Device {
        final int id, type;
        Device(int id, int type) { this.id = id; this.type = type; }
    }

    interface Platform {
        int mode();
        void mode(int mode);
        List<Device> devices();
        int currentDeviceId();
        boolean select(int id);
        void clear();
        void observe(Runnable changed);
        void unobserve();
        default long nowMs() { return SystemClock.elapsedRealtime(); }
        void recheckAfter(long delayMs);
    }

    private static final class AndroidPlatform implements Platform {
        final AudioManager audio;
        final Handler main = new Handler(Looper.getMainLooper());
        AudioDeviceCallback devicesCallback;
        AudioManager.OnCommunicationDeviceChangedListener routeCallback;
        Runnable refresh;
        Runnable routeRecheck;
        AndroidPlatform(Context context) { audio = context.getSystemService(AudioManager.class); }
        @Override public int mode() { return audio.getMode(); }
        @Override public void mode(int mode) { audio.setMode(mode); }
        @Override public List<Device> devices() {
            List<Device> result = new ArrayList<>();
            for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices())
                result.add(new Device(device.getId(), device.getType()));
            return result;
        }
        @Override public int currentDeviceId() {
            AudioDeviceInfo device = audio.getCommunicationDevice();
            return device == null ? -1 : device.getId();
        }
        @Override public boolean select(int id) {
            for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices())
                if (device.getId() == id) return audio.setCommunicationDevice(device);
            return false;
        }
        @Override public void clear() { audio.clearCommunicationDevice(); }
        @Override public void observe(Runnable changed) {
            refresh = changed;
            routeRecheck = changed::run;
            devicesCallback = new AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { refreshDevices(); }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { refreshDevices(); }
                private void refreshDevices() {
                    // Bluetooth's communication profile can appear after the device callback.
                    Runnable changed = refresh;
                    if (changed == null) return;
                    main.removeCallbacks(changed);
                    changed.run();
                    main.postDelayed(changed, 250);
                    main.postDelayed(changed, 1000);
                }
            };
            audio.registerAudioDeviceCallback(devicesCallback, main);
            routeCallback = device -> changed.run();
            audio.addOnCommunicationDeviceChangedListener(main::post, routeCallback);
        }
        @Override public void unobserve() {
            if (refresh != null) main.removeCallbacks(refresh);
            if (routeRecheck != null) main.removeCallbacks(routeRecheck);
            if (devicesCallback != null) audio.unregisterAudioDeviceCallback(devicesCallback);
            if (routeCallback != null) audio.removeOnCommunicationDeviceChangedListener(routeCallback);
            devicesCallback = null;
            routeCallback = null;
            refresh = null;
            routeRecheck = null;
        }
        @Override public void recheckAfter(long delayMs) {
            if (routeRecheck == null) return;
            main.removeCallbacks(routeRecheck);
            main.postDelayed(routeRecheck, delayMs);
        }
    }

    private final Platform platform;
    private boolean started;
    private int previousMode;
    private int requestedDeviceId = -1;
    private long requestDeadline;
    private static final long ROUTE_REQUEST_MS = 30_000;

    SubscriptionVoiceAudioRoute(Context context) {
        this(new AndroidPlatform(context));
    }

    SubscriptionVoiceAudioRoute(Platform platform) { this.platform = platform; }

    boolean start() {
        // Sending another reply must not override a connected headset or restart capture.
        if (started) return true;
        try {
            previousMode = platform.mode();
            // Do not take over an actual phone call or another communication app.
            if (previousMode != AudioManager.MODE_NORMAL) return false;
            started = true;
            platform.mode(AudioManager.MODE_IN_COMMUNICATION);
            platform.observe(this::devicesChanged);
            if (selectRoute()) return true;
        } catch (RuntimeException unavailable) {
            Log.w("PocketSubscriptionVoice", "Voice route unavailable ("
                + unavailable.getClass().getSimpleName() + ")");
        }
        close();
        return false;
    }

    private void devicesChanged() {
        if (!started) return;
        try {
            if (!selectRoute()) Log.w("PocketSubscriptionVoice", "Voice route change unavailable");
        } catch (RuntimeException unavailable) {
            Log.w("PocketSubscriptionVoice", "Voice route change unavailable ("
                + unavailable.getClass().getSimpleName() + ")");
        }
    }

    private boolean selectRoute() {
        int current = platform.currentDeviceId();
        // A request is only pending until Android confirms it. Keeping a confirmed
        // ID here forever would prevent recovery if Android later drops the route.
        if (current == requestedDeviceId) requestedDeviceId = -1;
        List<Device> candidates = new ArrayList<>(platform.devices());
        candidates.sort((a, b) -> {
            int rank = Integer.compare(priority(a.type), priority(b.type));
            if (rank != 0) return rank;
            return Integer.compare(a.id == current ? 0 : 1, b.id == current ? 0 : 1);
        });
        for (Device device : candidates) {
            if (priority(device.type) == Integer.MAX_VALUE) continue;
            if (device.id == current) {
                requestedDeviceId = -1;
                return true;
            }
            // Avoid repeated requests while an asynchronous change is in flight,
            // but retry after a bounded interval if Android never confirms it.
            if (device.id == requestedDeviceId && platform.nowMs() < requestDeadline) return true;
            if (!platform.select(device.id)) continue;
            requestedDeviceId = device.id;
            requestDeadline = platform.nowMs() + ROUTE_REQUEST_MS;
            platform.recheckAfter(ROUTE_REQUEST_MS);
            return true;
        }
        requestedDeviceId = -1;
        return false;
    }

    private static int priority(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY: return 0;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_HEARING_AID: return 1;
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return 2;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return 3;
            default: return Integer.MAX_VALUE;
        }
    }

    @Override public void close() {
        if (!started) return;
        started = false;
        requestedDeviceId = -1;
        try { platform.unobserve(); } catch (RuntimeException ignored) { }
        try { platform.clear(); } catch (RuntimeException ignored) { }
        try {
            if (platform.mode() == AudioManager.MODE_IN_COMMUNICATION)
                platform.mode(previousMode);
        } catch (RuntimeException ignored) { }
    }
}
