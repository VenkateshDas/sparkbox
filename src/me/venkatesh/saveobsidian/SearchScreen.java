package me.venkatesh.saveobsidian;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Search over notes, topics and people: words first, meaning merged in when it arrives. */
final class SearchScreen extends Screen {
    private static final String[] CHIPS = {"All", "Sources", "Topics", "People"};
    private static final int LIMIT = 60;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private EditText input;
    private LinearLayout chips, results;
    private String query = "", chip = "All";
    private List<Vault.Note> meaning;
    private Runnable pending, pendingMeaning;

    @Override View build(Ui ui) {
        this.ui = ui;
        LinearLayout root = new LinearLayout(ui.c);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(ui.c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        head.addView(ui.text("Search", 20, Ui.BOLD, ui.ink));
        root.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(64)));
        View rule = new View(ui.c);
        rule.setBackgroundColor(ui.line);
        root.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        input = new EditText(ui.c);
        input.setSingleLine();
        input.setHint("Search notes, topics, people");
        input.setHintTextColor(ui.sub);
        input.setTextColor(ui.ink);
        input.setTextSize(15);
        input.setText(query);
        input.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        input.setBackground(ui.round(ui.raised, ui.line, 24));
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                if (q.equals(query)) return;
                query = q;
                meaning = null;
                if (pending != null) main.removeCallbacks(pending);
                if (pendingMeaning != null) main.removeCallbacks(pendingMeaning);
                pending = SearchScreen.this::show;
                main.postDelayed(pending, 250);
                if (q.length() >= 3) { pendingMeaning = () -> semantic(q); main.postDelayed(pendingMeaning, 700); }
            }
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48));
        ip.setMargins(ui.dp(16), ui.dp(12), ui.dp(16), 0);
        root.addView(input, ip);

        HorizontalScrollView strip = new HorizontalScrollView(ui.c);
        strip.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(ui.c);
        chips.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), 0);
        strip.addView(chips);
        root.addView(strip);

        ScrollView scroll = new ScrollView(ui.c);
        results = new LinearLayout(ui.c);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24));
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        show();
        return root;
    }

    private void semantic(String q) {
        final VaultStore.Snap snap = VaultStore.cached(ui.c);
        if (snap == null) return;
        new Thread(() -> {
            List<Vault.Note> hits;
            try { hits = VaultStore.semantic(ui.c, snap, q, 30); } catch (Exception offline) { hits = null; } // no key or no network: the word results stay, silently
            final List<Vault.Note> done = hits;
            main.post(() -> { if (q.equals(query) && done != null && !done.isEmpty()) { meaning = done; show(); } });
        }).start();
    }

    private static String kind(Vault.Note n) {
        if (Layout.inSources(n.path)) return "Source";
        if (n.path.startsWith("Knowledge/Topics/")) return "Topic";
        if (n.path.startsWith("Knowledge/People/")) return "Person";
        return "Note";
    }

    private List<Vault.Note> found() {
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        if (snap == null || query.isEmpty()) return new ArrayList<>();
        List<Vault.Note> words = VaultStore.keyword(snap, query, 200);
        List<Vault.Note> all = meaning == null ? words : Vault.blend(meaning, words, query, 200);
        List<Vault.Note> out = new ArrayList<>();
        for (Vault.Note n : all) if (!n.path.endsWith("/My cards.md")) out.add(n);
        return out;
    }

    private boolean matches(Vault.Note n) {
        String k = kind(n);
        switch (chip) {
            case "Sources": return k.equals("Source");
            case "Topics": return k.equals("Topic");
            case "People": return k.equals("Person");
            default: return true;
        }
    }

    private void show() {
        List<Vault.Note> all = found();
        results.removeAllViews();
        drawChips(all.size());
        if (query.isEmpty()) { results.addView(ui.text("Type to search your notes, topics and people.", 15, Typeface.DEFAULT, ui.sub)); return; }
        List<Vault.Note> shown = new ArrayList<>();
        for (Vault.Note n : all) if (matches(n) && shown.size() < LIMIT) shown.add(n);
        if (shown.isEmpty()) { results.addView(none()); return; }
        for (Vault.Note n : shown) results.addView(row(n));
    }

    private void drawChips(int total) {
        chips.removeAllViews();
        for (String name : CHIPS) {
            boolean on = name.equals(chip);
            String label = name.equals("All") && !query.isEmpty() ? "All " + total : name;
            TextView v = ui.text(label, 14, Ui.MEDIUM, on ? ui.onAccent : ui.sub);
            v.setGravity(Gravity.CENTER);
            v.setPadding(ui.dp(14), 0, ui.dp(14), 0);
            v.setBackground(ui.round(on ? ui.accent : ui.raised, on ? 0 : ui.line, 18));
            v.setSelected(on);
            Ui.buttonRole(v);
            LinearLayout cell = new LinearLayout(ui.c);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(36)));
            cell.setOnClickListener(x -> { chip = name; show(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48));
            p.rightMargin = ui.dp(8);
            chips.addView(cell, p);
        }
    }

    private View none() {
        LinearLayout box = new LinearLayout(ui.c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(ui.dp(16), ui.dp(32), ui.dp(16), ui.dp(16));
        box.addView(ui.text("No results", 17, Ui.BOLD, ui.ink));
        TextView body = ui.text("Nothing matches this word. Try another, or ask your vault.", 15, Typeface.DEFAULT, ui.sub);
        body.setGravity(Gravity.CENTER);
        box.addView(body, ui.lp(8));
        box.addView(ui.button("Ask your vault", true, v -> host.startActivity(new Intent(host, AskActivity.class).putExtra(AskActivity.QUESTION, query))), ui.lp(16));
        return box;
    }

    private View row(Vault.Note n) {
        String kind = kind(n);
        LinearLayout card = new LinearLayout(ui.c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        card.setBackground(ui.ripple(ui.round(ui.surface, ui.line, 12)));
        LinearLayout top = new LinearLayout(ui.c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(ui.chip(kind, 0));
        String lane = Lane.of(n.path, n.meta);
        if (!lane.isEmpty()) { top.addView(new View(ui.c), new LinearLayout.LayoutParams(ui.dp(8), 1)); top.addView(ui.chip(lane, 0)); }
        top.addView(new View(ui.c), new LinearLayout.LayoutParams(0, 1, 1));
        if (kind.equals("Source")) {
            boolean[] t = new boolean[1];
            top.addView(ui.text(Feed.age(Feed.when(n.meta("captured"), t), t[0], LocalDateTime.now()), 12, Typeface.DEFAULT, ui.sub));
        }
        card.addView(top);
        TextView title = ui.text(n.title, 17, Ui.BOLD, ui.ink);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(title, ui.lp(8));
        String meta = kind.equals("Source") ? (Feed.host(n.meta("source")).isEmpty() ? n.meta("via") : Feed.host(n.meta("source"))) : "";
        if (meta.isEmpty() && kind.equals("Topic")) { VaultStore.Snap s = VaultStore.cached(ui.c); if (s != null) { int c = 0; for (Vault.Note f : s.graph.in(n)) if (Layout.inSources(f.path)) c++; meta = c + (c == 1 ? " source" : " sources"); } }
        if (!meta.isEmpty()) card.addView(ui.text(meta, 13, Typeface.DEFAULT, ui.sub), ui.lp(4));
        String snippet = Feed.snippet(n.digest, query, 90);
        if (!snippet.isEmpty()) card.addView(emphasis(snippet), ui.lp(8));
        card.setOnClickListener(v -> host.openNote(n.path));
        LinearLayout box = new LinearLayout(ui.c);
        box.setPadding(0, 0, 0, ui.dp(12));
        box.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /** The snippet with the matching words in semi-bold (weight, not a highlight colour). */
    private TextView emphasis(String snippet) {
        SpannableString s = new SpannableString(snippet);
        String low = snippet.toLowerCase(Locale.ROOT);
        for (String w : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (w.isEmpty()) continue;
            for (int at = low.indexOf(w); at >= 0; at = low.indexOf(w, at + w.length())) s.setSpan(new StyleSpan(Typeface.BOLD), at, at + w.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView v = ui.text("", 13, Typeface.DEFAULT, ui.sub);
        v.setText(s);
        return v;
    }
}
