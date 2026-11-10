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
        String page = "";
        String host = new URL(url).getHost().toLowerCase(java.util.Locale.ROOT);
        boolean youtube = host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com");
        // YouTube serves an "unsupported browser" page to this fetch; the title alone is better than that text.
        String full = "";
        if (!youtube) try { full = Enrich.pageText(PageTitle.html(url, true)); } catch (Exception ignored) { } // title and URL still classify
        page = full.length() > Enrich.PROMPT_CHARS ? full.substring(0, Enrich.PROMPT_CHARS) : full;
        if (pageSink != null && !full.isEmpty()) pageSink.accept(full); // the whole page, so Ask and later processing need no refetch
        JSONObject request = new JSONObject().put("model", model).put("max_tokens", 300)
            .put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", Enrich.SYSTEM + Enrich.INSTRUCTIONS))
                .put(new JSONObject().put("role", "user").put("content", "Title: " + title + "\nURL: " + url.replaceFirst("[?#].*$", "") + "\nPage text:\n" + page + Enrich.notes(why, ideas)))); // query strings often carry tokens
        return parse(post("chat/completions", key, request));
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
            .put("input_audio", new JSONObject().put("data", Base64.encodeToString(audio, Base64.NO_WRAP)).put("format", format)))).optString("text").trim();
    }

    /** The model's JSON reply forced into the safe shape, or null when unusable. */
    private static String[] parse(String reply) {
        try {
            String content = new JSONObject(reply).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            JSONObject out = new JSONObject(content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1));
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
    static JSONObject stream(String key, JSONObject request, java.util.function.Consumer<String> live) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();
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
        } finally { connection.disconnect(); }
    }

    /** One non-streamed completion; the reply text. For Ingest, which asks for JSON. */
    static String complete(String key, String model, String system, String user, int maxTokens) throws Exception {
        JSONObject request = new JSONObject().put("model", model).put("max_tokens", maxTokens).put("messages", new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", system)).put(new JSONObject().put("role", "user").put("content", user)));
        JSONObject message = new JSONObject(post("chat/completions", key, request)).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        return message.isNull("content") ? "" : message.optString("content", "");
    }

    private static String post(String path, String key, JSONObject request) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/" + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(90000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(request.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = connection.getResponseCode();
            if (code == 400 || code == 413 || code == 415 || code == 422) throw new Rejected("OpenRouter refused this file (" + code + ")");
            if (code != 200) throw new IOException("Smart tags paused: OpenRouter answered " + code + (code == 401 ? " (check the key)" : code == 402 ? " (out of credit)" : ""));
            try (InputStream in = connection.getInputStream()) { return new String(CaptureService.readAll(in), StandardCharsets.UTF_8); }
        } finally { connection.disconnect(); }
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
