package me.venkatesh.saveobsidian;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The vault folder the user picked, read through the Storage Access Framework. Only Knowledge (which holds Learnings) and Sources are
 * read: journals and health notes in the same vault never leave the phone. Read-only; a cache keeps reopening instant.
 */
final class VaultStore {
    static final String[] ROOTS = {"Knowledge", "Sources"}; // Knowledge holds Learnings too
    private static final String[] COLUMNS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE};
    private static final long MAX_NOTE = 2L * 1024 * 1024;
    static volatile Snap last;
    private static final int CACHE = 4; // bump when Vault.parse changes what it extracts, so old caches are rebuilt

    /** Notes, their links, and the provider ids needed to open each one. */
    static final class Snap {
        final Vault.Graph graph;
        final Map<String, String> ids;
        final Uri tree;
        Snap(Vault.Graph graph, Map<String, String> ids, Uri tree) { this.graph = graph; this.ids = ids; this.tree = tree; }
        Uri uri(Vault.Note n) { return DocumentsContract.buildDocumentUriUsingTree(tree, ids.get(n.path)); }
    }

    static String tree(Context c) { return c.getSharedPreferences("capture", 0).getString("vault", ""); }
    static boolean ready(Context c) { return !tree(c).isEmpty(); }

    /** "Documents/Obsidian/Tiny experiments" for Settings. */
    static String label(Context c) {
        try { String id = DocumentsContract.getTreeDocumentId(Uri.parse(tree(c))); return id.substring(id.indexOf(':') + 1); }
        catch (RuntimeException unreadable) { return "Chosen folder"; }
    }

    private static File cacheFile(Context c) { return new File(c.getFilesDir(), "vault.json"); }

    /** The previous scan, straight from disk with no provider calls. Null when there is none. */
    static Snap cached(Context c) {
        if (last != null) return last;
        Snap snap = build(c, readCache(c).values());
        if (snap != null) last = snap;
        return snap;
    }

    /** Walks the vault, re-reading only files whose size or modified time changed. Blocking. */
    static Snap scan(Context c) throws Exception {
        long started = System.currentTimeMillis();
        Uri tree = Uri.parse(tree(c));
        Map<String, Cached> old = readCache(c), fresh = new HashMap<>();
        long cacheMs = System.currentTimeMillis() - started;
        int[] counts = new int[2]; // re-read, kept
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        List<Row> tops = children(c, tree, rootId);
        if (tops == null) throw new java.io.IOException("The vault folder cannot be read right now.");
        for (Row top : tops) {
            boolean wanted = false;
            for (String r : ROOTS) wanted |= r.equals(top.name);
            if (top.dir && wanted) walk(c, tree, top, top.name, old, fresh, counts);
        }
        if (fresh.isEmpty()) throw new java.io.IOException("No Knowledge or Sources folder in this folder. Choose your vault folder.");
        if (counts[0] > 0 || fresh.size() != old.size()) writeCache(c, fresh.values());
        Snap snap = build(c, fresh.values());
        last = snap;
        Log.i("sparkbox", "vault scan " + fresh.size() + " notes, read " + counts[0] + ", cached " + counts[1] + ", " + (System.currentTimeMillis() - started) + " ms");
        return snap;
    }

    private static final class Row { String id, name; boolean dir; long modified, size; }

    private static List<Row> children(Context c, Uri tree, String parentId) {
        List<Row> rows = new ArrayList<>();
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor q = c.getContentResolver().query(uri, COLUMNS, null, null, null)) {
            while (q != null && q.moveToNext()) {
                Row r = new Row();
                r.id = q.getString(0); r.name = q.getString(1);
                r.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(q.getString(2));
                r.modified = q.isNull(3) ? 0 : q.getLong(3); r.size = q.isNull(4) ? -1 : q.getLong(4);
                if (r.name != null && !r.name.startsWith(".")) rows.add(r); // .obsidian, .git, .env stay unseen
            }
        } catch (RuntimeException unreadable) { return null; } // null = could not list, which is not the same as empty
        return rows;
    }

    private static void walk(Context c, Uri tree, Row dir, String path, Map<String, Cached> old, Map<String, Cached> fresh, int[] counts) {
        List<Row> rows = children(c, tree, dir.id);
        if (rows == null) { // keep what was known about this folder rather than dropping its notes
            for (Map.Entry<String, Cached> e : old.entrySet()) if (e.getKey().startsWith(path + "/")) { fresh.put(e.getKey(), e.getValue()); counts[1]++; }
            return;
        }
        for (Row r : rows) {
            String p = path + "/" + r.name;
            if (r.dir) { walk(c, tree, r, p, old, fresh, counts); continue; }
            if (!r.name.endsWith(".md") || Layout.conflict(r.name) || p.equals(Layout.NEW + "/Inbox.md") || p.equals(Layout.OLD + "/Inbox.md") || r.size > MAX_NOTE) continue;
            Cached known = old.get(p);
            if (known != null && known.size == r.size && known.note.mtime == r.modified && known.id.equals(r.id)) { fresh.put(p, known); counts[1]++; continue; }
            try {
                String text = text(c, DocumentsContract.buildDocumentUriUsingTree(tree, r.id));
                fresh.put(p, new Cached(r.id, r.size, Vault.parse(p, r.modified, text)));
                counts[0]++;
            } catch (Exception unreadable) { if (known != null) fresh.put(p, known); } // keep the old copy rather than lose the note
        }
    }

    /** Full text of one note. */
    static String text(Context c, Uri uri) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("Note unavailable");
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); // a provider may not report size, so the cap is enforced while reading
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_NOTE) throw new java.io.IOException("Note over 2 MB");
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static Snap build(Context c, java.util.Collection<Cached> all) {
        if (!ready(c) || all.isEmpty()) return null;
        List<Vault.Note> notes = new ArrayList<>();
        Map<String, String> ids = new HashMap<>();
        for (Cached k : all) { notes.add(k.note); ids.put(k.note.path, k.id); }
        Layout.detect(ids.keySet());
        return new Snap(Vault.build(notes), ids, Uri.parse(tree(c)));
    }

    private static final class Cached {
        final String id; final long size; final Vault.Note note;
        Cached(String id, long size, Vault.Note note) { this.id = id; this.size = size; this.note = note; }
    }

    private static Map<String, Cached> readCache(Context c) {
        Map<String, Cached> map = new HashMap<>();
        try {
            JSONObject root = new JSONObject(new String(CaptureService.readAll(new AtomicFile(cacheFile(c)).openRead()), StandardCharsets.UTF_8));
            if (!root.optString("tree").equals(tree(c)) || root.optInt("v") != CACHE) return map; // another folder or an older parser: start over
            JSONArray notes = root.getJSONArray("notes");
            for (int i = 0; i < notes.length(); i++) {
                JSONObject o = notes.getJSONObject(i);
                Vault.Note n = new Vault.Note(o.getString("p"), o.getLong("m"), o.getString("t"), o.getString("y"), o.getString("d"), o.getString("u"),
                    o.getString("g"), strings(o.getJSONArray("o")), strings(o.getJSONArray("l")), meta(o.optJSONObject("f")), o.optString("w"), o.optInt("n"), o.optString("k"));
                map.put(n.path, new Cached(o.getString("i"), o.getLong("z"), n));
            }
        } catch (Exception none) { map.clear(); } // missing or damaged: a rescan rebuilds it
        return map;
    }

    private static Map<String, String> meta(JSONObject o) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (o != null) for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); out.put(k, o.optString(k)); }
        return out;
    }

    private static List<String> strings(JSONArray a) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    private static void writeCache(Context c, java.util.Collection<Cached> all) {
        AtomicFile file = new AtomicFile(cacheFile(c));
        FileOutputStream out = null;
        try {
            JSONArray notes = new JSONArray();
            for (Cached k : all) {
                Vault.Note n = k.note;
                notes.put(new JSONObject().put("p", n.path).put("m", n.mtime).put("t", n.title).put("y", n.type).put("d", n.description).put("u", n.updated)
                    .put("g", n.digest).put("o", new JSONArray(n.topics)).put("l", new JSONArray(n.links)).put("f", new JSONObject(n.meta)).put("w", n.why).put("n", n.words).put("k", n.cardsText).put("i", k.id).put("z", k.size));
            }
            out = file.startWrite();
            out.write(new JSONObject().put("v", CACHE).put("tree", tree(c)).put("notes", notes).toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (Exception e) { if (out != null) file.failWrite(out); }
    }

    // ---- search ----

    private static File indexFile(Context c) { return new File(c.getFilesDir(), "vault.bin"); }

    private static List<String> digests(Snap snap) {
        List<String> all = new ArrayList<>();
        for (Vault.Note n : snap.graph.notes) if (!n.catalog) all.add(n.digest);
        return all;
    }

    /** Up to 640 new vectors per run, saved after every batch so a stopped job keeps its progress. */
    static void embed(Context c, String key) throws Exception {
        if (!ready(c)) return;
        Snap snap;
        try { snap = scan(c); } catch (Exception unreadable) { return; } // no folder access right now: nothing to index
        Index index = Index.load(indexFile(c));
        List<String> all = digests(snap);
        index.retainTexts(all);
        List<String> todo = index.missingTexts(all);
        for (int i = 0; i < Math.min(todo.size(), 640) && !Tagger.halt(); i += 32) {
            List<String> batch = new ArrayList<>(todo.subList(i, Math.min(i + 32, todo.size())));
            List<float[]> vectors = Model.embed(key, batch);
            for (int j = 0; j < batch.size(); j++) index.put(batch.get(j), vectors.get(j));
            index.save(indexFile(c));
        }
        if (todo.isEmpty()) index.save(indexFile(c)); // persists the pruning
    }

    /** How many notes have a vector, out of how many can have one. */
    static int[] indexed(Context c, Snap snap) {
        Index index = Index.load(indexFile(c));
        int have = 0, total = 0;
        for (Vault.Note n : snap.graph.notes) if (!n.catalog) { total++; if (index.vector(n.digest) != null) have++; }
        return new int[]{have, total};
    }

    /** Word match, rare words and title words first. See Vault.search. */
    static List<Vault.Note> keyword(Snap snap, String query, int limit) { return Vault.search(snap.graph.notes, query, limit); }

    /** Unit vector of the query, or null without a key. Throws when the network or provider fails. */
    static float[] vector(Context c, String query) throws Exception {
        String key = Model.key(c);
        return key.isEmpty() ? null : Index.normalized(Model.embed(key, java.util.Collections.singletonList(query)).get(0));
    }

    /** Closest in meaning to the query. Needs a key and network for the query vector; throws when it cannot get one. */
    static List<Vault.Note> semantic(Context c, Snap snap, String query, int limit) throws Exception {
        float[] q = vector(c, query);
        return q == null ? new ArrayList<Vault.Note>() : nearest(c, snap, q, limit, Index.MIN_QUERY, null);
    }

    /** Notes closest in meaning to this one, offline from stored vectors. */
    static List<Vault.Note> similar(Context c, Snap snap, Vault.Note note, int limit) {
        float[] v = Index.load(indexFile(c)).vector(note.digest);
        return v == null ? new ArrayList<Vault.Note>() : nearest(c, snap, v, limit, Index.MIN_RELATED, note);
    }

    static List<Vault.Note> nearest(Context c, Snap snap, float[] q, int limit, float min, Vault.Note self) {
        Index index = Index.load(indexFile(c));
        final Map<Vault.Note, Float> score = new HashMap<>();
        for (Vault.Note n : snap.graph.notes) {
            float[] v = n == self || n.catalog ? null : index.vector(n.digest);
            if (v == null) continue;
            float dot = Index.dot(v, q);
            if (dot >= min) score.put(n, dot);
        }
        List<Vault.Note> ranked = new ArrayList<>(score.keySet());
        ranked.sort((a, b) -> Float.compare(score.get(b), score.get(a)));
        return ranked.subList(0, Math.min(limit, ranked.size()));
    }
}
