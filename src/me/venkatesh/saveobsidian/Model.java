package me.venkatesh.saveobsidian;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** OpenRouter call plus the API key, sealed with an Android Keystore key. Verified on device, not in JVM checks. */
final class Model {
    static final String DEFAULT = "scaledown/summarize"; // no JSON mode: the prompt asks for JSON and Enrich.clean enforces the shape
    static final String VISION = "google/gemini-2.5-flash-lite", TRANSCRIBE = "openai/whisper-large-v3";
    private static final String ALIAS = "sparkbox-ai-key";

    /** {type, tags, tldr, title}, or null when the reply is unusable. IOException (network, key, quota) means stop for now. */
    static String[] enrich(String key, String model, String title, String url, String why, String ideas, java.util.function.Consumer<String> pageSink) throws Exception {
        String full = "";
        try { full = Fetch.content(url, "", false); } catch (Exception ignored) { } // title and URL still classify; a video is tagged from its captions and description, never transcribed here
        String page = full.length() > Enrich.PROMPT_CHARS ? full.substring(0, Enrich.PROMPT_CHARS) : full;
        boolean thin = full.replaceAll("(?m)^(Channel|Length):.*$", "").trim().length() < 80; // a video with no description or captions has nothing to summarise
        if (pageSink != null && !full.isEmpty()) pageSink.accept(full); // the whole page, so Ask and later processing need no refetch
        JSONObject request = new JSONObject().put("model", model).put("max_tokens", 300)
            .put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", Enrich.SYSTEM + Enrich.INSTRUCTIONS))
                .put(new JSONObject().put("role", "user").put("content", "Title: " + title + "\nURL: " + Capture.identity(url) + "\nPage text:\n" + (thin ? "(nothing could be read from the page: judge only from the title and URL, and leave tldr empty)" : page) + Enrich.notes(why, ideas)))); // query strings often carry tokens
        String[] r = parse(post("chat/completions", key, request));
        if (r != null && thin) { r[1] = ""; r[2] = ""; } // with no text to read, tags and a summary would be guesses
        return r;
    }

    /** {type, tags, tldr} for a typed or spoken note. */
    static String[] enrichNote(String key, String model, String text, String why, String ideas) throws Exception {
        return parse(post("chat/completions", key, new JSONObject().put("model", model).put("max_tokens", 300).put("messages", new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", Enrich.NOTE + Enrich.INSTRUCTIONS))
            .put(new JSONObject().put("role", "user").put("content", (text.length() > 4000 ? text.substring(0, 4000) : text) + Enrich.notes(why, ideas))))));
    }

    /** Photo or PDF description. PDFs are read by OpenRouter's free parser first, so any model can summarise them. */
    static String[] describe(String key, String name, boolean pdf, byte[] data) throws Exception {
        String base64 = Base64.encodeToString(data, Base64.NO_WRAP);
        JSONObject part = pdf
            ? new JSONObject().put("type", "file").put("file", new JSONObject().put("filename", name).put("file_data", "data:application/pdf;base64," + base64))
            : new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + base64));
        JSONObject request = new JSONObject().put("model", VISION).put("max_tokens", 300).put("messages", new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", Enrich.PICTURE))
            .put(new JSONObject().put("role", "user").put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", "File name: " + name)).put(part))));
        if (pdf) request.put("plugins", new JSONArray().put(new JSONObject().put("id", "file-parser").put("pdf", new JSONObject().put("engine", "cloudflare-ai"))));
        return parse(post("chat/completions", key, request));
    }

    /** Plain text of a spoken clip. format is the file extension OpenRouter accepts (m4a, mp3, ogg...). */
    static String transcribe(String key, byte[] audio, String format) throws Exception {
        return new JSONObject(post("audio/transcriptions", key, new JSONObject().put("model", TRANSCRIBE)
            .put("input_audio", new JSONObject().put("data", Base64.encodeToString(audio, Base64.NO_WRAP)).put("format", format)), 300000)).optString("text").trim();
    }

    /** The model's JSON reply forced into the safe shape, or null when unusable. */
    private static String[] parse(String reply) {
        try {
            String content = new JSONObject(reply).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            JSONObject out = JsonFix.object(content);
            if (out == null) return null;
            List<String> tags = new ArrayList<>();
            JSONArray array = out.optJSONArray("tags");
            for (int i = 0; array != null && i < array.length(); i++) tags.add(array.optString(i));
            String[] r = Enrich.clean(out.optString("type"), tags, out.optString("tldr"));
            String title = Capture.clean(out.optString("title"));
            return new String[]{r[0], r[1], r[2], title.length() > 80 ? title.substring(0, 80).trim() : title};
        } catch (Exception unusable) { return null; }
    }

    /** The provider refused this particular input; retrying the same bytes will not help. */
    static final class Rejected extends IOException { Rejected(String m) { super(m); } }

    /** One float[] per input text, in order. IOException (network, key, quota) means stop for now. */
    static List<float[]> embed(String key, List<String> texts) throws Exception {
        String reply = post("embeddings", key, new JSONObject().put("model", Index.MODEL).put("dimensions", Index.DIMS).put("input", new JSONArray(texts)));
        JSONArray data = new JSONObject(reply).getJSONArray("data");
        float[][] out = new float[texts.size()][];
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            JSONArray numbers = item.getJSONArray("embedding");
            float[] v = new float[numbers.length()];
            for (int j = 0; j < v.length; j++) v[j] = (float) numbers.getDouble(j);
            out[item.optInt("index", i)] = v;
        }
        for (float[] v : out) if (v == null || v.length != Index.DIMS) throw new IOException("Smart tags paused: the embedding reply was incomplete");
        return java.util.Arrays.asList(out);
    }

    /** Streamed chat completion: calls live with the text so far, returns the assembled message {content, tool_calls?}. */
    /** cancel is this answer's own switch: stopping it ends the stream (what arrived so far is the reply) and never touches background work. */
    static JSONObject stream(String key, JSONObject request, java.util.function.Consumer<String> live, Cancel cancel) throws Exception {
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
            if (code != 200) throw new IOException("Smart tags paused: OpenRouter answered " + code + (code == 401 ? " (check the key)" : code == 402 ? " (out of credit)" : ""));
            StringBuilder text = new StringBuilder();
            JSONArray calls = new JSONArray();
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (cancel.stopped()) { calls = new JSONArray(); break; }
                    if (!line.startsWith("data:")) continue; // blank lines and ": OPENROUTER PROCESSING" keep-alives
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) break;
                    JSONObject chunk = new JSONObject(data);
                    if (chunk.has("error")) throw new IOException("Smart tags paused: " + chunk.getJSONObject("error").optString("message", "provider error"));
                    JSONArray choices = chunk.optJSONArray("choices");
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
            return message;
        } catch (IOException dropped) {
            if (cancel.stopped()) return new JSONObject().put("content", ""); // our own disconnect
            throw dropped;
        } finally { cancel.detach(connection); connection.disconnect(); }
    }

    /** One non-streamed completion; the reply text. */
    static String complete(String key, String model, String system, String user, int maxTokens) throws Exception { return complete(key, model, system, user, maxTokens, false); }

    /** noReasoning asks the provider to skip hidden thinking, which a reasoning model would otherwise spend the whole token budget on. Models without it ignore the field. */
    static String complete(String key, String model, String system, String user, int maxTokens, boolean noReasoning) throws Exception { return complete(key, model, system, user, maxTokens, noReasoning, false); }

    /** json asks the provider to guarantee a JSON object. A provider that does not know the field answers 400 (Rejected). */
    static String complete(String key, String model, String system, String user, int maxTokens, boolean noReasoning, boolean json) throws Exception { return complete(key, model, system, user, maxTokens, noReasoning, json, null, null); }

    /** schema (with its name) asks for structured output: the provider is held to that JSON Schema, and only providers that support it are used (require_parameters). */
    static String complete(String key, String model, String system, String user, int maxTokens, boolean noReasoning, boolean json, String schemaName, JSONObject schema) throws Exception {
        JSONObject request = new JSONObject().put("model", model).put("max_tokens", maxTokens).put("messages", new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", system)).put(new JSONObject().put("role", "user").put("content", user)));
        if (noReasoning) request.put("reasoning", "low".equals(REASONING.get(model)) ? new JSONObject().put("effort", "low") : new JSONObject().put("enabled", false));
        if (schema != null) {
            request.put("response_format", new JSONObject().put("type", "json_schema").put("json_schema", new JSONObject().put("name", schemaName).put("strict", true).put("schema", schema)));
            request.put("provider", new JSONObject().put("require_parameters", true));
        } else {
            if (json) request.put("response_format", new JSONObject().put("type", "json_object"));
        }
        long began = System.currentTimeMillis();
        String body = post("chat/completions", key, request, schema != null || json ? 90000 : 120000);
        JSONObject whole = new JSONObject(body);
        JSONObject usage = whole.optJSONObject("usage");
        JSONObject details = usage == null ? null : usage.optJSONObject("completion_tokens_details");
        android.util.Log.i("Sparkbox", "model " + model + " " + (System.currentTimeMillis() - began) + " ms, prompt " + (usage == null ? "?" : usage.optInt("prompt_tokens")) + " tokens, answer "
            + (usage == null ? "?" : usage.optInt("completion_tokens")) + " tokens (reasoning " + (details == null ? "?" : details.optInt("reasoning_tokens")) + "), max " + maxTokens
            + ", provider " + whole.optString("provider", "?") + ", structured " + (schema != null) + ", json " + json + ", lean " + noReasoning);
        JSONObject choice = whole.getJSONArray("choices").getJSONObject(0);
        JSONObject message = choice.getJSONObject("message");
        String content = message.isNull("content") ? "" : message.optString("content", "");
        if (content.trim().isEmpty()) {
            // a thinking model can spend the whole budget on hidden reasoning and leave the answer empty; the JSON is sometimes in the reasoning text instead
            String thought = message.isNull("reasoning") ? "" : message.optString("reasoning", "");
            if (thought.isEmpty() && !message.isNull("reasoning_content")) thought = message.optString("reasoning_content", "");
            LAST_EMPTY.set("finish: " + choice.optString("finish_reason", "?") + (thought.isEmpty() ? ", no reasoning text" : ", " + thought.length() + " chars of reasoning"));
            if (schema != null || json) { int open = thought.indexOf('{'); if (open >= 0 && JsonFix.object(thought) != null) return thought.substring(open); }
        }
        return content;
    }

    /** Why the last reply came back empty (finish reason, reasoning size), for the error shown to the owner. */
    private static final ThreadLocal<String> LAST_EMPTY = new ThreadLocal<>();

    /** Per model: "low" once it has answered that its thinking cannot be switched off (GLM 5.3 Flash), so the app asks for the least thinking instead. */
    private static final java.util.Map<String, String> REASONING = java.util.Collections.synchronizedMap(new java.util.HashMap<String, String>());

    private static boolean mandatoryThinking(Rejected r) { return String.valueOf(r.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("mandatory"); }

    private static final java.util.Set<String> NO_REASONING_FIELD = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final java.util.Set<String> NO_JSON_MODE = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** Asks for no hidden thinking so a reasoning model keeps its token budget for the answer; a model that refuses that field is asked again without it, and remembered. */
    static String completeLean(String key, String model, String system, String user, int maxTokens) throws Exception {
        if (NO_REASONING_FIELD.contains(model)) return complete(key, model, system, user, maxTokens, false);
        try { return complete(key, model, system, user, maxTokens, true); }
        catch (Rejected refused) {
            if (mandatoryThinking(refused) && !"low".equals(REASONING.get(model))) { REASONING.put(model, "low"); return complete(key, model, system, user, maxTokens, true); }
            NO_REASONING_FIELD.add(model); return complete(key, model, system, user, maxTokens, false);
        }
    }

    /**
     * A JSON object from the model, however it arrives: JSON mode and "no thinking" are asked for first and dropped (and remembered per model) when the
     * provider refuses them; a reply that is empty, fenced, cut off or has stray commas is repaired; one that is still unusable is asked for once more.
     * Throws IOException when nothing usable came back.
     */
    static JSONObject completeJson(String key, String model, String system, String user, int maxTokens) throws Exception { return completeJson(key, model, system, user, maxTokens, null, null, null); }

    /**
     * The ladder, best method first: (1) structured output, the reply held to a JSON Schema; if the model or its providers refuse it, (2) JSON mode, then (3) plain text, each remembered per model;
     * a reply that is empty or unreadable is repaired locally, asked for once more with a bigger budget, and finally asked of the fallback model when one is given.
     */
    static JSONObject completeJson(String key, String model, String system, String user, int maxTokens, String schemaName, JSONObject schema, String fallbackModel) throws Exception {
        String reply = "";
        String[] models = fallbackModel == null || fallbackModel.equals(model) ? new String[]{model, model} : new String[]{model, model, fallbackModel};
        for (int attempt = 0; attempt < models.length; attempt++) {
            String ask = attempt == 0 ? user : user + "\n\nYour last reply could not be read. Reply with one complete JSON object and nothing else.";
            LAST_EMPTY.remove();
            reply = chatJson(key, models[attempt], system, ask, attempt == 0 ? maxTokens : Math.max(maxTokens * 2, maxTokens + 1500), schemaName, schema);
            JSONObject o = JsonFix.object(reply);
            if (o != null) return o;
        }
        String shown = reply.trim().replaceAll("\\s+", " ");
        throw new IOException("The model did not return usable JSON" + (shown.isEmpty() ? " (empty reply" + (LAST_EMPTY.get() == null ? "" : "; " + LAST_EMPTY.get()) + ")" : ": " + (shown.length() > 120 ? shown.substring(0, 120) : shown)));
    }

    private static final java.util.Set<String> NO_SCHEMA = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static String chatJson(String key, String model, String system, String user, int maxTokens, String schemaName, JSONObject schema) throws Exception {
        boolean structured = schema != null && !NO_SCHEMA.contains(model), json = !NO_JSON_MODE.contains(model), lean = !NO_REASONING_FIELD.contains(model);
        boolean droppedSchema = false, droppedJson = false, droppedLean = false;
        for (;;) {
            try {
                String reply = complete(key, model, system, user, maxTokens, lean, json, structured ? schemaName : null, structured ? schema : null);
                if (droppedSchema) NO_SCHEMA.add(model); // only remembered once a plainer request worked, so a refused input is not blamed on the model
                if (droppedJson) NO_JSON_MODE.add(model);
                if (droppedLean) NO_REASONING_FIELD.add(model);
                return reply;
            } catch (Rejected refused) {
                android.util.Log.i("Sparkbox", "model " + model + " refused (structured " + structured + ", json " + json + ", lean " + lean + "): " + refused.getMessage());
                if (lean && mandatoryThinking(refused) && !"low".equals(REASONING.get(model))) { REASONING.put(model, "low"); continue; } // not the format's fault: this model always thinks, so ask for the least
                if (structured) { structured = false; droppedSchema = true; }
                else if (json) { json = false; droppedJson = true; }
                else if (lean) { lean = false; droppedLean = true; }
                else throw refused;
            }
        }
    }

    /** A reply the provider marks as a failure, or a transient one worth another try. */
    private static final class Transient extends IOException { Transient(String m) { super(m); } }

    private static String post(String path, String key, JSONObject request) throws Exception { return post(path, key, request, 120000); }

    /** A dropped connection, a timeout, a 429 or a 5xx waits a moment and goes again (three tries); a failed lookup or missing connection gets six, spread over about 40 seconds, because it is usually a short blip. A refusal (400s) or a bad key is not retried. */
    private static String post(String path, String key, JSONObject request, int readTimeout) throws Exception {
        byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
        IOException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            if (attempt > 0) Thread.sleep(new long[]{0, 2000, 5000, 8000, 12000, 15000}[attempt]);
            HttpURLConnection connection = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/" + path).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(readTimeout);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Content-Type", "application/json");
            Cancel cancel = Cancel.current();
            if (cancel.stopped() && attempt > 0) throw last; // told to stop: no further tries
            cancel.attach(connection);
            try {
                try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
                int code = connection.getResponseCode();
                if (code == 200) {
                    String body;
                    try (InputStream in = connection.getInputStream()) { body = new String(CaptureService.readAll(in), StandardCharsets.UTF_8); }
                    if (body.startsWith("{\"error\"") || body.startsWith("{ \"error\"")) throw new Transient("The provider reported an error: " + detail(body));
                    return body;
                }
                String why = detail(errorBody(connection));
                if (code == 400 || code == 404 || code == 413 || code == 415 || code == 422) throw new Rejected("OpenRouter refused this request (" + code + (why.isEmpty() ? "" : ": " + why) + ")");
                if (code == 408 || code == 425 || code == 429 || code >= 500) throw new Transient("OpenRouter answered " + code + (why.isEmpty() ? "" : ": " + why));
                throw new IOException("Smart tags paused: OpenRouter answered " + code + (code == 401 ? " (check the key)" : code == 402 ? " (out of credit)" : why.isEmpty() ? "" : ": " + why));
            } catch (Transient | java.net.SocketException | java.net.SocketTimeoutException | java.io.EOFException | javax.net.ssl.SSLException | java.net.UnknownHostException again) {
                last = again;
                android.util.Log.i("Sparkbox", "model call attempt " + (attempt + 1) + " failed: " + again);
                if (!Net.offline(again) && (attempt >= 2 || (again instanceof java.net.SocketTimeoutException && attempt >= 1))) break; // other trouble gets three tries, a slow answer only one more
            } finally { cancel.detach(connection); connection.disconnect(); }
        }
        if (last != null && Net.offline(last)) throw new Net.Offline("Smart tags paused: no connection to OpenRouter (" + last.getMessage() + ")");
        throw new IOException("Smart tags paused: " + (last == null ? "connection failed" : last.getMessage()));
    }

    private static String errorBody(HttpURLConnection c) {
        try (InputStream in = c.getErrorStream()) { return in == null ? "" : new String(CaptureService.readAll(in), StandardCharsets.UTF_8); } catch (Exception none) { return ""; }
    }

    /** The provider's own words for what went wrong, short, from an error body. */
    private static String detail(String body) {
        try {
            JSONObject e = new JSONObject(body).optJSONObject("error");
            String m = e == null ? "" : e.optString("message", "");
            m = m.replaceAll("\\s+", " ").trim();
            return m.length() > 160 ? m.substring(0, 160) : m;
        } catch (Exception none) { return ""; }
    }

    // --- key ---
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("capture", 0); }

    static void saveKey(Context c, String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret());
        prefs(c).edit().putString("aiKey", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
            + Base64.encodeToString(cipher.doFinal(key.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP)).apply();
    }

    /** The saved key, or "" when none (or when it can no longer be decrypted). */
    static String key(Context c) {
        String[] sealed = prefs(c).getString("aiKey", "").split(":");
        if (sealed.length != 2) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, Base64.decode(sealed[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(sealed[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception lost) { return ""; }
    }

    static void clear(Context c) { prefs(c).edit().remove("aiKey").apply(); }

    private static SecretKey secret() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
