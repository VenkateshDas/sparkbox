package me.venkatesh.saveobsidian;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    /**
     * kept holds the paths of notes the owner marked Keep: their new cards come first. Due cards always come before new ones, so when the owner is
     * behind, new cards pause. No overdue count is exposed anywhere.
     */
    public static Plan plan(List<Cards.Card> all, Set<String> kept, long now, ZoneId zone, int cap, List<String> newestFirstPaths) {
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
        final Set<String> keep = kept;
        Collections.sort(fresh, new Comparator<Cards.Card>() {
            @Override public int compare(Cards.Card a, Cards.Card b) {
                boolean ka = keep.contains(a.path), kb = keep.contains(b.path);
                if (ka != kb) return ka ? -1 : 1;
                int pa = order.indexOf(a.path), pb = order.indexOf(b.path);
                return pa == pb ? 0 : Integer.compare(pa < 0 ? Integer.MAX_VALUE : pa, pb < 0 ? Integer.MAX_VALUE : pb);
            }
        });
        int room = Math.max(0, cap - reviewed);
        List<Cards.Card> queue = new ArrayList<>();
        for (Cards.Card c : due) if (queue.size() < room) queue.add(c);
        for (Cards.Card c : fresh) if (queue.size() < room) queue.add(c);
        boolean waiting = !due.isEmpty() || !fresh.isEmpty();
        String state = !any ? NO_CARDS : !queue.isEmpty() ? QUESTION : room == 0 && waiting ? CAP_REACHED : CAUGHT_UP;
        return new Plan(queue, reviewed, cap, state, next);
    }

    /** Clamp for the Settings stepper. */
    public static int clampCap(int cap) { return Math.max(CAP_MIN, Math.min(CAP_MAX, cap)); }
}
