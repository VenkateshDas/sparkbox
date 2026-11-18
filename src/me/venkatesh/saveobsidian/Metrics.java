package me.venkatesh.saveobsidian;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private numeric journal. No prompts, URLs, note bodies, keys, or provider error messages. */
public final class Metrics {
    private Metrics() { }
    private static File file;
    private static volatile boolean enabled;
    private static volatile String problem = "";
    private static long epoch;
    private static long lastPruned;
    private static final long RETENTION = 90L * 86400000;
    private static final long MAX_BYTES = 24L * 1024 * 1024;
    private static final ExecutorService EVENTS = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "sparkbox-statistics"); t.setDaemon(true); return t; });
    // Child threads copy immutable tags. Executor entry points bind their own operation explicitly.
    private static final InheritableThreadLocal<JSONObject> tags = new InheritableThreadLocal<>();
    private static final ThreadLocal<Boolean> externalMeter = ThreadLocal.withInitial(() -> false);
    private static final Set<String> activeRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<String> activeOperations = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static synchronized void configure(File target, boolean collect) { file = target; enabled = collect; epoch++; lastPruned = 0; }
    public static boolean enabled() { return enabled; }
    public static String problem() { return problem; }
    public static synchronized void clear() {
        epoch++;
        if (file != null && file.exists() && !file.delete()) { problem = "Statistics could not be cleared."; return; }
        problem = "";
    }
    public static String id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
    public static JSONObject context() { return copy(tags.get()); }
    private static JSONObject copy(JSONObject o) { try { return o == null ? new JSONObject() : new JSONObject(o.toString()); } catch (Exception e) { return new JSONObject(); } }
    private static JSONObject put(JSONObject o, String key, Object value) { try { o.put(key, value); } catch (Exception ignored) { } return o; }

    public static final class Scope implements AutoCloseable {
        private final JSONObject previous;
        private Scope(JSONObject value) { previous = tags.get(); tags.set(copy(value)); }
        @Override public void close() { if (previous == null) tags.remove(); else tags.set(previous); }
    }
    public static Scope bind(JSONObject context) { return new Scope(context); }
    public static Scope stage(String stage) { return bind(put(context(), "stage", stage)); }
    public static void cards(int drafted, int saved) {
        JSONObject data = context(); if (data.optString("operationId").isEmpty()) return;
        put(data, "cardsDrafted", drafted); put(data, "cardsSaved", saved); emit(data, "operation");
    }
    public static void proposal(String proposalId, String sessionId) {
        JSONObject value = context(); if (value.optString("operationId").isEmpty()) return;
        put(value, "proposalId", proposalId); put(value, "sessionId", sessionId); emit(value, "proposal_link");
    }
    public static void proposalSaved(String proposalId, String path, String before, String after) {
        if (!enabled) return;
        try {
            JSONArray links = read().optJSONArray("proposals");
            for (int i = 0; links != null && i < links.length(); i++) {
                JSONObject link = links.getJSONObject(i); if (!link.optString("proposalId").equals(proposalId)) continue;
                Set<String> old = new HashSet<>(); for (Cards.Card c : Cards.parse(path, before == null ? "" : before)) old.add(c.id);
                int saved = 0; for (Cards.Card c : Cards.parse(path, after)) if (!old.contains(c.id)) saved++;
                JSONObject update = copy(link); put(update, "cardsSaved", saved); emit(update, "card_receipt");
                event("scout_write_saved", proposalId); return;
            }
        } catch (Exception ignored) { /* Observation never changes the approved write's outcome. */ }
    }
    public static final class Operation implements AutoCloseable {
        private final Scope scope;
        private final JSONObject state;
        private final long generation;
        private String outcome = "error";
        private Operation(String workflow, String key, String stage) {
            synchronized (Metrics.class) { generation = epoch; }
            state = put(put(put(new JSONObject(), "operationId", id(workflow + ":" + key)), "workflow", workflow), "startedAt", System.currentTimeMillis());
            if (!context().optString("sessionId").isEmpty()) put(state, "sessionId", context().optString("sessionId"));
            scope = bind(put(put(context(), "operationId", state.optString("operationId")), "workflow", workflow));
            tags.set(put(context(), "stage", stage));
            emit(put(copy(state), "status", "pending"), "operation");
            activeOperations.add(state.optString("operationId"));
        }
        public void done() { outcome = "completed"; }
        public void outcome(String value) { outcome = value; }
        public void cards(int drafted, int saved) { put(state, "cardsDrafted", drafted); put(state, "cardsSaved", saved); }
        public void session(String id) { put(state, "sessionId", id); tags.set(put(context(), "sessionId", id)); emit(put(copy(state), "status", "pending"), "operation"); }
        public void note(String path) { put(state, "noteId", Metrics.id(path)); }
        @Override public void close() {
            synchronized (Metrics.class) { if (generation == epoch) emit(put(put(copy(state), "status", outcome), "endedAt", System.currentTimeMillis()), "operation"); }
            activeOperations.remove(state.optString("operationId"));
            scope.close();
        }
    }
    public static Operation operation(String workflow, String key, String stage) { return new Operation(workflow, key, stage); }
    public static void event(String feature, String identity) {
        if (!enabled) return;
        final long version;
        synchronized (Metrics.class) { version = epoch; }
        JSONObject event = put(put(new JSONObject(), "feature", feature), "at", System.currentTimeMillis());
        put(event, "eventId", identity == null ? UUID.randomUUID().toString() : id(feature + ":" + identity));
        EVENTS.execute(() -> { synchronized (Metrics.class) { if (version == epoch) emit(event, "event"); } });
    }
    /** Worker-only receipt; durable before the pending card operation is removed. */
    public static void reviewSaved(String identity, long at) {
        JSONObject e = put(put(put(new JSONObject(), "feature", "review_saved"), "at", at), "eventId", id("review:" + identity));
        emit(e, "event");
    }
    /** Scout remains the owner of these request IDs; only whitelisted numeric metadata is mirrored. */
    public static void scout(JSONObject event, String sessionId) {
        boolean start = event.optString("type").equals("request");
        JSONObject out = put(start ? context() : new JSONObject(), "sessionId", sessionId);
        if (start) put(out, "workflow", "Scout interaction");
        for (String k : new String[]{"requestId", "timestamp", "model", "actualModel", "run", "purpose", "status", "attempt", "continuation", "generationId", "provider", "durationMs", "finish_reason", "recovered"})
            if (event.has(k)) put(out, k, event.opt(k));
        if (event.has("purpose")) put(out, "stage", event.optString("purpose"));
        if (event.optJSONObject("usage") != null) put(out, "usage", usage(event.optJSONObject("usage")));
        emit(out, event.optString("type"));
        if (start) activeRequests.add(event.optString("requestId"));
        else if (event.has("status") && !event.optString("status").equals("pending")) activeRequests.remove(event.optString("requestId"));
    }
    public static void forgetSession(String sessionId) { emit(put(new JSONObject(), "sessionId", sessionId), "delete_session"); }
    public static JSONObject usage(JSONObject raw) {
        JSONObject out = new JSONObject();
        for (String k : new String[]{"prompt_tokens", "completion_tokens", "total_tokens", "cost"}) if (!raw.isNull(k)) {
            try { java.math.BigDecimal n = new java.math.BigDecimal(raw.get(k).toString()); if (n.signum() >= 0) out.put(k, n); } catch (Exception ignored) { }
        }
        for (String k : new String[]{"prompt_tokens_details", "completion_tokens_details"}) {
            JSONObject nested = raw.optJSONObject(k), cleaned = new JSONObject();
            if (nested == null) continue;
            for (String n : new String[]{"cached_tokens", "cache_write_tokens", "reasoning_tokens", "audio_tokens", "image_tokens"})
                if (!nested.isNull(n) && nested.optLong(n, -1) >= 0) put(cleaned, n, nested.optLong(n));
            put(out, k, cleaned);
        }
        return out;
    }
    public interface Call { JSONObject run(Consumer<JSONObject> metadata) throws Exception; }
    public static JSONObject measure(JSONObject request, String fallbackStage, int attempt, Call call) throws Exception {
        if (externalMeter.get() || !enabled) return call.run(m -> { });
        JSONObject start = context();
        put(start, "requestId", UUID.randomUUID().toString());
        put(start, "model", request.optString("model")); put(start, "attempt", attempt);
        if (start.optString("workflow").isEmpty()) put(start, "workflow", "Unattributed AI");
        if (start.optString("stage").isEmpty()) put(start, "stage", fallbackStage);
        put(start, "status", "pending"); activeRequests.add(start.optString("requestId")); emit(start, "request");
        final JSONObject observed = new JSONObject();
        Consumer<JSONObject> metadata = m -> {
            synchronized (observed) {
                String before = observed.toString();
                for (String k : new String[]{"id", "model", "provider", "finish_reason"}) if (!m.isNull(k)) put(observed, k.equals("id") ? "generationId" : k.equals("model") ? "actualModel" : k, m.opt(k));
                if (m.optJSONObject("usage") != null) put(observed, "usage", usage(m.optJSONObject("usage")));
                if (!before.equals(observed.toString())) {
                    JSONObject update = copy(observed); put(update, "requestId", start.optString("requestId")); emit(update, "request_update");
                }
            }
        };
        long began = System.nanoTime(); String status = "error";
        try {
            JSONObject response = call.run(metadata); metadata.accept(response);
            status = "cancelled".equals(response.optString("finish_reason")) ? "cancelled" : "completed";
            return response;
        } finally {
            synchronized (observed) {
                JSONObject end = copy(observed); put(end, "requestId", start.optString("requestId"));
                put(end, "status", status); put(end, "durationMs", (System.nanoTime() - began) / 1000000); emit(end, "request_update");
                activeRequests.remove(start.optString("requestId"));
            }
        }
    }
    public static AutoCloseable suppress() { boolean prior = externalMeter.get(); externalMeter.set(true); return () -> externalMeter.set(prior); }
    private static synchronized void emit(JSONObject data, String type) {
        if ((!enabled && !type.equals("delete_session")) || file == null) return;
        try {
            if (file.length() > MAX_BYTES && !type.equals("delete_session")) { problem = "Statistics storage reached its limit; newer coverage is incomplete. Export or clear statistics."; return; }
            JSONObject e = copy(data); put(e, "type", type); if (!e.has("timestamp")) put(e, "timestamp", Instant.now().toString());
            File dir = file.getParentFile(); if (!dir.exists() && !dir.mkdirs()) throw new IOException();
            try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
                long length = out.length(); if (length > 0) { out.seek(length - 1); if (out.read() != '\n') { out.seek(length); out.write('\n'); } }
                out.seek(out.length()); out.write((e + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception failure) { problem = "Some local statistics could not be saved; coverage is partial."; }
    }
    /** Worker-only read. Original request timestamps survive updates and charge recovery. */
    public static synchronized JSONObject read() {
        List<JSONObject> events = new ArrayList<>(); int malformed = 0;
        if (file != null && file.exists()) try (BufferedReader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null;) try { events.add(new JSONObject(line)); } catch (Exception invalid) { malformed++; }
        } catch (Exception unreadable) { problem = "Local statistics could not be read."; }
        JSONObject data = reduce(events, System.currentTimeMillis() - RETENTION);
        JSONArray requests = data.optJSONArray("requests"), operations = data.optJSONArray("operations");
        for (int i = 0; requests != null && i < requests.length(); i++) {
            JSONObject r = requests.optJSONObject(i);
            if (r.optString("status").equals("pending") && !activeRequests.contains(r.optString("requestId"))) put(r, "status", "interrupted");
        }
        for (int i = 0; operations != null && i < operations.length(); i++) {
            JSONObject o = operations.optJSONObject(i);
            if (o.optString("status").equals("pending") && !activeOperations.contains(o.optString("operationId"))) put(o, "status", "interrupted");
        }
        put(data, "malformed", malformed); put(data, "problem", problem); return data;
    }
    public static JSONObject reduce(List<JSONObject> events, long since) {
        Map<String, JSONObject> requests = new LinkedHashMap<>(), operations = new LinkedHashMap<>(), actions = new LinkedHashMap<>(), proposals = new LinkedHashMap<>(), receipts = new LinkedHashMap<>();
        Set<String> deleted = new HashSet<>(); long coverage = Long.MAX_VALUE;
        for (JSONObject e : events) {
            String type = e.optString("type"), id = e.optString("requestId");
            long at; try { at = Instant.parse(e.optString("timestamp")).toEpochMilli(); } catch (Exception unknown) { continue; }
            if (type.equals("delete_session")) { deleted.add(e.optString("sessionId")); continue; }
            if (at < since) continue;
            coverage = Math.min(coverage, at);
            if (type.equals("request")) requests.put(id, copy(e));
            else if (type.equals("request_update") && requests.containsKey(id)) {
                JSONObject row = requests.get(id);
                for (Iterator<String> keys = e.keys(); keys.hasNext();) { String k = keys.next(); if (!k.equals("timestamp") && !k.equals("type")) put(row, k, e.opt(k)); }
            } else if (type.equals("operation")) {
                String opId = e.optString("operationId"); JSONObject old = operations.get(opId), next = copy(e);
                if (old != null) {
                    JSONObject merged = copy(old);
                    for (Iterator<String> keys = next.keys(); keys.hasNext();) { String k = keys.next(); put(merged, k, next.opt(k)); }
                    if (old.has("startedAt")) put(merged, "startedAt", next.has("startedAt") ? Math.min(old.optLong("startedAt"), next.optLong("startedAt")) : old.optLong("startedAt"));
                    next = merged;
                }
                operations.put(opId, next);
            } else if (type.equals("event")) actions.put(e.optString("eventId"), copy(e));
            else if (type.equals("proposal_link")) proposals.put(e.optString("proposalId"), copy(e));
            else if (type.equals("card_receipt")) receipts.put(e.optString("proposalId"), copy(e));
        }
        requests.values().removeIf(r -> deleted.contains(r.optString("sessionId")));
        operations.values().removeIf(o -> deleted.contains(o.optString("sessionId")));
        proposals.values().removeIf(o -> deleted.contains(o.optString("sessionId")));
        receipts.values().removeIf(o -> deleted.contains(o.optString("sessionId")));
        Map<String, Integer> savedByOperation = new HashMap<>();
        for (JSONObject receipt : receipts.values()) savedByOperation.merge(receipt.optString("operationId"), receipt.optInt("cardsSaved"), Integer::sum);
        for (Map.Entry<String, Integer> saved : savedByOperation.entrySet()) if (operations.containsKey(saved.getKey())) {
            JSONObject op = operations.get(saved.getKey()); put(op, "cardsSaved", saved.getValue()); put(op, "cardsDrafted", Math.max(op.optInt("cardsDrafted"), saved.getValue()));
            if (op.optString("status").equals("awaiting_approval")) put(op, "status", "saved");
        }
        JSONObject out = new JSONObject(); put(out, "requests", new JSONArray(requests.values())); put(out, "operations", new JSONArray(operations.values()));
        put(out, "events", new JSONArray(actions.values())); put(out, "proposals", new JSONArray(proposals.values())); put(out, "receipts", new JSONArray(receipts.values())); put(out, "deletedSessions", new JSONArray(deleted)); put(out, "coverageStart", coverage == Long.MAX_VALUE ? 0 : coverage); return out;
    }
    /** Retain only 90 days on explicit dashboard maintenance; atomically replace the local journal. */
    public static synchronized void prune() {
        if (file == null || !file.exists()) return;
        if (System.currentTimeMillis() - lastPruned < 86400000) return;
        JSONObject state = read(); File next = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(next)) {
            for (String key : new String[]{"operations", "requests", "events", "proposals", "receipts"}) {
                JSONArray rows = state.optJSONArray(key);
                for (int i = 0; rows != null && i < rows.length(); i++) out.write((rows.getJSONObject(i) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            JSONArray deleted = state.optJSONArray("deletedSessions");
            for (int i = 0; deleted != null && i < deleted.length(); i++) out.write((put(put(put(new JSONObject(), "type", "delete_session"), "sessionId", deleted.optString(i)), "timestamp", Instant.now().toString()) + "\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception failed) { problem = "Statistics retention cleanup failed."; return; }
        try { Files.move(next.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); lastPruned = System.currentTimeMillis(); }
        catch (Exception failed) { problem = "Statistics retention cleanup failed."; }
    }
}
