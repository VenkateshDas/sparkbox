package me.venkatesh.saveobsidian;

import android.content.Context;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android/network glue for Scout metrics. Model metadata and recovery never block the chat UI. */
final class ScoutUsage {
    private ScoutUsage() { }
    private static final Set<String> loading = new HashSet<>();

    static int window(Context c, String model) {
        try { return new JSONObject(c.getSharedPreferences("scout_usage", 0).getString(model, "{}")).optInt("context_length"); }
        catch (Exception missing) { return 0; }
    }

    static void model(Context c, String model, Runnable changed) {
        synchronized (loading) { if (!loading.add(model)) return; }
        new Thread(() -> {
            try {
                JSONObject saved = new JSONObject(c.getSharedPreferences("scout_usage", 0).getString(model, "{}"));
                if (System.currentTimeMillis() - saved.optLong("fetched") < 24 * 60 * 60 * 1000L) return;
                JSONObject response = get("models?q=" + java.net.URLEncoder.encode(model, "UTF-8"), "");
                JSONArray data = response.optJSONArray("data");
                for (int i = 0; data != null && i < data.length(); i++) {
                    JSONObject m = data.getJSONObject(i);
                    if (!model.equals(m.optString("id"))) continue;
                    int limit = m.optInt("context_length");
                    if (limit <= 0) break;
                    c.getSharedPreferences("scout_usage", 0).edit().putString(model, new JSONObject().put("context_length", limit)
                        .put("fetched", System.currentTimeMillis()).toString()).apply();
                    changed.run(); break;
                }
            } catch (Exception unavailable) { /* keep last known metadata; an unknown limit is never displayed as 128k */ }
            finally { synchronized (loading) { loading.remove(model); } }
        }, "scout-model-metadata").start();
    }

    static Agent.Provider provider(Context c, String key, Session session, String run, String purpose, Runnable changed) {
        final String level = Scout.thinking(c);
        return new Agent.Provider() {
            @Override public JSONObject stream(JSONObject request, java.util.function.Consumer<String> live, Cancel cancel) throws Exception {
                return streamAttempt(request, live, cancel, 0, false);
            }
            @Override public JSONObject streamAttempt(JSONObject request, java.util.function.Consumer<String> live, Cancel cancel, int attempt, boolean continuation) throws Exception {
                OpenRouter.thinking(request, level);
                return SessionUsage.measure(session, run, purpose, request, window(c, request.optString("model")), update -> {
                    if (purpose.equals("conversation")) {
                        try {
                            if (update.optString("type").equals("request")) SessionUsage.snapshot(session, request, window(c, request.optString("model")), "request");
                            else if (update.optJSONObject("usage") != null && !update.getJSONObject("usage").isNull("prompt_tokens")) {
                                JSONObject point = SessionUsage.describe(request, window(c, request.optString("model")))
                                    .put("type", "context_snapshot").put("reason", "reported_input").put("reported", true)
                                    .put("requestId", update.optString("requestId")).put("estimate", update.getJSONObject("usage").optLong("prompt_tokens"));
                                session.append(point);
                            }
                        } catch (Exception ignored) { }
                    }
                    changed.run();
                }, attempt, continuation, metadata -> OpenRouter.stream(key, request, live, cancel, metadata));
            }
        };
    }

    /** Non-streamed naming keeps Model.completeLean's existing reasoning fallbacks and response parsing. */
    static Agent.Provider naming(String key, Session session, String run, Runnable changed) {
        return (request, live, cancel) -> new JSONObject(Model.post("chat/completions", key, request, 120000, (attempt, exchange) -> {
            JSONObject whole = SessionUsage.measure(session, run, "naming", request, 0, update -> changed.run(), attempt, false, metadata -> {
                JSONObject response = new JSONObject(exchange.call());
                metadata.accept(response);
                return response;
            });
            return whole.toString();
        }));
    }

    /** Only missing charges with a known generation ID; updating the same record cannot double-count it. */
    static void reconcile(Context c, Session session, Runnable changed) {
        String key = Model.key(c);
        if (key.isEmpty()) return;
        int count = 0;
        for (JSONObject row : SessionUsage.requests(session)) {
            JSONObject usage = row.optJSONObject("usage");
            if (row.optString("generationId").isEmpty() || usage != null && !usage.isNull("cost") || row.optString("status").equals("pending") && ScoutRuns.sessionRunning(session)) continue;
            if (++count > 5) break; // a bounded refresh, never a background polling loop
            try {
                JSONObject d = get("generation?id=" + java.net.URLEncoder.encode(row.optString("generationId"), "UTF-8"), key).getJSONObject("data");
                JSONObject u = usage == null ? new JSONObject() : new JSONObject(usage.toString());
                if (d.isNull("total_cost")) continue;
                u.put("cost", d.get("total_cost"));
                for (String[] pair : new String[][]{{"native_tokens_prompt", "prompt_tokens"}, {"native_tokens_completion", "completion_tokens"}})
                    if (!d.isNull(pair[0])) u.put(pair[1], d.get(pair[0]));
                session.append(new JSONObject().put("type", "request_update").put("requestId", row.optString("requestId")).put("usage", u)
                    .put("recovered", true).put("provider", d.optString("provider_name")).put("actualModel", d.optString("model", row.optString("model")))
                    .put("finish_reason", d.optString("finish_reason")).put("status", d.optBoolean("cancelled") ? "cancelled" : "reconciled"));
            } catch (Exception unavailable) { /* leave the original missing value visible */ }
        }
        changed.run();
    }

    private static JSONObject get(String path, String key) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/" + path).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        if (!key.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + key);
        try (java.io.InputStream in = connection.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }
}
