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
    static String[] enrich(String key, String model, String title, String url) throws Exception {
        String page = "";
        String host = new URL(url).getHost().toLowerCase(java.util.Locale.ROOT);
        boolean youtube = host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com");
        // YouTube serves an "unsupported browser" page to this fetch; the title alone is better than that text.
        if (!youtube) try { page = Enrich.pageText(PageTitle.html(url, true)); } catch (Exception ignored) { } // title and URL still classify
        JSONObject request = new JSONObject().put("model", model).put("max_tokens", 300)
            .put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", Enrich.SYSTEM))
                .put(new JSONObject().put("role", "user").put("content", "Title: " + title + "\nURL: " + url.replaceFirst("[?#].*$", "") + "\nPage text:\n" + page))); // query strings often carry tokens
        return parse(post("chat/completions", key, request));
    }

    /** {type, tags, tldr} for a typed or spoken note. */
    static String[] enrichNote(String key, String model, String text) throws Exception {
        return parse(post("chat/completions", key, new JSONObject().put("model", model).put("max_tokens", 300).put("messages", new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", Enrich.NOTE))
            .put(new JSONObject().put("role", "user").put("content", text.length() > 4000 ? text.substring(0, 4000) : text)))));
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

    /** One chat completion, raw JSON reply. For callers that build their own request, such as Ask with tools. */
    static String chat(String key, JSONObject request) throws Exception { return post("chat/completions", key, request); }

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
