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
        head.setPadding(ui.dp(16), 0, ui.dp(4), 0);
        LinearLayout titles = new LinearLayout(ui.c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(ui.text("Feed", 20, Ui.BOLD, ui.ink));
        subtitle = ui.text("", 12, Typeface.DEFAULT, ui.sub);
        titles.addView(subtitle);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView menu = ui.text("☰", 22, Typeface.DEFAULT, ui.ink);
        menu.setMinHeight(ui.dp(48));
        menu.setMinWidth(ui.dp(48));
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("Menu");
        menu.setOnClickListener(v -> {
            android.widget.PopupMenu pop = new android.widget.PopupMenu(ui.c, v);
            pop.getMenu().add(0, 1, 0, "Profile");
            pop.getMenu().add(0, 2, 1, "Settings");
            pop.getMenu().add(0, 3, 2, "Browse vault");
            pop.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) host.push(new ProfileScreen());
                else if (item.getItemId() == 2) host.push(new SettingsScreen());
                else host.startActivity(new android.content.Intent(host, VaultActivity.class));
                return true;
            });
            pop.show();
        });
        Ui.buttonRole(menu);
        head.addView(menu);
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
        FrameLayout frame = new FrameLayout(ui.c);
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView plus = ui.text("+", 28, Ui.MEDIUM, ui.onAccent);
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(ui.ripple(ui.round(ui.accent, 0, 28)));
        plus.setContentDescription("Capture");
        plus.setElevation(ui.dp(6));
        plus.setOnClickListener(v -> captureMenu());
        Ui.buttonRole(plus);
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(ui.dp(56), ui.dp(56), Gravity.BOTTOM | Gravity.END);
        at.setMargins(0, 0, ui.dp(16), ui.dp(16));
        frame.addView(plus, at);
        return frame;
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
        android.widget.EditText input = new android.widget.EditText(ui.c);
        input.setHint("Paste a link or write a note");
        input.setMinLines(3);
        input.setGravity(Gravity.TOP);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); // autocorrect breaks pasted URLs
        LinearLayout box = new LinearLayout(ui.c);
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog d = new AlertDialog.Builder(host).setTitle("New capture").setView(box)
            .setPositiveButton("Save", (dialog, w) -> {
                String value = input.getText().toString().trim();
                if (!value.isEmpty()) host.startActivity(new Intent(host, ShareActivity.class).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value));
            }).setNegativeButton("Cancel", null).create();
        d.show();
        input.requestFocus();
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    @Override void onHide() { shown = false; main.removeCallbacks(ticker); Ingest.onStage = null; }

    @Override void onShow() {
        shown = true;
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 10000);
        Ingest.onStage = () -> main.post(this::redrawLive);
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
        if (all) { Spark.Plan plan = SparkScreen.plan(ui); if (!plan.queue.isEmpty()) rows.add(plan); }
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
        @Override public int getViewTypeCount() { return 5; }
        @Override public int getItemViewType(int i) {
            Object r = rows.get(i);
            return r instanceof Heading ? 0 : r instanceof Attention ? 1 : r instanceof Feed.Item ? 2 : r instanceof Spark.Plan ? 4 : 3;
        }
        @Override public View getView(int i, View reuse, ViewGroup parent) {
            Object r = rows.get(i);
            if (r instanceof Heading) return heading(((Heading) r).text);
            if (r instanceof Attention) return attentionCard((Attention) r);
            if (r instanceof Feed.Item) return itemCard((Feed.Item) r);
            if (r instanceof Spark.Plan) return strip((Spark.Plan) r);
            return emptyState(String.valueOf(r));
        }
    }

    /** "Morning Spark": what is due today and a way in. */
    private View strip(Spark.Plan plan) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout text = new LinearLayout(ui.c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(ui.text("Morning Spark", 17, Ui.BOLD, ui.ink));
        int n = plan.queue.size();
        text.addView(ui.text(n + (n == 1 ? " card" : " cards") + " due, about " + plan.minutes() + " min", 13, Typeface.DEFAULT, ui.sub));
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView start = ui.button("Start", true, v -> host.select(1));
        start.setMinWidth(ui.dp(96));
        card.addView(start);
        card.setOnClickListener(v -> host.select(1));
        return wrap(card);
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

    /** A round letter badge in the source's colour, like a profile picture on a post. */
    private View avatar(String seed, String glyph) {
        float[] hsv = {Math.abs(seed.hashCode()) % 360, ui.dark ? 0.35f : 0.25f, ui.dark ? 0.38f : 0.92f};
        android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable();
        disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        disc.setColor(android.graphics.Color.HSVToColor(hsv));
        TextView v = ui.text(glyph, 15, Ui.BOLD, ui.dark ? 0xFFDDDDDD : 0xFF444444);
        v.setGravity(Gravity.CENTER);
        v.setBackground(disc);
        v.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        return v;
    }

    /** One action under a post: icon and short label side by side, centred, 48 dp tall, spread evenly. */
    private View action(String label, int icon, View.OnClickListener click) {
        LinearLayout v = new LinearLayout(ui.c);
        v.setGravity(Gravity.CENTER);
        v.setMinimumHeight(ui.dp(48));
        android.widget.ImageView image = new android.widget.ImageView(ui.c);
        image.setImageResource(icon);
        image.setImageTintList(android.content.res.ColorStateList.valueOf(ui.sub));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.addView(image, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        TextView text = ui.text(label, 14, Ui.MEDIUM, ui.sub);
        text.setPadding(ui.dp(8), 0, 0, 0);
        v.addView(text);
        v.setBackground(ui.ripple(null));
        v.setOnClickListener(click);
        v.setContentDescription(label);
        Ui.buttonRole(v);
        return v;
    }

    /** A feed card built like a social post: who and when on top, the title and tldr, a link preview image, then a row of actions. */
    private View itemCard(Feed.Item it) {
        LinearLayout card = card();
        card.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), 0);
        boolean source = it.kind.equals(Feed.SOURCE), topic = it.kind.equals(Feed.TOPIC);

        LinearLayout byline = new LinearLayout(ui.c);
        byline.setGravity(Gravity.CENTER_VERTICAL);
        String who = source ? (it.sourceName.isEmpty() ? "Note" : it.sourceName) : topic ? "Topic" : "Person";
        byline.addView(avatar(who, who.substring(0, 1).toUpperCase()));
        LinearLayout names = new LinearLayout(ui.c);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        TextView name = ui.text(who, 15, Ui.BOLD, ui.ink);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(name);
        String when = topic ? it.count + " new sources" : it.age;
        String line = (when.isEmpty() ? "" : when) + (it.lane.isEmpty() ? "" : (when.isEmpty() ? "" : " · ") + it.lane);
        if (!line.isEmpty()) names.addView(ui.text(line, 12, Typeface.DEFAULT, ui.sub));
        byline.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (source) byline.addView(ui.chip(it.status, it.status.equals(Feed.PROCESSED) ? 1 : 0));
        card.addView(byline);

        TextView title = ui.text(it.title, 17, Ui.BOLD, ui.ink);
        title.setMaxLines(3);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(title, ui.lp(12));
        if (it.kind.equals(Feed.PERSON)) card.addView(ui.text("Mentioned in " + it.count + (it.count == 1 ? " note" : " notes"), 14, Typeface.DEFAULT, ui.sub), ui.lp(4));
        if (!it.tldr.isEmpty()) {
            TextView tldr = ui.text(it.tldr, 15, Typeface.DEFAULT, ui.ink);
            tldr.setMaxLines(4);
            tldr.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(tldr, ui.lp(8));
        }
        if (source && !it.url.isEmpty()) {
            View pic = Thumb.view(ui.c, ui, it.title, it.url, 0, 180);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(180));
            p.topMargin = ui.dp(12);
            card.addView(pic, p);
        }
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
        if (it.cards > 0) card.addView(ui.text(it.cards + (it.cards == 1 ? " card" : " cards") + (it.kept ? ", up first in Spark" : ""), 13, Typeface.DEFAULT, ui.sub), ui.lp(8));
        if (it.entry != null) { View live = progressPanel(it.entry); if (live != null) card.addView(live, ui.lp(12)); }
        String problem = it.entry == null ? "" : Ingest.problem(ui.c, it.entry);
        if (!problem.isEmpty()) {
            TextView warn = ui.text(problem, 13, Typeface.DEFAULT, ui.warn);
            warn.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            warn.setBackground(ui.round(ui.warnBg, 0, 8));
            card.addView(warn, ui.lp(8));
        }
        if (!it.related.isEmpty()) {
            LinearLayout rel = new LinearLayout(ui.c);
            rel.setOrientation(LinearLayout.VERTICAL);
            rel.addView(ui.text("Related", 12, Ui.BOLD, ui.sub));
            for (final String[] r : it.related) {
                TextView link = ui.text(r[0], 14, Ui.MEDIUM, ui.accent);
                link.setSingleLine(true);
                link.setEllipsize(android.text.TextUtils.TruncateAt.END);
                link.setMinHeight(ui.dp(40));
                link.setGravity(Gravity.CENTER_VERTICAL);
                link.setOnClickListener(v -> host.openNote(r[1]));
                Ui.buttonRole(link);
                rel.addView(link);
            }
            card.addView(rel, ui.lp(12));
        }

        View rule = new View(ui.c);
        rule.setBackgroundColor(ui.line);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        rp.topMargin = ui.dp(12);
        card.addView(rule, rp);
        LinearLayout bar = new LinearLayout(ui.c);
        if (it.entry == null) bar.addView(action("Ask", R.drawable.ic_nav_ask, v -> host.ask("Tell me about " + it.title)), new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        else if (!it.entry.url.isEmpty() && it.entry.file.isEmpty() && Model.key(ui.c).length() > 0) bar.addView(action("Retry", R.drawable.ic_retry, v -> retry(it.entry)), new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        if (it.cards > 0) bar.addView(action(it.kept ? "Kept" : "Keep", R.drawable.ic_keep, v -> { it.kept = !it.kept; CardStore.keep(ui.c, it.path, it.kept); drawRows(); }), new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        if (!it.url.isEmpty()) bar.addView(action("Open", R.drawable.ic_open, v -> openLink(it.url)), new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        String shared = it.url.isEmpty() ? it.title : it.title + "\n" + it.url;
        bar.addView(action("Share", R.drawable.ic_share, v -> host.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shared), null))), new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        card.addView(bar);
        card.setOnClickListener(v -> open(it));
        if (source && it.entry == null && !it.path.isEmpty()) card.setOnLongClickListener(v -> { Edits.confirmDelete(host, it.path, it.title, this::scan); return true; });
        return wrap(card);
    }

    /** The live checklist for a capture that is being processed (or waiting its turn): what is done, what is happening now, what comes next. Null when there is nothing to show. */
    private View progressPanel(InboxFile.Entry entry) {
        int now = Ingest.stage(ui.c, entry);
        boolean queued = now < 0 && Ingest.queued(ui.c, entry);
        if (now < 0 && !queued) return null;
        LinearLayout box = new LinearLayout(ui.c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        box.setBackground(ui.round(ui.raised, 0, 8));
        box.addView(ui.text(queued ? "Waiting for its turn" : "Processing now", 12, Ui.BOLD, ui.sub));
        for (int i = 0; i < Ingest.STEPS.length; i++) {
            boolean done = !queued && i < now || i == 0, active = !queued && i == now;
            String mark = done ? "✓" : active ? "●" : "○";
            LinearLayout line = new LinearLayout(ui.c);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView m = ui.text(mark, 14, Ui.BOLD, done || active ? ui.accent : ui.sub);
            m.setGravity(Gravity.CENTER);
            line.addView(m, new LinearLayout.LayoutParams(ui.dp(24), ViewGroup.LayoutParams.WRAP_CONTENT));
            String label = Ingest.STEPS[i] + (active ? "…  " + Ingest.stageSeconds(ui.c, entry) + "s" : "");
            line.addView(ui.text(label, 14, active ? Ui.MEDIUM : Typeface.DEFAULT, active ? ui.ink : done ? ui.sub : ui.sub));
            box.addView(line, ui.lp(i == 0 ? 8 : 4));
        }
        return box;
    }

    /** Steps redraw through Ingest.onStage; this slow pass is only a safety net (and moves the seconds counter). */
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (live()) redrawLive();
            if (shown) main.postDelayed(this, 10000);
        }
    };

    private boolean live() {
        for (Object row : rows) if (row instanceof Feed.Item && ((Feed.Item) row).entry != null && (Ingest.stage(ui.c, ((Feed.Item) row).entry) >= 0 || Ingest.queued(ui.c, ((Feed.Item) row).entry))) return true;
        return false;
    }

    private void redrawLive() {
        if (shown && list != null && list.getAdapter() instanceof android.widget.BaseAdapter) ((android.widget.BaseAdapter) list.getAdapter()).notifyDataSetChanged();
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
            if (none) host.startActivity(new Intent(host, VaultActivity.class).putExtra(VaultActivity.PICK_WRITE, true)); else scan();
        }), ui.lp(16));
        return box;
    }
}
