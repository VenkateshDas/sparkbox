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
public class VaultActivity extends androidx.activity.ComponentActivity {
    static final String PICK = "pick", OPEN = "open", PICK_WRITE = "pickWrite";
    private static final int PAGE = 60;

    private Ui ui;
    private final PageState page = new PageState();
    private List<Block> b;
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
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this); // system bar padding goes on a plain View; a ComposeView ignores it
        frame.setBackgroundColor(ui.bg);
        frame.addView(PageView.create(this, ui, "Knowledge", page, this::back));
        setContentView(frame);
        Ui.insets(frame);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) { @Override public void handleOnBackPressed() { back(); } });
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

    private void back() {
        if (stack.size() > 1) { stack.remove(stack.size() - 1); tab = "read"; shown = PAGE; draw(true); } else finish();
    }

    private void go(String... where) { stack.add(where); tab = "read"; shown = PAGE; draw(true); }

    // ---- screens ----

    private String title = "Knowledge";

    private void draw(boolean top) {
        main.removeCallbacksAndMessages(null);
        b = new ArrayList<>();
        String[] at = stack.get(stack.size() - 1);
        title = "Knowledge";
        if (!VaultStore.ready(this)) empty();
        else if (snap == null) loading();
        else switch (at[0]) {
            case "folder": folder(at[1]); break;
            case "note": note(at[1]); break;
            case "orphans": orphans(); break;
            default: home();
        }
        page.publish(title, b);
    }

    private void empty() {
        b.add(new Body("Choose your vault folder to browse Knowledge and Sources. Sparkbox only reads them and never changes a note.", true, false));
        b.add(new Primary("Choose vault folder", () -> pick(false)));
    }

    private void loading() {
        b.add(new Body(problem.isEmpty() ? "Reading your vault" : problem, true, false));
        if (!problem.isEmpty()) b.add(new Primary("Choose vault folder", () -> pick(false)));
    }

    private String statusLine() {
        Vault.Graph g = snap.graph;
        StringBuilder s = new StringBuilder(count(g.notes.size()) + " notes \u00b7 " + count(g.links - g.unresolved) + " links");
        if (indexed != null && indexed[1] > 0) s.append(indexed[0] == indexed[1] ? " \u00b7 search ready" : " \u00b7 indexing " + indexed[0] + " of " + indexed[1]);
        if (scanning) s.append(" \u00b7 checking for changes");
        if (!problem.isEmpty()) s.append(" \u00b7 ").append(problem);
        return s.toString();
    }

    private void status() { if (stack.size() == 1 && snap != null) draw(false); }

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
        b.add(new Body(statusLine(), true, false));
        b.add(new Field("", query, 1, new ArrayList<>(), t -> {
            String q = t.trim();
            if (q.equals(query)) return;
            query = q; semanticHits = null; shown = PAGE;
            draw(false);
            if (pendingSearch != null) main.removeCallbacks(pendingSearch);
            if (q.length() < 3) return;
            pendingSearch = () -> semantic(q);
            main.postDelayed(pendingSearch, 600); // embed the query once typing pauses
        }, "Search notes"));
        if (!query.isEmpty()) {
            List<Vault.Note> words = VaultStore.keyword(snap, query, 100);
            List<Vault.Note> hits = semanticHits == null ? words : Vault.blend(semanticHits, words, query, 100);
            if (hits.isEmpty()) { b.add(new Body("No notes match \u201c" + query + "\u201d", true, false)); return; }
            List<Nav> ask = new ArrayList<>();
            ask.add(new Nav("Ask your notes: \u201c" + query + "\u201d", "", "", false, () -> startActivity(new Intent(this, AskActivity.class).putExtra(AskActivity.QUESTION, query))));
            b.add(new Group(ask));
            b.add(new Heading(hits.size() + (hits.size() == 1 ? " note" : " notes")));
            list(hits, true);
            return;
        }
        b.add(new Heading("Folders"));
        Map<String, Integer> roots = new TreeMap<>();
        for (Vault.Note n : snap.graph.notes) { String top = n.path.substring(0, n.path.indexOf('/')); Integer o = roots.get(top); roots.put(top, o == null ? 1 : o + 1); }
        List<Nav> folders = new ArrayList<>();
        for (String r : VaultStore.ROOTS) if (roots.containsKey(r)) folders.add(folderRow(r, r, roots.get(r)));
        if (!folders.isEmpty()) b.add(new Group(folders));
        List<Vault.Note> recent = new ArrayList<>();
        for (Vault.Note n : snap.graph.notes) if (!n.catalog) recent.add(n);
        recent.sort((x, y) -> dayOf(y).compareTo(dayOf(x)));
        b.add(new Heading("Recently updated"));
        list(recent.subList(0, Math.min(8, recent.size())), false);
        int orphans = snap.graph.orphans().size();
        if (orphans > 0) b.add(new Heading(orphans + (orphans == 1 ? " note has" : " notes have") + " no links", Origin.NONE, () -> go("orphans")));
    }

    private void semantic(String q) {
        VaultStore.Snap s = snap;
        new Thread(() -> {
            List<Vault.Note> hits;
            Limits.interactive(true);
            try { hits = VaultStore.semantic(this, s, q, 20); } catch (Exception offline) { hits = null; } // no key or network: the word match stays
            List<Vault.Note> done = hits;
            runOnUiThread(() -> { if (!isDestroyed() && q.equals(query) && done != null && !done.isEmpty()) { semanticHits = done; draw(false); } });
        }).start();
    }

    private void orphans() {
        title = "Notes with no links";
        b.add(new Body("Nothing links to these and they link to nothing. Each is a place to connect an idea.", true, false));
        list(snap.graph.orphans(), true);
    }

    private void folder(String path) {
        title = path.substring(path.lastIndexOf('/') + 1);
        if (path.contains("/")) b.add(new Body(path, true, false));
        Map<String, Integer> folders = new TreeMap<>();
        List<Vault.Note> notes = new ArrayList<>();
        String prefix = path + "/";
        for (Vault.Note n : snap.graph.notes) {
            if (!n.path.startsWith(prefix)) continue;
            String rest = n.path.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash >= 0) { String f = rest.substring(0, slash); Integer o = folders.get(f); folders.put(f, o == null ? 1 : o + 1); } else notes.add(n);
        }
        List<Nav> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> f : folders.entrySet()) rows.add(folderRow(f.getKey(), prefix + f.getKey(), f.getValue()));
        if (!rows.isEmpty()) b.add(new Group(rows));
        if (Layout.inSources(path + "/") || path.startsWith("Sources")) notes.sort((x, y) -> { int c = dayOf(y).compareTo(dayOf(x)); return c != 0 ? c : x.title.compareToIgnoreCase(y.title); });
        else notes.sort((x, y) -> x.title.compareToIgnoreCase(y.title));
        list(notes, false);
    }

    private Nav folderRow(String name, String path, int count) { return new Nav(name, "", String.valueOf(count), false, () -> go("folder", path)); }

    /** Note rows, the first `shown` of them, and a button for the rest. */
    private void list(List<Vault.Note> notes, boolean withPath) {
        int limit = Math.min(notes.size(), shown);
        List<Nav> rows = new ArrayList<>();
        for (int i = 0; i < limit; i++) rows.add(noteRow(notes.get(i), withPath));
        if (!rows.isEmpty()) b.add(new Group(rows));
        if (notes.size() > limit) b.add(new Buttons(java.util.Collections.singletonList(new Act("Show more (" + (notes.size() - limit) + ")", () -> { shown += PAGE; draw(false); }))));
    }

    private Nav noteRow(Vault.Note n, boolean withPath) {
        StringBuilder meta = new StringBuilder();
        if (withPath) meta.append(n.folder());
        if (!n.type.isEmpty()) meta.append(meta.length() == 0 ? "" : " · ").append(n.type);
        meta.append(meta.length() == 0 ? "" : " · ").append(when(day(n)));
        if (!n.description.isEmpty() && !n.description.equalsIgnoreCase(n.title)) meta.append("\n").append(n.description.length() > 140 ? n.description.substring(0, 140).trim() + "…" : n.description);
        return new Nav(n.title, meta.toString(), "", false, () -> go("note", n.path));
    }

    // ---- one note ----

    private void note(String path) {
        Vault.Note n = snap.graph.byPath(path);
        title = "Note";
        if (n == null) { b.add(new Body("This note is no longer in the vault.", true, false)); return; }
        b.add(new Title(n.title));
        StringBuilder meta = new StringBuilder(Vault.stripMd(n.path));
        if (!n.type.isEmpty()) meta.append(" · ").append(n.type);
        meta.append(" · updated ").append(when(day(n)).toLowerCase(Locale.ROOT));
        b.add(new Body(meta.toString(), true, false));
        b.add(new Link("Open in Obsidian  ↗", () -> openInObsidian(n)));
        if (!n.catalog) {
            b.add(new Primary("Edit Markdown", () -> startActivity(new Intent(this, MainActivity.class).putExtra("noteAction", "edit").putExtra("notePath", n.path))));
            b.add(new Primary("Cards", () -> startActivity(new Intent(this, MainActivity.class).putExtra("noteAction", "cards").putExtra("notePath", n.path))));
        }
        if (!n.topics.isEmpty()) {
            List<Act> chips = new ArrayList<>();
            for (String t : n.topics) { Vault.Note to = snap.graph.resolve(t); chips.add(new Act(t, () -> { if (to != null) go("note", to.path); })); }
            b.add(new Chips(chips));
        }
        int in = snap.graph.in(n).size(), out = snap.graph.out(n).size();
        String[] ids = {"read", "links", "similar"};
        int sel = tab.equals("links") ? 1 : tab.equals("similar") ? 2 : 0;
        b.add(new Tabs(java.util.Arrays.asList("Read", "Links " + (in + out), "Similar"), sel, i -> { tab = ids[i]; draw(false); }));
        if (tab.equals("links")) links(n);
        else if (tab.equals("similar")) similar(n);
        else read(n);
    }

    private void read(Vault.Note n) {
        if (n.path.equals(noteTextPath) && noteText != null) { b.add(new Rich(() -> rendered(n))); return; }
        b.add(new Body("Opening", true, false));
        VaultStore.Snap s = snap;
        new Thread(() -> {
            String text;
            try { text = VaultStore.text(this, s.uri(n)); } catch (Exception e) { text = null; }
            String done = text;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                noteTextPath = n.path; noteText = done;
                String[] at = stack.get(stack.size() - 1);
                if (at[0].equals("note") && at[1].equals(n.path) && tab.equals("read")) {
                    if (done == null) { noteTextPath = ""; draw(false); b.add(new Body("Cannot read this note. Check folder access in Settings.", true, false)); page.publish(title, b); }
                    else draw(false);
                }
            });
        }).start();
    }

    /** The note's Markdown, rendered by Md into a selectable TextView with tappable links. */
    private View rendered(Vault.Note n) {
        TextView t = ui.text("", 15, Typeface.DEFAULT, ui.ink);
        t.setLineSpacing(0, 1.35f);
        t.setText(Md.render(ui, noteText, new Md.Open() {
            @Override public void open(String raw) { openLink(raw); }
            @Override public boolean exists(String raw) { return snap.graph.resolve(Vault.target(raw)) != null; }
        }));
        t.setMovementMethod(LinkMovementMethod.getInstance());
        t.setHighlightColor(0x33000000 | (ui.accent & 0xFFFFFF));
        return t;
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
        b.add(new Heading("Links to · " + out.size()));
        if (out.isEmpty()) b.add(new Body("This note does not link to another note.", true, false));
        list(out, true);
        b.add(new Heading("Linked from · " + in.size()));
        if (in.isEmpty()) b.add(new Body("No note links here yet.", true, false));
        list(in, true);
        List<String> missing = new ArrayList<>();
        for (String l : n.links) if (g.resolve(l) == null) missing.add(Vault.label(l));
        if (!missing.isEmpty()) {
            b.add(new Heading("No page yet · " + missing.size()));
            b.add(new Body(android.text.TextUtils.join("  ·  ", missing.subList(0, Math.min(40, missing.size()))), true, false));
        }
    }

    private void similar(Vault.Note n) {
        if (Model.key(this).isEmpty()) { b.add(new Body("Similar notes need an OpenRouter key. Add one in Settings.", true, false)); return; }
        if (n.path.equals(similarFor) && similar != null) { showSimilar(n); return; }
        b.add(new Body("Finding similar notes", true, false));
        VaultStore.Snap s = snap;
        new Thread(() -> {
            Limits.interactive(true);
            List<Vault.Note> found = VaultStore.similar(this, s, n, 10);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                similarFor = n.path; similar = found;
                String[] at = stack.get(stack.size() - 1);
                if (at[0].equals("note") && at[1].equals(n.path) && tab.equals("similar")) draw(false);
            });
        }).start();
    }

    private void showSimilar(Vault.Note n) {
        if (similar.isEmpty()) {
            int[] idx = VaultStore.indexed(this, snap);
            b.add(new Body(idx[0] < idx[1] ? "Still indexing: " + idx[0] + " of " + idx[1] + " notes are searchable. Open the app again in a few minutes." : "Nothing in your vault is close enough to this note.", true, false));
            return;
        }
        list(similar, true);
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
