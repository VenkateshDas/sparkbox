package me.venkatesh.saveobsidian;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Model downloads belong to Android so they continue when Sparkbox closes. */
public final class PdfModel extends BroadcastReceiver {
    private static final String URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/2d74ac41a33d7da82f39f7d5720a04c68514a103/gemma-4-E2B-it.litertlm";
    private static final long BYTES = 2_588_147_712L;
    private static final String MODEL = "pdf-vision.litertlm", PART = "pdf-vision.download";

    record Status(boolean downloading, String label) { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("pdf-model", 0); }
    private static DownloadManager manager(Context c) { return c.getSystemService(DownloadManager.class); }
    private static File legacy(Context c) { return new File(c.getNoBackupFilesDir(), MODEL); }
    private static File external(Context c, String name) {
        File folder = c.getExternalFilesDir(null);
        return folder == null ? null : new File(folder, name);
    }

    static File file(Context c) {
        File old = legacy(c);
        File added = external(c, MODEL);
        return added == null || old.isFile() ? old : added;
    }

    enum Mode { COMPATIBLE, LOCAL, REMOTE }
    static Mode mode(Context c) { return Mode.valueOf(prefs(c).getString("mode", Mode.COMPATIBLE.name())); }
    static void mode(Context c, Mode mode) {
        prefs(c).edit().putString("mode", mode.name()).apply();
        BackgroundJob.schedule(c);
    }
    static boolean figures(Context c) { return prefs(c).getBoolean("figures", mode(c) == Mode.COMPATIBLE); }
    static void figures(Context c, boolean enabled) { prefs(c).edit().putBoolean("figures", enabled).apply(); }
    static boolean local(Context c) { return mode(c) == Mode.LOCAL; }
    static boolean localReady(Context c) { return local(c) && PdfCapture.modelReady(c); }

    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", true); }

    static void enable(Context c, boolean enabled) {
        prefs(c).edit().putBoolean("enabled", enabled).apply();
        if (enabled) BackgroundJob.schedule(c);
    }

    static synchronized void download(Context c) throws IOException {
        if (file(c).isFile()) { enable(c, true); return; }
        if (status(c).downloading()) return;
        File part = external(c, PART);
        if (part == null) throw new IOException("Phone storage is unavailable.");
        if (part.getParentFile().getUsableSpace() < BYTES) throw new IOException("Free at least 2.59 GB on this phone.");
        Files.deleteIfExists(part.toPath());
        long id = manager(c).enqueue(new DownloadManager.Request(Uri.parse(URL))
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setTitle("Sparkbox on-device model")
            .setDestinationInExternalFilesDir(c, null, PART)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE));
        prefs(c).edit().putLong("download", id).remove("error").commit();
    }

    static synchronized void cancel(Context c) {
        long id = prefs(c).getLong("download", -1);
        if (id >= 0) manager(c).remove(id);
        prefs(c).edit().remove("download").remove("error").apply();
    }

    static synchronized void delete(Context c) throws IOException {
        cancel(c);
        enable(c, false);
        Files.deleteIfExists(legacy(c).toPath());
        File[] caches = c.getCacheDir().listFiles((dir, name) -> name.startsWith(MODEL + "_") && name.endsWith(".xnnpack_cache"));
        if (caches != null) for (File cache : caches) Files.deleteIfExists(cache.toPath());
        File added = external(c, MODEL);
        if (added != null) Files.deleteIfExists(added.toPath());
    }

    static synchronized Status status(Context c) {
        SharedPreferences p = prefs(c);
        long id = p.getLong("download", -1);
        if (id >= 0) {
            try (var cursor = manager(c).query(new DownloadManager.Query().setFilterById(id))) {
                if (cursor != null && cursor.moveToFirst()) {
                    int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (state == DownloadManager.STATUS_SUCCESSFUL) {
                        File part = external(c, PART), target = external(c, MODEL);
                        if (part == null || target == null || part.length() != BYTES) throw new IOException("Incomplete model file.");
                        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                        cancel(c);
                        enable(c, true);
                    } else if (state == DownloadManager.STATUS_FAILED) {
                        throw new IOException("Model download failed. Tap to retry.");
                    } else {
                        long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        return new Status(true, state == DownloadManager.STATUS_PAUSED ? "Download paused"
                            : "Downloading " + Math.min(99, Math.max(0, bytes) * 100 / BYTES) + "%");
                    }
                } else throw new IOException("Model download was interrupted. Tap to retry.");
            } catch (IOException | RuntimeException failed) {
                cancel(c);
                p.edit().putString("error", "Download failed. Tap to retry.").apply();
            }
        }
        if (file(c).isFile()) return new Status(false, enabled(c) ? "Loaded" : "Unloaded");
        return new Status(false, p.getString("error", "Download model · 2.59 GB"));
    }

    @Override public void onReceive(Context c, Intent intent) {
        long id = prefs(c).getLong("download", -1);
        if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())
            && id >= 0 && id == intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)) status(c);
    }
}
