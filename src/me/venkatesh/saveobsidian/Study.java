package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What to study in Spark: a scope (everything, due, new, weak spots, recent, one topic, one note) and the topics with their card counts. Pure Java so the JVM checks cover it. */
public final class Study {
    public static final String ALL = "all", DUE = "due", NEW = "new", WEAK = "weak", RECENT = "recent", TOPIC = "topic", NOTE = "note", NO_TOPIC = "_none";
    /** A card with an interval this long counts as learned for the mastery bar. */
    static final int MASTERED_DAYS = 21;

    private Study() { }

    public static final class Scope {
        public final String kind, key, label;
        /** The note paths a topic, note or recent scope covers; empty for the kinds that look at the cards themselves. */
        public final Set<String> paths;
        public Scope(String kind, String key, String label, Set<String> paths) { this.kind = kind; this.key = key; this.label = label; this.paths = paths; }
        public static Scope all() { return new Scope(ALL, "", "Everything", new LinkedHashSet<String>()); }
        public static Scope note(String path, String title) { Set<String> p = new LinkedHashSet<>(); p.add(path); return new Scope(NOTE, path, title, p); }
    }

    public static final class Topic {
        public final String path, title;
        public String lane = "";
        public int cards, due, fresh, mastered;
        public final Set<String> notes = new LinkedHashSet<>();
        Topic(String path, String title) { this.path = path; this.title = title; }
        public float mastery() { return cards == 0 ? 0f : mastered / (float) cards; }
    }

    /** A card the owner struggles with: it lapsed, or its latest grade was again or hard. */
    public static boolean weak(Cards.Card c) {
        if (c.state == null) return false;
        if (c.state.lapses > 0) return true;
        String[] w = c.log.split(";")[0].trim().split("\\s+");
        return w.length > 1 && (w[1].equals(Sched.AGAIN) || w[1].equals(Sched.HARD));
    }

    /** The cards a scope covers, in their given order. Flagged cards never count. */
    public static List<Cards.Card> filter(List<Cards.Card> cards, Scope s, long now) {
        List<Cards.Card> out = new ArrayList<>();
        for (Cards.Card c : cards) {
            if (c.flag || c.parked > now) continue; // the same cards the review queue would offer
            boolean in;
            switch (s.kind) {
                case DUE: in = c.state != null && c.state.due <= now && c.parked <= now; break;
                case NEW: in = c.state == null && c.parked <= now; break;
                case WEAK: in = weak(c); break;
                case TOPIC: case NOTE: case RECENT: in = s.paths.contains(c.path); break;
                default: in = true;
            }
            if (in) out.add(c);
        }
        return out;
    }

    /** Topics that have cards, with counts, the most due first then the biggest. Source notes with cards but no topic go under one "No topic" entry. */
    public static List<Topic> topics(Vault.Graph g, List<Cards.Card> cards, long now) {
        Map<String, List<Cards.Card>> byNote = new HashMap<>();
        for (Cards.Card c : cards) { if (c.flag || c.parked > now) continue; List<Cards.Card> l = byNote.get(c.path); if (l == null) byNote.put(c.path, l = new ArrayList<>()); l.add(c); }
        Map<String, Topic> out = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> lanes = new HashMap<>();
        for (Vault.Note n : g.notes) {
            if (!byNote.containsKey(n.path)) continue;
            boolean any = n.path.startsWith("Knowledge/Topics/");
            if (any) add(out, lanes, n.path, n.title, n);
            for (String name : n.topics) {
                Vault.Note t = g.resolve(name);
                if (t == null || !t.path.startsWith("Knowledge/Topics/")) continue;
                any = true;
                add(out, lanes, t.path, t.title, n);
            }
            if (!any) add(out, lanes, NO_TOPIC, "No topic", n);
        }
        for (Topic t : out.values()) {
            for (String p : t.notes) for (Cards.Card c : byNote.get(p)) {
                t.cards++;
                if (c.state == null) { if (c.parked <= now) t.fresh++; }
                else { if (c.state.due <= now && c.parked <= now) t.due++; if (c.state.interval >= MASTERED_DAYS) t.mastered++; }
            }
            int best = 0;
            for (Map.Entry<String, Integer> e : lanes.get(t.path).entrySet()) if (e.getValue() > best) { best = e.getValue(); t.lane = e.getKey(); }
        }
        List<Topic> list = new ArrayList<>(out.values());
        Collections.sort(list, (a, b) -> a.path.equals(NO_TOPIC) != b.path.equals(NO_TOPIC) ? (a.path.equals(NO_TOPIC) ? 1 : -1) : a.due != b.due ? Integer.compare(b.due, a.due) : Integer.compare(b.cards, a.cards));
        return list;
    }

    private static void add(Map<String, Topic> out, Map<String, Map<String, Integer>> lanes, String path, String title, Vault.Note n) {
        Topic t = out.get(path);
        if (t == null) { out.put(path, t = new Topic(path, title)); lanes.put(path, new HashMap<String, Integer>()); }
        t.notes.add(n.path);
        String lane = Lane.of(n.path, n.meta);
        if (!lane.isEmpty()) lanes.get(path).merge(lane, 1, Integer::sum);
    }

    public static Scope topic(Topic t) { return new Scope(TOPIC, t.path, t.title, t.notes); }

    /** The scope for a topic note path, from the graph: every source note that lists it. Null when no source does. */
    public static Scope topicOf(Vault.Graph g, String topicPath, String title) {
        if (g.byPath(topicPath) == null) return null;
        Set<String> notes = new LinkedHashSet<>();
        notes.add(topicPath);
        for (Vault.Note n : g.notes) for (String name : n.topics) { Vault.Note t = g.resolve(name); if (t != null && t.path.equals(topicPath)) notes.add(n.path); }
        return notes.isEmpty() ? null : new Scope(TOPIC, topicPath, title, notes);
    }
}
