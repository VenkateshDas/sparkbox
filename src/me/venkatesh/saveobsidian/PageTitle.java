package me.venkatesh.saveobsidian;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PageTitle {
    static String fetchTitle(String original) throws Exception {
        URL current = new URL(original);
        long deadline = System.currentTimeMillis() + 8000;
        for (int redirects = 0; redirects <= 4; redirects++) {
            // HTTPS only for title fetching. HTTP links are still saved unchanged.
            if (!"https".equalsIgnoreCase(current.getProtocol()) || current.getUserInfo() != null) return "";
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SaveToObsidian/1.0");
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
                if (code != 200 || contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).contains("html")) return "";
                Charset charset = StandardCharsets.UTF_8;
                Matcher encoding = Pattern.compile("charset\\s*=\\s*[\"']?([^\\s;\"']+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
                if (encoding.find()) {
                    try { charset = Charset.forName(encoding.group(1)); } catch (IllegalArgumentException ignored) { }
                }
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int size;
                    while (bytes.size() < 524288 && (size = input.read(buffer, 0, Math.min(buffer.length, 524288 - bytes.size()))) != -1) {
                        bytes.write(buffer, 0, size);
                        if (System.currentTimeMillis() > deadline) break;
                        String title = Capture.pageTitle(new String(bytes.toByteArray(), charset));
                        if (!title.isEmpty()) return title;
                    }
                }
                return "";
            } finally { connection.disconnect(); }
        }
        return "";
    }

}
