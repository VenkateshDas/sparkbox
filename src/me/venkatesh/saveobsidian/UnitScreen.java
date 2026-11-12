package me.venkatesh.saveobsidian;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** One Source note: what it says, what it connected to, and ways to read, open or ask about it. Read-only in Phase 1; editing arrives with Phase 2. */
final class UnitScreen extends Screen {
    private final String path;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private LinearLayout page;
    private String text = "";

    UnitScreen(String path) { this.path = path; }

    @Override View build(Ui ui) {
        this.ui = ui;
        LinearLayout root = new LinearLayout(ui.c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Bars.header(ui, "Source", v -> host.onBackPressed()), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(64)));
        ScrollView scroll = new ScrollView(ui.c);
        page = new LinearLayout(ui.c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        draw();
        load();
        return root;
    }

    private Vault.Note note() {
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        return snap == null ? null : snap.graph.byPath(path);
    }

    /** The note's text, read off the main thread, for the sections that live in its body. */
    private void load() {
        final VaultStore.Snap snap = VaultStore.cached(ui.c);
        final Vault.Note n = note();
        if (snap == null || n == null) return;
        new Thread(() -> {
            String t = "";
            try { t = VaultStore.text(ui.c, snap.uri(n)); } catch (Exception unreadable) { }
            final String read = Vault.clipLines(t.replace("\r\n", "\n"));
            main.post(() -> { text = read; draw(); });
        }).start();
    }

    private void draw() {
        page.removeAllViews();
        Vault.Note n = note();
        if (n == null) { page.addView(ui.text("This note is no longer in the vault.", 15, Typeface.DEFAULT, ui.sub)); return; }
        boolean processed = n.processed();
        LinearLayout top = new LinearLayout(ui.c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(ui.chip(processed ? Feed.PROCESSED : Feed.INGESTED, processed ? 1 : 0));
        String lane = Lane.of(n.path, n.meta);
        if (!lane.isEmpty()) { top.addView(new View(ui.c), new LinearLayout.LayoutParams(ui.dp(8), 1)); top.addView(ui.chip(lane, 0)); }
        top.addView(new View(ui.c), new LinearLayout.LayoutParams(0, 1, 1));
        boolean[] time = new boolean[1];
        top.addView(ui.text(Feed.age(Feed.when(n.meta("captured"), time), time[0], LocalDateTime.now()), 12, Typeface.DEFAULT, ui.sub));
        page.addView(top);
        page.addView(ui.text(n.title, 20, Ui.BOLD, ui.ink), ui.lp(8));
        String url = n.meta("source"), name = url.isEmpty() ? n.meta("via") : Feed.host(url);
        if (!name.isEmpty()) {
            TextView source = ui.text(name, 13, Ui.MEDIUM, url.isEmpty() ? ui.sub : ui.accent);
            source.setMinHeight(ui.dp(48));
            source.setGravity(Gravity.CENTER_VERTICAL);
            if (!url.isEmpty()) { source.setOnClickListener(v -> open(url)); Ui.buttonRole(source); }
            page.addView(source, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        String quality = n.meta("quality");
        if (quality.equals("title_only") || quality.equals("failed")) page.addView(banner("Only the title was saved. No cards were made.", false), ui.lp(8));
        if (!processed) page.addView(banner(waiting(), false), ui.lp(8));

        if (!n.meta("curiosity").isEmpty()) {
            LinearLayout q = block("Curiosity prompt");
            q.addView(ui.text(n.meta("curiosity"), 15, Typeface.DEFAULT, ui.ink), ui.lp(4));
            page.addView(q, ui.lp(12));
        }
        List<String> claims = Vault.bullets(text, "Key claims");
        String summary = Vault.section(text, "Summary");
        if (processed) {
            if (summary.isEmpty() && !claims.isEmpty()) { summary = Vault.plain(claims.get(0)).replaceAll("\\s*\\((high|medium|low)\\)\\s*$", ""); claims = new ArrayList<>(claims.subList(1, claims.size())); } // the first claim became the summary, so it is not listed twice
            if (summary.isEmpty()) summary = n.description;
            if (!summary.isEmpty()) { LinearLayout s = block("Summary"); s.addView(ui.text(Vault.plain(summary), 15, Typeface.DEFAULT, ui.ink), ui.lp(4)); page.addView(s, ui.lp(12)); }
            if (!claims.isEmpty()) {
                page.addView(ui.text("Key notes", 17, Ui.BOLD, ui.ink), ui.lp(16));
                for (String c : claims) {
                    boolean low = c.toLowerCase(java.util.Locale.ROOT).endsWith("(low)");
                    String shown = Vault.plain(c).replaceAll("\\s*\\((high|medium|low)\\)\\s*$", "");
                    LinearLayout row = new LinearLayout(ui.c);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.addView(ui.text(shown, 15, Typeface.DEFAULT, ui.ink));
                    if (low) { LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); cp.topMargin = ui.dp(4); row.addView(ui.chip("low confidence", 2), cp); }
                    page.addView(row, ui.lp(8));
                }
            }
        } else if (!n.description.isEmpty()) {
            page.addView(ui.text("Description", 17, Ui.BOLD, ui.ink), ui.lp(16));
            page.addView(ui.text(n.description, 15, Typeface.DEFAULT, ui.ink), ui.lp(8));
        }
        String mine = Vault.section(text, "My ideas");
        if (!mine.isEmpty()) { LinearLayout m = block("In my words"); m.addView(ui.text(Vault.plain(mine), 15, Typeface.DEFAULT, ui.ink), ui.lp(4)); page.addView(m, ui.lp(12)); }

        addedFrom(n);

        if (n.words > 0) page.addView(row("Full text", n.words + " words", v -> host.startActivity(new Intent(host, VaultActivity.class).putExtra(VaultActivity.OPEN, n.path))), ui.lp(12));
        else page.addView(row("Open note", "", v -> host.startActivity(new Intent(host, VaultActivity.class).putExtra(VaultActivity.OPEN, n.path))), ui.lp(12));
        TextView ask = ui.button("Ask about this", true, v -> host.startActivity(new Intent(host, AskActivity.class).putExtra(AskActivity.QUESTION, "Tell me about " + n.title)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = ui.dp(16);
        page.addView(ask, p);
    }

    private String waiting() {
        boolean auto = Ingest.ready(ui.c);
        return auto ? "Not processed yet. It will be processed when you are online." : "Not processed yet. The agent on your Mac has not read this note.";
    }

    /** "Added from this note": topics, people and entities, with a New badge from the made-* keys. The heading opens Capture Map. */
    private void addedFrom(Vault.Note n) {
        List<String[]> rows = CaptureMapScreen.rows(n); // {group, name, isNew}
        if (rows.isEmpty()) return;
        TextView head = ui.text("Added from this note", 17, Ui.BOLD, ui.ink);
        head.setMinHeight(ui.dp(48));
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setOnClickListener(v -> host.push(new CaptureMapScreen(n.path)));
        Ui.buttonRole(head);
        page.addView(head, ui.lp(12));
        LinearLayout card = new LinearLayout(ui.c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
        card.setBackground(ui.round(ui.surface, ui.line, 12));
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        for (String[] r : rows) card.addView(CaptureMapScreen.line(ui, snap, r, host));
        page.addView(card);
    }

    private LinearLayout block(String label) {
        LinearLayout b = new LinearLayout(ui.c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        b.setBackground(ui.round(ui.raised, 0, 8));
        b.addView(ui.text(label, 12, Ui.BOLD, ui.sub));
        return b;
    }

    private TextView banner(String text, boolean bad) {
        TextView v = ui.text(text, 13, Typeface.DEFAULT, bad ? ui.err : ui.warn);
        v.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        v.setBackground(ui.round(bad ? ui.errBg : ui.warnBg, 0, 8));
        return v;
    }

    private View row(String title, String meta, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(ui.c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(ui.dp(56));
        r.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        r.setBackground(ui.ripple(ui.round(ui.surface, ui.line, 12)));
        r.addView(ui.text(title, 16, Ui.BOLD, ui.ink), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!meta.isEmpty()) r.addView(ui.text(meta, 13, Typeface.DEFAULT, ui.sub));
        r.addView(ui.text("  ›", 17, Typeface.DEFAULT, ui.sub));
        r.setOnClickListener(click);
        Ui.buttonRole(r);
        return r;
    }

    private void open(String url) {
        try { host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (RuntimeException none) { Toast.makeText(ui.c, "No app can open this link", Toast.LENGTH_SHORT).show(); }
    }
}
