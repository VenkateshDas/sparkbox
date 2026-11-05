package me.venkatesh.saveobsidian;

import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Background work: tag untagged links, then embed whatever has no vector yet. Blocking; runs inside BackgroundJob. */
final class Tagger {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    /** Provider and inbox errors end the run and are kept in prefs for Settings; the next job retries. */
    static void run(Context c) {
        String key = Model.key(c);
        if (key.isEmpty() || c.getSharedPreferences("capture", 0).getString("inbox", "").isEmpty() || !RUNNING.compareAndSet(false, true)) return;
        try {
            tag(c, key);
            embed(c, key);
            c.getSharedPreferences("capture", 0).edit().remove("aiProblem").apply();
        } catch (Exception stop) {
            String m = String.valueOf(stop.getMessage()); // only provider trouble is worth showing; inbox contention clears itself
            c.getSharedPreferences("capture", 0).edit().putString("aiProblem", m.startsWith("Smart tags") ? m : "").apply();
        } finally { RUNNING.set(false); }
    }

    private static void tag(Context c, String key) throws Exception {
        String model = c.getSharedPreferences("capture", 0).getString("aiModel", Model.DEFAULT);
        Set<String> skipped = new HashSet<>();
        for (int calls = 0; calls < 40 && !SetupActivity.editing; ) {
            String destination = c.getSharedPreferences("capture", 0).getString("inbox", "");
            InboxFile.Entry next = null;
            List<InboxFile.Entry> all = InboxFile.parse(CaptureService.readText(c));
            for (int i = all.size() - 1; i >= 0 && next == null; i--) {
                InboxFile.Entry e = all.get(i);
                if (!e.text && !e.url.isEmpty() && e.type.isEmpty() && !skipped.contains(e.raw)) next = e;
            }
            if (next == null) return;
            String[] result = Model.enrich(key, model, next.title, next.url);
            calls++;
            if (result == null) { skipped.add(next.raw); continue; }
            String raw = next.raw;
            CaptureService.edit(c, destination, CaptureService.readText(c), text -> InboxFile.setEnrich(text, raw, result[0], result[1], result[2]));
            c.sendBroadcast(new Intent(CaptureService.CHANGED).setPackage(c.getPackageName()));
        }
    }

    /** Up to 320 new vectors per run, saved after every batch so a stopped job keeps its progress. */
    private static void embed(Context c, String key) throws Exception {
        File file = new File(c.getFilesDir(), "index.bin");
        Index index = Index.load(file);
        List<InboxFile.Entry> entries = InboxFile.parse(CaptureService.readText(c));
        index.retain(entries);
        List<String> todo = index.missing(entries);
        for (int i = 0; i < Math.min(todo.size(), 320); i += 32) {
            List<String> batch = new ArrayList<>(todo.subList(i, Math.min(i + 32, todo.size())));
            List<float[]> vectors = Model.embed(key, batch);
            for (int j = 0; j < batch.size(); j++) index.put(batch.get(j), vectors.get(j));
            index.save(file);
        }
        if (todo.isEmpty()) index.save(file); // persists the retain() pruning
    }
}
