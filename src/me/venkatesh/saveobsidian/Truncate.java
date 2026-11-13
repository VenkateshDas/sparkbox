package me.venkatesh.saveobsidian;

import java.nio.charset.StandardCharsets;

/**
 * Port of pi packages/coding-agent/src/core/tools/truncate.ts @ 3e23dd9: a tool result is cut by lines and by bytes, whichever limit
 * is hit first, never in the middle of a line. head keeps the start (reads, search), tail keeps the end (logs). Pure Java.
 */
public final class Truncate {
    public static final int LINES = 2000, BYTES = 50 * 1024;
    private Truncate() { }

    public static final class Cut {
        public final String text;
        public final boolean truncated;
        public final int totalLines, shownLines;
        Cut(String text, boolean truncated, int totalLines, int shownLines) { this.text = text; this.truncated = truncated; this.totalLines = totalLines; this.shownLines = shownLines; }
    }

    public static Cut head(String s) { return head(s, LINES, BYTES); }
    public static Cut tail(String s) { return tail(s, LINES, BYTES); }

    public static Cut head(String s, int maxLines, int maxBytes) {
        String[] lines = s.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int bytes = 0, n = 0;
        while (n < lines.length && n < maxLines) {
            int add = lines[n].getBytes(StandardCharsets.UTF_8).length + (n > 0 ? 1 : 0);
            if (bytes + add > maxBytes) break;
            if (n > 0) out.append('\n');
            out.append(lines[n]);
            bytes += add;
            n++;
        }
        return new Cut(out.toString(), n < lines.length, lines.length, n);
    }

    public static Cut tail(String s, int maxLines, int maxBytes) {
        String[] lines = s.split("\n", -1);
        int bytes = 0, n = 0;
        while (n < lines.length && n < maxLines) {
            int add = lines[lines.length - 1 - n].getBytes(StandardCharsets.UTF_8).length + (n > 0 ? 1 : 0);
            if (bytes + add > maxBytes) break;
            bytes += add;
            n++;
        }
        StringBuilder out = new StringBuilder();
        for (int i = lines.length - n; i < lines.length; i++) { if (i > lines.length - n) out.append('\n'); out.append(lines[i]); }
        return new Cut(out.toString(), n < lines.length, lines.length, n);
    }

    /** The cut text with a footer that tells the model what was left out and how to get it ("how" is tool specific, e.g. "Use read with offset=2001"). */
    public static String withFooter(Cut cut, boolean fromStart, String how) {
        if (!cut.truncated) return cut.text;
        String range = fromStart ? "1-" + cut.shownLines : (cut.totalLines - cut.shownLines + 1) + "-" + cut.totalLines;
        return cut.text + "\n\n[Showing lines " + range + " of " + cut.totalLines + ". " + how + "]";
    }
}
