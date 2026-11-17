package me.venkatesh.saveobsidian;

/** Pure destination rules and idempotent, attributed capture insertion. */
public final class DirectText {
    public static boolean allowed(String path) {
        if (path == null || !(path.startsWith("Knowledge/") || path.startsWith("Sources/")) || !path.endsWith(".md")) return false;
        for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith(".") || part.contains("\\") || part.contains(":")) return false;
        String name = path.substring(path.lastIndexOf('/') + 1);
        return !name.equalsIgnoreCase("Inbox.md") && !name.equalsIgnoreCase("index.md") && !name.equalsIgnoreCase("log.md")
            && !Layout.conflict(name) && !path.toLowerCase(java.util.Locale.ROOT).contains("/attachments/");
    }
    /** 0: already verified, 1: safe to attempt, otherwise preserve recovery data. */
    public static int recovery(String before, String current, String after) throws java.io.IOException {
        if (after.equals(current)) return 0;
        if (java.util.Objects.equals(before, current)) return 1;
        throw new java.io.IOException("Destination changed during save. Capture kept; inspect the note before retrying.");
    }
    public static String marker(String id) {
        if (!id.matches("[a-zA-Z0-9-]{1,80}")) throw new IllegalArgumentException("Invalid capture identity");
        return "<!-- sparkbox:" + id + " -->";
    }
    public static String append(String before, String text, String time, String id) {
        String mark = marker(id);
        if (before.contains(mark)) return before;
        boolean crlf = before.contains("\r\n");
        String normal = before.replace("\r\n", "\n");
        java.util.List<String> lines = java.util.Arrays.asList(normal.split("\n", -1));
        java.util.List<Boolean> fences = NoteEdit.fenced(lines);
        int start = NoteEdit.find(lines, fences, "My Notes"), stop = start < 0 ? -1 : NoteEdit.end(lines, fences, start);
        int offset = normal.length();
        if (stop >= 0 && stop < lines.size()) { offset = 0; for (int i = 0; i < stop; i++) offset += lines.get(i).length() + 1; }
        String prefix = normal.substring(0, offset);
        String block = (prefix.endsWith("\n\n") ? "" : prefix.endsWith("\n") ? "\n" : "\n\n")
            + (start < 0 ? "## My Notes\n\n" : "") + mark + "\nCaptured: " + time + " · via Sparkbox\n\n" + text.replace("\r\n", "\n") + "\n\n";
        String out = prefix + block + normal.substring(offset);
        return crlf ? out.replace("\n", "\r\n") : out;
    }
    public static String newNote(String title, String text, String time, String id) {
        String safe = IngestText.safe(title);
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) throw new IllegalArgumentException("Enter a note title");
        return append("---\ntype: topic\nconfidence: low\nvia: Sparkbox\ncaptured: \"" + time.replace("\"", "") + "\"\n---\n\n# " + safe + "\n", text, time, id);
    }
}
