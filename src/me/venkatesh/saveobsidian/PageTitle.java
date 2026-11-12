package me.venkatesh.saveobsidian;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

final class PageTitle {
    private static final String AGENT = "Mozilla/5.0 (Android) Sparkbox/1.2";

    static String fetchTitle(String original) throws Exception {
        String host = new URL(original).getHost().toLowerCase(Locale.ROOT);
        if (!publicHost(host)) return "";
        if (host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com")) {
            try {
                String title = youtube(original);
                if (!title.isEmpty()) return title;
            } catch (Exception ignored) { } // fall back to the page itself
        }
        return Capture.pageTitle(html(original, false));
    }

    /** YouTube's public oEmbed endpoint: real title without scraping a consent page. */
    private static String youtube(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8")).openConnection();
        connection.setConnectTimeout(2500);
        connection.setReadTimeout(2500);
        connection.setRequestProperty("User-Agent", AGENT);
        try {
            if (connection.getResponseCode() != 200) return "";
            long deadline = System.currentTimeMillis() + 5000;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int size;
                while (System.currentTimeMillis() < deadline && bytes.size() < 65536 && (size = input.read(buffer, 0, Math.min(buffer.length, 65536 - bytes.size()))) != -1) bytes.write(buffer, 0, size);
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).optString("title");
            }
        } finally { connection.disconnect(); }
    }

    /** A shared link must not make the phone probe its own network: loopback, LAN and link-local hosts are never fetched (also after redirects). */
    // ponytail: resolves twice, so DNS rebinding could still slip past; pin the resolved address if that ever matters
    static boolean publicHost(String host) {
        try {
            for (java.net.InetAddress a : java.net.InetAddress.getAllByName(host))
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()
                    || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xfe) == 0xfc) // fc00::/7 unique-local
                    || (a.getAddress().length == 4 && (a.getAddress()[0] & 0xff) == 100 && (a.getAddress()[1] & 0xc0) == 64)) return false; // 100.64.0.0/10 carrier-grade NAT
            return true;
        } catch (java.net.UnknownHostException | RuntimeException unknown) { return false; }
    }

    /** Bounded HTTPS image download (2 MB max, same host and redirect rules as html()), or null. */
    static byte[] image(String original) throws Exception {
        URL current = new URL(original);
        long deadline = System.currentTimeMillis() + 20000; // the host look-ups can take seconds on a slow network
        for (int redirects = 0; redirects <= 4; redirects++) {
            boolean known = current.getHost().endsWith(".ytimg.com"); // YouTube's own image host: no need to resolve it twice to prove it is public
            if (!"https".equalsIgnoreCase(current.getProtocol()) || current.getUserInfo() != null || (!known && !publicHost(current.getHost()))) return null;
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setRequestProperty("User-Agent", AGENT);
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) return null;
                    current = new URL(current, location);
                    continue;
                }
                String type = connection.getContentType();
                if (code != 200 || type == null || !type.toLowerCase(Locale.ROOT).startsWith("image/")) return null;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int size;
                    while ((size = input.read(buffer)) != -1) {
                        bytes.write(buffer, 0, size);
                        if (bytes.size() > 2 * 1024 * 1024 || System.currentTimeMillis() > deadline) return null;
                    }
                    return bytes.toByteArray();
                }
            } finally { connection.disconnect(); }
        }
        return null;
    }

    /** Bounded HTTPS fetch. whole=false stops after &lt;head&gt;, enough for titles; true reads the body too. */
    static String html(String original, boolean whole) throws Exception {
        URL current = new URL(original);
        long deadline = System.currentTimeMillis() + 8000;
        for (int redirects = 0; redirects <= 4; redirects++) {
            // HTTPS only for title fetching. HTTP links are still saved unchanged.
            if (!"https".equalsIgnoreCase(current.getProtocol()) || current.getUserInfo() != null || !publicHost(current.getHost())) return "";
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setRequestProperty("User-Agent", AGENT);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            try {
                int code = connection.getResponseCode();
                if (System.currentTimeMillis() > deadline) return "";
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) return "";
                    current = new URL(current, location);
                    continue;
                }
                String contentType = connection.getContentType();
                if (code != 200 || contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("html")) return "";
                Charset charset = StandardCharsets.UTF_8;
                Matcher encoding = Pattern.compile("charset\\s*=\\s*[\"']?([^\\s;\"']+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
                if (encoding.find()) {
                    try { charset = Charset.forName(encoding.group(1)); } catch (IllegalArgumentException ignored) { }
                }
                String html = "";
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int size;
                    while (bytes.size() < 524288 && (size = input.read(buffer, 0, Math.min(buffer.length, 524288 - bytes.size()))) != -1) {
                        bytes.write(buffer, 0, size);
                        html = new String(bytes.toByteArray(), charset);
                        // og:title may follow <title>, so read the whole <head>.
                        if (System.currentTimeMillis() > deadline || (!whole && html.toLowerCase(Locale.ROOT).contains("</head"))) break;
                    }
                }
                return html;
            } finally { connection.disconnect(); }
        }
        return "";
    }
}
