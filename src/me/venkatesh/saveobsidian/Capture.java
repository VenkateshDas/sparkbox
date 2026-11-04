package me.venkatesh.saveobsidian;

import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Capture {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"\\p{Cntrl}]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile("<title\\b[^>]*>(.*?)</title\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public static String firstUrl(String text) {
        Matcher m = URL.matcher(text == null ? "" : text);
        if (!m.find()) throw new IllegalArgumentException("No webpage link was shared. Share the page or post link and try again.");
        String value = m.group();
        URI uri = URI.create(value);
        if (uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException("This link is not supported.");
        return value;
    }

    public static String pageTitle(String html) {
        Matcher m = TITLE.matcher(html == null ? "" : html);
        return m.find() ? m.group(1) : "";
    }

    public static String clean(String text) {
        String result = (text == null ? "" : text).replaceAll("[\\p{Cntrl}\\s\\u00a0]+", " ").trim();
        return result.length() > 600 ? result.substring(0, 600) : result;
    }

    public static String timestamp() {
        return ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    public static String entry(String time, String title, String url) {
        String label = clean(title);
        return "- " + time + " — " + (label.isEmpty() ? url : label) + "\n  <" + url + ">\n";
    }
}
