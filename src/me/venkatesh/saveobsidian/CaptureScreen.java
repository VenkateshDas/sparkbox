package me.venkatesh.saveobsidian;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** One capture from the inbox: what it is, where it is in the Captured, Ingested, Processed flow, and your own Why and Ideas, with Save and Delete. */
final class CaptureScreen extends Screen {
    private static final String[] INTENTS = {"To read", "To try", "To cite", "Idea"};
    private final InboxFile.Entry entry;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PageState page = new PageState();
    private Ui ui;
    private String why, ideas;
    private boolean busy;

    CaptureScreen(InboxFile.Entry entry) { this.entry = entry; }

    /** Drawn by PageView: the capture, its status, and your Why and Ideas. */
    @Override View build(Ui ui) {
        this.ui = ui;
        if (why == null) { why = entry.why; ideas = entry.ideas; }
        draw();
        return PageView.create(ui.c, ui, "Capture", page, () -> host.onBackPressed());
    }

    private void draw() {
        java.util.List<Block> b = new java.util.ArrayList<>();
        if (!entry.text) b.add(new Picture(180, () -> Thumb.view(ui.c, ui, entry, 0, 180)));
        b.add(new Tags(java.util.Collections.singletonList(new Tag(Feed.CAPTURED, 0)), entry.url.isEmpty() ? (entry.text ? "Note" : "File") : Ui.host(entry.url)));
        b.add(new Title(entry.shownTitle()));
        String summary = entry.tldr.isEmpty() && entry.text ? entry.body : entry.tldr;
        if (!summary.isEmpty()) b.add(new Panel(entry.text ? "Your note" : "Summary", summary, entry.text ? Origin.YOU : Origin.AI));
        if (!entry.tags.isEmpty()) b.add(new Body("#" + entry.tags.replace(", ", "  #"), true, false));
        b.add(new Body("Saved on this phone. When ingest runs it becomes a note, then it is processed into topics and cards.", true, false));
        String problem = Ingest.problem(ui.c, entry);
        if (!problem.isEmpty()) b.add(new Banner(problem, false));
        if (!Instagram.shortcode(entry.url).isEmpty() && Failures.kind(problem).equals(Failures.ACCESS))
            b.add(new Primary("Instagram access", () -> InstagramActivity.open(ui.c, entry.url)));
        if (!entry.url.isEmpty() && entry.file.isEmpty() && !Model.key(ui.c).isEmpty())
            b.add(new Primary(problem.isEmpty() ? "Process now" : "Retry now", () -> {
                Ingest.retry(ui.c, entry);
                Toast.makeText(ui.c, "Processing in the background", Toast.LENGTH_SHORT).show();
                host.onBackPressed();
            }));
        if (!entry.url.isEmpty()) {
            java.util.List<Act> acts = new java.util.ArrayList<>();
            acts.add(new Act("Open source", this::open));
            acts.add(new Act("Copy link", () -> {
                ((android.content.ClipboardManager) ui.c.getSystemService(android.content.Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("link", entry.url));
                Toast.makeText(ui.c, "Link copied", Toast.LENGTH_SHORT).show();
            }));
            b.add(new Buttons(acts));
        }
        java.util.List<Act> chips = new java.util.ArrayList<>();
        for (String name : INTENTS) chips.add(new Act(name, () -> {
            why = why.startsWith(name) ? why.substring(name.length()).replaceFirst("^[.:,]?\\s*", "") : name + (why.isEmpty() ? "" : ". " + why);
            draw();
        }));
        b.add(new Field("Why I saved this", why, 2, chips, t -> why = t));
        b.add(new Field("My ideas", ideas, 3, new java.util.ArrayList<>(), t -> ideas = t));
        b.add(new Primary(busy ? "Saving…" : "Save", this::save));
        b.add(new Danger("Delete from inbox", this::confirmDelete));
        page.publish(b);
    }

    private void open() {
        if (!entry.url.matches("(?i)https?://.*")) return;
        try { host.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(entry.url))); }
        catch (RuntimeException none) { Toast.makeText(ui.c, "No app can open this link", Toast.LENGTH_SHORT).show(); }
    }

    private void busy(boolean on) { busy = on; draw(); }

    private void save() {
        if (busy) return;
        final String w = why, i = ideas;
        if (w.equals(entry.why) && i.equals(entry.ideas)) { host.onBackPressed(); return; }
        run((t, e) -> InboxFile.setNotes(t, e, w, i), "Saved", true);
    }

    private void confirmDelete() {
        if (busy) return;
        new AlertDialog.Builder(host).setTitle("Delete this capture?").setMessage("It is removed from the inbox file. A note already made from it stays.")
            .setPositiveButton("Delete", (d, k) -> run(InboxFile::delete, "Deleted", false)).setNegativeButton("Cancel", null).show();
    }

    /** The verified inbox edit. When background tagging rewrote the entry meanwhile, retries once on the fresh file if it is clearly the same capture. */
    private void run(java.util.function.BiFunction<String, InboxFile.Entry, String> change, String done, boolean resummarise) {
        busy(true);
        final android.content.Context c = ui.c;
        new Thread(() -> {
            String problem = "";
            try {
                String destination = c.getSharedPreferences("capture", 0).getString("inbox", ""), snapshot = CaptureService.readText(c);
                try { CaptureService.edit(c, destination, snapshot, t -> change.apply(t, entry)); }
                catch (java.io.IOException stale) {
                    if (!String.valueOf(stale.getMessage()).startsWith("Inbox changed. Refresh")) throw stale;
                    String fresh = CaptureService.readText(c);
                    InboxFile.Entry same = null;
                    for (InboxFile.Entry e : InboxFile.parse(fresh)) if (e.time.equals(entry.time) && e.url.equals(entry.url) && (!e.url.isEmpty() || e.body.equals(entry.body))) {
                        if (same != null) throw stale;
                        same = e;
                    }
                    if (same == null || !same.why.equals(entry.why) || !same.ideas.equals(entry.ideas)) throw stale;
                    final InboxFile.Entry target = same;
                    CaptureService.edit(c, destination, fresh, t -> change.apply(t, target));
                }
                if (resummarise) BackgroundJob.schedule(c); // a new Why or Idea re-summarises the capture
                Usage.hit(c, resummarise ? "annotate" : "delete");
            } catch (Exception failure) { problem = String.valueOf(failure.getMessage()); }
            final String message = problem;
            main.post(() -> {
                busy(false);
                if (message.isEmpty()) { Toast.makeText(c, done, Toast.LENGTH_SHORT).show(); host.onBackPressed(); }
                else new AlertDialog.Builder(host).setTitle("Not saved").setMessage(message).setPositiveButton("OK", null).show();
            });
        }).start();
    }
}
