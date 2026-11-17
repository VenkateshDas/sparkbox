package me.venkatesh.saveobsidian;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Embedding sidecar: unit vectors keyed by a hash of the text that was embedded. Pure Java so the JVM checks cover it. */
public final class Index {
    public static final String MODEL = "openai/text-embedding-3-large";
    public static final int DIMS = 1024;
    /** Measured on a real inbox: unrelated queries score under 0.2, relevant ones 0.3 to 0.6. */
    public static final float MIN_QUERY = 0.28f, MIN_RELATED = 0.35f;
    private final Map<Long, float[]> vectors = new HashMap<>();

    /** What gets embedded: the capture as described, including your own words. */
    public static String text(InboxFile.Entry e) {
        String all = (e.title + ". " + e.tldr + " Tags: " + e.tags + " " + e.why + " " + e.ideas + " " + (e.text ? e.body : "")).trim();
        return all.length() > 2000 ? all.substring(0, 2000) : all;
    }

    static long key(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            long key = 0;
            for (int i = 0; i < 8; i++) key = key << 8 | (hash[i] & 0xff);
            return key;
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static float[] unit(float[] v) {
        double sum = 0;
        for (float x : v) sum += (double) x * x;
        double norm = Math.sqrt(sum);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = norm == 0 ? 0 : (float) (v[i] / norm);
        return out;
    }

    public void put(String text, float[] vector) { vectors.put(key(text), unit(vector)); }

    /** Distinct texts that still need a vector. */
    public List<String> missing(List<InboxFile.Entry> entries) {
        Set<Long> seen = new HashSet<>();
        List<String> todo = new ArrayList<>();
        for (InboxFile.Entry e : entries) {
            String text = text(e);
            long key = key(text);
            if (!vectors.containsKey(key) && seen.add(key)) todo.add(text);
        }
        return todo;
    }

    /** Drops vectors of entries that were edited or deleted, so the file does not grow forever. */
    public void retain(List<InboxFile.Entry> entries) {
        Set<Long> live = new HashSet<>();
        for (InboxFile.Entry e : entries) live.add(key(text(e)));
        vectors.keySet().retainAll(live);
    }

    /** Best matches first, only those scoring at least min. Entries without a vector are skipped. */
    public List<InboxFile.Entry> rank(float[] query, List<InboxFile.Entry> entries, int limit, float min, long exclude) {
        float[] q = unit(query);
        Map<InboxFile.Entry, Float> score = new HashMap<>();
        for (InboxFile.Entry e : entries) {
            long key = key(text(e));
            float[] v = vectors.get(key);
            if (v == null || key == exclude) continue;
            float dot = 0;
            for (int i = 0; i < v.length; i++) dot += v[i] * q[i];
            if (dot >= min) score.put(e, dot);
        }
        List<InboxFile.Entry> ranked = new ArrayList<>(score.keySet());
        ranked.sort(Comparator.comparing((InboxFile.Entry e) -> score.get(e)).reversed());
        return ranked.subList(0, Math.min(limit, ranked.size()));
    }

    /** Entries closest to this one, found offline from stored vectors. */
    public List<InboxFile.Entry> related(InboxFile.Entry entry, List<InboxFile.Entry> entries, int limit) {
        long self = key(text(entry));
        float[] v = vectors.get(self);
        return v == null ? new ArrayList<>() : rank(v, entries, limit, MIN_RELATED, self);
    }

    /** Stored unit vector of this exact text, or null. */
    public float[] vector(String text) { return vectors.get(key(text)); }

    public static float dot(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    /** Unit-length copy, for a query vector that did not come from put(). */
    public static float[] normalized(float[] v) { return unit(v); }

    /** Distinct texts that still need a vector (for text that is not an inbox entry, such as vault notes). */
    public List<String> missingTexts(java.util.Collection<String> texts) {
        Set<Long> seen = new HashSet<>();
        List<String> todo = new ArrayList<>();
        for (String t : texts) { long k = key(t); if (!vectors.containsKey(k) && seen.add(k)) todo.add(t); }
        return todo;
    }

    /** Keeps only vectors of these texts. */
    public void retainTexts(java.util.Collection<String> texts) {
        Set<Long> live = new HashSet<>();
        for (String t : texts) live.add(key(t));
        vectors.keySet().retainAll(live);
    }

    public int size() { return vectors.size(); }

    /** Write-then-rename so a crash never leaves a half-written index. */
    public void save(File file) throws IOException {
        File temp = new File(file.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(temp)))) {
            out.writeUTF(MODEL);
            out.writeInt(DIMS);
            out.writeInt(vectors.size());
            for (Map.Entry<Long, float[]> e : vectors.entrySet()) {
                out.writeLong(e.getKey());
                for (float x : e.getValue()) out.writeFloat(x);
            }
        }
        if (!temp.renameTo(file)) throw new IOException("Cannot save the search index");
        synchronized (Index.class) { SNAPSHOTS.remove(file.getAbsolutePath()); }
    }

    private static final Map<String, Snapshot> SNAPSHOTS = new java.util.LinkedHashMap<>(4, 0.75f, true);
    private static final class Snapshot {
        final long modified, bytes;
        final Index index;
        Snapshot(File file, Index index) { modified = file.lastModified(); bytes = file.length(); this.index = index; }
    }

    /** Read-only resident snapshot. Writers use load(); successful saves invalidate this snapshot. */
    public static synchronized Index cached(File file) {
        String path = file.getAbsolutePath();
        Snapshot found = SNAPSHOTS.get(path);
        if (found != null && found.modified == file.lastModified() && found.bytes == file.length()) return found.index;
        Index index = load(file);
        SNAPSHOTS.put(path, new Snapshot(file, index));
        if (SNAPSHOTS.size() > 4) SNAPSHOTS.remove(SNAPSHOTS.keySet().iterator().next());
        return index;
    }

    /** Missing, corrupt, or built by another model: start empty and re-embed. Vectors are cheap to rebuild. */
    public static Index load(File file) {
        Index index = new Index();
        if (!file.exists()) return index;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(file)))) {
            if (!in.readUTF().equals(MODEL) || in.readInt() != DIMS) return index;
            Index loaded = new Index();
            for (int n = in.readInt(); n > 0; n--) {
                long key = in.readLong();
                float[] v = new float[DIMS];
                for (int i = 0; i < DIMS; i++) v[i] = in.readFloat();
                loaded.vectors.put(key, v);
            }
            return loaded;
        } catch (IOException damaged) { return index; }
    }
}
