package dev.pocket.android;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import dev.pocket.control.ControlException;
import java.util.List;
import static dev.pocket.control.ControlException.Code.*;

public final class AndroidMediaActions {
    private final MediaSessionManager manager;
    private final ComponentName authorizedListener;
    public AndroidMediaActions(Context context,ComponentName authorizedListener) {
        manager=context.getSystemService(MediaSessionManager.class);this.authorizedListener=authorizedListener;
    }
    public List<MediaController> sessions() throws ControlException {
        if(manager==null) throw new ControlException(UNSUPPORTED,"Media session service unavailable");
        try {return manager.getActiveSessions(authorizedListener);}
        catch(SecurityException e) {throw new ControlException(PERMISSION_REQUIRED,"Enabled notification listener or media-control access required");}
    }
    public AndroidSystemActions.Outcome control(String packageName,String action,long positionMillis) throws ControlException {
        List<MediaController> matches=sessions().stream().filter(c->c.getPackageName().equals(packageName)).collect(java.util.stream.Collectors.toList());
        if(matches.size()!=1) throw new ControlException(matches.isEmpty()?NOT_READY:AMBIGUOUS_TARGET,"Exactly one matching media session required");
        MediaController controller=matches.get(0);PlaybackState state=controller.getPlaybackState();
        long needed=switch(action==null?"":action) {
            case "play" -> PlaybackState.ACTION_PLAY; case "pause" -> PlaybackState.ACTION_PAUSE;
            case "next" -> PlaybackState.ACTION_SKIP_TO_NEXT; case "previous" -> PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            case "seek" -> PlaybackState.ACTION_SEEK_TO; default -> throw new ControlException(UNSUPPORTED,"Unsupported media action");
        };
        if(state==null || (state.getActions()&needed)==0) throw new ControlException(UNSUPPORTED,"Session does not advertise this action");
        MediaController.TransportControls controls=controller.getTransportControls();
        switch(action) {
            case "play" -> controls.play(); case "pause" -> controls.pause(); case "next" -> controls.skipToNext();
            case "previous" -> controls.skipToPrevious(); case "seek" -> {
                if(positionMillis<0) throw new ControlException(INVALID_ARGUMENT,"Nonnegative seek position required");
                controls.seekTo(positionMillis);
            }
        }
        return new AndroidSystemActions.Outcome("requested",packageName,"Media command requested; verify updated playback state");
    }
}
