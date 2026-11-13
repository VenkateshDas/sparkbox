package me.venkatesh.saveobsidian;

/** The review scheduler: a small SM-2 variant. Deterministic, no fuzz. Pure Java so the JVM checks cover it. */
public final class Sched {
    public static final String AGAIN = "again", HARD = "hard", GOOD = "good", EASY = "easy";
    static final double EASE_START = 2.5, EASE_MIN = 1.3;
    private static final long DAY = 24L * 3600 * 1000, TEN_MIN = 10L * 60 * 1000;

    private Sched() { }

    /** The state after grading. s is null for a card never reviewed. */
    public static Cards.State next(Cards.State s, String grade, long now) {
        boolean fresh = s == null || s.reps == 0;
        double ease = s == null ? EASE_START : s.ease;
        int interval = s == null ? 0 : s.interval, reps = s == null ? 0 : s.reps, lapses = s == null ? 0 : s.lapses;
        switch (grade) {
            case AGAIN:
                if (s != null) ease = Math.max(EASE_MIN, ease - 0.2);
                if (s != null && s.reps > 0) lapses++;
                return new Cards.State(now + TEN_MIN, 0, ease, 0, lapses);
            case HARD:
                if (fresh) interval = 1; else { interval = Math.max(1, (int) Math.round(interval * 1.2)); ease = Math.max(EASE_MIN, ease - 0.15); }
                break;
            case EASY:
                if (fresh) interval = 4; else { interval = (int) Math.round(interval * ease * 1.3); ease = ease + 0.15; }
                break;
            default: // good
                if (fresh) interval = 1; else if (reps == 1) interval = Math.max(3, interval + 1); else interval = (int) Math.round(interval * ease);
        }
        return new Cards.State(now + interval * DAY, interval, ease, reps + 1, lapses);
    }

    /** The label on a grade button: "later today", "1d", "3d", "2w", "3mo". It comes from the same function as the real update. */
    public static String label(Cards.State s, String grade, long now) {
        if (grade.equals(AGAIN)) return "later today";
        int d = next(s, grade, now).interval;
        if (d < 14) return d + "d";
        if (d < 60) return (d / 7) + "w";
        return (d / 30) + "mo";
    }
}
