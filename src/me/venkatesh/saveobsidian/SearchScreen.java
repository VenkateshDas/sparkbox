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
    private final SearchState view = new SearchState();
    private Ui ui;
    private String query = "", chip = "All";
    private List<Vault.Note> meaning;
    private Runnable pending, pendingMeaning;

    /** Drawn by SearchView; search and ranking stay here. */
    @Override View build(Ui ui) {
        this.ui = ui;
        view.typed(query);
        show();
        return SearchView.create(ui.c, ui, view, actions);
    }

    private final SearchActions actions = new SearchActions() {
        @Override public void query(String q) {
            if (q.equals(query)) return;
            query = q;
            meaning = null;
            if (pending != null) main.removeCallbacks(pending);
            if (pendingMeaning != null) main.removeCallbacks(pendingMeaning);
            pending = SearchScreen.this::show;
            main.postDelayed(pending, 250);
            if (q.length() >= 3) { pendingMeaning = () -> semantic(q); main.postDelayed(pendingMeaning, 700); }
        }
        @Override public void chip(String name) { chip = name; show(); }
        @Override public void open(String path) { host.openNote(path); }
        @Override public void ask() { host.ask(query); }
    };

    private void semantic(String q) {
        final VaultStore.Snap snap = VaultStore.cached(ui.c);
        if (snap == null) return;
        new Thread(() -> {
            List<Vault.Note> hits;
            Limits.interactive(true);
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
        List<Hit> hits = new ArrayList<>();
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        for (Vault.Note n : all) {
            if (!matches(n) || hits.size() >= LIMIT) continue;
            String kind = kind(n), age = "";
            if (kind.equals("Source")) { boolean[] t = new boolean[1]; age = Feed.age(Feed.when(n.meta("captured"), t), t[0], LocalDateTime.now()); }
            String meta = kind.equals("Source") ? (Feed.host(n.meta("source")).isEmpty() ? n.meta("via") : Feed.host(n.meta("source"))) : "";
            if (meta.isEmpty() && kind.equals("Topic") && snap != null) { int c = 0; for (Vault.Note f : snap.graph.in(n)) if (Layout.inSources(f.path)) c++; meta = c + (c == 1 ? " source" : " sources"); }
            hits.add(new Hit(n.path, kind, Lane.of(n.path, n.meta), age, n.title, meta, Feed.snippet(n.digest, query, 90)));
        }
        view.publish(chip, all.size(), hits);
    }
}
