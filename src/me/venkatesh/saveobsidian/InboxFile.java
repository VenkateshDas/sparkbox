package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and edits the inbox text. An entry is a "- " line plus the indented lines under it.
 * Everything else (frontmatter, headings, blank lines) is left untouched.
 * ponytail: LF line endings only; Obsidian on Android writes LF.
 */
public final class InboxFile {
    public static final class Entry {
        public final String raw, title, url, time, why, ideas;
        public final boolean text;
        Entry(String raw, String title, String url, String time, String why, String ideas, boolean text) {
            this.raw = raw; this.title = title; this.url = url; this.time = time; this.why = why; this.ideas = ideas; this.text = text;
        }
    }

    private static final class Block { int start, end; String raw; }

    private static List<Block> blocks(String text) {
        List<Block> found = new ArrayList<>();
        int at = 0;
        Block open = null;
        while (at <= text.length()) {
            int nl = text.indexOf('\n', at);
            int lineEnd = nl < 0 ? text.length() : nl;
            String line = text.substring(at, lineEnd);
            if (line.startsWith("- ")) {
                if (open != null) close(open, text, found);
                open = new Block();
                open.start = at;
                open.end = lineEnd;
            } else if (open != null && line.startsWith("  ") && !line.trim().isEmpty()) {
                open.end = lineEnd;
            } else if (open != null) {
                close(open, text, found);
                open = null;
            }
            if (nl < 0) break;
            at = nl + 1;
        }
        if (open != null) close(open, text, found);
        return found;
    }
    private static void close(Block b, String text, List<Block> into) { b.raw = text.substring(b.start, b.end); into.add(b); }

    /** Entries in file order (oldest first). */
    public static List<Entry> parse(String text) {
        List<Entry> entries = new ArrayList<>();
        for (Block b : blocks(text)) entries.add(entry(b.raw));
        return entries;
    }

    /** Calendar day of a capture stamp: "2026-11-04 23:13 · via Sparkbox" or Telegram's "18/10/26, 7:36 AM". Null if unreadable. */
    public static java.time.LocalDate day(String time) {
        try {
            java.util.regex.Matcher iso = java.util.regex.Pattern.compile("^(\\d{4})-(\\d\\d)-(\\d\\d)").matcher(time);
            if (iso.find()) return java.time.LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            java.util.regex.Matcher tg = java.util.regex.Pattern.compile("^(\\d\\d?)/(\\d\\d?)/(\\d\\d)\\b").matcher(time);
            if (tg.find()) return java.time.LocalDate.of(2000 + Integer.parseInt(tg.group(3)), Integer.parseInt(tg.group(2)), Integer.parseInt(tg.group(1)));
        } catch (java.time.DateTimeException | NumberFormatException ignored) { }
        return null;
    }

    private static Entry entry(String raw) {
        String[] lines = raw.split("\n");
        String head = lines[0].substring(2).trim();
        boolean note = head.startsWith("📝");
        String title = head.replaceFirst("^(🔗|📝)\\s*", "");
        String url = "", time = "";
        // Sparkbox 1.1 wrote "- 2026-11-04 22:24 — Title"; show the date as the date.
        java.util.regex.Matcher old = java.util.regex.Pattern.compile("^(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d) — (.*)$").matcher(title);
        if (old.matches()) { time = old.group(1); title = old.group(2); }
        StringBuilder why = new StringBuilder(), ideas = new StringBuilder(), current = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("  Why:")) { current = why; current.append(line.substring(6).trim()); }
            else if (line.startsWith("  Ideas:")) { current = ideas; current.append(line.substring(8).trim()); }
            else if (line.startsWith("    ") && current != null) current.append('\n').append(line.trim());
            else {
                current = null;
                String t = line.trim();
                if (t.startsWith("<") && t.endsWith(">")) t = t.substring(1, t.length() - 1);
                if (url.isEmpty() && t.matches("(?i)https?://\\S+")) url = t;
                else if (t.startsWith("Captured:")) time = t.substring(9).trim();
            }
        }
        return new Entry(raw, title, url, time, why.toString(), ideas.toString(), note);
    }

    private static Block find(String text, String raw) {
        for (Block b : blocks(text)) if (b.raw.equals(raw)) return b;
        throw new IllegalStateException("That entry changed in Obsidian. Refresh and try again.");
    }

    /** Remove the entry and one following blank line, so entries stay one blank line apart. */
    public static String delete(String text, String raw) {
        Block b = find(text, raw);
        int end = b.end;
        for (int i = 0; i < 2 && end < text.length() && text.charAt(end) == '\n'; i++) end++;
        return text.substring(0, b.start) + text.substring(end);
    }

    /** Replace the entry's Why and Ideas lines. Empty text removes the line. */
    public static String setNotes(String text, String raw, String why, String ideas) {
        Block b = find(text, raw);
        StringBuilder out = new StringBuilder();
        boolean skipping = false;
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0 && (line.startsWith("  Why:") || line.startsWith("  Ideas:"))) { skipping = true; continue; }
            if (skipping && line.startsWith("    ")) continue;
            skipping = false;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        out.append(note("Why", why)).append(note("Ideas", ideas));
        return text.substring(0, b.start) + out + text.substring(b.end);
    }

    private static String note(String label, String value) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String line : (value == null ? "" : value.replace("\r", "")).split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue; // a blank line would end the entry
            out.append(first ? "\n  " + label + ": " : "\n    ").append(t);
            first = false;
        }
        return out.toString();
    }
}
