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

/** Background work: tag untagged links, then embed whatever has no vector yet. Blocking; runs inside BackgroundJob. */
final class Tagger {
    /** One run at a time, each with its own stop switch. A run silent for 20 minutes is asked to stop; a new one waits for its thread to end. */
    static final RunGate GATE = new RunGate();
    /** True when this thread's run was told to stop (Android ended the job): loops end after the call in flight. */
    private static final ThreadLocal<Long> deadline = new ThreadLocal<>();
    static boolean halt() {
        Long until = deadline.get();
        return Cancel.current().stopped() || (until != null && android.os.SystemClock.elapsedRealtime() >= until);
    }

    /** Provider and inbox errors end the run and are kept in prefs for Settings; the next job retries. */
    static void run(Context c, Cancel cancel) {
        String key = Model.key(c);

        try { if (GATE.enter(20 * 60000L, 30000L, cancel) == null) return; } catch (InterruptedException i) { return; } // busy: that run looks again when done
        deadline.set(android.os.SystemClock.elapsedRealtime() + 7 * 60_000L);
        String problem = "";
        try {
            if (Ingest.ready(c) && !c.getSharedPreferences("capture", 0).getString("inbox", "").isEmpty())
                Ingest.runPdfAttachments(c, key);
            DirectCapture.run(c, key);
            if (c.getSharedPreferences("capture", 0).getString("inbox", "").isEmpty()) return;
            String[] stages;
            if (!key.isEmpty()) stages = new String[]{"sweep", "tag", "file", "transcribe", "describe", "cards", "backfill", "embed"};
            else {
                if (Ingest.ready(c)) Ingest.run(c, key);
                return;
            }
            for (String stage : stages) {
                if (halt()) break;
                long stageBegan = System.currentTimeMillis();
                try {
                    switch (stage) {
                        case "sweep": CaptureService.sweep(c); break;
                        case "transcribe": transcribe(c, key); break;
                        case "tag": tag(c, key); break;
                        case "backfill": backfill(c); break;
                        case "file": if (Ingest.ready(c)) Ingest.run(c, key); else fileNotes(c); break; // ingest files each capture into Knowledge; the Captures folder is the lighter option
                        case "cards": if (CardGen.on(c)) CardGen.run(c); break; // a batch of cards for existing notes, when the owner started it
                        case "describe": describe(c, key); break;
                        default: try (Metrics.Operation metrics = Metrics.operation("Background indexing", java.util.UUID.randomUUID().toString(), "embeddings")) { embed(c, key); VaultStore.embed(c, key); metrics.done(); }
                    }
                    android.util.Log.i("Sparkbox", "stage " + stage + " " + (System.currentTimeMillis() - stageBegan) + " ms");
                } catch (Exception stop) {
                    android.util.Log.i("Sparkbox", "stage " + stage + " stopped after " + (System.currentTimeMillis() - stageBegan) + " ms: " + stop);
                    String m = String.valueOf(stop.getMessage()); // only provider trouble is worth showing; inbox contention clears itself
                    if (m.startsWith("Smart tags") && problem.isEmpty()) problem = m;
                }
            }
        } finally {
            c.getSharedPreferences("capture", 0).edit().putString("aiProblem", problem).apply();
            deadline.remove();
            GATE.exit();
        }
    }

    private static void tag(Context c, String key) throws Exception {
        String model = c.getSharedPreferences("capture", 0).getString("aiModel", Model.DEFAULT);
        Set<String> skipped = new HashSet<>();
        for (int calls = 0; calls < 8 && !halt(); ) {
            String destination = c.getSharedPreferences("capture", 0).getString("inbox", "");
            InboxFile.Entry next = null;
            List<InboxFile.Entry> all = InboxFile.parse(CaptureService.readText(c));
            for (int i = all.size() - 1; i >= 0 && next == null; i--) {
                InboxFile.Entry e = all.get(i);
                boolean worth = (e.text ? e.body.length() >= IngestText.MIN_NOTE : !e.url.isEmpty()) && !Ingest.ready(c); // a three-word note needs no summary; with processing on, links are tagged by the ingest call itself
                if (worth && (e.type.isEmpty() || e.heading.isEmpty() || e.heading.equals(e.title) || stale(c, e)) && !skipped.contains(e.raw)) next = e; // heading.isEmpty(): backfill titles for captures tagged before titles existed
            }
            if (next == null) return;
            String pageUrl = next.url;
            String[] result;
            try (Metrics.Operation metrics = Tracking.capture(c, next, "enrichment")) {
                result = next.text ? Model.enrichNote(key, model, next.body, next.why, next.ideas) : Model.enrich(c, key, model, next.title, next.url, next.why, next.ideas, page -> Content.save(c, pageUrl, page));
                metrics.outcome("enriched");
            }
            catch (Exception failed) {
                if (String.valueOf(failed.getMessage()).startsWith("Smart tags paused: OpenRouter answered 40") || Net.offline(failed)) throw failed; // bad key or no credit: every call would fail
                result = null; // a dropped connection or a refusal for this one capture: the others still get their turn
            }
            calls++;
            if (result == null) {
                skipped.add(next.raw);
                android.content.SharedPreferences seen = c.getSharedPreferences("enriched", 0);
                int failures = seen.getInt("tagfail:" + notesKey(next), 0) + 1;
                seen.edit().putInt("tagfail:" + notesKey(next), failures).apply();
                if (failures >= 2 && !next.text && !next.url.isEmpty() && next.type.isEmpty()) { // the model cannot tag it: give it a plain type so processing can go ahead
                    final String raw = next.raw, kind = guessType(next.url), clean = Capture.clean(next.title), heading = clean.isEmpty() ? Feed.host(next.url) : clean;
                    CaptureService.edit(c, destination, CaptureService.readText(c), text -> InboxFile.setEnrich(text, raw, kind, "", "", heading));
                    c.sendBroadcast(new Intent(CaptureService.CHANGED).setPackage(c.getPackageName()));
                }
                continue;
            }
            c.getSharedPreferences("enriched", 0).edit().putString(notesKey(next), notesHash(next)).apply(); // this Why and Ideas are now reflected, so it is not redone
            if (result[3].isEmpty() && !next.type.isEmpty()) { skipped.add(next.raw); continue; } // already tagged and the model gave no title: leave it, retry next run
            final String raw = next.raw, heading = result[3];
            final String[] tagged = result;
            CaptureService.edit(c, destination, CaptureService.readText(c), text -> InboxFile.setEnrich(text, raw, tagged[0], tagged[1], tagged[2], heading));
            c.sendBroadcast(new Intent(CaptureService.CHANGED).setPackage(c.getPackageName()));
        }
    }

    static String notesKey(InboxFile.Entry e) { return Long.toHexString(Index.key(e.time + "|" + e.sourceId() + "|" + (e.text ? e.body : ""))); }
    private static String guessType(String url) {
        String host = Feed.host(url);
        if (!Transcript.videoId(url).isEmpty() || host.equals("vimeo.com")) return "video";
        if (host.equals("github.com") || host.equals("gitlab.com")) return "repo";
        if (host.equals("arxiv.org")) return "paper";
        return "article";
    }

    static String notesHash(InboxFile.Entry e) { return Long.toHexString(Index.key(e.why.trim() + "\n" + e.ideas.trim())); }

    /** Tagged before you wrote a Why or Idea (or changed it since): summarise once more so the summary follows your instructions. */
    static boolean stale(Context c, InboxFile.Entry e) {
        boolean noted = !e.why.trim().isEmpty() || !e.ideas.trim().isEmpty();
        return noted && !e.type.isEmpty() && !notesHash(e).equals(c.getSharedPreferences("enriched", 0).getString(notesKey(e), ""));
    }

    private static void fileNotes(Context c) {
        android.content.SharedPreferences.Editor note = c.getSharedPreferences("capture", 0).edit();
        try { Captures.file(c); note.remove("capturesProblem"); }
        catch (Exception problem) { note.putString("capturesProblem", String.valueOf(problem.getMessage())); }
        note.apply();
    }

    /** Links tagged before page text was kept: fetch it once (no model call), ten per run. A page that fails is not retried. */
    private static void backfill(Context c) throws Exception {
        android.content.SharedPreferences tried = c.getSharedPreferences("enriched", 0);
        int fetched = 0;
        for (InboxFile.Entry e : InboxFile.parse(CaptureService.readText(c))) {
            if (fetched >= 3 || halt()) return;
            String mark = "page:" + Long.toHexString(Index.key(Capture.identity(e.url)));
            if (e.text || e.url.isEmpty() || e.type.isEmpty() || Content.has(c, e.url) || tried.getBoolean(mark, false)) continue;
            fetched++;
            try {
                String page = Fetch.content(c, e.url, "", false);
                if (!page.isEmpty()) Content.save(c, e.url, page);
                tried.edit().putBoolean(mark, true).apply(); // answered (even with nothing usable): do not ask again. A network error is retried next run.
            } catch (Exception offline) { }
        }
    }

    /** Voice clips: transcribe, file the note, then delete the audio. Errors stay on the job so Settings can show them. */
    private static void transcribe(Context c, String key) throws Exception {
        for (File file : CaptureService.pending(c)) {
            if (halt()) return;
            JSONObject job;
            try { job = CaptureService.readJob(file); } catch (Exception unreadable) { continue; }
            if (DirectCapture.direct(job) || !"voice".equals(job.optString("kind")) || job.has("error")) continue;
            File audio = new File(job.optString("audio"));
            if (job.optString("entry").isEmpty()) {
                String text;
                try (Metrics.Operation metrics = Metrics.operation("Capture processing", Tracking.scope(c, job.optString("captureId")), "transcription")) {
                    text = Model.transcribe(key, Files.readAllBytes(audio.toPath()), job.optString("format", "m4a")); metrics.outcome("transcribed");
                }
                catch (Model.Rejected refused) { CaptureService.writeJob(file, job.put("error", refused.getMessage() + ". The recording is kept.")); continue; }
                catch (java.nio.file.NoSuchFileException gone) { new AtomicFile(file).delete(); continue; }
                if (text.isEmpty()) { audio.delete(); new AtomicFile(file).delete(); continue; } // nothing was said
                CaptureService.writeJob(file, job.put("entry", Capture.textEntry(job.getString("time"), text, "Sparkbox voice")).put("label", Capture.clean(text)));
            }
            Tracking.linked(c, job, job.getString("entry"));
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
            try { JSONObject job = CaptureService.readJob(file); if (("voice".equals(job.optString("kind")) || DirectCapture.direct(job)) && !job.has("error") && !Model.key(c).isEmpty()) return true; }
            catch (Exception unreadable) { }
        }
        return false;
    }

    /** Opt-in: photos and PDFs in the vault get a type, tags and summary so they can be found. */
    private static void describe(Context c, String key) throws Exception {
        if (!c.getSharedPreferences("capture", 0).getBoolean("describe", false) || !Attach.ready(c)) return;
        boolean ingesting = Ingest.ready(c);
        Set<String> skipped = new HashSet<>();
        for (int calls = 0; calls < 20 && !halt(); ) {
            String destination = c.getSharedPreferences("capture", 0).getString("inbox", "");
            InboxFile.Entry next = null;
            for (InboxFile.Entry e : InboxFile.parse(CaptureService.readText(c))) {
                if (ingesting && IngestText.eligible(e)) continue;
                String ext = Attach.extension(e.file);
                if (!e.file.isEmpty() && e.type.isEmpty() && !skipped.contains(e.raw) && (ext.equals("pdf") || Enrich.IMAGES.contains(ext) || Enrich.VIDEOS.contains(ext))) next = e;
            }
            if (next == null) return;
            skipped.add(next.raw);
            Uri uri = Attach.find(c, next.file);
            if (uri == null) continue;
            boolean pdf = Attach.extension(next.file).equals("pdf");
            boolean video = Enrich.VIDEOS.contains(Attach.extension(next.file));
            String[] result;
            try {
                try (Metrics.Operation metrics = Tracking.capture(c, next, "vision")) {
                    if (pdf) {
                        String text;
                        try { text = PdfCapture.read(c, uri); }
                        catch (java.io.IOException unreadable) { continue; }
                        result = Model.describePdfText(key, text);
                    } else {
                        byte[] data = video ? Attach.read(c, uri, 24 * 1024 * 1024) : Attach.jpeg(c, uri, 1024);
                        if (data == null) continue;
                        result = video ? Model.describeVideo(key, next.file, data) : Model.describe(key, next.file, data);
                    }
                    metrics.outcome("described");
                }
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
        Content.retain(c, entries);
        List<String> todo = index.missing(entries);
        for (int i = 0; i < Math.min(todo.size(), 320) && !halt(); i += 32) {
            List<String> batch = new ArrayList<>(todo.subList(i, Math.min(i + 32, todo.size())));
            List<float[]> vectors = Model.embed(key, batch);
            for (int j = 0; j < batch.size(); j++) index.put(batch.get(j), vectors.get(j));
            index.save(file);
        }
        if (todo.isEmpty()) index.save(file); // persists the retain() pruning
    }
}
