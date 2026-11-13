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
    private Ui ui;
    private EditText why, ideas;
    private TextView save, delete;
    private boolean busy;

    CaptureScreen(InboxFile.Entry entry) { this.entry = entry; }

    @Override View build(Ui ui) {
        this.ui = ui;
        LinearLayout root = new LinearLayout(ui.c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Bars.header(ui, "Capture", v -> host.onBackPressed()));
        ScrollView scroll = new ScrollView(ui.c);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(ui.c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        if (!entry.text) page.addView(Thumb.view(ui.c, ui, entry, 0, 180));
        LinearLayout meta = new LinearLayout(ui.c);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.addView(ui.chip(Feed.CAPTURED, 0));
        TextView where = ui.text(entry.url.isEmpty() ? (entry.text ? "Note" : "File") : Ui.host(entry.url), 13, Typeface.DEFAULT, ui.sub);
        where.setPadding(ui.dp(8), 0, 0, 0);
        meta.addView(where);
        page.addView(meta, ui.lp(entry.text ? 0 : 12));
        page.addView(ui.text(entry.shownTitle(), 20, Ui.BOLD, ui.ink), ui.lp(8));
        String summary = entry.tldr.isEmpty() && entry.text ? entry.body : entry.tldr;
        if (!summary.isEmpty()) page.addView(ui.text(summary, 15, Typeface.DEFAULT, ui.sub), ui.lp(8));
        if (!entry.tags.isEmpty()) page.addView(ui.text("#" + entry.tags.replace(", ", "  #"), 13, Typeface.DEFAULT, ui.sub), ui.lp(8));
        page.addView(ui.text("Saved on this phone. When ingest runs it becomes a note, then it is processed into topics and cards.", 13, Typeface.DEFAULT, ui.sub), ui.lp(12));

        String problem = Ingest.problem(ui.c, entry);
        if (!problem.isEmpty()) {
            TextView warn = ui.text(problem, 13, Typeface.DEFAULT, ui.warn);
            warn.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            warn.setBackground(ui.round(ui.warnBg, 0, 8));
            page.addView(warn, ui.lp(0));
        }
        if (!entry.url.isEmpty() && entry.file.isEmpty() && !Model.key(ui.c).isEmpty()) {
            page.addView(ui.button(problem.isEmpty() ? "Process now" : "Retry now", true, v -> {
                Ingest.retry(ui.c, entry);
                Toast.makeText(ui.c, "Processing in the background", Toast.LENGTH_SHORT).show();
                host.onBackPressed();
            }), ui.lp(12));
        }
        if (!entry.url.isEmpty()) {
            LinearLayout row = new LinearLayout(ui.c);
            row.addView(ui.button("Open source", true, v -> open()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            View gap = new View(ui.c);
            row.addView(gap, new LinearLayout.LayoutParams(ui.dp(8), 1));
            row.addView(ui.button("Copy link", false, v -> {
                ((android.content.ClipboardManager) ui.c.getSystemService(android.content.Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("link", entry.url));
                Toast.makeText(ui.c, "Link copied", Toast.LENGTH_SHORT).show();
            }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            page.addView(row, ui.lp(16));
        }

        page.addView(ui.label("Why I saved this"), ui.lp(20));
        LinearLayout chips = new LinearLayout(ui.c);
        why = field(entry.why, 2);
        for (String name : INTENTS) {
            TextView c = ui.button(name, false, v -> {
                String now = why.getText().toString();
                why.setText(now.startsWith(name) ? now.substring(name.length()).replaceFirst("^[.:,]?\\s*", "") : name + (now.isEmpty() ? "" : ". " + now));
                why.setSelection(why.getText().length());
            });
            c.setPadding(ui.dp(12), 0, ui.dp(12), 0);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.rightMargin = ui.dp(6);
            chips.addView(c, p);
        }
        android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(ui.c);
        strip.setHorizontalScrollBarEnabled(false);
        strip.addView(chips);
        page.addView(strip, ui.lp(8));
        page.addView(why, ui.lp(8));
        page.addView(ui.label("My ideas"), ui.lp(16));
        ideas = field(entry.ideas, 3);
        page.addView(ideas, ui.lp(8));

        save = ui.button("Save", true, v -> save());
        page.addView(save, ui.lp(20));
        delete = ui.text("Delete from inbox", 14, Ui.MEDIUM, ui.err);
        delete.setGravity(Gravity.CENTER);
        delete.setMinHeight(ui.dp(48));
        delete.setOnClickListener(v -> confirmDelete());
        Ui.buttonRole(delete);
        page.addView(delete, ui.lp(4));
        return root;
    }

    private EditText field(String value, int lines) {
        EditText e = new EditText(ui.c);
        e.setText(value);
        e.setMinLines(lines);
        e.setGravity(Gravity.TOP);
        e.setTextColor(ui.ink);
        e.setTextSize(15);
        e.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        e.setBackground(ui.round(ui.surface, ui.line, 8));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return e;
    }

    private void open() {
        if (!entry.url.matches("(?i)https?://.*")) return;
        try { host.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(entry.url))); }
        catch (RuntimeException none) { Toast.makeText(ui.c, "No app can open this link", Toast.LENGTH_SHORT).show(); }
    }

    private void busy(boolean on) { busy = on; save.setEnabled(!on); delete.setEnabled(!on); save.setAlpha(on ? 0.5f : 1f); }

    private void save() {
        if (busy) return;
        final String w = why.getText().toString(), i = ideas.getText().toString();
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
