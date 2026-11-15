package me.venkatesh.saveobsidian;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * The text of a saved link, whatever kind it is: a YouTube video (description and transcript), a GitHub repository (its README), a post on X,
 * or any web page (the article, found with Extract). One front door so tagging, Ask and ingest all read links the same way.
 */
final class Fetch {
    private Fetch() { }

    static final class Result {
        String title = "", byline = "", description = "", image = "", text = "", problem = "";
        /** True when the text still lacks what the owner would expect (a video with no transcript yet, a page that asked for a human). */
        boolean thin;
    }

    /**
     * The readable text of this link. Returns "" text (with a problem) when the site answered but gave nothing readable; throws IOException when the
     * network failed, so the caller can try again later instead of treating it as "empty". transcribe lets a video without captions be transcribed (slow, costs a little).
     */
    static Result page(String url, String key, boolean transcribe) throws IOException {
        try { Limits.FETCH.acquire(); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw new IOException("Stopped"); }
        try { return pageNow(url, key, transcribe); } finally { Limits.FETCH.release(); }
    }

    private static Result pageNow(String url, String key, boolean transcribe) throws IOException {
        Result r = new Result();
        String host;
        try { host = new URL(url).getHost().toLowerCase(Locale.ROOT); } catch (java.net.MalformedURLException bad) { r.problem = "Not a web link"; return r; }
        if (!Transcript.videoId(url).isEmpty()) {
            Youtube.Info v = Youtube.fetch(url, key, transcribe);
            r.title = v.title;
            r.byline = v.author;
            r.description = v.description.length() > 300 ? v.description.substring(0, 300) : v.description;
            r.text = v.text();
            r.thin = v.transcript.isEmpty();
            if (r.thin) r.problem = "No transcript";
            return r;
        }
        String shortcode = Instagram.shortcode(url);
        if (!shortcode.isEmpty()) return instagram(shortcode, key, transcribe);
        if (host.equals("github.com") || host.equals("www.github.com")) { Result g = github(url); if (g != null) return g; }
        if (host.endsWith(".wikipedia.org")) { Result w = wikipedia(url, host); if (w != null) return w; }
        if (host.equals("x.com") || host.equals("twitter.com") || host.endsWith(".twitter.com") || host.equals("www.x.com")) { Result t = tweet(url); if (t != null) return t; }
        Web.Reply reply = Web.get(url);
        if (reply.code != 200) { r.problem = "The site answered " + reply.code; return r; }
        if (reply.type.toLowerCase(Locale.ROOT).contains("pdf")) { r.problem = "This link is a PDF"; r.thin = true; return r; }
        if (reply.text()) { r.text = reply.string().trim(); return r; }
        if (!reply.html()) { r.problem = "Not a web page (" + reply.type + ")"; return r; }
        Extract.Page p = Extract.parse(reply.string(), reply.url);
        r.title = p.title;
        r.byline = p.byline;
        r.description = p.description;
        r.image = p.image;
        r.text = p.text;
        if (p.blocked) { r.problem = "The site asked for a person (sign-in, cookie or bot check)"; r.thin = true; }
        else if (p.text.length() < 200) { r.problem = "Very little readable text"; r.thin = true; }
        return r;
    }

    /** What to keep as the link's content: the text, or "" when none. */
    static String content(String url, String key, boolean transcribe) throws IOException { return page(url, key, transcribe).text; }

    private static Result instagram(String shortcode, String key, boolean watch) throws IOException {
        // Full browser identities receive Instagram's login app instead of the public embed data.
        Web.Reply reply = Web.get("https://www.instagram.com/p/" + shortcode + "/embed/", Web.MAX_BYTES, "Mozilla/5.0");
        Instagram.Media media = reply.code == 200 ? Instagram.parse(reply.string(), shortcode) : new Instagram.Media();
        if (media.caption.isEmpty() && media.video.isEmpty() || media.isVideo && media.video.isEmpty()) media = InstagramApi.fetch(shortcode);
        Result r = new Result();
        r.byline = media.author;
        r.title = media.author.isEmpty() ? "Instagram post" : "Instagram post by @" + media.author;
        r.image = media.image;
        r.description = media.caption;
        r.text = media.caption.isEmpty() ? "" : "### Caption\n" + media.caption;
        r.thin = media.isVideo;
        if (media.isVideo && watch && !key.isEmpty()) {
            if (media.video.isEmpty()) throw new IOException("Instagram did not provide the video. It may be private, removed or blocked. Share the video file instead.");
            Web.Reply video = Web.get(media.video, 24 * 1024 * 1024);
            if (video.code != 200 || !video.type.toLowerCase(Locale.ROOT).startsWith("video/"))
                throw new IOException("Instagram video could not be downloaded. Retry or share the video file.");
            try { r.text += "\n\n### Video content\n" + Model.watch(key, video.body, "video/mp4"); }
            catch (IOException failed) { throw failed; }
            catch (Exception failed) { throw new IOException("Video processing failed: " + failed.getMessage(), failed); }
            r.thin = false;
        }
        if (r.text.isEmpty()) throw new IOException("Instagram did not provide readable content. The post may be private, removed or blocked. Share the video file instead.");
        if (r.thin) r.problem = "Caption only; video has not been watched";
        return r;
    }

    private static final Pattern REPO = Pattern.compile("^/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/?$");

    /** A repository's front page is a JavaScript app; its README through the public API is the real content. Null when this is not a repository root or the API says no. */
    private static Result github(String url) {
        try {
            Matcher m = REPO.matcher(new URL(url).getPath());
            if (!m.matches()) return null;
            String base = "https://api.github.com/repos/" + m.group(1) + "/" + m.group(2);
            Result r = new Result();
            String readme = api(base + "/readme", "application/vnd.github.raw");
            if (readme == null || readme.trim().isEmpty()) return null;
            String about = api(base, "application/vnd.github+json");
            if (about != null) {
                JSONObject o = new JSONObject(about);
                r.title = o.optString("full_name");
                r.description = o.optString("description", "");
                r.byline = o.optJSONObject("owner") == null ? "" : o.optJSONObject("owner").optString("login");
                if (!r.description.isEmpty()) readme = r.description + "\n\n" + readme;
            }
            r.text = readme.trim();
            return r;
        } catch (Exception unavailable) { return null; }
    }

    /** A Wikipedia article as plain text through the encyclopedia's own API: the page itself is split into collapsible sections that are hard to read as one article. */
    private static Result wikipedia(String url, String host) {
        try {
            Matcher m = Pattern.compile("^/wiki/([^?#]+)").matcher(new URL(url).getPath());
            if (!m.find()) return null;
            String title = java.net.URLDecoder.decode(m.group(1).replace("+", "%2B"), "UTF-8");
            String reply = api("https://" + host + "/w/api.php?action=query&prop=extracts&explaintext=1&exsectionformat=wiki&redirects=1&format=json&titles=" + java.net.URLEncoder.encode(title, "UTF-8"), "application/json");
            if (reply == null) return null;
            JSONObject pages = new JSONObject(reply).getJSONObject("query").getJSONObject("pages");
            JSONObject page = pages.getJSONObject(pages.keys().next());
            String extract = page.optString("extract", "").trim();
            if (extract.length() < 200) return null;
            Result r = new Result();
            r.title = page.optString("title");
            r.byline = "Wikipedia";
            r.text = extract.replaceAll("(?m)^={2}\\s*(.+?)\\s*={2}$", "### $1").replaceAll("(?m)^={3,}\\s*(.+?)\\s*={3,}$", "#### $1").replaceAll("\\n{3,}", "\n\n");
            r.description = r.text.length() > 300 ? r.text.substring(0, 300) : r.text;
            return r;
        } catch (Exception unavailable) { return null; }
    }

    private static String api(String address, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Sparkbox");
        c.setRequestProperty("Accept", accept);
        try {
            if (c.getResponseCode() != 200) return null;
            try (java.io.InputStream in = c.getInputStream()) { return new String(Web.readAll(in), StandardCharsets.UTF_8); }
        } finally { c.disconnect(); }
    }

    /** A post on X: the page needs JavaScript, but the public embed service returns the post text. */
    private static Result tweet(String url) {
        try {
            if (!new URL(url).getPath().matches("(?i)^/[^/]+/status/\\d+.*")) return null;
            String reply = api("https://publish.twitter.com/oembed?omit_script=1&dnt=true&url=" + java.net.URLEncoder.encode(url.replaceFirst("[?#].*$", ""), "UTF-8"), "application/json");
            if (reply == null) return null;
            JSONObject o = new JSONObject(reply);
            Result r = new Result();
            org.jsoup.nodes.Document d = org.jsoup.Jsoup.parse(o.optString("html"));
            org.jsoup.nodes.Element p = d.selectFirst("blockquote p");
            r.text = p == null ? d.text() : p.text();
            r.byline = o.optString("author_name");
            r.title = r.byline.isEmpty() ? "Post on X" : "Post by " + r.byline;
            return r.text.isEmpty() ? null : r;
        } catch (Exception unavailable) { return null; }
    }
}
