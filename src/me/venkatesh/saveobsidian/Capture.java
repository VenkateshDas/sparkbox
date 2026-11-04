package me.venkatesh.saveobsidian;

import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Capture {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"\\p{Cntrl}]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile("<title\\b[^>]*>(.*?)</title\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_NAME = Pattern.compile("(?:property|name)\\s*=\\s*[\"'](?:og|twitter):title[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CONTENT = Pattern.compile("content\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACKING = Pattern.compile("(?i)utm_.*|fbclid|gclid|igshid|mc_[ce]id|si|ref_src");
    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
        "youtube", "linkedin", "instagram", "x", "reddit", "facebook", "tiktok", "twitter", "x (formerly twitter)"));

    public static String firstUrl(String text) {
        Matcher m = URL.matcher(text == null ? "" : text);
        if (!m.find()) throw new IllegalArgumentException("No webpage link was shared. Share the page or post link and try again.");
        String value = m.group();
        URI uri = URI.create(value);
        if (uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException("This link is not supported.");
        return value;
    }

    /** Drops tracking parameters, keeps everything else. */
    public static String cleanUrl(String url) {
        int hash = url.indexOf('#');
        String fragment = hash < 0 ? "" : url.substring(hash), rest = hash < 0 ? url : url.substring(0, hash);
        int q = rest.indexOf('?');
        if (q < 0) return url;
        StringBuilder kept = new StringBuilder();
        for (String param : rest.substring(q + 1).split("&")) {
            if (param.isEmpty() || TRACKING.matcher(param.split("=", 2)[0]).matches()) continue;
            kept.append(kept.length() == 0 ? '?' : '&').append(param);
        }
        return rest.substring(0, q) + kept + fragment;
    }

    /** First non-generic candidate, else the URL. */
    public static String chooseTitle(String subject, String lead, String fetched, String url) {
        for (String candidate : new String[]{subject, lead, fetched}) {
            String title = clean(candidate);
            if (!isGeneric(title, url)) return title;
        }
        return url;
    }

    public static boolean isGeneric(String title, String url) {
        String t = clean(title);
        return t.isEmpty() || t.equals(url) || GENERIC.contains(t.toLowerCase(Locale.ROOT));
    }

    /** One-line text shared before the link, e.g. "Great article" in "Great article https://...". */
    public static String leadingText(String text, String url) {
        int at = text == null ? -1 : text.indexOf(url);
        String lead = at <= 0 ? "" : clean(text.substring(0, at));
        return lead.length() > 200 ? "" : lead;
    }

    public static String pageTitle(String html) {
        String page = html == null ? "" : html;
        Matcher meta = META.matcher(page);
        while (meta.find()) {
            if (!META_NAME.matcher(meta.group()).find()) continue;
            Matcher content = META_CONTENT.matcher(meta.group());
            if (content.find()) {
                String value = content.group(1) != null ? content.group(1) : content.group(2);
                if (!value.trim().isEmpty()) return value;
            }
        }
        Matcher m = TITLE.matcher(page);
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
        return "- 🔗 " + (label.isEmpty() ? url : label) + "\n  " + url + "\n  Captured: " + time + " · via Sparkbox\n";
    }

    public static String textEntry(String time, String text) {
        String all = (text == null ? "" : text).replace("\r", "").trim();
        if (all.length() > 2000) all = all.substring(0, 2000) + "…";
        StringBuilder body = new StringBuilder();
        String first = "";
        for (String line : all.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue; // a blank line would end the entry
            if (first.isEmpty()) first = line;
            body.append("  ").append(line).append('\n');
        }
        String head = first.length() > 80 ? first.substring(0, 80) + "…" : first;
        boolean repeat = !head.equals(first) || body.length() > first.length() + 3;
        return "- 📝 " + head + "\n" + (repeat ? body : "") + "  Captured: " + time + " · via Sparkbox\n";
    }

    /** Newlines to prepend so exactly one blank line separates entries. */
    public static String separator(byte[] before) {
        int newlines = 0;
        for (int i = before.length - 1; i >= 0 && before[i] == '\n' && newlines < 2; i--) newlines++;
        if (before.length == 0) return "";
        return newlines == 0 ? "\n\n" : newlines == 1 ? "\n" : "";
    }

    /** True when a whole line of the inbox is this URL (bare or in <>). */
    public static boolean alreadyListed(String inbox, String url) {
        String want = url.replaceAll("/+$", "");
        for (String line : inbox.split("\n")) {
            String t = line.trim();
            if (t.startsWith("<") && t.endsWith(">")) t = t.substring(1, t.length() - 1);
            if (t.replaceAll("/+$", "").equals(want)) return true;
        }
        return false;
    }
}
