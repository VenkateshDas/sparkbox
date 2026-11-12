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
    private ListView list;
    private TextView subtitle;
    private LinearLayout banner, chips;
    private final List<Object> rows = new ArrayList<>();
    private List<Feed.Item> items = new ArrayList<>();
    private String chip = "All";
    private boolean scanning;
    private long checkedAt;

    /** A failed or stuck capture, from the queue the share sheet fills. */
    private static final class Attention { File file; String label, error, kind; long when; }
    private static final class Heading { final String text; Heading(String t) { text = t; } }

    @Override View build(Ui ui) {
        this.ui = ui;
        chip = ui.c.getSharedPreferences("capture", 0).getString("feedFilter", "All");
        checkedAt = ui.c.getSharedPreferences("capture", 0).getLong("scanAt", 0);
        LinearLayout root = new LinearLayout(ui.c);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(ui.c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ui.dp(16), 0, ui.dp(8), 0);
        LinearLayout titles = new LinearLayout(ui.c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(ui.text("Feed", 20, Ui.BOLD, ui.ink));
        subtitle = ui.text("", 12, Typeface.DEFAULT, ui.sub);
        titles.addView(subtitle);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView search = ui.text("Search", 14, Ui.MEDIUM, ui.accent);
        search.setMinHeight(ui.dp(48));
        search.setMinWidth(ui.dp(48));
        search.setGravity(Gravity.CENTER);
        search.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        search.setOnClickListener(v -> host.select(2));
        Ui.buttonRole(search);
        head.addView(search);
        TextView setup = ui.text("Setup", 14, Ui.MEDIUM, ui.sub); // until the Settings screen exists, this opens the existing setup and settings page
        setup.setMinHeight(ui.dp(48));
        setup.setMinWidth(ui.dp(48));
        setup.setGravity(Gravity.CENTER);
        setup.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        setup.setOnClickListener(v -> host.startActivity(new Intent(host, SetupActivity.class)));
        Ui.buttonRole(setup);
        head.addView(setup);
        root.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(64)));
        View rule = new View(ui.c);
        rule.setBackgroundColor(ui.line);
        root.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        banner = new LinearLayout(ui.c);
        banner.setOrientation(LinearLayout.VERTICAL);
        root.addView(banner);

        HorizontalScrollView strip = new HorizontalScrollView(ui.c);
        strip.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(ui.c);
        chips.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(0));
        strip.addView(chips);
        root.addView(strip);

        list = new ListView(ui.c);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16));
        list.setAdapter(new Rows());
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        drawChips();
        drawBanner();
        refreshFromCache();
        return root;
    }

    @Override void onShow() {
        drawBanner();
        refreshFromCache();
        scan();
    }

    // ---- data ----

    private void refreshFromCache() {
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        if (snap != null) fill(snap, inboxEntries());
        else drawRows();
    }

    private List<InboxFile.Entry> inboxEntries() {
        try { return InboxFile.parse(CaptureService.readText(ui.c)); } catch (Exception none) { return new ArrayList<>(); }
    }

    private void scan() {
        if (scanning || !VaultStore.ready(ui.c)) return;
        scanning = true;
        final Context c = ui.c;
        new Thread(() -> {
            VaultStore.Snap fresh = null;
            try { fresh = VaultStore.scan(c); } catch (Exception unreadable) { }
            List<InboxFile.Entry> inbox = inboxEntries();
            VaultStore.Snap done = fresh;
            main.post(() -> {
                scanning = false;
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

    private List<Attention> attention() {
        List<Attention> out = new ArrayList<>();
        for (File file : CaptureService.pending(ui.c)) {
            try {
                JSONObject job = CaptureService.readJob(file);
                String phase = job.optString("phase");
                if (!"failed".equals(phase) && !"uncertain".equals(phase) && System.currentTimeMillis() - file.lastModified() < 120000) continue;
                Attention a = new Attention();
                a.file = file; a.kind = job.optString("kind", "link"); a.when = file.lastModified();
                a.label = job.optString("label");
                if (a.label.isEmpty()) a.label = "link".equals(a.kind) ? job.optString("url") : Capture.clean(job.optString("text"));
                a.error = job.optString("error");
                if (a.error.isEmpty() && "voice".equals(a.kind)) a.error = "Waiting to be transcribed. This needs a network connection and your OpenRouter key.";
                else if (a.error.isEmpty()) a.error = "failed".equals(phase) ? "Not saved." : "Queued or interrupted. Retry checks without saving twice.";
                out.add(a);
            } catch (Exception unreadable) { }
        }
        return out;
    }

    // ---- drawing ----

    private void drawRows() {
        rows.clear();
        List<Attention> need = attention();
        boolean all = chip.equals("All");
        if (!need.isEmpty() && all) { rows.add(new Heading("Needs attention (" + need.size() + ")")); rows.addAll(need); }
        List<Feed.Item> shown = Feed.filter(items, chip);
        if (!need.isEmpty() && all && !shown.isEmpty()) rows.add(new Heading("Latest"));
        rows.addAll(shown);
        if (shown.isEmpty() && (need.isEmpty() || !all)) rows.add(VaultStore.ready(ui.c) ? "empty" : "novault");
        ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        subtitle.setText(checkedAt == 0 ? "Vault not checked yet" : "Vault checked " + ago(checkedAt));
    }

    private static String ago(long at) {
        long mins = Math.max(0, (System.currentTimeMillis() - at) / 60000);
        if (mins < 1) return "just now";
        if (mins < 60) return mins + "m ago";
        if (mins < 24 * 60) return (mins / 60) + "h ago";
        return (mins / 1440) + "d ago";
    }

    private void drawBanner() {
        banner.removeAllViews();
        ConnectivityManager cm = (ConnectivityManager) ui.c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && cm.getActiveNetwork() == null) {
            TextView v = ui.text("Offline. Changes save to your vault and sync later.", 13, Typeface.DEFAULT, ui.warn);
            v.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            v.setBackground(ui.round(ui.warnBg, 0, 8));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMargins(ui.dp(16), ui.dp(12), ui.dp(16), 0);
            banner.addView(v, p);
        }
    }

    private void drawChips() {
        chips.removeAllViews();
        for (String name : CHIPS) {
            boolean on = name.equals(chip);
            TextView v = ui.text(name, 14, Ui.MEDIUM, on ? ui.onAccent : ui.sub);
            v.setGravity(Gravity.CENTER);
            v.setPadding(ui.dp(14), 0, ui.dp(14), 0);
            v.setBackground(ui.round(on ? ui.accent : ui.raised, on ? 0 : ui.line, 18));
            v.setSelected(on);
            Ui.buttonRole(v);
            LinearLayout cell = new LinearLayout(ui.c); // a 36 dp chip inside a 48 dp touch area
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(36)));
            cell.setOnClickListener(x -> {
                chip = name;
                ui.c.getSharedPreferences("capture", 0).edit().putString("feedFilter", name).apply();
                drawChips();
                drawRows();
                list.setSelection(0);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48));
            p.rightMargin = ui.dp(8);
            chips.addView(cell, p);
        }
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 4; }
        @Override public int getItemViewType(int i) {
            Object r = rows.get(i);
            return r instanceof Heading ? 0 : r instanceof Attention ? 1 : r instanceof Feed.Item ? 2 : 3;
        }
        @Override public View getView(int i, View reuse, ViewGroup parent) {
            Object r = rows.get(i);
            if (r instanceof Heading) return heading(((Heading) r).text);
            if (r instanceof Attention) return attentionCard((Attention) r);
            if (r instanceof Feed.Item) return itemCard((Feed.Item) r);
            return emptyState(String.valueOf(r));
        }
    }

    private View heading(String text) {
        TextView v = ui.text(text, 14, Ui.BOLD, ui.ink);
        v.setPadding(0, ui.dp(8), 0, ui.dp(8));
        return v;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(ui.c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        card.setBackground(ui.ripple(ui.round(ui.surface, ui.line, 12)));
        return card;
    }

    /** A ListView has no gap setting, so each card sits in a wrapper that adds the space under it. */
    private View wrap(View card) {
        LinearLayout box = new LinearLayout(ui.c);
        box.setPadding(0, 0, 0, ui.dp(12));
        box.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private View attentionCard(Attention a) {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(ui.c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(ui.chip("Capture failed", 3));
        top.addView(new View(ui.c), new LinearLayout.LayoutParams(0, 1, 1));
        top.addView(ui.text(ago(a.when), 12, Typeface.DEFAULT, ui.sub));
        card.addView(top);
        TextView label = ui.text(a.label, 15, Ui.MEDIUM, ui.ink);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(label, ui.lp(8));
        card.addView(ui.text(a.error, 13, Typeface.DEFAULT, ui.sub), ui.lp(4));
        LinearLayout actions = new LinearLayout(ui.c);
        TextView retry = ui.button("Retry", true, v -> {
            if ("voice".equals(a.kind)) { try { JSONObject j = CaptureService.readJob(a.file); j.remove("error"); CaptureService.writeJob(a.file, j); } catch (Exception ignored) { } BackgroundJob.schedule(ui.c); }
            else CaptureService.start(ui.c, a.file.getName());
            Toast.makeText(ui.c, "Trying again", Toast.LENGTH_SHORT).show();
            main.postDelayed(this::drawRows, 1500);
        });
        TextView dismiss = ui.button("Dismiss", false, v -> new AlertDialog.Builder(host)
            .setTitle("Dismiss this capture?").setMessage("It leaves the queue. A private copy is kept until app data is cleared.")
            .setPositiveButton("Dismiss", (d, w) -> {
                try { CaptureService.discard(ui.c, a.file); Toast.makeText(ui.c, "Dismissed. Private copy kept.", Toast.LENGTH_SHORT).show(); }
                catch (Exception e) { Toast.makeText(ui.c, "Could not dismiss", Toast.LENGTH_SHORT).show(); }
                drawRows();
            }).setNegativeButton("Cancel", null).show());
        actions.addView(retry, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.leftMargin = ui.dp(8);
        actions.addView(dismiss, p);
        card.addView(actions, ui.lp(12));
        return wrap(card);
    }

    private View itemCard(Feed.Item it) {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(ui.c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (it.kind.equals(Feed.SOURCE)) top.addView(ui.chip(it.status, it.status.equals(Feed.PROCESSED) ? 1 : 0));
        else top.addView(ui.chip(it.kind.equals(Feed.TOPIC) ? "Topic" : "Person", 0));
        if (!it.lane.isEmpty()) {
            top.addView(new View(ui.c), new LinearLayout.LayoutParams(ui.dp(8), 1));
            top.addView(ui.chip(it.lane, 0));
        }
        top.addView(new View(ui.c), new LinearLayout.LayoutParams(0, 1, 1));
        String right = it.kind.equals(Feed.TOPIC) ? it.count + " new sources" : it.age;
        if (!right.isEmpty()) top.addView(ui.text(right, 12, Typeface.DEFAULT, ui.sub));
        card.addView(top);
        TextView title = ui.text(it.title, 17, Ui.BOLD, ui.ink);
        title.setMaxLines(3);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(title, ui.lp(8));
        if (it.kind.equals(Feed.PERSON)) card.addView(ui.text("Mentioned in " + it.count + (it.count == 1 ? " note" : " notes"), 13, Typeface.DEFAULT, ui.sub), ui.lp(4));
        if (!it.sourceName.isEmpty()) card.addView(ui.text(it.sourceName, 13, Typeface.DEFAULT, ui.sub), ui.lp(4));
        if (!it.curiosity.isEmpty()) {
            LinearLayout q = new LinearLayout(ui.c);
            q.setOrientation(LinearLayout.VERTICAL);
            q.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            q.setBackground(ui.round(ui.raised, 0, 8));
            q.addView(ui.text("Curiosity prompt", 12, Ui.BOLD, ui.sub));
            q.addView(ui.text(it.curiosity, 15, Typeface.DEFAULT, ui.ink), ui.lp(4));
            card.addView(q, ui.lp(12));
        }
        if (!it.why.isEmpty()) card.addView(ui.text("Why saved: " + it.why, 13, Typeface.DEFAULT, ui.sub), ui.lp(12));
        if (!it.waiting.isEmpty()) card.addView(ui.text(it.waiting, 13, Typeface.DEFAULT, ui.sub), ui.lp(8));
        if (!it.made.isEmpty()) card.addView(ui.text(it.made, 13, Typeface.DEFAULT, ui.sub), ui.lp(8));
        if (it.entry == null) {
            TextView ask = ui.button("Ask", false, v -> host.startActivity(new Intent(host, AskActivity.class).putExtra(AskActivity.QUESTION, "Tell me about " + it.title)));
            ask.setMinWidth(ui.dp(96));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = ui.dp(12);
            card.addView(ask, p);
        }
        card.setOnClickListener(v -> open(it));
        return wrap(card);
    }

    private void open(Feed.Item it) {
        if (it.entry != null) return; // a capture with no note has no page of its own yet
        host.openNote(it.path);
    }

    private View emptyState(String which) {
        LinearLayout box = new LinearLayout(ui.c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(ui.dp(16), ui.dp(48), ui.dp(16), ui.dp(16));
        boolean none = which.equals("novault");
        box.addView(ui.text(none ? "Choose your vault folder" : "Nothing here yet", 17, Ui.BOLD, ui.ink));
        TextView body = ui.text(none ? "Sparkbox reads Knowledge from your Obsidian vault." : "Share a link or text to Sparkbox from any app. It shows up here.", 15, Typeface.DEFAULT, ui.sub);
        body.setGravity(Gravity.CENTER);
        box.addView(body, ui.lp(8));
        box.addView(ui.button(none ? "Choose folder" : "Check vault folder now", true, v -> {
            if (none) host.startActivity(new Intent(host, VaultActivity.class).putExtra(VaultActivity.PICK, true)); else scan();
        }), ui.lp(16));
        return box;
    }
}
