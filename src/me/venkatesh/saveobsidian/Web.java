package me.venkatesh.saveobsidian;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches a page the way a phone browser does: current browser headers, redirects followed (every hop checked to be a public host), gzip, a size
 * and time cap, a second try on a transient failure, and a second identity when a site refuses the first. Used for page text, not for titles at share time.
 */
final class Web {
    static final String MOBILE = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    static final String DESKTOP = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    static final int MAX_BYTES = 3 * 1024 * 1024;

    static final class Reply {
        int code;
        String url = "", type = "", charset = "";
        byte[] body = new byte[0];
        boolean html() { String t = type.toLowerCase(Locale.ROOT); return t.contains("html") || t.contains("xml") || t.isEmpty(); }
        boolean text() { return type.toLowerCase(Locale.ROOT).startsWith("text/plain"); }
        String string() {
            Charset cs = StandardCharsets.UTF_8;
            try { if (!charset.isEmpty()) cs = Charset.forName(charset); } catch (RuntimeException ignored) { }
            return new String(body, cs);
        }
    }

    private Web() { }

    /** All of a small reply (an API answer), at most 8 MB. */
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            bytes.write(buffer, 0, n);
            if (bytes.size() > 8 * 1024 * 1024) throw new IOException("The reply was too large");
        }
        return bytes.toByteArray();
    }

    /** The page at this address, or an IOException saying why not (network, blocked, too slow). A non-200 answer is returned, not thrown, so the caller can tell "gone" from "offline". */
    static Reply get(String address) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) { // first as a phone, then as a desktop browser
            try {
                Reply r = once(address, attempt == 0 ? MOBILE : DESKTOP);
                if (r.code == 403 || r.code == 401 || r.code == 429 || r.code >= 500) { last = new IOException("The site answered " + r.code); if (attempt < 2) { pause(700L * (attempt + 1)); continue; } return r; }
                return r;
            } catch (java.net.UnknownHostException | java.net.MalformedURLException final_) { throw final_; }
            catch (IOException failed) { last = failed; if (attempt < 2) pause(700L * (attempt + 1)); }
        }
        throw last == null ? new IOException("Could not fetch the page") : last;
    }

    private static void pause(long ms) { try { Thread.sleep(ms); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); } }

    private static Reply once(String address, String agent) throws IOException {
        URL current = new URL(address);
        long deadline = System.currentTimeMillis() + 30000;
        for (int hop = 0; hop <= 6; hop++) {
            boolean secure = "https".equalsIgnoreCase(current.getProtocol());
            if ((!secure && !"http".equalsIgnoreCase(current.getProtocol())) || current.getUserInfo() != null) throw new IOException("Not a public web address");
            if (!secure && hop == 0) current = new URL("https", current.getHost(), current.getPort() == 80 ? -1 : current.getPort(), current.getFile()); // try the safe address first; a site that has none redirects or fails below
            if (!PageTitle.publicHost(current.getHost())) throw new IOException("Not a public web address");
            HttpURLConnection c = (HttpURLConnection) current.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", agent);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            c.setRequestProperty("Upgrade-Insecure-Requests", "1");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    current = new URL(current, c.getHeaderField("Location"));
                    if (System.currentTimeMillis() > deadline) throw new IOException("The site took too long");
                    continue;
                }
                Reply r = new Reply();
                r.code = code;
                r.url = current.toString();
                r.type = c.getContentType() == null ? "" : c.getContentType();
                Matcher m = Pattern.compile("charset\\s*=\\s*[\"']?([^\\s;\"']+)", Pattern.CASE_INSENSITIVE).matcher(r.type);
                if (m.find()) r.charset = m.group(1);
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in == null) return r;
                try (InputStream body = in; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while (bytes.size() < MAX_BYTES && (n = body.read(buffer, 0, Math.min(buffer.length, MAX_BYTES - bytes.size()))) != -1) {
                        bytes.write(buffer, 0, n);
                        if (System.currentTimeMillis() > deadline) break; // keep what arrived
                    }
                    r.body = bytes.toByteArray();
                }
                if (r.charset.isEmpty() && r.html()) { // the page names its own encoding in a <meta> near the top
                    Matcher meta = Pattern.compile("<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(new String(r.body, 0, Math.min(r.body.length, 4096), StandardCharsets.ISO_8859_1));
                    if (meta.find()) r.charset = meta.group(1);
                }
                return r;
            } finally { c.disconnect(); }
        }
        throw new IOException("Too many redirects");
    }
}
