package dev.pocket.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

final class DevHistory {
    private DevHistory() { }

    static JSONObject query(StateStore store, JSONObject request) throws Exception {
        String query = request.optString("query", "").trim().toLowerCase(Locale.ROOT);
        String owner = request.optString("conversationId", "");
        if (query.length() > 250 || owner.length() > 160) throw new IllegalArgumentException("Invalid filter");
        int limit = request.optInt("limit", 25), offset = request.optInt("offset", 0);
        if (limit < 1 || limit > 100 || offset < 0 || offset > 100000) throw new IllegalArgumentException("Invalid page");
        JSONObject checkpoint = store.loadDurable();
        JSONArray matches = new JSONArray();
        int[] skipped = {0};
        boolean[] hasMore = {false};
        if (owner.isEmpty() || owner.equals("main"))
            collect(matches, checkpoint.optJSONArray("mainMessages"), "main", query, limit, offset, skipped, hasMore);
        JSONObject workers = checkpoint.optJSONObject("workerMessages");
        if (workers != null) {
            java.util.Iterator<String> iterator = workers.keys();
            while (iterator.hasNext()) {
                String id = iterator.next();
                if (owner.isEmpty() || owner.equals(id))
                    collect(matches, workers.optJSONArray(id), id, query, limit, offset, skipped, hasMore);
            }
        }
        return new JSONObject().put("messages", matches).put("nextOffset", hasMore[0] ? offset + matches.length() : JSONObject.NULL)
            .put("limit", limit).put("persisted", true);
    }

    private static void collect(JSONArray output, JSONArray messages, String owner, String query,
                                int limit, int offset, int[] skipped, boolean[] hasMore) throws Exception {
        if (messages == null) return;
        for (int i = messages.length()-1; i >= 0; i--) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String raw = message.toString();
            if (!query.isEmpty() && !raw.toLowerCase(Locale.ROOT).contains(query)) continue;
            if (skipped[0]++ < offset) continue;
            if (output.length() >= limit) { hasMore[0] = true; return; }
            output.put(new JSONObject().put("conversationId", owner).put("index", i)
                .put("role", message.optString("role", "unknown"))
                .put("timestamp", message.optLong("timestamp", 0))
                .put("data", raw.substring(0, Math.min(raw.length(), 12000)))
                .put("truncated", raw.length() > 12000));
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
