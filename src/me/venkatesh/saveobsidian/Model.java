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
    private static final String ALIAS = "sparkbox-ai-key";

    /** {type, tags, tldr}, or null when the reply is unusable. IOException (network, key, quota) means stop for now. */
    static String[] enrich(String key, String model, String title, String url) throws Exception {
        String page = "";
        String host = new URL(url).getHost().toLowerCase(java.util.Locale.ROOT);
        boolean youtube = host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com");
        // YouTube serves an "unsupported browser" page to this fetch; the title alone is better than that text.
        if (!youtube) try { page = Enrich.pageText(PageTitle.html(url, true)); } catch (Exception ignored) { } // title and URL still classify
        JSONObject request = new JSONObject().put("model", model).put("max_tokens", 300)
            .put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", Enrich.SYSTEM))
                .put(new JSONObject().put("role", "user").put("content", "Title: " + title + "\nURL: " + url + "\nPage text:\n" + page)));
        HttpURLConnection connection = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("Content-Type", "application/json");
        String reply;
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(request.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("Smart tags paused: OpenRouter answered " + code + (code == 401 ? " (check the key)" : code == 402 ? " (out of credit)" : ""));
            try (InputStream in = connection.getInputStream()) { reply = new String(CaptureService.readAll(in), StandardCharsets.UTF_8); }
        } finally { connection.disconnect(); }
        try {
            String content = new JSONObject(reply).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            JSONObject out = new JSONObject(content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1));
            List<String> tags = new ArrayList<>();
            JSONArray array = out.optJSONArray("tags");
            for (int i = 0; array != null && i < array.length(); i++) tags.add(array.optString(i));
            return Enrich.clean(out.optString("type"), tags, out.optString("tldr"));
        } catch (Exception unusable) { return null; }
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
