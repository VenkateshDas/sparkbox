package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Surgical edits of a note's text: replace or add one "## Section" and leave every other byte alone. Each edit takes the freshly read text, so it can be
 * applied again after a conflict, and applying it twice changes nothing. Headings inside code fences are ignored. Pure Java so the JVM checks cover it.
 */
public final class NoteEdit {
    private NoteEdit() { }

    private static final Pattern EDITED = Pattern.compile("(?m)^Edited:\\s*(\\d{4}-\\d{2}-\\d{2})\\s*$");

    static boolean heading(String line) { return line.startsWith("## ") || line.equals("##"); }

    static List<Boolean> fenced(List<String> lines) {
        List<Boolean> out = new ArrayList<>();
        boolean in = false;
        for (String l : lines) {
            String t = l.trim();
            boolean marker = t.startsWith("```") || t.startsWith("~~~");
            out.add(in || marker);
            if (marker) in = !in;
        }
        return out;
    }

    /** Index of the "## heading" line (case-insensitive), or -1. */
    static int find(List<String> lines, List<Boolean> fence, String heading) {
        for (int i = 0; i < lines.size(); i++)
            if (!fence.get(i) && heading(lines.get(i)) && lines.get(i).substring(2).trim().equalsIgnoreCase(heading)) return i;
        return -1;
    }

    /** One past the last line of the section that starts at index i. */
    static int end(List<String> lines, List<Boolean> fence, int i) {
        int j = i + 1;
        while (j < lines.size() && !(heading(lines.get(j)) && !fence.get(j))) j++;
        return j;
    }

    /**
     * Sets the body of "## heading". An existing section keeps its place. A missing one is added after the first of the "after" headings that
     * exists, else at the end. An empty body removes the section.
     */
    public static String setSection(String text, String heading, String body, String... after) {
        boolean crlf = text.contains("\r\n");
        List<String> lines = new ArrayList<>(Arrays.asList(text.replace("\r\n", "\n").split("\n", -1)));
        List<Boolean> fence = fenced(lines);
        String clean = body.replace("\r\n", "\n").trim();
        int at = find(lines, fence, heading);
        List<String> block = new ArrayList<>();
        if (!clean.isEmpty()) { block.add("## " + heading); block.addAll(Arrays.asList(clean.split("\n", -1))); block.add(""); }
        if (at >= 0) {
            int stop = end(lines, fence, at);
            List<String> next = new ArrayList<>(lines.subList(0, at));
            next.addAll(block);
            // a section that ran to the end of the file keeps the file's own trailing newline
            List<String> tail = new ArrayList<>(lines.subList(stop, lines.size()));
            if (stop >= lines.size() && !block.isEmpty()) { tail = new ArrayList<>(); if (!next.get(next.size() - 1).isEmpty()) next.add(""); }
            next.addAll(tail);
            lines = next;
        } else if (!block.isEmpty()) {
            int pos = -1;
            for (String a : after) {
                int i = find(lines, fence, a);
                if (i >= 0) { pos = end(lines, fence, i); break; }
            }
            if (pos < 0) {
                pos = lines.size();
                if (pos > 0 && lines.get(pos - 1).isEmpty()) pos--; // before the file's final newline
            }
            List<String> next = new ArrayList<>(lines.subList(0, pos));
            if (!next.isEmpty() && !next.get(next.size() - 1).isEmpty()) next.add("");
            next.addAll(block);
            next.addAll(lines.subList(pos, lines.size()));
            while (next.size() > 1 && next.get(next.size() - 1).isEmpty() && next.get(next.size() - 2).isEmpty()) next.remove(next.size() - 1); // one final newline, not a blank line
            lines = next;
        }
        String out = String.join("\n", lines);
        if (!out.endsWith("\n")) out += "\n";
        return crlf ? out.replace("\n", "\r\n") : out;
    }

    /** Bookmark metadata also works on personal notes without frontmatter. */
    public static String bookmark(String text, boolean on) {
        if (!text.startsWith("---\n") && !text.startsWith("---\r\n")) {
            String nl = text.contains("\r\n") ? "\r\n" : "\n";
            return on ? "---" + nl + "kept: true" + nl + "---" + nl + text : text;
        }
        String out = setKey(text, "kept", on ? "true" : null);
        if (on && out.equals(text) && !text.contains("kept: true")) throw new IllegalArgumentException("Repair malformed frontmatter before bookmarking.");
        return out;
    }

    /** The text of a "## My take" body without its "Edited:" line. */
    public static String takeText(String section) { return EDITED.matcher(section).replaceAll("").trim(); }

    /** The date in the body's "Edited: yyyy-MM-dd" line, or "". */
    public static String takeEdited(String section) {
        Matcher m = EDITED.matcher(section);
        return m.find() ? m.group(1) : "";
    }

    /** A take body ready for setSection: the text and an Edited line, or "" when the text is empty. */
    public static String takeBody(String text, String isoDate) { return text.trim().isEmpty() ? "" : text.trim() + "\n\nEdited: " + isoDate; }

    /**
     * Sets one frontmatter key (added at the end of the frontmatter when missing, removed when value is null). A note with no frontmatter is returned
     * unchanged: the app never invents one for a note it did not write.
     */
    public static String setKey(String text, String key, String value) {
        boolean crlf = text.contains("\r\n");
        List<String> lines = new ArrayList<>(Arrays.asList(text.replace("\r\n", "\n").split("\n", -1)));
        if (lines.isEmpty() || !lines.get(0).equals("---")) return text;
        int close = -1;
        for (int i = 1; i < lines.size(); i++) if (lines.get(i).equals("---")) { close = i; break; }
        if (close < 0) return text;
        int at = -1, stop = -1;
        for (int i = 1; i < close; i++) {
            if (lines.get(i).startsWith(key + ":")) {
                at = i;
                stop = i + 1;
                while (stop < close && (lines.get(stop).startsWith(" ") || lines.get(stop).startsWith("- "))) stop++; // a wrapped value or list under the key
                break;
            }
        }
        List<String> next = new ArrayList<>(lines.subList(0, at >= 0 ? at : close));
        if (value != null) next.add(key + ": " + value.replace("\n", " "));
        next.addAll(lines.subList(at >= 0 ? stop : close, lines.size()));
        String out = String.join("\n", next);
        return crlf ? out.replace("\n", "\r\n") : out;
    }
}
