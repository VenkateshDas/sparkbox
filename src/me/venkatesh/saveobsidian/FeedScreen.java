package me.venkatesh.saveobsidian;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** The Feed tab: captures, Source notes and topic activity in one stream, with filters and the captures that need attention. */
final class FeedScreen extends Screen {
    private static final String[] CHIPS = {"All", "Craft", "Life", "Sources", "Topics", "People"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private final FeedState view = new FeedState();
    private final java.util.Map<String, Attention> attentionByKey = new java.util.HashMap<>();
    private List<Feed.Item> items = new ArrayList<>();
    private String chip = "All", query = "";
    private List<Vault.Note> meaning;
    private Runnable pendingQuery, pendingMeaning;
    private boolean scanning;
    private long checkedAt;

    /** A failed or stuck capture, from the queue the share sheet fills. */
    private static final class Attention { File file; String label, error, kind; long when; }

    /** Drawn by Compose (FeedView.kt); this class keeps the data and the actions. */
    @Override View build(Ui ui) {
        this.ui = ui;
        chip = ui.c.getSharedPreferences("capture", 0).getString("feedFilter", "All");
        checkedAt = ui.c.getSharedPreferences("capture", 0).getLong("scanAt", 0);
        view.setSteps(java.util.Arrays.asList(Ingest.STEPS));
        // no refresh here: onShow runs right after build and refreshes (doing it twice cost ~115 ms of startup)
        return FeedView.create(ui.c, ui, view, actions);
    }

    private final FeedActions actions = new FeedActions() {
        @Override public void open(Feed.Item it) { FeedScreen.this.open(it); }
        @Override public void link(String url) { openLink(url); }
        @Override public void ask(Feed.Item it) { host.askAbout(it.path, it.title); }
        @Override public void retry(Feed.Item it) { if (it.entry != null) FeedScreen.this.retry(it.entry); }
        @Override public void keep(Feed.Item it) { CardStore.keep(ui.c, it.path, !it.kept); drawRows(); }
        @Override public void edit(Feed.Item it) { host.push(new NoteEditorScreen(it.path)); }
        @Override public void cards(Feed.Item it) { host.push(new CardsScreen(it.path)); }
        @Override public void share(Feed.Item it) {
            String shared = it.url.isEmpty() ? it.title : it.title + "\n" + it.url;
            host.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shared), null));
        }
        @Override public void note(String path) { host.openNote(path); }
        @Override public void query(String q) {
            if (q.equals(query)) return;
            query = q; meaning = null;
            if (pendingQuery != null) main.removeCallbacks(pendingQuery);
            if (pendingMeaning != null) main.removeCallbacks(pendingMeaning);
            pendingQuery = () -> { drawRows(); view.toTop(); };
            main.postDelayed(pendingQuery, 250);
            if (q.length() >= 3) { pendingMeaning = () -> semantic(q); main.postDelayed(pendingMeaning, 700); } // meaning-based matches join the word matches when they arrive
        }
        @Override public void askScout(String q) { host.draft(q); }
        @Override public void chip(String name) {
            chip = name;
            ui.c.getSharedPreferences("capture", 0).edit().putString("feedFilter", name).apply();
            drawRows();
            view.toTop();
        }
        @Override public void menu(int which) {
            if (which == 0) host.push(new ProfileScreen());
            else if (which == 1) host.push(new SettingsScreen());
            else if (which == 3) host.push(new DashboardScreen());
            else host.startActivity(new Intent(host, VaultActivity.class));
        }
        @Override public void capture() { captureMenu(); }
        @Override public void refresh() { scan(); }
        @Override public void retryCapture(String key) {
            Attention a = attentionByKey.get(key);
            if (a == null) return;
            if ("voice".equals(a.kind) || directJob(a.file)) {
                new Thread(() -> { synchronized (DirectCapture.LOCK) {
                    try { if (a.file.exists()) { JSONObject j = CaptureService.readJob(a.file); j.remove("error"); CaptureService.writeJob(a.file, j); BackgroundJob.schedule(ui.c); } }
                    catch (Exception e) { main.post(() -> Toast.makeText(ui.c, "Could not retry; capture kept.", Toast.LENGTH_LONG).show()); }
                } }, "capture-retry").start();
            }
            else CaptureService.start(ui.c, a.file.getName());
            Toast.makeText(ui.c, "Trying again", Toast.LENGTH_SHORT).show();
            main.postDelayed(FeedScreen.this::drawRows, 1500);
        }
        @Override public void destinationCapture(String key) {
            Attention a = attentionByKey.get(key); if (a == null) return;
            try {
                JSONObject job = CaptureService.readJob(a.file);
                if (!DirectCapture.direct(job) || job.has("write:note")) { Toast.makeText(ui.c, "Inspect the current note before changing a capture with an attempted write.", Toast.LENGTH_LONG).show(); return; }
                DestinationPicker.show(host, job, d -> {
                    if (!DirectCapture.direct(d)) { Toast.makeText(ui.c, "Choose an existing or new note for this capture.", Toast.LENGTH_LONG).show(); return; }
                    new Thread(() -> { synchronized (DirectCapture.LOCK) {
                        try {
                            if (!a.file.exists()) throw new java.io.IOException("Capture already completed.");
                            JSONObject latest = CaptureService.readJob(a.file);
                            if (latest.has("write:note")) throw new java.io.IOException("A write was attempted. Inspect the note before changing destination.");
                            for (String field : new String[]{"destination", "destinationVersion", "vault", "target", "noteTitle"}) { latest.remove(field); if (d.has(field)) latest.put(field, d.get(field)); }
                            latest.remove("error"); latest.remove("waiting"); latest.put("phase", "queued"); CaptureService.writeJob(a.file, latest);
                            BackgroundJob.schedule(ui.c);
                        } catch (Exception e) { main.post(() -> Toast.makeText(ui.c, e.getMessage(), Toast.LENGTH_LONG).show()); }
                        main.post(FeedScreen.this::drawRows);
                    } }, "capture-destination-change").start();
                });
            } catch (Exception ignored) { }
        }
        @Override public void dismissCapture(String key) {
            Attention a = attentionByKey.get(key);
            if (a == null) return;
            new AlertDialog.Builder(host).setTitle("Dismiss this capture?").setMessage("It leaves the queue. A private copy is kept until app data is cleared.")
                .setPositiveButton("Dismiss", (d, w) -> {
                    new Thread(() -> { synchronized (DirectCapture.LOCK) {
                        try { CaptureService.discard(ui.c, a.file); main.post(() -> Toast.makeText(ui.c, "Dismissed. Private copy kept.", Toast.LENGTH_SHORT).show()); }
                        catch (Exception e) { main.post(() -> Toast.makeText(ui.c, "Could not dismiss", Toast.LENGTH_SHORT).show()); }
                        main.post(FeedScreen.this::drawRows);
                    } }, "capture-dismiss").start();
                }).setNegativeButton("Cancel", null).show();
        }
        @Override public void trash(Feed.Item it) { Edits.confirmDelete(host, it.path, it.title, FeedScreen.this::scan); }
        @Override public void lane(Feed.Item it, String lane) {
            Edits.setLane(ui.c, it.path, lane, problem -> {
                if (problem.isEmpty()) { Toast.makeText(ui.c, "Moved to " + lane, Toast.LENGTH_SHORT).show(); scan(); }
                else Toast.makeText(ui.c, problem, Toast.LENGTH_LONG).show();
            });
        }
        @Override public void emptyAction(boolean vaultReady) {
            if (vaultReady) scan(); else host.startActivity(new Intent(host, VaultActivity.class).putExtra(VaultActivity.PICK_WRITE, true));
        }
        @Override public View thumb(Feed.Item it) { return Thumb.view(ui.c, ui, it.title, it.url, 0, 160); }
    };

    // ---- search: words first, meaning merged in when it arrives ----

    private void semantic(final String q) {
        final VaultStore.Snap snap = VaultStore.cached(ui.c);
        if (snap == null) return;
        new Thread(() -> {
            List<Vault.Note> hits;
            Limits.interactive(true);
            try (Metrics.Operation metrics = Metrics.operation("Semantic search", java.util.UUID.randomUUID().toString(), "query embedding")) { Metrics.event("search", null); hits = VaultStore.semantic(ui.c, snap, q, 30); metrics.done(); } catch (Exception offline) { hits = null; } // no key or no network: the word results stay, silently
            final List<Vault.Note> done = hits;
            main.post(() -> { if (q.equals(query) && done != null && !done.isEmpty()) { meaning = done; drawRows(); } });
        }).start();
    }

    private static String kind(Vault.Note n) {
        if (Layout.inSources(n.path)) return "Source";
        if (n.path.startsWith("Knowledge/Topics/")) return "Topic";
        if (n.path.startsWith("Knowledge/People/")) return "Person";
        return "Note";
    }

    /** The filter chips narrow search results too: Craft and Life by lane, the rest by kind. */
    private boolean matches(Vault.Note n) {
        String k = kind(n);
        switch (chip) {
            case "Craft": case "Life": return Lane.of(n.path, n.meta).equals(chip);
            case "Kept": return CardStore.kept().contains(n.path);
            case "Sources": return k.equals("Source");
            case "Topics": return k.equals("Topic");
            case "People": return k.equals("Person");
            default: return true;
        }
    }

    private List<FeedRow> searchRows() {
        List<FeedRow> out = new ArrayList<>();
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        if (snap == null) return out;
        List<Vault.Note> words = VaultStore.keyword(snap, query, 200);
        List<Vault.Note> all = meaning == null ? words : Vault.blend(meaning, words, query, 200);
        int n = 0;
        for (Vault.Note note : all) {
            if (note.path.endsWith("/My cards.md") || !matches(note)) continue;
            if (n++ >= 60) break;
            String kind = kind(note), age = "";
            if (kind.equals("Source")) { boolean[] t = new boolean[1]; age = Feed.age(Feed.when(note.meta("captured"), t), t[0], LocalDateTime.now()); }
            String meta = kind.equals("Source") ? (Feed.host(note.meta("source")).isEmpty() ? note.meta("via") : Feed.host(note.meta("source"))) : "";
            if (meta.isEmpty() && kind.equals("Topic")) { int c = 0; for (Vault.Note f : snap.graph.in(note)) if (Layout.inSources(f.path)) c++; meta = c + (c == 1 ? " source" : " sources"); }
            out.add(new HitRow(new Hit(note.path, kind, Lane.of(note.path, note.meta), age, note.title, meta, Feed.snippet(note.digest, query, 90))));
        }
        if (out.isEmpty()) out.add(new NoHitsRow(query));
        return out;
    }

    // ---- capture ----

    private void captureMenu() {
        new AlertDialog.Builder(host).setTitle("Capture").setItems(new String[]{"Link or text", "Voice note", "Photo or file"}, (d, which) -> {
            if (which == 0) captureText();
            else if (which == 1) host.startActivity(new Intent(host, QuickActivity.class).setAction(QuickActivity.VOICE));
            else host.startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), MainActivity.PICK_FILES);
        }).show();
    }

    private void captureText() {
        host.startActivity(new Intent(host, QuickActivity.class));
    }

    private boolean listening;
    private final android.content.BroadcastReceiver changed = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { refreshFromCache(); }
    };
    @Override void onHide() {
        shown = false; main.removeCallbacks(ticker); Ingest.onStage = null;
        if (listening) { ui.c.unregisterReceiver(changed); listening = false; }
    }

    @Override void onShow() {
        if (!listening) {
            android.content.IntentFilter filter = new android.content.IntentFilter(CaptureService.CHANGED);
            androidx.core.content.ContextCompat.registerReceiver(ui.c, changed, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
            listening = true;
        }
        shown = true;
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 10000);
        Ingest.onStage = () -> main.post(this::drawRows); // rows are data: only the capture whose step changed recomposes
        refreshFromCache();
        scan();
    }

    // ---- data ----

    /** Reads the cached vault and the inbox off the main thread (about 100 ms on a big vault), then draws. */
    private void refreshFromCache() {
        final Context c = ui.c;
        CACHE.execute(() -> {
            VaultStore.Snap snap = VaultStore.cached(c);
            List<Feed.Item> built = snap == null ? null : Feed.build(snap.graph.notes, snap.graph, inboxEntries(), LocalDateTime.now());
            main.post(() -> { if (built != null) items = built; drawRows(); });
        });
    }
    private static final java.util.concurrent.ExecutorService CACHE = java.util.concurrent.Executors.newSingleThreadExecutor(); // one at a time, in order

    private List<InboxFile.Entry> inboxEntries() {
        try { return InboxFile.parse(CaptureService.readText(ui.c)); } catch (Exception none) { return new ArrayList<>(); }
    }

    private void scan() {
        if (scanning || !VaultStore.ready(ui.c)) return;
        scanning = true;
        view.refreshing(true);
        final Context c = ui.c;
        new Thread(() -> {
            VaultStore.Snap fresh = null;
            try { fresh = VaultStore.scan(c); } catch (Exception unreadable) { }
            List<InboxFile.Entry> inbox = inboxEntries();
            VaultStore.Snap done = fresh;
            main.post(() -> {
                scanning = false;
                view.refreshing(false);
                if (done == null) return;
                checkedAt = System.currentTimeMillis();
                c.getSharedPreferences("capture", 0).edit().putLong("scanAt", checkedAt).apply();
                fill(done, inbox);
            });
        }).start();
    }

    private void fill(VaultStore.Snap snap, List<InboxFile.Entry> inbox) {
        items = Feed.build(snap.graph.notes, snap.graph, inbox, LocalDateTime.now());
        drawRows();
    }

    private boolean directJob(File file) { try { return DirectCapture.direct(CaptureService.readJob(file)); } catch (Exception e) { return false; } }

    private List<Attention> attention() {
        List<Attention> out = new ArrayList<>();
        for (File file : CaptureService.pending(ui.c)) {
            try {
                JSONObject job = CaptureService.readJob(file);
                String phase = job.optString("phase");
                if (!DirectCapture.direct(job) && !job.has("waiting") && !job.has("error") && !"failed".equals(phase) && !"uncertain".equals(phase) && System.currentTimeMillis() - file.lastModified() < 120000) continue;
                Attention a = new Attention();
                a.file = file; a.kind = job.optString("kind", "link"); a.when = file.lastModified();
                a.label = job.optString("label");
                if (a.label.isEmpty()) a.label = "link".equals(a.kind) ? job.optString("url") : Capture.clean(job.optString("text"));
                a.error = job.optString("error", job.optString("waiting"));
                if (DirectCapture.direct(job) && a.error.isEmpty()) a.error = "Queued for " + DestinationPicker.label(job);
                if (a.error.isEmpty() && "voice".equals(a.kind)) a.error = "Waiting to be transcribed. This needs a network connection and your OpenRouter key.";
                else if (a.error.isEmpty()) a.error = "failed".equals(phase) ? "Not saved." : "Queued or interrupted. Retry checks without saving twice.";
                out.add(a);
            } catch (Exception unreadable) { }
        }
        return out;
    }

    // ---- drawing ----

    private static boolean usable;

    /** Builds the rows as plain data and hands them to Compose. Cheap: no views are made here. */
    private void drawRows() {
        if (ui == null) return;
        CardStore.all(ui.c);
        if (!query.isEmpty()) { // searching: the results replace the stream, under the same filters
            List<FeedRow> found = searchRows();
            int count = found.size() == 1 && found.get(0) instanceof NoHitsRow ? 0 : found.size();
            view.publish(found, chip, count + (count == 1 ? " result" : " results"), false);
            return;
        }
        List<FeedRow> out = new ArrayList<>();
        attentionByKey.clear();
        List<Attention> need = attention();
        boolean all = chip.equals("All");
        if (!need.isEmpty() && all) {
            out.add(new HeadingRow("h:attention", "Needs attention (" + need.size() + ")"));
            for (Attention a : need) { String k = "a:" + a.file.getName(); attentionByKey.put(k, a); out.add(new AttentionRow(k, a.label, a.error, ago(a.when))); }
        }
        CardStore.all(ui.c);
        java.util.Set<String> kept = CardStore.kept();
        for (Feed.Item it : items) it.kept = kept.contains(it.path);
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        List<Feed.Item> shown = chip.equals("Kept") && snap != null ? Feed.bookmarks(snap.graph.notes, kept) : Feed.filter(items, chip);
        if (!need.isEmpty() && all && !shown.isEmpty()) out.add(new HeadingRow("h:latest", "Latest"));
        boolean key = Model.key(ui.c).length() > 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Feed.Item it : shown) {
            String k = it.entry != null ? "c:" + it.entry.time + "|" + it.entry.url + "|" + it.entry.title : it.kind + ":" + it.path + ":" + it.title;
            if (!seen.add(k)) continue; // keys must be unique
            int stage = it.entry == null ? -1 : Ingest.stage(ui.c, it.entry);
            boolean queued = it.entry != null && stage < 0 && Ingest.queued(ui.c, it.entry);
            out.add(new ItemRow(k, it, stage, queued, stage >= 0 ? Ingest.stageSeconds(ui.c, it.entry) : 0,
                it.entry == null ? "" : Ingest.problem(ui.c, it.entry), it.kept,
                it.entry != null && !it.entry.url.isEmpty() && it.entry.file.isEmpty() && key,
                it.entry == null && !it.path.isEmpty()));
        }
        if (shown.isEmpty() && (need.isEmpty() || !all)) out.add(new EmptyRow(VaultStore.ready(ui.c)));
        ConnectivityManager cm = (ConnectivityManager) ui.c.getSystemService(Context.CONNECTIVITY_SERVICE);
        view.publish(out, chip, checkedAt == 0 ? "Vault not checked yet" : "Vault checked " + ago(checkedAt), cm != null && cm.getActiveNetwork() == null);
        if (!usable && !items.isEmpty()) { usable = true; main.post(() -> android.util.Log.i("Sparkbox", "feed usable " + (android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()) + " ms")); } // startup marker for docs/baseline.md
    }

    private static String ago(long at) {
        long mins = Math.max(0, (System.currentTimeMillis() - at) / 60000);
        if (mins < 1) return "just now";
        if (mins < 60) return mins + "m ago";
        if (mins < 24 * 60) return (mins / 60) + "h ago";
        return (mins / 1440) + "d ago";
    }

    /** Steps redraw through Ingest.onStage; this slow pass is only a safety net (and moves the seconds counter). */
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (live()) drawRows();
            if (shown) main.postDelayed(this, 10000);
        }
    };

    private boolean live() {
        for (Feed.Item it : items) if (it.entry != null && (Ingest.stage(ui.c, it.entry) >= 0 || Ingest.queued(ui.c, it.entry))) return true;
        return false;
    }
    private boolean shown;

    /** Runs the whole pipeline again for this capture now: tagging, reading the page, ingest. */
    private void retry(InboxFile.Entry entry) {
        if (!Ingest.on(ui.c)) {
            // ingest is off: tagging is all that runs; still ask for it
            Toast.makeText(ui.c, "Turn on Process captures in Settings to make notes", Toast.LENGTH_LONG).show();
        } else Toast.makeText(ui.c, "Processing in the background", Toast.LENGTH_SHORT).show();
        Ingest.retry(ui.c, entry);
        main.postDelayed(this::refreshFromCache, 3000);
    }

    private void openLink(String url) {
        if (!url.matches("(?i)https?://.*")) return;
        try { host.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); } catch (RuntimeException none) { Toast.makeText(ui.c, "No app can open this link", Toast.LENGTH_SHORT).show(); }
    }

    private void open(Feed.Item it) {
        if (it.entry != null) { host.push(new CaptureScreen(it.entry)); return; } // a capture with no note yet opens its own page
        host.openNote(it.path);
    }
}
