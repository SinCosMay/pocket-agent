package dev.pocket.agent;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import dev.pocket.android.AndroidScreenBackend;
import java.util.concurrent.ConcurrentHashMap;

public final class AgentAccessibility extends AccessibilityService {
    static volatile AgentAccessibility connected;
    private AndroidScreenBackend backend;
    private final ConcurrentHashMap<Integer,AndroidScreenBackend> virtualBackends=new ConcurrentHashMap<>();
    @Override protected void onServiceConnected() {backend=new AndroidScreenBackend(this);connected=this;}
    AndroidScreenBackend backend() {return backend;}
    AndroidScreenBackend backend(int displayId) {
        if(displayId<=0)throw new IllegalArgumentException("Virtual display required");
        return virtualBackends.computeIfAbsent(displayId,id->new AndroidScreenBackend(this,id));
    }
    void release(int displayId){virtualBackends.remove(displayId);}
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        int type=event.getEventType();
        if(type==AccessibilityEvent.TYPE_VIEW_CLICKED || type==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || type==AccessibilityEvent.TYPE_VIEW_SCROLLED || type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            int displayId=event.getDisplayId();
            AndroidScreenBackend target=displayId==0?backend:virtualBackends.get(displayId);
            if(target!=null)target.noteInteraction(event.getEventTime());
        }
    }
    @Override public void onInterrupt() {}
    @Override public void onDestroy() {virtualBackends.clear();if(connected==this) connected=null;super.onDestroy();}
}
