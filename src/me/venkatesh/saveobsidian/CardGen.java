package me.venkatesh.saveobsidian;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Makes a curiosity question and recall cards for every Source note that has none. Resumable: a note counts as done once it has a `cardv` key, so a
 * stopped run picks up where it left off and never repeats a note. Runs in small batches, newest notes first, and stops on a provider error.
 */
final class CardGen {
    static final int BATCH = 48, WORKERS = 12, TRIES = 3;
    static final String DEFAULT_MODEL = "z-ai/glm-5.3-flash";
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    static volatile boolean stop;
    private static volatile boolean reasoningOff = true;

    private CardGen() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("cardgen", 0); }

    static boolean on(Context c) { return prefs(c).getBoolean("on", false); }
    static void on(Context c, boolean on) { prefs(c).edit().putBoolean("on", on).apply(); }
    static String problem(Context c) { return prefs(c).getString("problem", ""); }
    static boolean running() { return RUNNING.get(); }

    /** Picks the run back up when the app opens, if the owner started it and has not paused it. */
    static void resume(Context c) {
        if (on(c) && !running()) { final Context app = c.getApplicationContext(); new Thread(() -> loop(app)).start(); }
    }

    static String model(Context c) { return c.getSharedPreferences("capture", 0).getString("cardModel", DEFAULT_MODEL); }

    /** Source notes eligible for cards: the notes themselves, not indexes, the inbox or the owner's own card file. */
    private static boolean eligible(Vault.Note n) { return Layout.inSources(n.path) && !n.catalog && !n.path.endsWith("/My cards.md") && !n.path.endsWith("/Inbox.md"); }

    /** Notes still to do, newest first. */
    static List<Vault.Note> todo(VaultStore.Snap snap) {
        List<Vault.Note> out = new ArrayList<>();
        for (Vault.Note n : snap.graph.notes) if (eligible(n) && n.meta("cardv").isEmpty()) out.add(n);
        Collections.sort(out, (a, b) -> {
            boolean[] t = new boolean[1];
            java.time.LocalDateTime x = Feed.when(a.meta("captured"), t), y = Feed.when(b.meta("captured"), t);
            int c = x == null ? (y == null ? 0 : 1) : y == null ? -1 : y.compareTo(x); // newest first, notes without a date last: a consistent order, or the sort throws
            return c != 0 ? c : Long.compare(b.mtime, a.mtime);
        });
        return out;
    }

    /** {done, total} Source notes. */
    static int[] progress(VaultStore.Snap snap) {
        int done = 0, total = 0;
        for (Vault.Note n : snap.graph.notes) if (eligible(n)) { total++; if (!n.meta("cardv").isEmpty()) done++; }
        return new int[]{done, total};
    }

    /** Rough size of what is left: {notes, characters of page text, estimated input tokens}. Page text is capped at 12,000 characters a note. */
    static long[] estimate(VaultStore.Snap snap) {
        long chars = 0;
        List<Vault.Note> todo = todo(snap);
        for (Vault.Note n : todo) chars += Math.min(CardPrompt.RAW_MAX, n.words * 6L) + 900; // words to characters, plus the claims and the prompt
        return new long[]{todo.size(), chars, chars / 4};
    }

    private static int tries(Context c, String path) { return prefs(c).getInt("t:" + Long.toHexString(Index.key(path)), 0); }
    private static void tried(Context c, String path, int n) { prefs(c).edit().putInt("t:" + Long.toHexString(Index.key(path)), n).apply(); }

    private static final class Result { Vault.Note note; Exception error; boolean skipped; }

    /**
     * One batch: up to BATCH notes, worked on by WORKERS at a time. The model calls are the slow part and are independent, so they overlap; progress
     * (the scan that moves the count, the failure bookkeeping) is handled here one result at a time. Returns true when it did work, so a loop knows whether to go on.
     */
    static boolean run(Context c) {
        String key = Model.key(c);
        if (key.isEmpty() || !VaultWrite.writable(c)) {
            prefs(c).edit().putString("problem", key.isEmpty() ? "Add your OpenRouter key in Setup." : "Allow write access to your vault folder in Setup.").apply();
            return false;
        }
        VaultStore.Snap snap = VaultStore.cached(c);
        if (snap == null) return false;
        List<Vault.Note> todo = todo(snap);
        final List<Vault.Note> batch = new ArrayList<>(todo.subList(0, Math.min(BATCH, todo.size())));
        if (batch.isEmpty()) return false;
        final java.util.concurrent.atomic.AtomicBoolean abort = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(WORKERS, batch.size()));
        java.util.concurrent.CompletionService<Result> finished = new java.util.concurrent.ExecutorCompletionService<>(pool);
        for (final Vault.Note n : batch) finished.submit(() -> {
            Result r = new Result();
            r.note = n;
            if (abort.get() || stop || Tagger.halt()) { r.skipped = true; return r; }
            try { process(c, key, n); } catch (Exception e) { r.error = e; }
            return r;
        });
        int did = 0, streak = 0;
        try {
            for (int i = 0; i < batch.size(); i++) {
                Result r;
                try { r = finished.take().get(); } catch (Exception interrupted) { break; }
                if (r.skipped) continue;
                if (r.error == null) {
                    did++;
                    streak = 0;
                    try { VaultStore.scan(c); } catch (Exception ignored) { } // re-reads only the changed notes, so the progress count moves note by note
                    prefs(c).edit().remove("problem").apply();
                } else if (r.error instanceof Provider) {
                    prefs(c).edit().putString("problem", r.error.getMessage()).apply();
                    abort.set(true);
                } else if (r.error instanceof VaultWrite.Stale) {
                    // the note was edited while the model worked: not the note's fault, so it is not counted as a failed try
                } else {
                    if (++streak >= 3) { // three in a row points at the model or the network, not at the notes: stop before any note is marked failed
                        prefs(c).edit().putString("problem", "Three notes failed in a row: " + r.error.getMessage() + ". Check the card model in Settings.").apply();
                        abort.set(true);
                        continue;
                    }
                    int n1 = tries(c, r.note.path) + 1;
                    tried(c, r.note.path, n1);
                    android.util.Log.w("sparkbox", "cards failed for " + r.note.path + ": " + r.error);
                    if (n1 >= TRIES) { try { markFailed(c, r.note); } catch (Exception ignored) { } }
                }
            }
        } finally { pool.shutdownNow(); }
        return did > 0;
    }

    /** One model call. A rate limit (429) is waited out once, because several calls run at a time now. */
    private static String call(Context c, String key, String input, boolean noReasoning) throws Exception {
        try { return Model.complete(key, model(c), CardPrompt.SYSTEM, input, 4000, noReasoning); }
        catch (java.io.IOException limited) {
            if (!String.valueOf(limited.getMessage()).contains("answered 429")) throw limited;
            Thread.sleep(4000);
            return Model.complete(key, model(c), CardPrompt.SYSTEM, input, 4000, noReasoning);
        }
    }

    private static final class Provider extends Exception { Provider(String m) { super(m); } }

    private static void markFailed(Context c, Vault.Note n) throws Exception {
        String before = VaultWrite.read(c, n.path);
        if (before == null) return;
        VaultWrite.write(c, n.path, before, NoteEdit.setKey(NoteEdit.setKey(before, "quality", CardPrompt.FAILED), "cardv", "2"));
    }

    private static void process(Context c, String key, Vault.Note n) throws Exception {
        String before = VaultWrite.read(c, n.path);
        if (before == null) return;
        String quality = CardPrompt.quality(before), input = CardPrompt.input(before);
        String curiosity = "";
        List<Cards.Card> cards = new ArrayList<>();
        if (!input.isEmpty() && !quality.equals(CardPrompt.TITLE_ONLY)) {
            String reply;
            try {
                try { reply = call(c, key, input, reasoningOff); }
                catch (Model.Rejected refused) { // some models refuse the "no thinking" field: ask again without it, and remember
                    if (!reasoningOff) throw refused;
                    reasoningOff = false;
                    reply = call(c, key, input, false);
                }
            }
            catch (java.io.IOException e) { if (String.valueOf(e.getMessage()).startsWith("Smart tags paused")) throw new Provider(e.getMessage().replace("Smart tags paused: ", "")); throw e; }
            int from = reply.indexOf('{'), to = reply.lastIndexOf('}');
            if (from < 0 || to <= from) throw new java.io.IOException("The model did not return JSON: " + (reply.length() > 160 ? reply.substring(0, 160) : reply));
            JSONObject o = new JSONObject(reply.substring(from, to + 1));
            curiosity = o.optString("curiosity_question");
            List<String[]> proposed = new ArrayList<>();
            JSONArray rc = o.optJSONArray("recall_cards");
            for (int i = 0; rc != null && i < rc.length(); i++) { JSONObject x = rc.optJSONObject(i); if (x != null) proposed.add(new String[]{x.optString("q"), x.optString("a"), x.optString("basis"), x.optString("confidence"), x.optString("type"), x.optString("data")}); }
            cards = CardPrompt.validate(n.path, proposed);
        }
        String after = CardPrompt.apply(before, curiosity, quality, cards);
        if (after.equals(before)) after = NoteEdit.setKey(before, "quality", quality);
        VaultWrite.write(c, n.path, before, after);
        // throws Stale when the note changed meanwhile: it stays undone and is tried again
    }

    /** Batches until nothing is left, the owner pauses, or the provider stops it. For a foreground run. */
    static void loop(Context c) {
        if (!RUNNING.compareAndSet(false, true)) return;
        stop = false;
        VaultWrite.pruneBackups(c); // every note written here leaves a private backup; old ones go
        try {
            while (on(c) && !stop && run(c)) { /* next batch */ }
        } finally { RUNNING.set(false); }
    }
}
