package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.List;

/** Small block splitter for Scout only. Raw code and answer text remain unchanged for Copy. */
public final class ScoutMarkdown {
    private ScoutMarkdown() { }
    public static final class Block {
        public final String kind, text, language;
        public final int level, offset;
        public final boolean complete;
        public final List<List<String>> rows;
        Block(String kind, String text, String language, int level, int offset, boolean complete, List<List<String>> rows) {
            this.kind = kind; this.text = text; this.language = language; this.level = level; this.offset = offset; this.complete = complete; this.rows = rows;
        }
    }
    private static String plain(String line) { return line.replaceFirst("\\r?\\n$", ""); }
    private static String fence(String line) {
        String t = plain(line).stripLeading();
        if (!(t.startsWith("```") || t.startsWith("~~~"))) return "";
        char c = t.charAt(0); int n = 0; while (n < t.length() && t.charAt(n) == c) n++;
        return t.substring(0, n);
    }
    public static List<String> cells(String line) {
        String text = plain(line).trim();
        if (text.startsWith("|")) text = text.substring(1);
        if (text.endsWith("|") && !text.endsWith("\\|")) text = text.substring(0, text.length() - 1);
        List<String> out = new ArrayList<>(); StringBuilder cell = new StringBuilder();
        int ticks = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '|') { cell.append('|'); i++; continue; }
            if (c == '`') { int run = 1; while (i + run < text.length() && text.charAt(i + run) == '`') run++; if (ticks == 0) ticks = run; else if (ticks == run) ticks = 0; cell.append(text, i, i + run); i += run - 1; continue; }
            if (c == '|' && ticks == 0) { out.add(cell.toString().trim()); cell.setLength(0); } else cell.append(c);
        }
        out.add(cell.toString().trim()); return out;
    }
    private static boolean separator(String line) {
        List<String> row = cells(line);
        if (row.size() < 2) return false;
        for (String cell : row) if (!cell.matches(":?-{3,}:?")) return false;
        return true;
    }
    public static List<Block> parse(String raw) {
        String[] lines = raw.split("(?<=\\n)", -1);
        List<Block> out = new ArrayList<>(); int offset = 0;
        for (int i = 0; i < lines.length;) {
            String line = lines[i], t = plain(line).trim();
            if (t.isEmpty()) { offset += line.length(); i++; continue; }
            int start = offset;
            String marker = fence(line);
            if (!marker.isEmpty()) {
                String language = t.substring(marker.length()).trim();
                offset += line.length(); i++;
                StringBuilder body = new StringBuilder(); boolean complete = false;
                while (i < lines.length) {
                    String close = plain(lines[i]).stripLeading();
                    if (close.matches(java.util.regex.Pattern.quote(marker.charAt(0) + "") + "{" + marker.length() + ",}\\s*")) { offset += lines[i].length(); i++; complete = true; break; }
                    body.append(lines[i]); offset += lines[i].length(); i++;
                }
                out.add(new Block("code", body.toString(), language, 0, start, complete, List.of())); continue;
            }
            if (i + 1 < lines.length && t.contains("|") && separator(lines[i + 1])) {
                List<List<String>> rows = new ArrayList<>(); rows.add(cells(line));
                offset += line.length() + lines[i + 1].length(); i += 2;
                while (i < lines.length && plain(lines[i]).contains("|") && !plain(lines[i]).isBlank() && fence(lines[i]).isEmpty()) {
                    rows.add(cells(lines[i])); offset += lines[i].length(); i++;
                }
                out.add(new Block("table", "", "", 0, start, true, rows)); continue;
            }
            if (t.matches("#{1,6} .*")) {
                int level = t.indexOf(' ');
                out.add(new Block("heading", t.substring(level + 1), "", level, start, true, List.of()));
                offset += line.length(); i++; continue;
            }
            StringBuilder prose = new StringBuilder();
            do {
                prose.append(lines[i]); offset += lines[i].length(); i++;
            } while (i < lines.length && !plain(lines[i]).isBlank() && fence(lines[i]).isEmpty() && !plain(lines[i]).trim().matches("#{1,6} .*" ) && !(i + 1 < lines.length && separator(lines[i + 1])));
            out.add(new Block("prose", prose.toString(), "", 0, start, true, List.of()));
        }
        return out;
    }
}
