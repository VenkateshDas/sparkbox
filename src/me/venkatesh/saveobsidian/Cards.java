package me.venkatesh.saveobsidian;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Review cards stored in a note's "## Cards" section. Each card is a "### c_id" block of "Key: value" lines. Every edit is a surgical transform of the
 * freshly read note text: it changes one block and nothing else, and applying it twice changes nothing. Pure Java so the JVM checks cover it.
 */
public final class Cards {
    public static final String SECTION = "Cards";
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'");
    static final int LOG_KEEP = 50;
    private static final String[] ORDER = {"Q", "A", "Type", "Data", "Basis", "Take", "Origin", "State", "Log", "Parked", "Flag"};

    /** Scheduling state: due time (epoch ms, UTC), interval in days, ease, review count, lapses. */
    public static final class State {
        public final long due; public final int interval, reps, lapses; public final double ease;
        /** FSRS memory stability (days) and difficulty (1 to 10); 0 for a card scheduled before FSRS. */
        public final double stab, diff;
        public State(long due, int interval, double ease, int reps, int lapses) { this(due, interval, ease, reps, lapses, 0, 0); }
        public State(long due, int interval, double ease, int reps, int lapses, double stab, double diff) { this.due = due; this.interval = interval; this.ease = ease; this.reps = reps; this.lapses = lapses; this.stab = stab; this.diff = diff; }
    }

    public static final class Card {
        /** type: classic, cloze, quiz, tf, why, teach, apply, sort. data: type extras (quiz distractors, sort order, tf explanation), "|" separated. */
        public String id = "", path = "", q = "", a = "", type = "classic", data = "", basis = "", take = "", origin = "", log = "";
        public State state;     // null for a card never reviewed
        public long parked;     // epoch ms, 0 when not parked
        public boolean flag;
        public boolean mine() { return origin.equals("mine"); }
    }

    private Cards() { }

    // ---- ids and times ----

    /** c_ plus 8 hex digits of SHA-256 of "<path>|<question, whitespace collapsed, lowercase>". Own cards pass "mine|<created>" as the path. */
    public static String id(String path, String question) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((path + "|" + question.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT)).getBytes("UTF-8"));
            StringBuilder s = new StringBuilder("c_");
            for (int i = 0; i < 4; i++) s.append(String.format("%02x", h[i] & 0xff));
            return s.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public static String time(long ms) { return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC).format(MINUTE); }

    public static long parseTime(String s) {
        try { return LocalDateTime.parse(s.trim(), MINUTE).toInstant(ZoneOffset.UTC).toEpochMilli(); } catch (RuntimeException bad) { return 0; }
    }

    public static String stateText(State s) {
        return "due " + time(s.due) + " interval " + s.interval + " ease " + String.format(Locale.US, "%.2f", s.ease) + " reps " + s.reps + " lapses " + s.lapses
            + (s.stab > 0 ? " stab " + String.format(Locale.US, "%.2f", s.stab) + " diff " + String.format(Locale.US, "%.2f", s.diff) : "");
    }

    public static State parseState(String v) {
        try {
            String[] t = v.trim().split("\\s+");
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i + 1 < t.length; i += 2) m.put(t[i], t[i + 1]);
            long due = parseTime(m.get("due"));
            if (due == 0) return null;
            return new State(due, Integer.parseInt(m.get("interval")), Double.parseDouble(m.get("ease")), Integer.parseInt(m.get("reps")), Integer.parseInt(m.get("lapses")),
                m.containsKey("stab") ? Double.parseDouble(m.get("stab")) : 0, m.containsKey("diff") ? Double.parseDouble(m.get("diff")) : 0);
        } catch (RuntimeException bad) { return null; }
    }

    /** The log with one more entry on the front, newest first, kept to the last LOG_KEEP. */
    public static String logWith(String log, long ms, String grade, int seconds) { return logWith(log, ms, grade, seconds, ""); }

    /** conf: how sure the owner was before the answer ("sure", "unsure", "guess") or "". */
    public static String logWith(String log, long ms, String grade, int seconds, String conf) {
        List<String> parts = new ArrayList<>();
        parts.add(time(ms) + " " + grade + " " + seconds + "s" + (conf.isEmpty() ? "" : " " + conf));
        if (!log.trim().isEmpty()) for (String p : log.split(";")) if (!p.trim().isEmpty()) parts.add(p.trim());
        return String.join("; ", parts.subList(0, Math.min(LOG_KEEP, parts.size())));
    }

    /** UTC minutes of every log entry, newest first. */
    public static List<Long> logTimes(String log) {
        List<Long> out = new ArrayList<>();
        for (String p : log.split(";")) { String[] w = p.trim().split("\\s+"); long t = w.length > 0 ? parseTime(w[0]) : 0; if (t > 0) out.add(t); }
        return out;
    }

    // ---- reading ----

    /** The cards in a note's "## Cards" section. A malformed block (no id, no Q or no A) is skipped. */
    public static List<Card> parse(String path, String noteText) {
        List<Card> out = new ArrayList<>();
        String t = noteText.replace("\r\n", "\n");
        List<String> lines = Arrays.asList(t.split("\n", -1));
        List<Boolean> fence = NoteEdit.fenced(lines);
        int s = NoteEdit.find(lines, fence, SECTION);
        if (s < 0) return out;
        int e = NoteEdit.end(lines, fence, s);
        for (int i = s + 1; i < e; i++) {
            if (!lines.get(i).startsWith("### ")) continue;
            int j = i + 1;
            while (j < e && !lines.get(j).startsWith("### ")) j++;
            Card c = block(path, lines.subList(i, j));
            if (c != null) out.add(c);
            i = j - 1;
        }
        return out;
    }

    private static Card block(String path, List<String> lines) {
        Card c = new Card();
        c.path = path;
        c.id = lines.get(0).substring(4).trim();
        Map<String, StringBuilder> kv = new LinkedHashMap<>();
        StringBuilder cur = null;
        for (int i = 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("  ") && cur != null) { cur.append('\n').append(l.substring(2)); continue; }
            int colon = l.indexOf(':');
            if (colon > 0 && Character.isUpperCase(l.charAt(0)) && l.substring(0, colon).matches("[A-Za-z]+")) {
                cur = new StringBuilder(l.substring(colon + 1).replaceFirst("^ ", ""));
                kv.put(l.substring(0, colon), cur);
            } else cur = null;
        }
        if (c.id.isEmpty() || !kv.containsKey("Q") || !kv.containsKey("A") || kv.get("Q").toString().trim().isEmpty() || kv.get("A").toString().trim().isEmpty()) return null;
        c.q = kv.get("Q").toString().trim();
        c.a = kv.get("A").toString().trim();
        if (kv.containsKey("Type") && !kv.get("Type").toString().trim().isEmpty()) c.type = kv.get("Type").toString().trim().toLowerCase(Locale.ROOT);
        if (kv.containsKey("Data")) c.data = kv.get("Data").toString().trim();
        if (kv.containsKey("Basis")) c.basis = kv.get("Basis").toString().trim();
        if (kv.containsKey("Take")) c.take = kv.get("Take").toString().trim();
        if (kv.containsKey("Origin")) c.origin = kv.get("Origin").toString().trim();
        if (kv.containsKey("Log")) c.log = kv.get("Log").toString().trim();
        if (kv.containsKey("State")) c.state = parseState(kv.get("State").toString());
        if (kv.containsKey("Parked")) c.parked = parseTime(kv.get("Parked").toString());
        if (kv.containsKey("Flag")) c.flag = kv.get("Flag").toString().trim().equalsIgnoreCase("yes");
        return c;
    }

    // ---- writing ----

    private static void put(StringBuilder b, String key, String value) {
        if (value == null || value.trim().isEmpty()) return;
        b.append(key).append(": ").append(value.trim().replace("\n", "\n  ")).append('\n');
    }

    /** One card as a block, without a trailing blank line. */
    public static String format(Card c) {
        StringBuilder b = new StringBuilder("### " + c.id + "\n");
        put(b, "Q", c.q); put(b, "A", c.a); if (!c.type.equals("classic")) put(b, "Type", c.type); put(b, "Data", c.data); put(b, "Basis", c.basis); put(b, "Take", c.take); put(b, "Origin", c.origin);
        if (c.state != null) put(b, "State", stateText(c.state));
        put(b, "Log", c.log);
        if (c.parked > 0) put(b, "Parked", time(c.parked));
        if (c.flag) put(b, "Flag", "yes");
        return b.toString();
    }

    /** Adds the cards that are not already there (by id). Creates "## Cards" at the end of the note when missing. */
    public static String add(String noteText, List<Card> cards) {
        boolean crlf = noteText.contains("\r\n");
        String t = noteText.replace("\r\n", "\n");
        List<Card> have = parse("", t);
        StringBuilder add = new StringBuilder();
        for (Card c : cards) {
            boolean dup = false;
            for (Card h : have) dup |= h.id.equals(c.id);
            if (!dup) add.append(format(c)).append('\n');
        }
        if (add.length() == 0) return noteText;
        List<String> lines = new ArrayList<>(Arrays.asList(t.split("\n", -1)));
        List<Boolean> fence = NoteEdit.fenced(lines);
        int s = NoteEdit.find(lines, fence, SECTION);
        String out;
        if (s < 0) {
            out = t + (t.endsWith("\n") ? "" : "\n") + "\n## Cards\n\n" + add;
        } else {
            int e = NoteEdit.end(lines, fence, s);
            while (e > s + 1 && lines.get(e - 1).isEmpty()) e--; // before the section's trailing blank lines
            List<String> next = new ArrayList<>(lines.subList(0, e));
            next.add("");
            next.addAll(Arrays.asList(add.toString().split("\n", -1)));
            List<String> tail = new ArrayList<>(lines.subList(e, lines.size()));
            while (!next.isEmpty() && next.get(next.size() - 1).isEmpty() && !tail.isEmpty() && tail.get(0).isEmpty()) next.remove(next.size() - 1);
            next.addAll(tail);
            out = String.join("\n", next);
        }
        if (!out.endsWith("\n")) out += "\n";
        return crlf ? out.replace("\n", "\r\n") : out;
    }

    /** Sets keys of one card; a null value removes the key. Unknown ids leave the note unchanged. */
    public static String setKeys(String noteText, String id, Map<String, String> changes) {
        boolean crlf = noteText.contains("\r\n");
        List<String> lines = new ArrayList<>(Arrays.asList(noteText.replace("\r\n", "\n").split("\n", -1)));
        List<Boolean> fence = NoteEdit.fenced(lines);
        int s = NoteEdit.find(lines, fence, SECTION);
        if (s < 0) return noteText;
        int e = NoteEdit.end(lines, fence, s), start = -1;
        for (int i = s + 1; i < e; i++) if (lines.get(i).equals("### " + id)) { start = i; break; }
        if (start < 0) return noteText;
        int stop = start + 1;
        while (stop < e && !lines.get(stop).startsWith("### ") && !lines.get(stop).isEmpty()) stop++;
        List<String> block = new ArrayList<>(lines.subList(start + 1, stop));
        for (Map.Entry<String, String> ch : changes.entrySet()) {
            int at = -1, to = -1;
            for (int i = 0; i < block.size(); i++) if (block.get(i).startsWith(ch.getKey() + ":")) { at = i; to = i + 1; while (to < block.size() && block.get(to).startsWith("  ")) to++; break; }
            List<String> repl = new ArrayList<>();
            if (ch.getValue() != null && !ch.getValue().trim().isEmpty()) {
                StringBuilder b = new StringBuilder();
                put(b, ch.getKey(), ch.getValue());
                repl.addAll(Arrays.asList(b.toString().split("\n")));
            }
            if (at >= 0) { for (int k = to - 1; k >= at; k--) block.remove(k); block.addAll(at, repl); }
            else block.addAll(repl);
        }
        List<String> next = new ArrayList<>(lines.subList(0, start + 1));
        next.addAll(block);
        next.addAll(lines.subList(stop, lines.size()));
        String out = String.join("\n", next);
        return crlf ? out.replace("\n", "\r\n") : out;
    }

    /** Removes a card's block. */
    public static String remove(String noteText, String id) {
        boolean crlf = noteText.contains("\r\n");
        List<String> lines = new ArrayList<>(Arrays.asList(noteText.replace("\r\n", "\n").split("\n", -1)));
        List<Boolean> fence = NoteEdit.fenced(lines);
        int s = NoteEdit.find(lines, fence, SECTION);
        if (s < 0) return noteText;
        int e = NoteEdit.end(lines, fence, s);
        for (int i = s + 1; i < e; i++) if (lines.get(i).equals("### " + id)) {
            int stop = i + 1;
            while (stop < e && !lines.get(stop).startsWith("### ")) stop++;
            for (int k = stop - 1; k >= i; k--) lines.remove(k);
            String out = String.join("\n", lines);
            return crlf ? out.replace("\n", "\r\n") : out;
        }
        return noteText;
    }

    /** The key order a block uses, for tests and tools. */
    static String[] order() { return ORDER; }
}
