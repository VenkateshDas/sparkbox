package me.venkatesh.saveobsidian;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/** Reducers over the session journal. UI drafts are deliberately not conversation messages. */
public final class ScoutState {
    private ScoutState() { }

    public static JSONObject latest(Session session, String kind) {
        JSONObject found = null;
        for (JSONObject e : session.log()) if (e.optString("type").equals("custom") && e.optString("kind").equals(kind)) found = e.optJSONObject("data");
        return found;
    }

    public static String accept(Session session, String text, boolean followUp) throws IOException {
        String id = UUID.randomUUID().toString();
        try { session.appendCustom("queue_accept", new JSONObject().put("queueId", id).put("text", text).put("followUp", followUp)); }
        catch (org.json.JSONException impossible) { throw new IOException(impossible); }
        return id;
    }

    /** A correlated durable message consumes acceptance even if a later marker was never saved. */
    public static List<JSONObject> pending(Session session) {
        Map<String, JSONObject> pending = new LinkedHashMap<>();
        java.util.Set<String> consumed = new java.util.HashSet<>();
        for (JSONObject e : session.log()) {
            JSONObject d = e.optJSONObject("data"), m = e.optJSONObject("message");
            if (m != null && !m.optString("queueId").isEmpty()) consumed.add(m.optString("queueId"));
            if (!e.optString("type").equals("custom") || d == null) continue;
            String id = d.optString("queueId");
            switch (e.optString("kind")) {
                case "queue_accept": case "queue_edit": pending.put(id, d); break;
                case "queue_remove": pending.remove(id); break;
                default: break;
            }
        }
        pending.keySet().removeAll(consumed);
        return new ArrayList<>(pending.values());
    }
    public static String partial(Session session) {
        StringBuilder text = new StringBuilder();
        for (JSONObject e : session.log()) {
            String kind = e.optString("kind"); JSONObject data = e.optJSONObject("data");
            if (kind.equals("partial_start") || kind.equals("partial_complete")) text.setLength(0);
            if (kind.equals("partial_text") && data != null && text.length() < 2 * 1024 * 1024) text.append(data.optString("text"));
        }
        return text.toString();
    }

    public static synchronized JSONObject budget(Session session) {
        JSONObject saved = latest(session, "budget");
        return saved == null ? new JSONObject() : saved;
    }

    public static synchronized void spend(Session session, String counter, int limit, long activeMs) throws Exception {
        JSONObject state = budget(session);
        if (state.optInt(counter) >= limit) throw new Sse.Protocol("Scout reached its " + counter + " limit; answer is partial");
        state.put(counter, state.optInt(counter) + 1).put("activeMs", Math.max(state.optLong("activeMs"), activeMs));
        session.appendCustom("budget", state);
    }

}
