package dev.pocket.agent;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/** Tracks allocated displays even while bootstrap or cleanup is incomplete. */
final class DisplayLeases {
    private final Map<String,Integer> displays=new LinkedHashMap<>();
    private final Set<String> provisional=new LinkedHashSet<>();

    Integer get(String owner) {return displays.get(owner);}
    boolean containsValue(int id) {return displays.containsValue(id);}
    int size() {return displays.size();}
    Set<String> owners() {return new LinkedHashSet<>(displays.keySet());}
    Iterable<Integer> ids() {return displays.values();}
    boolean provisional(String owner) {return provisional.contains(owner);}

    void provision(String owner,int id) {
        if(id<=0||displays.containsKey(owner)||displays.containsValue(id))
            throw new IllegalStateException("Display lease already exists or has invalid ID");
        displays.put(owner,id);
        provisional.add(owner);
    }
    void activate(String owner,int id) {
        if(!Integer.valueOf(id).equals(displays.get(owner))||!provisional.remove(owner))
            throw new IllegalStateException("Display lease lost during bootstrap");
    }
    boolean release(String owner,IntPredicate remove) {
        Integer id=displays.get(owner);
        if(id==null)return true;
        if(!remove.test(id))return false;
        displays.remove(owner);
        provisional.remove(owner);
        return true;
    }
    /** A failed native release must not prevent attempting every other owner. */
    static void releaseIndependently(Iterable<String> owners,Predicate<String> release) {
        for(String owner:owners) {
            try {release.test(owner);} catch(RuntimeException ignored) {
                // Caller retains the failed lease for later retry.
            }
        }
    }
    void clear() {displays.clear();provisional.clear();}
}
