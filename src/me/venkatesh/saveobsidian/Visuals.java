package me.venkatesh.saveobsidian;

import android.content.Context;
import java.io.IOException;
import org.json.JSONObject;

/** Source-scoped visual checkpoints. Each paid result survives a later network or job interruption. */
final class Visuals {
    static boolean complete(Context c, String source) {
        JSONObject saved = Content.media(c, source);
        return saved.optBoolean("complete") && (Capture.arxivPdfUrl(source).isEmpty() || saved.optBoolean("pdf"));
    }

    static String read(Context c, String key, String source, Fetch.Result page) throws Exception {
        JSONObject saved = Content.media(c, source);
        StringBuilder visual = new StringBuilder();
        org.json.JSONArray order = new org.json.JSONArray();
        int calls = 0, ordinal = 0;
        for (Extract.Media media : page.media) {
            if (Tagger.halt()) throw new Net.Deferred("Processing paused");
            String identity = Instagram.shortcode(source).isEmpty() ? media.url
                : ordinal + "|" + media.video + "|" + new java.net.URL(media.url).getPath(); // CDN signatures rotate between passes
            String id = Long.toHexString(Index.key(identity));
            order.put(id);
            String text = saved.optString(id);
            ordinal++;
            if (text.isEmpty()) {
                // ponytail: three new media per slice; the next persisted job continues larger carousels.
                if (calls >= 3) throw new Net.Deferred("More images are queued for the next processing pass");
                Web.Reply reply = Web.get(media.url, media.video ? 24 * 1024 * 1024 : 6 * 1024 * 1024);
                String mime = reply.type.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
                if (reply.code == 429 || reply.code >= 500) throw new Net.Deferred("The media host answered HTTP " + reply.code);
                if (reply.code != 200) throw new IOException("Source media could not be read (HTTP " + reply.code + "). Retry or share the media file.");
                if (media.video) {
                    if (!mime.startsWith("video/")) throw new IOException("Source did not provide the video. Share the media file.");
                    text = Model.watch(key, reply.body, mime);
                } else {
                    if (!mime.startsWith("image/")) throw new IOException("Source did not provide an image. Share the media file.");
                    byte[] jpeg = Attach.jpeg(reply.body, 2048);
                    if (jpeg == null) throw new IOException("Source image could not be decoded. Share the image file.");
                    String context = page.title + "\n" + media.caption;
                    text = Model.readImage(key, jpeg, context.length() > 1500 ? context.substring(0, 1500) : context);
                }
                saved.put(id, text);
                Content.saveMedia(c, source, saved);
                calls++;
            }
            visual.append("\n\n#### ").append(media.video ? "Video " : "Image ").append(ordinal)
                .append(" (AI interpretation)\n").append(text);
        }
        String combined = visual.length() == 0 ? page.text
            : "### Visual content (AI interpretation of source media)" + visual + "\n\n### Page text\n" + page.text;
        Content.save(c, source, combined);
        saved.put("order", order).put("page", page.text).put("pdf", page.pdf).put("complete", true);
        Content.saveMedia(c, source, saved);
        return combined;
    }
    /** Give every inspected slide space in the plan, even when a carousel exceeds the text budget. */
    static String prompt(Context c, String source, int max) {
        JSONObject saved = Content.media(c, source);

        org.json.JSONArray order = saved.optJSONArray("order");
        String page = saved.optString("page");
        if (saved.optBoolean("pdf")) return IngestText.pdfPrompt(page, Ingest.PDF_FOR_MODEL);
        if (order == null || order.length() == 0) return page.length() > max ? page.substring(0, max) : page;
        StringBuilder out = new StringBuilder("Visual evidence (AI interpretation; excerpts from every inspected item):\n");
        int each = Math.max(1, (max / 2 - order.length() * 20) / order.length());
        for (int i = 0; i < order.length(); i++) {
            String text = saved.optString(order.optString(i));
            out.append("\nItem ").append(i + 1).append(": ").append(text.length() > each ? text.substring(0, each) + "…" : text);
        }
        out.append("\n\nPage text:\n");
        int remaining = Math.max(0, max - out.length());
        out.append(page.length() > remaining ? page.substring(0, remaining) : page);
        return out.toString();
    }

}
