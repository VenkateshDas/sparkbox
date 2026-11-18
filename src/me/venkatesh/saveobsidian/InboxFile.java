package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.List;

/** Supported capture blocks only; unfamiliar Markdown stays untouched. */
public final class InboxFile {
    public static final class Entry {
        public final String raw, title, url, time, why, ideas, body, sharedText, type, tags, tldr, file, heading;
        public final boolean text;
        public final int start;
        Entry(int start, String raw, String title, String url, String time, String why, String ideas,
                String body, String sharedText, boolean text, String type, String tags, String tldr, String file, String heading) {
            this.start = start; this.raw = raw; this.title = title; this.url = url; this.time = time;
            this.why = why; this.ideas = ideas; this.body = body; this.sharedText = sharedText; this.text = text;
            this.type = type; this.tags = tags; this.tldr = tldr; this.file = file; this.heading = heading;
        }
        /** Stable across enrichment and edits, including files shared in the same minute. */
        String sourceId() { return file.isEmpty() ? url : "attachment:" + file; }
        /** The model-written title when there is one, else the saved title. */
        public String shownTitle() { return heading.isEmpty() ? title : heading; }
        public boolean hasTag(String tag) { return (", " + tags + ", ").contains(", " + tag + ", "); }
        /** Every word of the query appears somewhere in the entry (case-insensitive). */
        public boolean contains(String query) {
            String hay = (title + "\n" + heading + "\n" + file + "\n" + url + "\n" + why + "\n" + ideas + "\n" + body + "\n" + tldr + "\n" + tags).toLowerCase(java.util.Locale.ROOT);
            for (String word : query.toLowerCase(java.util.Locale.ROOT).split("\\s+")) if (!hay.contains(word)) return false;
            return true;
        }
    }
    private static final class Block { int start, end; String raw; }

    private static List<Block> blocks(String text) {
        List<Block> found = new ArrayList<>();
        int at = 0;
        Block open = null;
        boolean yaml = text.startsWith("---\n") || text.startsWith("---\r\n") || text.startsWith("\uFEFF---\n") || text.startsWith("\uFEFF---\r\n");
        boolean fence = false;
        String fenceMarker = "";
        while (at <= text.length()) {
            int nl = text.indexOf('\n', at), end = nl < 0 ? text.length() : nl;
            String line = text.substring(at, end), trimmed = line.trim();
            if (yaml) {
                if (at > 0 && (trimmed.equals("---") || trimmed.equals("..."))) yaml = false;
            } else if (!line.startsWith("  ") && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                if (open != null) { close(open, text, found); open = null; }
                int run = 0;
                while (run < trimmed.length() && trimmed.charAt(run) == trimmed.charAt(0)) run++;
                String marker = trimmed.substring(0, run);
                if (!fence) { fence = true; fenceMarker = marker; }
                else if (marker.charAt(0) == fenceMarker.charAt(0) && marker.length() >= fenceMarker.length() && trimmed.substring(run).isEmpty()) fence = false;
            } else if (!fence) {
                boolean capture = line.startsWith("- 🔗 ") || line.startsWith("- 📝 ") || line.startsWith("- 🖼 ") || line.startsWith("- 📎 ") ||
                    line.matches("- \\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d — .*");
                if (capture) {
                    if (open != null) close(open, text, found);
                    open = new Block(); open.start = at; open.end = end;
                } else if (open != null && line.startsWith("  ")) open.end = end;
                else if (open != null) { close(open, text, found); open = null; }
            }
            if (nl < 0) break;
            at = nl + 1;
        }
        if (open != null) close(open, text, found);
        return found;
    }
    private static void close(Block b, String text, List<Block> found) {
        b.raw = text.substring(b.start, b.end);
        found.add(b);
    }
    public static List<Entry> parse(String text) {
        List<Entry> result = new ArrayList<>();
        for (Block b : blocks(text)) result.add(entry(b));
        return result;
    }
    public static java.time.LocalDate day(String time) {
        try {
            java.util.regex.Matcher iso = java.util.regex.Pattern.compile("^(\\d{4})-(\\d\\d)-(\\d\\d)").matcher(time);
            if (iso.find()) return java.time.LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            java.util.regex.Matcher tg = java.util.regex.Pattern.compile("^(\\d\\d?)/(\\d\\d?)/(\\d\\d)\\b").matcher(time);
            if (tg.find()) return java.time.LocalDate.of(2000 + Integer.parseInt(tg.group(3)), Integer.parseInt(tg.group(2)), Integer.parseInt(tg.group(1)));
        } catch (java.time.DateTimeException | NumberFormatException ignored) { }
        return null;
    }
    private static Entry entry(Block block) {
        String[] lines = block.raw.split("\n", -1);
        String head = lines[0].substring(2).trim();
        boolean note = head.startsWith("📝");
        String title = head.replaceFirst("^(🔗|📝|🖼|📎)\\s*", ""), url = "", time = "", type = "", tags = "", tldr = "", file = "", heading = "";
        java.util.regex.Matcher old = java.util.regex.Pattern.compile("^(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d) — (.*)$").matcher(title);
        if (old.matches()) { time = old.group(1); title = old.group(2); }
        StringBuilder why = new StringBuilder(), ideas = new StringBuilder(), body = new StringBuilder(), shared = new StringBuilder();
        StringBuilder current = null;
        boolean bodyPresent = false, continuation = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("    ") && current != null) {
                if (continuation) current.append('\n');
                current.append(line.substring(4));
                continuation = true;
                continue;
            }
            current = null; continuation = false;
            if (line.startsWith("  Why:")) { current = why; current.append(inline(line.substring(6))); continuation = true; }
            else if (line.startsWith("  Ideas:")) { current = ideas; current.append(inline(line.substring(8))); continuation = true; }
            else if (line.equals("  Text:")) { current = body; bodyPresent = true; }
            else if (line.equals("  Shared text:")) current = shared;
            else if (line.startsWith("  Type:")) type = inline(line.substring(7)).trim();
            else if (line.startsWith("  Tags:")) tags = inline(line.substring(7)).trim();
            else if (line.startsWith("  Title:")) heading = inline(line.substring(8)).trim();
            else if (line.startsWith("  TL;DR:")) tldr = inline(line.substring(8)).trim();
            else if (line.startsWith("  Captured:")) time = line.substring(11).trim();
            else {
                String t = line.trim();
                if (t.startsWith("<") && t.endsWith(">")) t = t.substring(1, t.length() - 1);
                java.util.regex.Matcher embed = java.util.regex.Pattern.compile("^!\\[\\[([^\\]]+)\\]\\]$").matcher(t);
                if (!note && file.isEmpty() && embed.matches()) file = embed.group(1);
                else if (!note && url.isEmpty() && t.matches("(?i)https?://\\S+")) url = t;
                else if (note && line.startsWith("  ")) {
                    if (bodyPresent) body.append('\n');
                    body.append(line.substring(2)); bodyPresent = true;
                }
            }
        }
        return new Entry(block.start, block.raw, title, url, time, why.toString(), ideas.toString(),
            bodyPresent ? body.toString() : note ? title : "", shared.toString(), note, type, tags, tldr, file, heading);
    }
    private static Block find(String text, String raw) {
        Block found = null;
        for (Block b : blocks(text)) if (b.raw.equals(raw)) {
            if (found != null) throw new IllegalStateException("Identical entries are ambiguous. Refresh and select the entry again.");
            found = b;
        }
        if (found == null) throw new IllegalStateException("That entry changed. Refresh and try again.");
        return found;
    }
    private static Block find(String text, Entry entry) {
        for (Block b : blocks(text)) if (b.start == entry.start && b.raw.equals(entry.raw)) return b;
        throw new IllegalStateException("That entry changed. Refresh and try again.");
    }
    public static String delete(String text, String raw) { return delete(text, find(text, raw)); }
    public static String delete(String text, Entry entry) { return delete(text, find(text, entry)); }
    private static String delete(String text, Block b) {
        int end = b.end;
        for (int i = 0; i < 2 && end < text.length() && text.charAt(end) == '\n'; i++) end++;
        return text.substring(0, b.start) + text.substring(end);
    }
    public static String setNotes(String text, String raw, String why, String ideas) { return setNotes(text, find(text, raw), why, ideas); }
    public static String setNotes(String text, Entry entry, String why, String ideas) { return setNotes(text, find(text, entry), why, ideas); }
    private static String setNotes(String text, Block b, String why, String ideas) {
        StringBuilder out = new StringBuilder();
        boolean skipping = false;
        for (String line : b.raw.split("\n", -1)) {
            if (line.startsWith("  Why:") || line.startsWith("  Ideas:")) { skipping = true; continue; }
            if (skipping && line.startsWith("    ")) continue;
            skipping = false;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        out.append(note("Why", why)).append(note("Ideas", ideas));
        return text.substring(0, b.start) + out + text.substring(b.end);
    }
    /** Replaces the model-written Type/Tags/TL;DR lines. Values must already be one clean line each. */
    public static String setEnrich(String text, String raw, String type, String tags, String tldr) { return setEnrich(text, raw, type, tags, tldr, ""); }
    public static String setEnrich(String text, String raw, String type, String tags, String tldr, String heading) {
        Block b = find(text, raw);
        StringBuilder out = new StringBuilder();
        for (String line : b.raw.split("\n", -1)) {
            if (line.startsWith("  Type:") || line.startsWith("  Tags:") || line.startsWith("  TL;DR:") || line.startsWith("  Title:")) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        out.append("\n  Type: ").append(type);
        if (!tags.isEmpty()) out.append("\n  Tags: ").append(tags);
        if (!tldr.isEmpty()) out.append("\n  TL;DR: ").append(tldr);
        if (!heading.isEmpty()) out.append("\n  Title: ").append(heading);
        return text.substring(0, b.start) + out + text.substring(b.end);
    }
    private static String inline(String value) { return value.startsWith(" ") ? value.substring(1) : value; }
    private static String note(String label, String value) {
        if (value == null || value.isEmpty()) return "";
        String[] lines = Capture.normalize(value).split("\n", -1);
        StringBuilder out = new StringBuilder("\n  " + label + ": " + lines[0]);
        for (int i = 1; i < lines.length; i++) out.append("\n    ").append(lines[i]);
        return out.toString();
    }
    /** Recovery requires the whole rendered entry at a supported block boundary. */
    public static boolean containsEntry(String text, String rendered) {
        String raw = rendered.endsWith("\n") ? rendered.substring(0, rendered.length() - 1) : rendered;
        for (Block b : blocks(text)) if (b.raw.equals(raw)) return true;
        return false;
    }
}
