package me.venkatesh.saveobsidian;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Thumbnail for a capture: the page's og:image, a YouTube frame, or the attached photo. A coloured letter tile stands in until (or unless) one exists. */
final class Thumb {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final long RETRY_MS = 3L * 24 * 3600 * 1000; // a page with no image is asked again after 3 days

    /** Square or banner view: tile first, image swapped in when loaded. */
    static View view(Context c, Ui ui, InboxFile.Entry e, int widthDp, int heightDp) {
        String seed = e.file.isEmpty() ? (e.url.isEmpty() ? e.type + e.title : Ui.host(e.url)) : e.file;
        boolean dark = Color.luminance(ui.bg) < 0.5f;
        float[] hsv = {Math.abs(seed.hashCode()) % 360, dark ? 0.35f : 0.25f, dark ? 0.38f : 0.92f};
        FrameLayout box = new FrameLayout(c);
        box.setBackground(ui.round(Color.HSVToColor(hsv), 0, 10));
        box.setClipToOutline(true);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), ui.dp(10)); }
        });
        String glyph = e.text || (e.url.isEmpty() && e.file.isEmpty()) ? "✎" : !e.file.isEmpty() ? "▤" : Ui.host(e.url).isEmpty() ? "•" : Ui.host(e.url).substring(0, 1).toUpperCase();
        TextView letter = ui.text(glyph, heightDp > 80 ? 36 : 20, Ui.MEDIUM, dark ? 0xFFDDDDDD : 0xFF444444);
        box.addView(letter, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.setLayoutParams(new ViewGroup.LayoutParams(widthDp > 0 ? ui.dp(widthDp) : ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(heightDp)));
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        Handler main = new Handler(Looper.getMainLooper());
        POOL.execute(() -> {
            Bitmap b = get(c, e);
            if (b != null) main.post(() -> { image.setImageBitmap(b); letter.setVisibility(View.GONE); });
        });
        return box;
    }

    static Bitmap get(Context c, InboxFile.Entry e) {
        String id = e.file.isEmpty() ? e.url : e.file;
        if (id.isEmpty() || e.text) return null;
        File dir = new File(c.getCacheDir(), "thumbs");
        dir.mkdirs();
        String key = Long.toHexString(Index.key(id));
        File jpg = new File(dir, key + ".jpg"), none = new File(dir, key + ".none");
        if (jpg.exists()) return BitmapFactory.decodeFile(jpg.getPath());
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < RETRY_MS) return null;
        Bitmap b = null;
        try { b = scale(fetch(c, e)); } catch (Exception offline) { return null; } // no network now: try again next time
        try {
            if (b == null) { none.createNewFile(); none.setLastModified(System.currentTimeMillis()); return null; }
            try (FileOutputStream out = new FileOutputStream(jpg)) { b.compress(Bitmap.CompressFormat.JPEG, 85, out); }
        } catch (Exception ignored) { } // cache write is best effort
        return b;
    }

    private static byte[] fetch(Context c, InboxFile.Entry e) throws Exception {
        if (!e.file.isEmpty()) {
            Uri uri = Attach.find(c, e.file);
            return uri != null && Enrich.IMAGES.contains(Attach.extension(e.file)) ? Attach.jpeg(c, uri, 480) : null;
        }
        Matcher yt = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/))([\\w-]{11})").matcher(e.url);
        if (yt.find()) return PageTitle.image("https://i.ytimg.com/vi/" + yt.group(1) + "/hqdefault.jpg");
        String src = ogImage(PageTitle.html(e.url, false));
        return src.isEmpty() ? null : PageTitle.image(new URL(new URL(e.url), src).toString());
    }

    /** The page's preview image URL, or "". */
    static String ogImage(String html) {
        Matcher tag = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (tag.find()) {
            String t = tag.group();
            if (!Pattern.compile("(?:property|name)\\s*=\\s*[\"'](?:og:image(?::url)?|twitter:image)[\"']", Pattern.CASE_INSENSITIVE).matcher(t).find()) continue;
            Matcher content = Pattern.compile("content\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)')", Pattern.CASE_INSENSITIVE).matcher(t);
            if (content.find()) return (content.group(1) != null ? content.group(1) : content.group(2)).replace("&amp;", "&").trim();
        }
        return "";
    }

    private static Bitmap scale(byte[] data) {
        if (data == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = Math.max(1, Math.max(o.outWidth, o.outHeight) / 480);
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }
}
