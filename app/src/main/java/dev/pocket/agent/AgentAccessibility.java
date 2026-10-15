package dev.pocket.agent;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import dev.pocket.android.AndroidScreenBackend;

public final class AgentAccessibility extends AccessibilityService {
    static volatile AgentAccessibility connected;
    private AndroidScreenBackend backend;
    @Override protected void onServiceConnected() {backend=new AndroidScreenBackend(this);connected=this;}
    AndroidScreenBackend backend() {return backend;}
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        int type=event.getEventType();
        if(backend!=null && (type==AccessibilityEvent.TYPE_VIEW_CLICKED || type==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || type==AccessibilityEvent.TYPE_VIEW_SCROLLED || type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) backend.noteInteraction();
    }
    @Override public void onInterrupt() {}
    @Override public void onDestroy() {if(connected==this) connected=null;super.onDestroy();}
}
