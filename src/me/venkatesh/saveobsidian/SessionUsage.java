package me.venkatesh.saveobsidian;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Scout's local request ledger. Never part of model context; pure Java for deterministic checks. */
public final class SessionUsage {
    private SessionUsage() { }

    public interface Call { JSONObject run(Consumer<JSONObject> metadata) throws Exception; }

    /** Each actual HTTP attempt is recorded, even if its text is discarded or the stream fails. */
    public static JSONObject measure(Session session, String run, String purpose, JSONObject request, int window,
                                     Consumer<JSONObject> changed, Call call) throws Exception {
        return measure(session, run, purpose, request, window, changed, 0, false, call);
    }

    public static JSONObject measure(Session session, String run, String purpose, JSONObject request, int window,
                                     Consumer<JSONObject> changed, int attempt, boolean continuation, Call call) throws Exception {
        String id = UUID.randomUUID().toString();
        long began = System.nanoTime();
        JSONObject start = describe(request, window);
        start.put("operationId", Metrics.context().optString("operationId")).put("type", "request").put("requestId", id).put("run", run).put("purpose", purpose).put("status", "pending").put("attempt", attempt).put("continuation", continuation);
        if (attempt > 0 || continuation) {
            List<JSONObject> earlier = requests(session);
            for (int i = earlier.size() - 1; i >= 0; i--) if (earlier.get(i).optString("run").equals(run) && earlier.get(i).optString("purpose").equals(purpose)) {
                start.put("relatedRequestId", earlier.get(i).optString("requestId")); break;
            }
        }
        session.append(start);
        Metrics.scout(start, session.id());
        changed.accept(start);
        JSONObject observed = new JSONObject();
        Consumer<JSONObject> metadata = m -> {
            try {
                boolean dirty = false;
                for (String k : new String[]{"id", "model", "provider", "usage", "finish_reason"}) {
                    if (!m.has(k) || m.isNull(k)) continue;
                    String target = k.equals("id") ? "generationId" : k.equals("model") ? "actualModel" : k;
                    if (!String.valueOf(m.get(k)).equals(String.valueOf(observed.opt(target)))) { observed.put(target, m.get(k)); dirty = true; }
                }
                if (dirty) {
                    JSONObject update = new JSONObject(observed.toString()).put("type", "request_update").put("requestId", id);
                    session.append(update); // capture the generation ID before a possible process kill
                    Metrics.scout(update, session.id());
                }
            } catch (Exception ignored) { /* metrics must not interrupt a response already in flight */ }
        };
        String status = "error";
        try {
            JSONObject reply;
            try (AutoCloseable meter = Metrics.suppress()) { reply = call.run(metadata); }
            metadata.accept(reply);
            status = reply.optString("finish_reason").equals("cancelled") ? "cancelled" : "completed";
            reply.put("_requestId", id);
            JSONObject usage = reply.optJSONObject("usage");
            if (usage != null) usage.put("_requestId", id); // prevents legacy message/summary totals counting it twice
            return reply;
        } finally {
            JSONObject end = new JSONObject(observed.toString()).put("type", "request_update").put("requestId", id)
                .put("status", status).put("durationMs", (System.nanoTime() - began) / 1_000_000);
            try { session.append(end); Metrics.scout(end, session.id()); changed.accept(end); } catch (Exception ignored) { }
        }
    }

    private static String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static List<String> keys(JSONObject object) {
        List<String> keys = new ArrayList<>();
        java.util.Iterator<String> it = object.keys();
        while (it.hasNext()) keys.add(it.next());
        return keys;
    }

    private static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = keys(object);
            java.util.Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))).append(',');
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) out.append(canonical(array.get(i))).append(',');
            return out.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }

    /** Count the effective payload, including instructions and tool schemas, without storing note text again. */
    public static JSONObject describe(JSONObject request, int window) throws Exception {
        JSONArray messages = request.optJSONArray("messages"), hashes = new JSONArray();
        long chars = 0;
        for (int i = 0; messages != null && i < messages.length(); i++) {
            String text = messages.getJSONObject(i).toString();
            hashes.put(hash(canonical(messages.getJSONObject(i)))); chars += text.length();
        }
        String tools = request.has("tools") ? request.getJSONArray("tools").toString() : "";
        chars += tools.length();
        return new JSONObject().put("model", request.optString("model")).put("window", window)
            .put("reserve", request.optInt("max_tokens")).put("inputChars", chars).put("inputHashes", hashes)
            .put("toolsHash", hash(request.has("tools") ? canonical(request.getJSONArray("tools")) : "")).put("estimate", chars / 4 + (messages == null ? 0 : messages.length() * 4L) + 3);
    }

    /** Calibrate from an intact earlier input prefix. Compaction, edits, tools/model changes invalidate it. */
    public static long estimate(JSONObject current, List<JSONObject> requests) {
        JSONArray next = current.optJSONArray("inputHashes");
        for (int i = requests.size() - 1; i >= 0; i--) {
            JSONObject old = requests.get(i), u = old.optJSONObject("usage");
            JSONArray before = old.optJSONArray("inputHashes");
            if (!old.optString("purpose").equals("conversation") || u == null || u.isNull("prompt_tokens")
                || !old.optString("model").equals(current.optString("model"))
                || !old.optString("toolsHash").equals(current.optString("toolsHash"))
                || before == null || next == null || before.length() > next.length()) continue;
            boolean prefix = true;
            for (int j = 0; j < before.length(); j++) if (!before.optString(j).equals(next.optString(j))) { prefix = false; break; }
            if (prefix) return Math.max(0, u.optLong("prompt_tokens") + (current.optLong("inputChars") - old.optLong("inputChars")) / 4
                + 4L * (next.length() - before.length()));
        }
        return current.optLong("estimate");
    }

    public static void snapshot(Session s, JSONObject request, int window, String reason) throws Exception {
        JSONObject point = describe(request, window);
        List<JSONObject> rows = requests(s);
        point.put("estimate", estimate(point, rows)).put("type", "context_snapshot").put("reason", reason);
        for (int i = rows.size() - 1; i >= 0; i--) if (rows.get(i).optString("purpose").equals("conversation")) {
            point.put("requestId", rows.get(i).optString("requestId")); break;
        }
        JSONObject previous = context(s);
        if (!reason.equals("request") && !reason.equals("compaction_end") && point.optLong("estimate") == previous.optLong("estimate", -1)
            && point.optInt("window") == previous.optInt("window") && point.optInt("reserve") == previous.optInt("reserve")
            && point.optString("model").equals(previous.optString("model")) && point.optString("inputHashes").equals(previous.optString("inputHashes"))
            && point.optString("toolsHash").equals(previous.optString("toolsHash"))) return;
        s.append(point);
    }

    /** Fold append-only updates by request ID; recovery replaces usage, it never adds another request. */
    public static List<JSONObject> requests(Session s) {
        Map<String, JSONObject> rows = new LinkedHashMap<>();
        try {
            for (JSONObject e : s.log()) {
                String type = e.optString("type"), id = e.optString("requestId");
                if (type.equals("request")) rows.put(id, new JSONObject(e.toString()));
                else if (type.equals("request_update") && rows.containsKey(id)) {
                    JSONObject row = rows.get(id);
                    for (String key : keys(e)) if (!key.equals("type") && !key.equals("id") && !key.equals("timestamp") && !key.equals("parentId")) row.put(key, e.get(key));
                }
            }
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return new ArrayList<>(rows.values());
    }

    public static List<JSONObject> history(Session s) {
        List<JSONObject> points = new ArrayList<>();
        for (JSONObject e : s.log()) if (e.optString("type").equals("context_snapshot") || e.optString("type").equals("compaction") || e.optString("type").equals("model_change")) points.add(e);
        return points;
    }

    public static JSONObject context(Session s) {
        List<JSONObject> history = history(s);
        for (int i = history.size() - 1; i >= 0; i--) if (history.get(i).optString("type").equals("context_snapshot")) return history.get(i);
        return new JSONObject();
    }

    /** Includes legacy usage where it exists, while flagging historical coverage as partial. */
    public static JSONObject totals(Session s) {
        List<JSONObject> rows = requests(s);
        boolean historical = false, seenRequest = false;
        try {
            for (JSONObject e : s.log()) {
                if (e.optString("type").equals("request")) seenRequest = true;
                JSONObject m = e.optString("type").equals("message") ? e.optJSONObject("message") : null;
                JSONObject u = m != null ? m.optJSONObject("usage") : e.optString("type").equals("usage") ? e.optJSONObject("usage") : null;
                if (u != null && u.optString("_requestId").isEmpty()) { rows.add(new JSONObject().put("usage", u).put("status", "completed").put("purpose", "historical").put("model", "Unknown historical model")); historical = true; }
                if (m != null && m.optString("role").equals("assistant") && u == null && !seenRequest && m.optString("_requestId").isEmpty()) historical = true;
            }
            JSONObject total = sum(rows);
            return total.put("historical", historical).put("partial", historical || total.optInt("missingCost") > 0 || total.optInt("pending") > 0);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public static JSONObject sum(List<JSONObject> rows) throws Exception {
        long prompt = 0, completion = 0, cached = 0, writes = 0, reasoning = 0, duration = 0;
        int knownCost = 0, knownTokens = 0, missing = 0, pending = 0, cacheKnown = 0, writeKnown = 0, reasoningKnown = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (JSONObject row : rows) {
            JSONObject u = row.optJSONObject("usage");
            duration += row.optLong("durationMs");
            if (row.optString("status").equals("pending")) pending++;
            if (u == null || u.isNull("cost")) missing++;
            else { cost = cost.add(new BigDecimal(u.get("cost").toString())); knownCost++; }
            if (u == null) continue;
            if (!u.isNull("prompt_tokens") && !u.isNull("completion_tokens")) knownTokens++;
            prompt += u.optLong("prompt_tokens"); completion += u.optLong("completion_tokens");
            JSONObject p = u.optJSONObject("prompt_tokens_details"), c = u.optJSONObject("completion_tokens_details");
            if (p != null && !p.isNull("cached_tokens")) { cached += p.optLong("cached_tokens"); cacheKnown++; }
            if (p != null && !p.isNull("cache_write_tokens")) { writes += p.optLong("cache_write_tokens"); writeKnown++; }
            if (c != null && !c.isNull("reasoning_tokens")) { reasoning += c.optLong("reasoning_tokens"); reasoningKnown++; }
        }
        return new JSONObject().put("prompt_tokens", prompt).put("completion_tokens", completion).put("cost", cost.toPlainString()).put("calls", rows.size())
            .put("knownCost", knownCost).put("knownTokens", knownTokens).put("missingCost", missing).put("pending", pending).put("durationMs", duration)
            .put("cached", cached).put("cacheKnown", cacheKnown).put("cacheWrites", writes).put("writeKnown", writeKnown).put("reasoning", reasoning).put("reasoningKnown", reasoningKnown);
    }

    public static String tokens(long n) {
        if (n < 1000) return String.valueOf(n);
        return String.format(java.util.Locale.US, "%.1fk", n / 1000.0).replace(".0k", "k");
    }

    public static String money(JSONObject totals) {
        if (totals.optInt("knownCost") == 0) return "Cost unavailable";
        return dollars(totals.optString("cost", "0")) + (totals.optBoolean("partial") ? " · partial" : "");
    }

    public static String dollars(String value) {
        try {
            BigDecimal n = new BigDecimal(value);
            if (n.signum() == 0) return "$0.00";
            if (n.compareTo(new BigDecimal("0.0001")) < 0) return "<$0.0001";
            return "$" + n.setScale(n.compareTo(BigDecimal.ONE) < 0 ? 4 : 2, java.math.RoundingMode.HALF_UP).toPlainString();
        } catch (Exception missing) { return "Unavailable"; }
    }
}
