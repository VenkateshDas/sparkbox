package me.venkatesh.saveobsidian;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Every review card in the vault, held in memory, with the owner's changes applied at once and written to the notes in the background. Each change
 * is a small instruction kept in a private queue until its note has been rewritten from freshly read text, so a crash, a slow save or an edit made
 * on the Mac in the meantime loses nothing.
 */
final class CardStore {
    private static final Object LOCK = new Object();
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static List<Cards.Card> cards = new ArrayList<>();
    private static Set<String> kept = new HashSet<>();
    private static List<String> newestFirst = new ArrayList<>();
    private static Vault.Graph loaded;

    private CardStore() { }

    private static File queueFile(Context c) { return new File(c.getFilesDir(), "pending-cards.jsonl"); }

    // ---- reading ----

    /** All cards, rebuilt from the vault scan when it changed, with changes still waiting to be saved applied on top. */
    static List<Cards.Card> all(Context c) {
        VaultStore.Snap snap = VaultStore.cached(c);
        synchronized (LOCK) {
            if (snap != null && snap.graph != loaded) rebuild(c, snap);
            return new ArrayList<>(cards);
        }
    }

    static Set<String> kept() { synchronized (LOCK) { return new HashSet<>(kept); } }
    static List<String> newestFirst() { synchronized (LOCK) { return new ArrayList<>(newestFirst); } }

    private static void rebuild(Context c, VaultStore.Snap snap) {
        List<Cards.Card> next = new ArrayList<>();
        Set<String> keep = new HashSet<>();
        List<Vault.Note> withCards = new ArrayList<>();
        for (Vault.Note n : snap.graph.notes) {
            if (n.cardsText.isEmpty()) continue;
            withCards.add(n);
            if (n.meta("kept").equalsIgnoreCase("true")) keep.add(n.path);
            next.addAll(Cards.parse(n.path, n.cardsText));
        }
        withCards.sort((a, b) -> {
            boolean[] t = new boolean[1];
            java.time.LocalDateTime x = Feed.when(a.meta("captured"), t), y = Feed.when(b.meta("captured"), t);
            int cmp = x == null ? (y == null ? 0 : 1) : y == null ? -1 : y.compareTo(x); // newest first, notes without a date last: a consistent order, or the sort throws
            return cmp != 0 ? cmp : Long.compare(b.mtime, a.mtime);
        });
        List<String> order = new ArrayList<>();
        for (Vault.Note n : withCards) order.add(n.path);
        cards = next;
        kept = keep;
        newestFirst = order;
        loaded = snap.graph;
        for (JSONObject op : pending(c)) applyToMemory(op); // changes not yet saved still show
    }

    // ---- changes ----

    /** Applies the grade: new state and one more log entry. */
    static void grade(Context c, Cards.Card card, String grade, int seconds, String conf) {
        long now = System.currentTimeMillis();
        Cards.State state = Sched.next(card.state, grade, now);
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("State", Cards.stateText(state));
        kv.put("Log", Cards.logWith(card.log, now, grade, seconds, conf));
        change(c, op("keys", card.path, "id", card.id, "kv", json(kv)));
    }

    static void setTake(Context c, Cards.Card card, String take) { keys(c, card, "Take", take.trim()); }
    static void flag(Context c, Cards.Card card, boolean on) { keys(c, card, "Flag", on ? "yes" : null); }
    static void park(Context c, Cards.Card card, long until) { keys(c, card, "Parked", until > 0 ? Cards.time(until) : null); }

    static void edit(Context c, Cards.Card card, String q, String a, String take) {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("Q", q); kv.put("A", a); kv.put("Take", take.trim().isEmpty() ? null : take.trim());
        change(c, op("keys", card.path, "id", card.id, "kv", json(kv)));
    }

    /** The owner's own card, on a note or (path null) in My cards. */
    static Cards.Card addOwn(Context c, String path, String q, String a, String take) {
        Cards.Card card = new Cards.Card();
        card.path = path == null ? Layout.sources() + "/My cards.md" : path;
        card.q = q.trim(); card.a = a.trim(); card.take = take.trim(); card.origin = "mine";
        card.id = Cards.id("mine|" + System.currentTimeMillis(), card.q);
        change(c, op("add", card.path, "block", Cards.format(card), "fresh", path == null));
        return card;
    }

    static void remove(Context c, Cards.Card card) { change(c, op("remove", card.path, "id", card.id)); }

    /** Marks a note's cards to come first (Keep). */
    static void keep(Context c, String path, boolean on) { change(c, op("key", path, "key", "kept", "value", on ? "true" : JSONObject.NULL)); }

    private static void keys(Context c, Cards.Card card, String key, String value) {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put(key, value == null || value.isEmpty() ? null : value);
        change(c, op("keys", card.path, "id", card.id, "kv", json(kv)));
    }

    private static JSONObject op(String type, String path, Object... pairs) {
        try {
            JSONObject o = new JSONObject().put("t", type).put("path", path);
            for (int i = 0; i + 1 < pairs.length; i += 2) o.put((String) pairs[i], pairs[i + 1]);
            return o;
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static JSONObject json(Map<String, String> kv) {
        JSONObject o = new JSONObject();
        try { for (Map.Entry<String, String> e : kv.entrySet()) o.put(e.getKey(), e.getValue() == null ? JSONObject.NULL : e.getValue()); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return o;
    }

    private static void change(Context c, JSONObject op) {
        synchronized (LOCK) {
            applyToMemory(op);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(queueFile(c), true)) {
                out.write((op.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (Exception e) { android.util.Log.w("sparkbox", "card change not queued: " + e); }
        }
        flushSoon(c);
    }

    private static void applyToMemory(JSONObject op) {
        try {
            String path = op.getString("path");
            switch (op.getString("t")) {
                case "keys": {
                    JSONObject kv = op.getJSONObject("kv");
                    for (Cards.Card k : cards) if (k.path.equals(path) && k.id.equals(op.getString("id"))) {
                        for (java.util.Iterator<String> it = kv.keys(); it.hasNext(); ) { String key = it.next(); fieldOf(k, key, kv.isNull(key) ? null : kv.getString(key)); }
                    }
                    break;
                }
                case "add": {
                    List<Cards.Card> one = Cards.parse(path, "## Cards\n\n" + op.getString("block"));
                    for (Cards.Card k : one) { boolean have = false; for (Cards.Card x : cards) have |= x.path.equals(path) && x.id.equals(k.id); if (!have) cards.add(k); }
                    break;
                }
                case "remove": {
                    for (int i = cards.size() - 1; i >= 0; i--) if (cards.get(i).path.equals(path) && cards.get(i).id.equals(op.getString("id"))) cards.remove(i);
                    break;
                }
                case "key": {
                    if (op.getString("key").equals("kept")) { if (op.isNull("value")) kept.remove(path); else kept.add(path); }
                    break;
                }
                default:
            }
        } catch (Exception bad) { android.util.Log.w("sparkbox", "card op skipped: " + bad); }
    }

    private static void fieldOf(Cards.Card k, String key, String v) {
        String s = v == null ? "" : v;
        switch (key) {
            case "State": k.state = v == null ? null : Cards.parseState(v); break;
            case "Log": k.log = s; break;
            case "Take": k.take = s; break;
            case "Q": k.q = s; break;
            case "A": k.a = s; break;
            case "Flag": k.flag = s.equalsIgnoreCase("yes"); break;
            case "Parked": k.parked = v == null ? 0 : Cards.parseTime(v); break;
            default:
        }
    }

    // ---- saving ----

    private static List<JSONObject> pending(Context c) {
        List<JSONObject> out = new ArrayList<>();
        File f = queueFile(c);
        if (!f.exists()) return out;
        try {
            for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) if (!line.trim().isEmpty()) out.add(new JSONObject(line));
        } catch (Exception damaged) { android.util.Log.w("sparkbox", "card queue unreadable: " + damaged); }
        return out;
    }

    private static volatile Runnable later;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Writes the queue two seconds after the last change, so a session of grades becomes a few writes. */
    static void flushSoon(Context c) {
        final Context app = c.getApplicationContext();
        if (later != null) MAIN.removeCallbacks(later);
        later = () -> flushNow(app, null);
        MAIN.postDelayed(later, 2000);
    }

    /** Writes everything waiting now (leaving a screen, a background run). done runs on the main thread when finished. */
    static void flushNow(Context c, Runnable done) {
        final Context app = c.getApplicationContext();
        WRITER.execute(() -> {
            try { flush(app); } catch (Exception e) { android.util.Log.w("sparkbox", "card flush failed: " + e); }
            if (done != null) MAIN.post(done);
        });
    }

    private static void flush(Context c) throws Exception {
        if (!VaultWrite.writable(c)) return;
        List<JSONObject> ops;
        synchronized (LOCK) { ops = pending(c); }
        if (ops.isEmpty()) return;
        Map<String, List<JSONObject>> byPath = new LinkedHashMap<>();
        for (JSONObject op : ops) { List<JSONObject> l = byPath.get(op.getString("path")); if (l == null) byPath.put(op.getString("path"), l = new ArrayList<>()); l.add(op); }
        Set<JSONObject> done = new HashSet<>();
        for (Map.Entry<String, List<JSONObject>> e : byPath.entrySet()) {
            String path = e.getKey();
            boolean saved = false;
            VaultLock.L.lock(); // read, transform and write as one step against other in-app writers
            try {
            for (int attempt = 0; attempt < 3 && !saved; attempt++) {
                String before = VaultWrite.read(c, path);
                if (before == null) {
                    boolean create = false;
                    for (JSONObject op : e.getValue()) create |= op.optBoolean("fresh");
                    if (!create) { saved = true; break; } // the note is gone: its cards go with it
                    before = "---\ntype: source\ndescription: My own cards\nvia: Sparkbox\n---\n\n# My cards\n";
                    String after = transform(before, e.getValue());
                    try { VaultWrite.write(c, path, null, after); saved = true; } catch (java.io.IOException exists) { /* appeared meanwhile: retry from its text */ }
                    continue;
                }
                String after = transform(before, e.getValue());
                if (after.equals(before)) { saved = true; break; }
                try {
                    boolean frontmatter = false;
                    for (JSONObject op : e.getValue()) frontmatter |= op.getString("t").equals("key");
                    if (frontmatter) VaultWrite.write(c, path, before, after); else VaultWrite.writeNoBackup(c, path, before, after);
                    saved = true;
                } catch (VaultWrite.Stale changed) { /* the file changed under us: read it again and re-apply */ }
            }
            } finally { VaultLock.L.unlock(); }
            if (saved) done.addAll(e.getValue());
        }
        synchronized (LOCK) {
            List<JSONObject> now = pending(c);
            StringBuilder keep = new StringBuilder();
            for (JSONObject op : now) { boolean gone = false; for (JSONObject d : done) gone |= d.toString().equals(op.toString()); if (!gone) keep.append(op).append('\n'); }
            Files.write(queueFile(c).toPath(), keep.toString().getBytes(StandardCharsets.UTF_8));
        }
        VaultStore.scan(c); // re-reads only the changed notes
    }

    private static String transform(String text, List<JSONObject> ops) throws Exception {
        String out = text;
        for (JSONObject op : ops) {
            switch (op.getString("t")) {
                case "keys": {
                    Map<String, String> kv = new LinkedHashMap<>();
                    JSONObject o = op.getJSONObject("kv");
                    for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); kv.put(k, o.isNull(k) ? null : o.getString(k)); }
                    out = Cards.setKeys(out, op.getString("id"), kv);
                    break;
                }
                case "add": out = Cards.add(out, Cards.parse(op.getString("path"), "## Cards\n\n" + op.getString("block"))); break;
                case "remove": out = Cards.remove(out, op.getString("id")); break;
                case "key": out = NoteEdit.setKey(out, op.getString("key"), op.isNull("value") ? null : op.getString("value")); break;
                default:
            }
        }
        return out;
    }
}
