package me.venkatesh.saveobsidian;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Background work: tag untagged links, then embed whatever has no vector yet. Blocking; runs inside BackgroundJob. */
final class Tagger {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** Set when Android stops the job: the loops end after the call in flight instead of working on unseen. */
    static volatile boolean halt;

    /** Provider and inbox errors end the run and are kept in prefs for Settings; the next job retries. */
    static void run(Context c) {
        String key = Model.key(c);
        if (key.isEmpty() || c.getSharedPreferences("capture", 0).getString("inbox", "").isEmpty() || !RUNNING.compareAndSet(false, true)) return;
        halt = false;
        try {
            CaptureService.sweep(c);
            transcribe(c, key);
            tag(c, key);
            describe(c, key);
            embed(c, key);
            VaultStore.embed(c, key);
            c.getSharedPreferences("capture", 0).edit().remove("aiProblem").apply();
        } catch (Exception stop) {
            String m = String.valueOf(stop.getMessage()); // only provider trouble is worth showing; inbox contention clears itself
            c.getSharedPreferences("capture", 0).edit().putString("aiProblem", m.startsWith("Smart tags") ? m : "").apply();
        } finally { RUNNING.set(false); }
    }

    private static void tag(Context c, String key) throws Exception {
        String model = c.getSharedPreferences("capture", 0).getString("aiModel", Model.DEFAULT);
        Set<String> skipped = new HashSet<>();
        for (int calls = 0; calls < 40 && !SetupActivity.editing && !halt; ) {
            String destination = c.getSharedPreferences("capture", 0).getString("inbox", "");
            InboxFile.Entry next = null;
            List<InboxFile.Entry> all = InboxFile.parse(CaptureService.readText(c));
            for (int i = all.size() - 1; i >= 0 && next == null; i--) {
                InboxFile.Entry e = all.get(i);
                boolean worth = e.text ? e.body.length() >= 40 : !e.url.isEmpty(); // a three-word note needs no summary
                if (worth && (e.type.isEmpty() || e.heading.isEmpty() || e.heading.equals(e.title)) && !skipped.contains(e.raw)) next = e; // heading.isEmpty(): backfill titles for captures tagged before titles existed
            }
            if (next == null) return;
            String[] result = next.text ? Model.enrichNote(key, model, next.body) : Model.enrich(key, model, next.title, next.url);
            calls++;
            if (result == null) { skipped.add(next.raw); continue; }
            if (result[3].isEmpty() && !next.type.isEmpty()) { skipped.add(next.raw); continue; } // already tagged and the model gave no title: leave it, retry next run
            String raw = next.raw, heading = result[3];
            CaptureService.edit(c, destination, CaptureService.readText(c), text -> InboxFile.setEnrich(text, raw, result[0], result[1], result[2], heading));
            c.sendBroadcast(new Intent(CaptureService.CHANGED).setPackage(c.getPackageName()));
        }
    }

    /** Voice clips: transcribe, file the note, then delete the audio. Errors stay on the job so Settings can show them. */
    private static void transcribe(Context c, String key) throws Exception {
        for (File file : CaptureService.pending(c)) {
            if (SetupActivity.editing || halt) return;
            JSONObject job;
            try { job = CaptureService.readJob(file); } catch (Exception unreadable) { continue; }
            if (!"voice".equals(job.optString("kind")) || job.has("error")) continue;
            File audio = new File(job.optString("audio"));
            if (job.optString("entry").isEmpty()) {
                String text;
                try { text = Model.transcribe(key, Files.readAllBytes(audio.toPath()), job.optString("format", "m4a")); }
                catch (Model.Rejected refused) { CaptureService.writeJob(file, job.put("error", refused.getMessage() + ". The recording is kept.")); continue; }
                catch (java.nio.file.NoSuchFileException gone) { new AtomicFile(file).delete(); continue; }
                if (text.isEmpty()) { audio.delete(); new AtomicFile(file).delete(); continue; } // nothing was said
                CaptureService.writeJob(file, job.put("entry", Capture.textEntry(job.getString("time"), text, "Sparkbox voice")).put("label", Capture.clean(text)));
            }
            String entry = job.getString("entry"), destination = c.getSharedPreferences("capture", 0).getString("inbox", "");
            // containsEntry makes a repeat after a crash harmless: the same entry is never appended twice.
            CaptureService.edit(c, destination, CaptureService.readText(c), text -> InboxFile.containsEntry(text, entry) ? text
                : text + Capture.separator(text.getBytes(StandardCharsets.UTF_8)) + entry + "\n");
            audio.delete();
            new AtomicFile(file).delete();
            c.sendBroadcast(new Intent(CaptureService.CHANGED).setPackage(c.getPackageName()));
        }
    }

    /** True while a clip still waits for its transcript, so BackgroundJob asks Android to run again later. */
    static boolean waiting(Context c) {
        for (File file : CaptureService.pending(c)) {
            try { JSONObject job = CaptureService.readJob(file); if ("voice".equals(job.optString("kind")) && !job.has("error")) return true; }
            catch (Exception unreadable) { }
        }
        return false;
    }

    /** Opt-in: photos and PDFs in the vault get a type, tags and summary so they can be found. */
    private static void describe(Context c, String key) throws Exception {
        if (!c.getSharedPreferences("capture", 0).getBoolean("describe", false) || !Attach.ready(c)) return;
        Set<String> skipped = new HashSet<>();
        for (int calls = 0; calls < 20 && !SetupActivity.editing && !halt; ) {
            String destination = c.getSharedPreferences("capture", 0).getString("inbox", "");
            InboxFile.Entry next = null;
            for (InboxFile.Entry e : InboxFile.parse(CaptureService.readText(c))) {
                String ext = Attach.extension(e.file);
                if (!e.file.isEmpty() && e.type.isEmpty() && !skipped.contains(e.raw) && (ext.equals("pdf") || Enrich.IMAGES.contains(ext))) next = e;
            }
            if (next == null) return;
            skipped.add(next.raw);
            Uri uri = Attach.find(c, next.file);
            if (uri == null) continue;
            boolean pdf = Attach.extension(next.file).equals("pdf");
            String[] result;
            try {
                byte[] data = pdf ? Attach.read(c, uri, 10 * 1024 * 1024) : Attach.jpeg(c, uri, 1024);
                if (data == null) continue;
                result = Model.describe(key, next.file, pdf, data);
                calls++;
            } catch (Model.Rejected | java.io.FileNotFoundException | SecurityException refused) { continue; } // this file only; the rest still run
            if (result == null) continue;
            String raw = next.raw;
            CaptureService.edit(c, destination, CaptureService.readText(c), text -> InboxFile.setEnrich(text, raw, result[0], result[1], result[2], result[3]));
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
        for (int i = 0; i < Math.min(todo.size(), 320) && !halt; i += 32) {
            List<String> batch = new ArrayList<>(todo.subList(i, Math.min(i + 32, todo.size())));
            List<float[]> vectors = Model.embed(key, batch);
            for (int j = 0; j < batch.size(); j++) index.put(batch.get(j), vectors.get(j));
            index.save(file);
        }
        if (todo.isEmpty()) index.save(file); // persists the retain() pruning
    }
}
