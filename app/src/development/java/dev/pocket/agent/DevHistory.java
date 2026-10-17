package dev.pocket.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

final class DevHistory {
    private DevHistory() { }

    private static final class Hit {
        final String owner;
        final int index;
        final JSONObject message;
        final long timestamp;

        Hit(String owner, int index, JSONObject message) {
            this.owner=owner;
            this.index=index;
            this.message=message;
            this.timestamp=message.optLong("timestamp",0);
        }

        JSONObject result() throws Exception {
            String raw=message.toString();
            return new JSONObject().put("conversationId",owner).put("index",index)
                .put("role",message.optString("role","unknown"))
                .put("timestamp",timestamp)
                .put("data",raw.substring(0,Math.min(raw.length(),12000)))
                .put("truncated",raw.length()>12000);
        }
    }

    static JSONObject query(StateStore store, JSONObject request) throws Exception {
        return queryCheckpoint(store.loadDurable(),request);
    }

    static JSONObject queryCheckpoint(JSONObject checkpoint, JSONObject request) throws Exception {
        String query = request.optString("query", "").trim().toLowerCase(Locale.ROOT);
        String owner = request.optString("conversationId", "");
        if (query.length() > 250 || owner.length() > 160) throw new IllegalArgumentException("Invalid filter");
        int limit = request.optInt("limit", 25), offset = request.optInt("offset", 0);
        if (limit < 1 || limit > 100 || offset < 0 || offset > 100000) throw new IllegalArgumentException("Invalid page");
        ArrayList<Hit> candidates = new ArrayList<>();
        if (owner.isEmpty() || owner.equals("main"))
            collect(candidates, checkpoint.optJSONArray("mainMessages"), "main", query);
        JSONObject workers = checkpoint.optJSONObject("workerMessages");
        if (workers != null) {
            java.util.Iterator<String> iterator = workers.keys();
            while (iterator.hasNext()) {
                String id = iterator.next();
                if (owner.isEmpty() || owner.equals(id))
                    collect(candidates, workers.optJSONArray(id), id, query);
            }
        }
        // Sort references rather than copying up to 12 KiB of transcript per match.
        // Serialize only the requested page, after global chronological ordering.
        candidates.sort(Comparator.comparingLong((Hit hit)->hit.timestamp).reversed());
        JSONArray matches = new JSONArray();
        for (int i=offset; i<candidates.size() && i<(long)offset+limit; i++)
            matches.put(candidates.get(i).result());
        boolean hasMore = (long)offset+matches.length()<candidates.size();
        return new JSONObject().put("messages", matches).put("nextOffset", hasMore ? offset + matches.length() : JSONObject.NULL)
            .put("limit", limit).put("persisted", true);
    }

    private static void collect(ArrayList<Hit> output, JSONArray messages, String owner, String query) {
        if (messages == null) return;
        for (int i=messages.length()-1; i >= 0; i--) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            if (!query.isEmpty() && !message.toString().toLowerCase(Locale.ROOT).contains(query)) continue;
            output.add(new Hit(owner,i,message));
        }
    }

    static JSONObject events(StateStore store, JSONObject request) throws Exception {
        String query = request.optString("query", "");
        int limit = request.optInt("limit", 50), offset = request.optInt("offset", 0);
        if (query.length() > 250 || limit < 1 || limit > 100 || offset < 0 || offset > 100000)
            throw new IllegalArgumentException("Invalid page");
        JSONArray rows = store.queryEvents(query, offset, limit);
        return new JSONObject().put("events", rows).put("nextOffset", rows.length() == limit ? offset + rows.length() : JSONObject.NULL);
    }
}
