package dev.pocket.agent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pins an owned destination across an entire borrowed-task migration.
 * A removal cannot begin while another display is restoring a task into it.
 */
final class DisplayRestorationReservations {
    private final Set<Integer> removing=new HashSet<>();
    private final Map<Integer,Integer> originPins=new HashMap<>();

    synchronized void beginRemoval(int id) {
        if(removing.contains(id))throw new IllegalStateException("Display cleanup is already in progress");
        if(originPins.getOrDefault(id,0)>0)
            throw new IllegalStateException("Display is receiving a protected task; retry cleanup");
        removing.add(id);
    }

    synchronized int pinOrigin(int origin,boolean stillOwned) {
        if(origin<=0||!stillOwned||removing.contains(origin))return 0;
        originPins.merge(origin,1,Integer::sum);
        return origin;
    }

    synchronized void unpinOrigin(int destination) {
        if(destination<=0)return;
        int count=originPins.getOrDefault(destination,0);
        if(count<=0)throw new IllegalStateException("Unbalanced display restoration reservation");
        if(count==1)originPins.remove(destination);
        else originPins.put(destination,count-1);
    }

    synchronized boolean isRemoving(int id) {return removing.contains(id);}

    synchronized boolean hasReservations() {
        return !removing.isEmpty()||!originPins.isEmpty();
    }

    synchronized void finishRemoval(int id) {
        if(!removing.remove(id))throw new IllegalStateException("Unbalanced display cleanup reservation");
    }
}
