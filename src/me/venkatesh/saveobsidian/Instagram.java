package me.venkatesh.saveobsidian;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Public Instagram embed data. Never treats navigation, a login wall or another post as the saved reel. */
public final class Instagram {
    private Instagram() { }

    public static Media data(String json, String shortcode) {
        try {
            JSONObject node = find(new JSONTokener(json).nextValue(), shortcode, 0);
            return node == null ? new Media() : media(node);
        } catch (Exception invalid) { return new Media(); }
    }

    public static final class AccessRequired extends java.io.IOException {
        public AccessRequired() { super("Instagram requires account access for this reel. Sign in through Instagram access in Sparkbox, then retry. You can also share the video file."); }
    }

    public static String shortcode(String url) {
        try {
            URI u = new URI(url);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getUserInfo() != null
                || !(host.equals("instagram.com") || host.equals("www.instagram.com") || host.equals("m.instagram.com"))) return "";
            Matcher m = Pattern.compile("^/(?:[A-Za-z0-9_.]+/)?(?:reel|reels|p)/([A-Za-z0-9_-]+)/?$").matcher(u.getPath());
            return m.matches() ? m.group(1) : "";
        } catch (Exception bad) { return ""; }
    }

    public static final class Media {
        public String caption = "", author = "", video = "", image = "";
        public boolean isVideo;
        public final java.util.List<Extract.Media> items = new java.util.ArrayList<>();
    }

    public static Media parse(String html, String shortcode) {
        Media result = new Media();
        for (Element script : Jsoup.parse(html).select("script")) {
            String data = script.data().trim();
            int handle = data.indexOf("s.handle(");
            try {
                Object root = new JSONTokener(handle >= 0 ? data.substring(handle + 9) : data).nextValue();
                JSONObject media = find(root, shortcode, 0);
                if (media == null) continue;
                return media(media);
            } catch (Exception unrelatedScript) { /* Ignore scripts that do not contain JSON data. */ }
        }
        return result;
    }

    private static Media media(JSONObject node) {
        Media result = new Media();
        result.isVideo = node.optBoolean("is_video") || "GraphVideo".equals(node.optString("__typename")) || node.optInt("media_type") == 2;
        result.video = node.optString("video_url");
        JSONArray versions = node.optJSONArray("video_versions");
        if (result.video.isEmpty() && versions != null && versions.optJSONObject(0) != null) result.video = versions.optJSONObject(0).optString("url");
        if (!result.video.isEmpty()) result.isVideo = true;
        result.image = node.optString("display_url");
        JSONObject candidates = node.optJSONObject("image_versions2");
        JSONArray images = candidates == null ? null : candidates.optJSONArray("candidates");
        if (result.image.isEmpty() && images != null && images.optJSONObject(0) != null)
            result.image = images.optJSONObject(0).optString("url");
        JSONArray children = node.optJSONArray("carousel_media");
        JSONObject sidecar = node.optJSONObject("edge_sidecar_to_children");
        if (children == null && sidecar != null) children = sidecar.optJSONArray("edges");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.optJSONObject(i);
                if (child == null) continue;
                if (child.optJSONObject("node") != null) child = child.optJSONObject("node");
                result.items.addAll(media(child).items);
            }
        } else if (result.isVideo && !result.video.isEmpty()) result.items.add(new Extract.Media(result.video, "", true));
        else if (!result.image.isEmpty()) result.items.add(new Extract.Media(result.image, "", false));
        JSONObject owner = node.optJSONObject("owner");
        if (owner == null) owner = node.optJSONObject("user");
        if (owner != null) result.author = owner.optString("username");
        JSONObject caption = node.optJSONObject("caption");
        if (caption != null) result.caption = caption.optString("text");
        JSONObject captions = node.optJSONObject("edge_media_to_caption");
        JSONArray edges = captions == null ? null : captions.optJSONArray("edges");
        if (edges != null && edges.optJSONObject(0) != null) {
            JSONObject text = edges.optJSONObject(0).optJSONObject("node");
            if (text != null) result.caption = text.optString("text");
        }
        return result;
    }

    private static JSONObject find(Object value, String shortcode, int depth) {
        if (depth > 30) return null;
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            if ((shortcode.equals(o.optString("shortcode")) || shortcode.equals(o.optString("code")))
                && (o.has("is_video") || o.has("video_url") || o.has("video_versions") || o.has("media_type") || o.has("display_url") || o.has("edge_sidecar_to_children"))) return o;
            for (java.util.Iterator<String> keys = o.keys(); keys.hasNext(); ) {
                JSONObject found = find(o.opt(keys.next()), shortcode, depth + 1);
                if (found != null) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) {
                JSONObject found = find(a.opt(i), shortcode, depth + 1);
                if (found != null) return found;
            }
        } else if (value instanceof String && (((String) value).contains("shortcode_media") || ((String) value).contains("xdt_api__v1__media__shortcode__web_info"))) {
            try { return find(new JSONTokener((String) value).nextValue(), shortcode, depth + 1); }
            catch (Exception notJson) { }
        }
        return null;
    }
}
