package me.venkatesh.saveobsidian;

/**
 * The review scheduler: FSRS-4.5 with its published default weights, targeting 90% recall. Deterministic, no fuzz. Cards scheduled before FSRS (no stability)
 * start from their old interval. Pure Java so the JVM checks cover it.
 */
public final class Sched {
    public static final String AGAIN = "again", HARD = "hard", GOOD = "good", EASY = "easy";
    static final double EASE_START = 2.5;
    private static final long DAY = 24L * 3600 * 1000, TEN_MIN = 10L * 60 * 1000;
    private static final double[] W = {0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031, 1.6474, 0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755};

    private Sched() { }

    private static int rank(String g) { return g.equals(AGAIN) ? 1 : g.equals(HARD) ? 2 : g.equals(GOOD) ? 3 : 4; }
    private static double clamp(double d) { return Math.max(1, Math.min(10, d)); }

    /** The state after grading. s is null for a card never reviewed. */
    public static Cards.State next(Cards.State s, String grade, long now) {
        int g = rank(grade);
        boolean fresh = s == null || s.reps == 0 && s.stab == 0;
        double stab, diff;
        int lapses = s == null ? 0 : s.lapses, reps = s == null ? 0 : s.reps;
        if (fresh) {
            stab = W[g - 1];
            diff = clamp(W[4] - (g - 3) * W[5]);
        } else {
            double s0 = s.stab > 0 ? s.stab : Math.max(1, s.interval), d0 = s.diff > 0 ? s.diff : 5;
            double elapsed = Math.max(0, (now - (s.due - s.interval * DAY)) / (double) DAY);
            double r = Math.pow(1 + 19.0 / 81 * elapsed / s0, -0.5);
            diff = clamp(W[7] * clamp(W[4] - 0) + (1 - W[7]) * (d0 - W[6] * (g - 3)));
            if (g == 1) stab = Math.min(s0, W[11] * Math.pow(d0, -W[12]) * (Math.pow(s0 + 1, W[13]) - 1) * Math.exp(W[14] * (1 - r)));
            else stab = s0 * (1 + Math.exp(W[8]) * (11 - d0) * Math.pow(s0, -W[9]) * (Math.exp(W[10] * (1 - r)) - 1) * (g == 2 ? W[15] : g == 4 ? W[16] : 1));
        }
        stab = Math.max(0.1, stab);
        if (g == 1) return new Cards.State(now + TEN_MIN, 0, EASE_START, 0, reps > 0 ? lapses + 1 : lapses, stab, diff);
        int interval = Math.max(1, (int) Math.round(stab));
        return new Cards.State(now + interval * DAY, interval, EASE_START, reps + 1, lapses, stab, diff);
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
