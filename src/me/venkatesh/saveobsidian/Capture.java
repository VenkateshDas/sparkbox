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
    private static final Pattern TRACKING = Pattern.compile("(?i)utm_.*|fbclid|gclid|igshid|mc_[ce]id|ref_src");
    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
        "youtube", "linkedin", "instagram", "x", "reddit", "facebook", "tiktok", "twitter", "x (formerly twitter)"));

    public static String firstUrl(String text) {
        Matcher m = URL.matcher(text == null ? "" : text);
        if (!m.find()) throw new IllegalArgumentException("No webpage link was shared. Share the page or post link and try again.");
        String value = m.group();
        // Remove prose punctuation and unmatched closing brackets; keep balanced URL brackets.
        while (!value.isEmpty()) {
            char end = value.charAt(value.length() - 1);
            char open = end == ')' ? '(' : end == ']' ? '[' : end == '}' ? '{' : 0;
            if (end == '.' || end == ',' || end == ';' || end == '!' || end == '\'' ||
                    (open != 0 && value.chars().filter(c -> c == end).count() > value.chars().filter(c -> c == open).count()))
                value = value.substring(0, value.length() - 1);
            else break;
        }
        URI uri = URI.create(value);
        if (uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException("This link is not supported.");
        return value;
    }

    private static final Pattern ARXIV_PAPER = Pattern.compile(
        "^/(?:abs|pdf|html)/((?:\\d{4}\\.\\d{4,5}|[a-zA-Z.-]+/\\d{7})(?:v\\d+)?)(?:\\.pdf)?/?$");

    /** Full-text URL for an arXiv paper link, preserving the requested paper version. */
    public static String arxivPdfUrl(String url) {
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException bad) { return ""; }
        String host = uri.getHost(), scheme = uri.getScheme();
        if (!("arxiv.org".equalsIgnoreCase(host) || "www.arxiv.org".equalsIgnoreCase(host) || "export.arxiv.org".equalsIgnoreCase(host))
            || uri.getUserInfo() != null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) return "";
        Matcher paper = ARXIV_PAPER.matcher(uri.getPath());
        return paper.matches() ? "https://arxiv.org/pdf/" + paper.group(1) : "";
    }

    /** Drops tracking parameters, keeps everything else. */
    /** What makes a link the same link: tracking parameters and the #fragment dropped, the rest of the query kept (a YouTube video is /watch?v=ID, and nothing else tells two videos apart). */
    public static String identity(String url) {
        String u;
        try { u = cleanUrl(url.trim()); } catch (RuntimeException odd) { u = url.trim(); }
        int h = u.indexOf('#');
        return h < 0 ? u : u.substring(0, h);
    }

    public static String cleanUrl(String url) {
        int hash = url.indexOf('#');
        String fragment = hash < 0 ? "" : url.substring(hash), rest = hash < 0 ? url : url.substring(0, hash);
        int q = rest.indexOf('?');
        if (q < 0) return url;
        String host = URI.create(url).getHost();
        boolean youtube = host != null && (host.equalsIgnoreCase("youtu.be") || host.equalsIgnoreCase("youtube.com") || host.toLowerCase(Locale.ROOT).endsWith(".youtube.com"));
        StringBuilder kept = new StringBuilder();
        for (String param : rest.substring(q + 1).split("&")) {
            if (param.isEmpty() || (TRACKING.matcher(param.split("=", 2)[0]).matches() || (youtube && param.split("=", 2)[0].equalsIgnoreCase("si")))) continue;
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

    /** Full note text. Four-space continuation preserves blank lines and indentation. */
    public static String textEntry(String time, String text) { return textEntry(time, text, "Sparkbox"); }

    /** via is "Sparkbox" for typed notes and "Sparkbox voice" for transcribed ones. */
    public static String textEntry(String time, String text, String via) {
        String all = normalize(text);
        String first = all.split("\n", -1)[0];
        String head = clean(first);
        if (head.length() > 80) head = head.substring(0, 80) + "…";
        String body = all.equals(head) ? "" : block("Text", all);
        return "- 📝 " + head + "\n" + body + "  Captured: " + time + " · via " + via + "\n";
    }

    /** Photo or document stored in the vault: an Obsidian embed on its own line. */
    public static String fileEntry(String time, String title, String stored, boolean image) {
        String label = clean(title);
        return "- " + (image ? "🖼" : "📎") + " " + (label.isEmpty() ? stored : label) + "\n  ![[" + stored + "]]\n  Captured: " + time + " · via Sparkbox\n";
    }

    /** Vault-safe file name: Obsidian links break on [ ] # ^ | and path separators. */
    public static String fileName(String time, String original, String id) {
        String name = original == null || original.isEmpty() ? "file" : original;
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 && name.length() - dot <= 8 ? name.substring(dot).replaceAll("[^A-Za-z0-9.]", "") : "";
        String base = (dot > 0 && !ext.isEmpty() ? name.substring(0, dot) : name).replaceAll("[\\[\\]#^|/\\\\:*?\"<>\\p{Cntrl}]+", " ").trim().replaceAll("\\s+", " ");
        if (base.length() > 60) base = base.substring(0, 60).trim();
        if (base.isEmpty()) base = "file";
        return time.replace(":", "") + " " + base + "-" + id + ext;
    }

    public static String normalize(String text) {
        return (text == null ? "" : text).replace("\r\n", "\n").replace('\r', '\n');
    }

    public static String block(String label, String text) {
        StringBuilder out = new StringBuilder("  " + label + ":\n");
        for (String line : normalize(text).split("\n", -1)) out.append("    ").append(line).append('\n');
        return out.toString();
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
