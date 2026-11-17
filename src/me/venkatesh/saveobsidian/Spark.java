package me.venkatesh.saveobsidian;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/** Today's review: which cards, in what order, how many, and which of the Spark screen's states applies. Pure Java so the JVM checks cover it. */
public final class Spark {
    public static final int CAP_DEFAULT = 7, CAP_MIN = 3, CAP_MAX = 20;
    public static final String QUESTION = "question", CAUGHT_UP = "caught_up", NO_CARDS = "no_cards", CAP_REACHED = "cap_reached";

    public static final class Plan {
        /** Cards to review now, in order. */
        public final List<Cards.Card> queue;
        public final int reviewedToday, cap;
        public final String state;
        /** When the next card is due, or 0. */
        public final long nextDue;
        Plan(List<Cards.Card> queue, int reviewedToday, int cap, String state, long nextDue) { this.queue = queue; this.reviewedToday = reviewedToday; this.cap = cap; this.state = state; this.nextDue = nextDue; }
        /** Minutes shown on the strip and header: 30 seconds a card, rounded up. */
        public int minutes() { return (queue.size() + 1) / 2; }
    }

    private Spark() { }

    /** A card is out of rotation when it is flagged, parked or has no note behind it. */
    public static boolean active(Cards.Card c, long now) { return !c.flag && c.parked <= now; }

    /** Cards graded on this local day, counting each card once. */
    public static int reviewedToday(List<Cards.Card> cards, long now, ZoneId zone) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        int n = 0;
        for (Cards.Card c : cards)
            for (long t : Cards.logTimes(c.log)) if (Instant.ofEpochMilli(t).atZone(zone).toLocalDate().equals(today)) { n++; break; }
        return n;
    }

    /** Due cards precede new cards. Bookmarks never affect review order. */
    public static Plan plan(List<Cards.Card> all, long now, ZoneId zone, int cap, List<String> newestFirstPaths) { return plan(all, now, zone, cap, newestFirstPaths, true); }

    /** gate false shows why, teach and apply cards from the start (the owner picked a topic or asked for everything). */
    public static Plan plan(List<Cards.Card> all, long now, ZoneId zone, int cap, List<String> newestFirstPaths, boolean gate) {
        int reviewed = reviewedToday(all, now, zone);
        final List<Cards.Card> due = new ArrayList<>(), fresh = new ArrayList<>();
        boolean any = false;
        long next = 0;
        for (Cards.Card c : all) {
            if (c.flag) continue;
            any = true;
            if (c.parked > now) continue;
            if (c.state == null) fresh.add(c);
            else if (c.state.due <= now) due.add(c);
            else if (next == 0 || c.state.due < next) next = c.state.due;
        }
        Collections.sort(due, new Comparator<Cards.Card>() { @Override public int compare(Cards.Card a, Cards.Card b) { return Long.compare(a.state.due, b.state.due); } });
        final List<String> order = newestFirstPaths;
        Collections.sort(fresh, new Comparator<Cards.Card>() {
            @Override public int compare(Cards.Card a, Cards.Card b) {
                int pa = order.indexOf(a.path), pb = order.indexOf(b.path);
                return pa == pb ? 0 : Integer.compare(pa < 0 ? Integer.MAX_VALUE : pa, pb < 0 ? Integer.MAX_VALUE : pb);
            }
        });
        // elaboration cards (why, teach, apply) wait until the note's basic cards have been seen once
        Set<String> base = new HashSet<>(), seen = new HashSet<>();
        for (Cards.Card c : all) {
            if (!gated(c)) base.add(c.path);
            if (c.state != null && c.state.reps > 0) seen.add(c.path);
        }
        for (int i = fresh.size() - 1; gate && i >= 0; i--) { Cards.Card c = fresh.get(i); if (gated(c) && base.contains(c.path) && !seen.contains(c.path)) fresh.remove(i); }
        int room = Math.max(0, cap - reviewed);
        List<Cards.Card> mixed = interleave(due); // due cards from different notes alternate: interleaving helps long-term recall
        due.clear(); due.addAll(mixed);
        List<Cards.Card> queue = new ArrayList<>();
        for (Cards.Card c : due) if (queue.size() < room) queue.add(c);
        for (Cards.Card c : fresh) if (queue.size() < room) queue.add(c);
        boolean waiting = !due.isEmpty() || !fresh.isEmpty();
        String state = !any ? NO_CARDS : !queue.isEmpty() ? QUESTION : room == 0 && waiting ? CAP_REACHED : CAUGHT_UP;
        return new Plan(queue, reviewed, cap, state, next);
    }

    static boolean gated(Cards.Card c) { return c.type.equals("why") || c.type.equals("teach") || c.type.equals("apply"); }

    /** Round-robin across notes, keeping each note's own order. */
    public static List<Cards.Card> interleave(List<Cards.Card> in) {
        Map<String, List<Cards.Card>> by = new LinkedHashMap<>();
        for (Cards.Card c : in) { List<Cards.Card> l = by.get(c.path); if (l == null) by.put(c.path, l = new ArrayList<>()); l.add(c); }
        List<Cards.Card> out = new ArrayList<>();
        while (out.size() < in.size()) for (List<Cards.Card> l : by.values()) if (!l.isEmpty()) out.add(l.remove(0));
        return out;
    }

    /** Deep dive: every card of one note that is due or new, due first, no daily cap. */
    public static List<Cards.Card> noteQueue(List<Cards.Card> all, String path, long now) {
        List<Cards.Card> due = new ArrayList<>(), fresh = new ArrayList<>();
        for (Cards.Card c : all) if (c.path.equals(path) && active(c, now)) { if (c.state == null) fresh.add(c); else if (c.state.due <= now) due.add(c); }
        due.addAll(fresh);
        return due;
    }

    /** Grade counts for cards graded today: {again, hard, good, easy}, counting each card's latest grade today. */
    public static int[] gradesToday(List<Cards.Card> cards, long now, ZoneId zone) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        int[] n = new int[4];
        for (Cards.Card c : cards) {
            String g = lastToday(c, today, zone);
            if (g != null) n["again hard good easy".indexOf(g) / 5]++;
        }
        return n;
    }

    /** The cards whose latest grade today was "again": the deck to try once more. */
    public static List<Cards.Card> misses(List<Cards.Card> cards, long now, ZoneId zone) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        List<Cards.Card> out = new ArrayList<>();
        for (Cards.Card c : cards) if (Sched.AGAIN.equals(lastToday(c, today, zone))) out.add(c);
        return out;
    }

    private static String lastToday(Cards.Card c, LocalDate today, ZoneId zone) {
        String first = c.log.split(";")[0].trim();
        String[] w = first.split("\\s+");
        long t = w.length > 1 ? Cards.parseTime(w[0]) : 0;
        return t > 0 && Instant.ofEpochMilli(t).atZone(zone).toLocalDate().equals(today) ? w[1] : null;
    }

    /** Days in a row with at least one review, ending today (or yesterday, so the streak is not lost before you start today). */
    public static int streak(List<Cards.Card> cards, long now, ZoneId zone) {
        Set<LocalDate> days = new HashSet<>();
        for (Cards.Card c : cards) for (long t : Cards.logTimes(c.log)) days.add(Instant.ofEpochMilli(t).atZone(zone).toLocalDate());
        LocalDate d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        if (!days.contains(d)) d = d.minusDays(1);
        int n = 0;
        while (days.contains(d)) { n++; d = d.minusDays(1); }
        return n;
    }

    /** How close a typed answer is to the real one, 0 to 1: the share of the answer's words (3+ letters) the owner used. On-device, no model call. */
    public static double match(String typed, String answer) {
        Set<String> want = words(answer), got = words(typed);
        if (want.isEmpty()) return 0;
        int hit = 0;
        for (String w : want) if (got.contains(w)) hit++;
        return hit / (double) want.size();
    }

    private static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 3) out.add(w.length() > 5 ? w.substring(0, w.length() - 1) : w);
        return out;
    }

    /** Clamp for the Settings stepper. */
    public static int clampCap(int cap) { return Math.max(CAP_MIN, Math.min(CAP_MAX, cap)); }
}
