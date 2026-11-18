package me.venkatesh.saveobsidian;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The readable text fetched for a link while it was tagged, kept privately so Ask can use it without fetching the page again. */
final class Content {
    private static String name(String url) { return Long.toHexString(Index.key(Capture.identity(url))); }

    private static File file(Context c, String url) { return new File(new File(c.getFilesDir(), "content"), name(url) + ".txt"); }

    static void save(Context c, String url, String text) {
        try {
            File f = file(c, url);
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { } // a cache: losing it only costs a refetch
    }

    private static File mediaFile(Context c, String url) { return new File(file(c, url).getParentFile(), name(url) + ".media.json"); }

    static org.json.JSONObject media(Context c, String url) {
        try { return CaptureService.readJob(mediaFile(c, url)); }
        catch (Exception none) { return new org.json.JSONObject(); }
    }

    static void saveMedia(Context c, String url, org.json.JSONObject data) throws Exception {
        File f = mediaFile(c, url);
        if (!f.getParentFile().isDirectory() && !f.getParentFile().mkdirs()) throw new java.io.IOException("Cannot save visual checkpoint");
        CaptureService.writeJob(f, data);
    }

    static void remove(Context c, String url) { file(c, url).delete(); mediaFile(c, url).delete(); }

    static boolean has(Context c, String url) { return file(c, url).length() > 0; }

    /** Up to max characters of the saved page text, or "". */
    static String load(Context c, String url, int max) {
        try {
            String text = new String(Files.readAllBytes(file(c, url).toPath()), StandardCharsets.UTF_8);
            return text.length() > max ? text.substring(0, max) : text;
        } catch (Exception none) { return ""; }
    }

    /** Drops text of captures that are no longer in the inbox. */
    static void retain(Context c, List<InboxFile.Entry> entries) {
        Set<String> keep = new HashSet<>();
        for (InboxFile.Entry e : entries) if (!e.sourceId().isEmpty()) {
            keep.add(name(e.sourceId()) + ".txt");
            keep.add(name(e.sourceId()) + ".media.json");
        }
        File[] saved = new File(c.getFilesDir(), "content").listFiles();
        if (saved != null) for (File f : saved) if (!keep.contains(f.getName())) f.delete();
    }
}
