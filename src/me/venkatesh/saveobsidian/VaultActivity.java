package me.venkatesh.saveobsidian;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The knowledge base on the phone: folders, notes, what links to what, and search. Read-only.
 * Quiet chrome: notes are set in the reading column, paths in mono so a note's address is always visible.
 */
public class VaultActivity extends Activity {
    static final String PICK = "pick", OPEN = "open", PICK_WRITE = "pickWrite";
    private static final int PAGE = 60;

    private Ui ui;
    private ScrollView scroll;
    private LinearLayout page, body;
    private VaultStore.Snap snap;
    private String problem = "";
    private boolean scanning;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** {"home"}, {"folder", path}, {"note", path}, {"orphans"}. The last one is on screen. */
    private final ArrayList<String[]> stack = new ArrayList<>();
    private String query = "", tab = "read", noteText, noteTextPath = "";
    private List<Vault.Note> semanticHits, similar;
    private String similarFor = "";
    private int shown = PAGE;
    private final Map<Vault.Note, String> days = new java.util.HashMap<>();
    private int[] indexed; // {have, total}, counted off the main thread because it reads the whole vector file
    private Runnable pendingSearch;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Usage.hit(this, "knowledge");
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false); // the app switcher must not show notes
        ui = new Ui(this);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.bg);
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(32));
        scroll.addView(page);
        setContentView(scroll);
        Ui.back(this, this::onBackPressed);
        Ui.insets(scroll);
        stack.add(new String[]{"home"});
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handle(intent); }

    private void handle(Intent intent) {
        if (intent.getBooleanExtra(PICK, false)) { intent.removeExtra(PICK); pick(false); return; }
        if (intent.getBooleanExtra(PICK_WRITE, false)) { intent.removeExtra(PICK_WRITE); pick(true); return; }
        String path = intent.getStringExtra(OPEN);
        if (path != null) { intent.removeExtra(OPEN); stack.clear(); stack.add(new String[]{"home"}); stack.add(new String[]{"note", path}); tab = "read"; }
    }

    @Override protected void onResume() {
        super.onResume();
        if (snap == null) snap = VaultStore.cached(this);
        draw(false);
        countIndex();
        scan();
    }

    private void scan() {
        if (scanning || !VaultStore.ready(this)) return;
        scanning = true;
        new Thread(() -> {
            VaultStore.Snap fresh = null;
            String error = "";
            try { fresh = VaultStore.scan(this); } catch (Exception e) { error = String.valueOf(e.getMessage()); }
            VaultStore.Snap done = fresh;
            String why = error;
            runOnUiThread(() -> {
                scanning = false;
                if (isDestroyed()) return;
                boolean changed = done != null && (snap == null || done.graph.notes.size() != snap.graph.notes.size() || newest(done) != newest(snap));
                if (done != null) snap = done;
                problem = why;
                if (changed || !why.isEmpty() || snap == null) { draw(false); countIndex(); } else { status(); countIndex(); }
            });
        }).start();
    }

    private static long newest(VaultStore.Snap s) { long m = 0; for (Vault.Note n : s.graph.notes) m = Math.max(m, n.mtime); return m; }

    /** write=false: read-only, as before. write=true: the same vault folder with write access, which Ingest needs to file captures into Knowledge. */
    private void pick(boolean write) {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | (write ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0));
        pick.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/Obsidian"));
        startActivityForResult(pick, write ? 8 : 7);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request != 7 && request != 8) || result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | (request == 8 ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0));
            if (request == 8 && (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new SecurityException();
            getContentResolver().takePersistableUriPermission(data.getData(), flags);
            getSharedPreferences("capture", 0).edit().putString("vault", data.getData().toString()).commit();
            VaultStore.last = null;
            snap = null;
            problem = "";
            BackgroundJob.schedule(this);
            draw(true);
            scan();
            if (request == 8) { Toast.makeText(this, "Write access granted", Toast.LENGTH_SHORT).show(); finish(); }
        } catch (Exception e) { Toast.makeText(this, request == 8 ? "Could not get write access to that folder." : "Could not read that folder.", Toast.LENGTH_SHORT).show(); }
    }

    @Override public void onBackPressed() {
        if (stack.size() > 1) { stack.remove(stack.size() - 1); tab = "read"; shown = PAGE; draw(true); } else finish();
    }

    private void go(String... where) { stack.add(where); tab = "read"; shown = PAGE; draw(true); }

    // ---- screens ----

    private void draw(boolean top) {
        main.removeCallbacksAndMessages(null);
        page.removeAllViews();
        body = null;
        String[] at = stack.get(stack.size() - 1);
        if (!VaultStore.ready(this)) { empty(); return; }
        if (snap == null) { loading(); return; }
        switch (at[0]) {
            case "folder": folder(at[1]); break;
            case "note": note(at[1]); break;
            case "orphans": orphans(); break;
            default: home();
        }
        if (top) scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void bar(String back, TextView... actions) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView b = ui.text("‹  " + back, 14, Ui.MEDIUM, ui.sub);
        b.setMinHeight(ui.dp(48));
        b.setMinWidth(ui.dp(48));
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setOnClickListener(v -> onBackPressed());
        Ui.buttonRole(b);
        row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        for (TextView a : actions) row.addView(a);
        page.addView(row);
    }

    private void empty() {
        bar("Inbox");
        page.addView(ui.text("Knowledge", 20, Ui.MEDIUM, ui.ink), ui.lp(8));
        page.addView(ui.text("Choose your vault folder to browse Knowledge and Sources. Sparkbox only reads them and never changes a note.", 14, Typeface.DEFAULT, ui.sub), ui.lp(12));
        page.addView(ui.button("Choose vault folder", true, v -> pick(false)), ui.lp(24));
    }

    private void loading() {
        bar("Inbox");
        page.addView(ui.text("Knowledge", 20, Ui.MEDIUM, ui.ink), ui.lp(8));
        page.addView(ui.text(problem.isEmpty() ? "Reading your vault" : problem, 14, Typeface.DEFAULT, ui.sub), ui.lp(12));
        if (!problem.isEmpty()) page.addView(ui.button("Choose vault folder", false, v -> pick(false)), ui.lp(16));
    }

    private TextView status;

    private void status() {
        if (status == null) return;
        Vault.Graph g = snap.graph;
        StringBuilder s = new StringBuilder(count(g.notes.size()) + " notes \u00b7 " + count(g.links - g.unresolved) + " links");
        if (indexed != null && indexed[1] > 0) s.append(indexed[0] == indexed[1] ? " \u00b7 search ready" : " \u00b7 indexing " + indexed[0] + " of " + indexed[1]);
        if (scanning) s.append(" \u00b7 checking for changes");
        if (!problem.isEmpty()) s.append(" \u00b7 ").append(problem);
        status.setText(s.toString());
    }

    /** Counts searchable notes in the background, then refreshes the status line. */
    private void countIndex() {
        if (Model.key(this).isEmpty() || snap == null) return;
        VaultStore.Snap s = snap;
        new Thread(() -> {
            int[] counted = VaultStore.indexed(this, s);
            runOnUiThread(() -> { if (!isDestroyed() && snap == s) { indexed = counted; status(); } });
        }).start();
    }

    private static String count(int n) { return String.format(Locale.US, "%,d", n); }

    private void home() {
        TextView ask = ui.button("Ask", false, v -> startActivity(new Intent(this, AskActivity.class)));
        ask.setMinHeight(ui.dp(40));
        bar("Inbox", ask);
        page.addView(ui.text("Knowledge", 20, Ui.MEDIUM, ui.ink), ui.lp(4));
        status = ui.text("", 12, Typeface.MONOSPACE, ui.muted);
        page.addView(status, ui.lp(8));
        status();

        EditText search = new EditText(this);
        search.setSingleLine();
        search.setHint("Search notes");
        search.setText(query);
        search.setTextColor(ui.ink);
        search.setHintTextColor(ui.muted);
        search.setTextSize(15);
        search.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        search.setBackground(ui.round(0, ui.line, 8));
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                if (q.equals(query)) return;
                query = q; semanticHits = null; shown = PAGE;
                fill();
                if (pendingSearch != null) main.removeCallbacks(pendingSearch);
                if (q.length() < 3) return;
                pendingSearch = () -> semantic(q);
                main.postDelayed(pendingSearch, 600); // embed the query once typing pauses
            }
        });
        page.addView(search, ui.lp(16));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        page.addView(body, ui.lp(8));
        fill();
    }

    private void semantic(String q) {
        VaultStore.Snap s = snap;
        new Thread(() -> {
            List<Vault.Note> hits;
            try { hits = VaultStore.semantic(this, s, q, 20); } catch (Exception offline) { hits = null; } // no key or network: the word match stays
            List<Vault.Note> done = hits;
            runOnUiThread(() -> { if (!isDestroyed() && q.equals(query) && done != null && !done.isEmpty()) { semanticHits = done; fill(); } });
        }).start();
    }

    private void fill() {
        if (body == null) return;
        body.removeAllViews();
        if (!query.isEmpty()) {
            List<Vault.Note> words = VaultStore.keyword(snap, query, 100);
            List<Vault.Note> hits = semanticHits == null ? words : Vault.blend(semanticHits, words, query, 100);
            if (hits.isEmpty()) { body.addView(ui.text("No notes match “" + query + "”", 14, Typeface.DEFAULT, ui.sub), ui.lp(16)); return; }
            body.addView(askRow(), ui.lp(8));
            body.addView(ui.label(hits.size() + (hits.size() == 1 ? " note" : " notes")), ui.lp(16));
            list(body, hits, true);
            return;
        }
        body.addView(ui.label("Folders"), ui.lp(16));
        Map<String, Integer> roots = new TreeMap<>();
        for (Vault.Note n : snap.graph.notes) { String top = n.path.substring(0, n.path.indexOf('/')); Integer o = roots.get(top); roots.put(top, o == null ? 1 : o + 1); }
        for (String r : VaultStore.ROOTS) if (roots.containsKey(r)) body.addView(folderRow(r, r, roots.get(r)));

        List<Vault.Note> recent = new ArrayList<>();
        for (Vault.Note n : snap.graph.notes) if (!n.catalog) recent.add(n);
        recent.sort((a, b) -> dayOf(b).compareTo(dayOf(a)));
        body.addView(ui.label("Recently updated"), ui.lp(24));
        list(body, recent.subList(0, Math.min(8, recent.size())), false);

        int orphans = snap.graph.orphans().size();
        if (orphans > 0) {
            TextView o = ui.text(orphans + (orphans == 1 ? " note has" : " notes have") + " no links", 14, Ui.MEDIUM, ui.sub);
            o.setMinHeight(ui.dp(48));
            o.setGravity(Gravity.CENTER_VERTICAL);
            o.setBackground(ui.ripple(null));
            o.setOnClickListener(v -> go("orphans"));
            Ui.buttonRole(o);
            body.addView(o, ui.lp(16));
        }
    }

    /** Turns the current search into a question for Ask. */
    private View askRow() {
        TextView row = ui.text("Ask your notes: \u201c" + query + "\u201d", 14, Ui.MEDIUM, ui.ink);
        row.setMinHeight(ui.dp(48));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        row.setMaxLines(2);
        row.setBackground(ui.ripple(ui.round(ui.raised, ui.line, 12)));
        row.setOnClickListener(v -> startActivity(new Intent(this, AskActivity.class).putExtra(AskActivity.QUESTION, query)));
        Ui.buttonRole(row);
        return row;
    }

    private void orphans() {
        bar("Knowledge");
        page.addView(ui.text("Notes with no links", 20, Ui.MEDIUM, ui.ink), ui.lp(8));
        page.addView(ui.text("Nothing links to these and they link to nothing. Each is a place to connect an idea.", 14, Typeface.DEFAULT, ui.sub), ui.lp(8));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        page.addView(box, ui.lp(16));
        list(box, snap.graph.orphans(), true);
    }

    private void folder(String path) {
        bar(path.contains("/") ? parentName(path) : "Knowledge");
        page.addView(ui.text(path.substring(path.lastIndexOf('/') + 1), 20, Ui.MEDIUM, ui.ink), ui.lp(8));
        if (path.contains("/")) page.addView(ui.text(path, 12, Typeface.MONOSPACE, ui.muted), ui.lp(4));
        Map<String, Integer> folders = new TreeMap<>();
        List<Vault.Note> notes = new ArrayList<>();
        String prefix = path + "/";
        for (Vault.Note n : snap.graph.notes) {
            if (!n.path.startsWith(prefix)) continue;
            String rest = n.path.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash >= 0) { String f = rest.substring(0, slash); Integer o = folders.get(f); folders.put(f, o == null ? 1 : o + 1); } else notes.add(n);
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        page.addView(box, ui.lp(16));
        for (Map.Entry<String, Integer> f : folders.entrySet()) box.addView(folderRow(f.getKey(), prefix + f.getKey(), f.getValue()));
        if (Layout.inSources(path + "/") || path.startsWith("Sources")) notes.sort((a, b) -> { int c = dayOf(b).compareTo(dayOf(a)); return c != 0 ? c : a.title.compareToIgnoreCase(b.title); });
        else notes.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        list(box, notes, false);
    }

    private String parentName(String path) {
        String parent = path.substring(0, path.lastIndexOf('/'));
        return parent.substring(parent.lastIndexOf('/') + 1);
    }

    private View folderRow(String name, String path, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(56));
        row.addView(ui.text(name, 16, Ui.MEDIUM, ui.ink), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(ui.text(String.valueOf(count), 14, Typeface.MONOSPACE, ui.muted));
        row.setBackground(ui.ripple(null));
        row.setOnClickListener(v -> go("folder", path));
        Ui.buttonRole(row);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        wrap.addView(rule());
        return wrap;
    }

    private View rule() {
        View v = new View(this);
        v.setBackgroundColor(ui.line);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1))));
        return v;
    }

    /** Note rows with a divider each. Shows the first `shown` and a button for the rest. */
    private void list(LinearLayout into, List<Vault.Note> notes, boolean withPath) {
        int limit = Math.min(notes.size(), shown);
        for (int i = 0; i < limit; i++) { into.addView(noteRow(notes.get(i), withPath)); into.addView(rule()); }
        if (notes.size() > limit) into.addView(ui.button("Show more (" + (notes.size() - limit) + ")", false, v -> { shown += PAGE; draw(false); }), ui.lp(16));
    }

    private View noteRow(Vault.Note n, boolean withPath) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, ui.dp(12), 0, ui.dp(12));
        TextView title = ui.text(n.title, 16, Ui.MEDIUM, ui.ink);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(title);
        StringBuilder meta = new StringBuilder();
        if (withPath) meta.append(n.folder());
        if (!n.type.isEmpty()) meta.append(meta.length() == 0 ? "" : " · ").append(n.type);
        meta.append(meta.length() == 0 ? "" : " · ").append(when(day(n)));
        TextView m = ui.text(meta.toString(), 12, Typeface.MONOSPACE, ui.muted);
        m.setMaxLines(1);
        m.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(m, ui.lp(4));
        if (!n.description.isEmpty() && !n.description.equalsIgnoreCase(n.title)) {
            TextView d = ui.text(n.description, 14, Typeface.DEFAULT, ui.sub);
            d.setMaxLines(2);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(d, ui.lp(4));
        }
        row.setBackground(ui.ripple(null));
        row.setOnClickListener(v -> go("note", n.path));
        Ui.buttonRole(row);
        return row;
    }

    // ---- one note ----

    private void note(String path) {
        Vault.Note n = snap.graph.byPath(path);
        if (n == null) { bar("Back"); page.addView(ui.text("This note is no longer in the vault.", 14, Typeface.DEFAULT, ui.sub), ui.lp(16)); return; }
        bar(stack.size() > 2 ? "Back" : "Knowledge");
        TextView title = ui.text(n.title, 20, Ui.MEDIUM, ui.ink);
        page.addView(title, ui.lp(8));
        TextView where = ui.text(Vault.stripMd(n.path), 12, Typeface.MONOSPACE, ui.muted);
        where.setMaxLines(1);
        where.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        where.setOnClickListener(v -> { if (n.folder().length() > 0) go("folder", n.folder()); });
        page.addView(where, ui.lp(8));
        StringBuilder meta = new StringBuilder();
        if (!n.type.isEmpty()) meta.append(n.type);
        meta.append(meta.length() == 0 ? "" : " · ").append("updated ").append(when(day(n)).toLowerCase(Locale.ROOT));
        page.addView(ui.text(meta.toString(), 12, Typeface.MONOSPACE, ui.muted), ui.lp(4));

        TextView obsidian = ui.text("Open in Obsidian", 12, Ui.MEDIUM, ui.accent);
        obsidian.setMinHeight(ui.dp(48));
        obsidian.setGravity(Gravity.CENTER_VERTICAL);
        obsidian.setOnClickListener(v -> openInObsidian(n));
        Ui.buttonRole(obsidian);
        page.addView(obsidian, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!n.topics.isEmpty()) {
            HorizontalScrollView strip = new HorizontalScrollView(this);
            strip.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(this);
            for (String t : n.topics) {
                Vault.Note to = snap.graph.resolve(t);
                TextView chip = ui.text(t, 12, Ui.MEDIUM, to == null ? ui.muted : ui.ink);
                chip.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
                chip.setMinHeight(ui.dp(32));
                chip.setGravity(Gravity.CENTER);
                chip.setBackground(ui.ripple(ui.round(ui.raised, ui.line, 8)));
                if (to != null) { chip.setOnClickListener(v -> go("note", to.path)); Ui.buttonRole(chip); }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = ui.dp(8);
                chips.addView(chip, lp);
            }
            strip.addView(chips);
            page.addView(strip, ui.lp(12));
        }

        LinearLayout tabs = new LinearLayout(this);
        int in = snap.graph.in(n).size(), out = snap.graph.out(n).size();
        tabs.addView(tabView("read", "Read", n), new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        tabs.addView(tabView("links", "Links " + (in + out), n), new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        tabs.addView(tabView("similar", "Similar", n), new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        page.addView(tabs, ui.lp(16));
        page.addView(rule());

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        page.addView(body, ui.lp(16));
        if (tab.equals("links")) links(n);
        else if (tab.equals("similar")) similar(n);
        else read(n);
    }

    private View tabView(String id, String label, Vault.Note n) {
        boolean on = tab.equals(id);
        TextView t = ui.text(label, 14, Ui.MEDIUM, on ? ui.ink : ui.muted);
        t.setGravity(Gravity.CENTER);
        t.setSelected(on);
        t.setBackground(ui.ripple(null));
        t.setOnClickListener(v -> { tab = id; draw(false); });
        Ui.buttonRole(t);
        return t;
    }

    private void read(Vault.Note n) {
        if (n.path.equals(noteTextPath) && noteText != null) { renderText(n); return; }
        body.addView(ui.text("Opening", 14, Typeface.DEFAULT, ui.muted));
        VaultStore.Snap s = snap;
        new Thread(() -> {
            String text;
            try { text = VaultStore.text(this, s.uri(n)); } catch (Exception e) { text = null; }
            String done = text;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                noteTextPath = n.path; noteText = done;
                String[] at = stack.get(stack.size() - 1);
                if (at[0].equals("note") && at[1].equals(n.path) && tab.equals("read") && body != null) { body.removeAllViews(); if (done == null) body.addView(ui.text("Cannot read this note. Check folder access in Settings.", 14, Typeface.DEFAULT, ui.sub)); else renderText(n); }
            });
        }).start();
    }

    private void renderText(Vault.Note n) {
        TextView t = ui.text("", 15, Typeface.DEFAULT, ui.ink);
        t.setLineSpacing(0, 1.35f);
        t.setText(Md.render(ui, noteText, new Md.Open() {
            @Override public void open(String raw) { openLink(raw); }
            @Override public boolean exists(String raw) { return snap.graph.resolve(Vault.target(raw)) != null; }
        }));
        t.setMovementMethod(LinkMovementMethod.getInstance());
        t.setHighlightColor(0x33000000 | (ui.accent & 0xFFFFFF));
        body.addView(t);
    }

    private void openInObsidian(Vault.Note n) {
        String label = VaultStore.label(this), vault = label.substring(label.lastIndexOf('/') + 1);
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("obsidian://open?vault=" + android.net.Uri.encode(vault) + "&file=" + android.net.Uri.encode(Vault.stripMd(n.path))))); }
        catch (RuntimeException none) { Toast.makeText(this, "Obsidian is not installed", Toast.LENGTH_SHORT).show(); }
    }

    private void openLink(String raw) {
        Vault.Note to = snap.graph.resolve(Vault.target(raw));
        if (to != null) go("note", to.path);
        else Toast.makeText(this, "No page for “" + Vault.label(raw) + "” yet", Toast.LENGTH_SHORT).show();
    }

    private void links(Vault.Note n) {
        Vault.Graph g = snap.graph;
        List<Vault.Note> out = g.out(n), in = g.in(n);
        body.addView(ui.label("Links to · " + out.size()));
        if (out.isEmpty()) body.addView(ui.text("This note does not link to another note.", 14, Typeface.DEFAULT, ui.muted), ui.lp(8));
        list(body, out, true);
        body.addView(ui.label("Linked from · " + in.size()), ui.lp(24));
        if (in.isEmpty()) body.addView(ui.text("No note links here yet.", 14, Typeface.DEFAULT, ui.muted), ui.lp(8));
        list(body, in, true);
        List<String> missing = new ArrayList<>();
        for (String l : n.links) if (g.resolve(l) == null) missing.add(Vault.label(l));
        if (!missing.isEmpty()) {
            body.addView(ui.label("No page yet · " + missing.size()), ui.lp(24));
            body.addView(ui.text(android.text.TextUtils.join("  ·  ", missing.subList(0, Math.min(40, missing.size()))), 14, Typeface.DEFAULT, ui.muted), ui.lp(8));
        }
    }

    private void similar(Vault.Note n) {
        if (Model.key(this).isEmpty()) { body.addView(ui.text("Similar notes need an OpenRouter key. Add one in Settings under Smart tags.", 14, Typeface.DEFAULT, ui.sub)); return; }
        if (n.path.equals(similarFor) && similar != null) { showSimilar(n); return; }
        body.addView(ui.text("Finding similar notes", 14, Typeface.DEFAULT, ui.muted));
        VaultStore.Snap s = snap;
        new Thread(() -> {
            List<Vault.Note> found = VaultStore.similar(this, s, n, 10);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                similarFor = n.path; similar = found;
                String[] at = stack.get(stack.size() - 1);
                if (at[0].equals("note") && at[1].equals(n.path) && tab.equals("similar") && body != null) { body.removeAllViews(); showSimilar(n); }
            });
        }).start();
    }

    private void showSimilar(Vault.Note n) {
        if (similar.isEmpty()) {
            int[] idx = VaultStore.indexed(this, snap);
            body.addView(ui.text(idx[0] < idx[1] ? "Still indexing: " + idx[0] + " of " + idx[1] + " notes are searchable. Open the app again in a few minutes." : "Nothing in your vault is close enough to this note.", 14, Typeface.DEFAULT, ui.sub));
            return;
        }
        list(body, similar, true);
    }

    private String dayOf(Vault.Note n) {
        String d = days.get(n);
        if (d == null) days.put(n, d = day(n));
        return d;
    }

    // ---- dates ----

    private static final Pattern DMY = Pattern.compile("^(\\d{2})/(\\d{2})/(\\d{2})"), ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** yyyy-MM-dd from the note's own date, else its file name, else the file's modified time. */
    static String day(Vault.Note n) {
        String u = n.updated;
        if (u.length() >= 10 && ISO.matcher(u).region(0, 10).matches()) return u.substring(0, 10);
        Matcher m = DMY.matcher(u);
        if (m.find()) return "20" + m.group(3) + "-" + m.group(2) + "-" + m.group(1);
        String file = n.path.substring(n.path.lastIndexOf('/') + 1);
        if (file.length() >= 10 && ISO.matcher(file).region(0, 10).matches()) return file.substring(0, 10);
        return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(n.mtime));
    }

    static String when(String day) {
        try {
            LocalDate d = LocalDate.parse(day), now = LocalDate.now();
            long n = ChronoUnit.DAYS.between(d, now);
            if (n == 0) return "Today";
            if (n == 1) return "Yesterday";
            if (n > 1 && n < 7) return n + " days ago";
            return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) + (d.getYear() == now.getYear() ? "" : " " + d.getYear());
        } catch (RuntimeException bad) { return day; }
    }
}
