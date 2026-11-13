package me.venkatesh.saveobsidian;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/** The text side of YouTube capture: video ids, which caption track to read, and caption XML to paragraphs. Pure Java so the JVM checks cover it. */
public final class Transcript {
    private Transcript() { }

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    /** The 11-character video id of a YouTube link (watch, youtu.be, shorts, live, embed, mobile and music hosts), or "". */
    public static String videoId(String url) {
        try {
            java.net.URI u = new java.net.URI(url.trim());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT), path = u.getPath() == null ? "" : u.getPath();
            if (host.equals("youtu.be")) { String id = path.replaceFirst("^/", "").split("/")[0]; return ID.matcher(id).matches() ? id : ""; }
            if (!(host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com"))) return "";
            Matcher v = Pattern.compile("(?:^|&)v=([A-Za-z0-9_-]{11})(?:&|$)").matcher(u.getRawQuery() == null ? "" : u.getRawQuery());
            if (path.equals("/watch") && v.find()) return v.group(1);
            Matcher p = Pattern.compile("^/(?:shorts|live|embed|v)/([A-Za-z0-9_-]{11})(?:/|$)").matcher(path);
            return p.find() ? p.group(1) : "";
        } catch (Exception bad) { return ""; }
    }

    /** The caption track to read: a person-written English track, then automatic English, then any written track, then any. Null when none. */
    public static JSONObject pick(JSONArray tracks) {
        if (tracks == null) return null;
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            if (t == null || t.optString("baseUrl").isEmpty()) continue;
            boolean auto = "asr".equals(t.optString("kind")), english = t.optString("languageCode").toLowerCase(Locale.ROOT).startsWith("en");
            int score = (english ? 2 : 0) * 2 + (auto ? 0 : 1);
            if (score > bestScore) { best = t; bestScore = score; }
        }
        return best;
    }

    /** Caption XML (either YouTube format) as readable paragraphs. A pause of a couple of seconds, or a long run, starts a new paragraph. Empty when there is no speech. */
    public static String fromXml(String xml) {
        if (xml == null || xml.isEmpty()) return "";
        org.jsoup.nodes.Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
        StringBuilder out = new StringBuilder(), para = new StringBuilder();
        long lastEnd = -1;
        for (Element e : doc.select("p, text")) {
            String raw = e.text();
            if (raw.indexOf('&') >= 0) raw = Parser.unescapeEntities(raw, false); // one format double-encodes
            String line = raw.replace(' ', ' ').replace("♪", "").replaceAll("\\s+", " ").trim();
            if (line.isEmpty() || line.matches("^[\\[(].{0,30}[\\])]$")) continue; // [Music], (applause): sound labels, not speech
            boolean seconds = e.tagName().equals("text");
            long start = seconds ? Math.round(num(e.attr("start")) * 1000) : (long) num(e.attr("t")), len = seconds ? Math.round(num(e.attr("dur")) * 1000) : (long) num(e.attr("d"));
            boolean pause = lastEnd >= 0 && start - lastEnd > 2500, long_ = para.length() > 600 && para.toString().matches("(?s).*[.?!]\"?$");
            if (para.length() > 0 && (pause || long_)) { out.append(para).append("\n\n"); para.setLength(0); }
            if (para.length() > 0) para.append(' ');
            para.append(line);
            lastEnd = start + len;
        }
        if (para.length() > 0) out.append(para);
        return out.toString().trim();
    }

    private static double num(String s) { try { return Double.parseDouble(s); } catch (NumberFormatException none) { return 0; } }
}
