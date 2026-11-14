package me.venkatesh.saveobsidian;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** The streamed OpenRouter chat completion (SSE), pure Java so the JVM checks and scripts/live-agent.sh can use it. Model.stream delegates here. */
public final class OpenRouter {
    private OpenRouter() { }

    /** The start of the error body (": message"), so a context-length refusal can be recognised and a 400 explained. */
    private static String detail(HttpURLConnection connection) {
        try (java.io.InputStream es = connection.getErrorStream()) {
            if (es == null) return "";
            java.io.Reader in = new java.io.InputStreamReader(es, StandardCharsets.UTF_8);
            char[] buf = new char[600];
            int n = in.read(buf);
            return n <= 0 ? "" : ": " + new String(buf, 0, n).replaceAll("\\s+", " ");
        } catch (IOException none) { return ""; }
    }

    /** Streamed chat completion: calls live with the text so far, returns the assembled message {content, tool_calls?}. */
    /** cancel is this answer's own switch: stopping it ends the stream (what arrived so far is the reply) and never touches background work. */
    public static JSONObject stream(String key, JSONObject request, java.util.function.Consumer<String> live, Cancel cancel) throws Exception {
        return stream(key, request, live, cancel, metadata -> { });
    }

    /** Metadata is delivered independently of text, including before an interrupted stream. */
    public static JSONObject stream(String key, JSONObject request, java.util.function.Consumer<String> live, Cancel cancel,
                                    java.util.function.Consumer<JSONObject> metadata) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();
        cancel.attach(connection);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(90000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(request.put("stream", true).toString().getBytes(StandardCharsets.UTF_8)); }
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("Smart tags paused: OpenRouter answered " + code + (code == 401 ? " (check the key)" : code == 402 ? " (out of credit)" : "") + detail(connection));
            StringBuilder text = new StringBuilder();
            JSONArray calls = new JSONArray();
            String finish = "";
            JSONObject usage = null;
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (cancel.stopped()) { calls = new JSONArray(); break; }
                    if (!line.startsWith("data:")) continue; // blank lines and ": OPENROUTER PROCESSING" keep-alives
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) break;
                    JSONObject chunk = new JSONObject(data);
                    metadata.accept(chunk);
                    if (chunk.has("error")) throw new IOException("Smart tags paused: " + chunk.getJSONObject("error").optString("message", "provider error"));
                    if (chunk.optJSONObject("usage") != null) usage = chunk.getJSONObject("usage"); // OpenRouter sends usage in the last chunk by itself
                    JSONArray choices = chunk.optJSONArray("choices");
                    if (choices != null && choices.length() > 0 && !choices.getJSONObject(0).isNull("finish_reason")) finish = choices.getJSONObject(0).optString("finish_reason", "");
                    JSONObject delta = choices == null || choices.length() == 0 ? null : choices.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) continue;
                    if (!delta.isNull("content") && delta.has("content")) { text.append(delta.getString("content")); if (live != null) live.accept(text.toString()); }
                    JSONArray parts = delta.optJSONArray("tool_calls");
                    for (int i = 0; parts != null && i < parts.length(); i++) {
                        JSONObject part = parts.getJSONObject(i), fn = part.optJSONObject("function");
                        int at = part.optInt("index", i);
                        while (calls.length() <= at) calls.put(new JSONObject().put("id", "").put("type", "function").put("function", new JSONObject().put("name", "").put("arguments", "")));
                        JSONObject slot = calls.getJSONObject(at), slotFn = slot.getJSONObject("function");
                        if (part.optString("id", "").length() > 0) slot.put("id", part.getString("id"));
                        if (fn != null) {
                            if (!fn.isNull("name") && fn.has("name")) slotFn.put("name", slotFn.getString("name") + fn.getString("name"));
                            if (!fn.isNull("arguments") && fn.has("arguments")) slotFn.put("arguments", slotFn.getString("arguments") + fn.getString("arguments"));
                        }
                    }
                }
            }
            JSONObject message = new JSONObject().put("content", text.toString());
            if (calls.length() > 0) message.put("tool_calls", calls);
            if (cancel.stopped()) message.put("finish_reason", "cancelled");
            else if (!finish.isEmpty()) message.put("finish_reason", finish);
            if (usage != null) message.put("usage", usage);
            return message;
        } catch (IOException dropped) {
            if (cancel.stopped()) return new JSONObject().put("content", "").put("finish_reason", "cancelled"); // our own disconnect
            throw dropped;
        } finally { cancel.detach(connection); connection.disconnect(); }
    }


    /**
     * Thinking level on a request ("off", "low", "medium", "high"; anything else leaves the provider's default). A reasoning model otherwise spends the
     * token budget on hidden thinking and can leave the answer empty or cut off; models without reasoning ignore the field. One place, so every request
     * (turns and summaries) follows the same setting.
     */
    public static void thinking(JSONObject request, String level) {
        try {
            if ("off".equals(level)) request.put("reasoning", new JSONObject().put("enabled", false));
            else if ("low".equals(level) || "medium".equals(level) || "high".equals(level)) request.put("reasoning", new JSONObject().put("effort", level));
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
