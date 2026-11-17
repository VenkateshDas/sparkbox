package me.venkatesh.saveobsidian;

import android.webkit.CookieManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Fallback for reels without a public embed. Instagram cookies stay on Instagram's own origin. */
final class InstagramApi {
    private static final String ORIGIN = "https://www.instagram.com";
    private InstagramApi() { }

    static boolean signedIn() { return !cookie("sessionid").isEmpty(); }

    private static String cookie(String name) {
        String cookies = CookieManager.getInstance().getCookie(ORIGIN);
        if (cookies != null) for (String part : cookies.split(";")) {
            String value = part.trim();
            if (value.startsWith(name + "=")) return value.substring(name.length() + 1);
        }
        return "";
    }

    static Instagram.Media fetch(String shortcode) throws IOException {
        String home = request("/", null, "");
        String lsd = token(home, "LSD");
        if (lsd.isEmpty()) throw new Instagram.AccessRequired();
        String dtsg = token(home, "DTSGInitialData");
        Matcher user = Pattern.compile(Pattern.quote("\"IG_USER_EIMU\":") + "\"?([0-9]+)").matcher(home);
        String av = user.find() ? user.group(1) : "0";
        try {
            JSONObject variables = new JSONObject().put("shortcode", shortcode)
                .put("__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider", false);
            String body = "doc_id=27128499623469141&server_timestamps=true&variables=" + encode(variables.toString())
                + "&lsd=" + encode(lsd) + "&av=" + encode(av.isEmpty() ? "0" : av);
            if (!dtsg.isEmpty()) body += "&fb_dtsg=" + encode(dtsg);
            Instagram.Media media = Instagram.data(request("/api/graphql", body, lsd), shortcode);
            if (media.items.isEmpty() && media.caption.isEmpty()) throw new Instagram.AccessRequired();
            return media;
        } catch (org.json.JSONException invalid) { throw new IOException("Instagram metadata could not be read", invalid); }
    }

    private static String encode(String value) throws IOException { return URLEncoder.encode(value, "UTF-8"); }

    private static String token(String html, String name) {
        Matcher match = Pattern.compile(Pattern.quote("\"" + name + "\",[],{") + "[^{}]*" + Pattern.quote("\"token\":\"") + "([^\"]+)").matcher(html);
        return match.find() ? match.group(1) : "";
    }

    private static String request(String path, String body, String lsd) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(ORIGIN + path).openConnection();
        Cancel cancel = Cancel.current();
        cancel.attach(connection);
        connection.setInstanceFollowRedirects(false); // Never forward account cookies to a redirected origin.
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", Web.DESKTOP);
        connection.setRequestProperty("Accept", body == null ? "text/html" : "*/*");
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        String cookies = CookieManager.getInstance().getCookie(ORIGIN);
        if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
        if (body != null) {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Origin", ORIGIN);
            connection.setRequestProperty("Referer", ORIGIN + "/");
            connection.setRequestProperty("X-IG-App-ID", "936619743392459");
            connection.setRequestProperty("X-FB-LSD", lsd);
            connection.setRequestProperty("X-CSRFToken", cookie("csrftoken"));
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            connection.setRequestProperty("Sec-Fetch-Dest", "empty");
            connection.setRequestProperty("Sec-Fetch-Mode", "cors");
            connection.setRequestProperty("Sec-Fetch-Site", "same-origin");
        }
        try {
            if (body != null) try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            int code = connection.getResponseCode();
            for (java.util.Map.Entry<String, java.util.List<String>> header : connection.getHeaderFields().entrySet())
                if ("Set-Cookie".equalsIgnoreCase(header.getKey()))
                    for (String value : header.getValue()) CookieManager.getInstance().setCookie(ORIGIN, value);
            if (code == 401 || code == 403 || (code >= 300 && code < 400)) throw new Instagram.AccessRequired();
            if (code != 200) throw new IOException("Instagram metadata request failed (HTTP " + code + "). Retry later.");
            try (InputStream in = connection.getInputStream()) { return new String(Web.readAll(in), StandardCharsets.UTF_8); }
        } finally { cancel.detach(connection); connection.disconnect(); }
    }
}
