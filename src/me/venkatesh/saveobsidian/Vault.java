package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The knowledge base as notes and links. Pure Java so the JVM checks cover it; reading files lives in VaultStore. */
public final class Vault {
    /** [[target]] or [[target|alias]]; the pipe may be escaped as \| inside Markdown tables. */
    public static final Pattern LINK = Pattern.compile("(!?)\\[\\[([^\\]\\n]+)\\]\\]");
    private static final Pattern KEY = Pattern.compile("^([A-Za-z0-9_-]+):\\s*(.*)$");
    private static final String[] TOPIC_KEYS = {"topics", "people", "related-topics"};
    static final int DIGEST = 2000;

    public static final class Note {
        public final String path, title, type, description, updated, digest;
        public final List<String> topics, links;
        public final long mtime;
        /** index.md and log.md: browsable and readable, but left out of search ranking. */
        public final boolean catalog;

        Note(String path, long mtime, String title, String type, String description, String updated, String digest, List<String> topics, List<String> links) {
            this.path = path; this.mtime = mtime; this.title = title; this.type = type; this.description = description;
            this.updated = updated; this.digest = digest; this.topics = topics; this.links = links;
            String name = name(path);
            this.catalog = name.equals("index") || name.equals("log");
        }

        public String folder() { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
        /** [[Knowledge/Topics/X|X]]: the title with its path, tappable in the app and valid in Obsidian. */
        public String cite() { return "[[" + stripMd(path) + "|" + title.replaceAll("[\\[\\]|]", " ").trim() + "]]"; }

        /** Every word of the query appears in the title, path, topics or the digest (case-insensitive). */
        public boolean contains(String query) {
            String hay = (title + "\n" + path + "\n" + type + "\n" + topics + "\n" + digest).toLowerCase(Locale.ROOT);
            for (String word : query.toLowerCase(Locale.ROOT).split("\\s+")) if (!word.isEmpty() && !hay.contains(word)) return false;
            return true;
        }
    }

    static String name(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return stripMd(file).toLowerCase(Locale.ROOT);
    }

    static String stripMd(String s) { return s.toLowerCase(Locale.ROOT).endsWith(".md") ? s.substring(0, s.length() - 3) : s; }

    /** The note a link points at, without alias, heading or extension: "Knowledge/Topics/X". */
    public static String target(String raw) {
        String t = raw;
        int pipe = t.indexOf('|');
        if (pipe >= 0) t = t.substring(0, pipe);
        if (t.endsWith("\\")) t = t.substring(0, t.length() - 1); // [[X\|X]] inside a table cell
        int hash = Math.min(t.indexOf('#') < 0 ? t.length() : t.indexOf('#'), t.indexOf('^') < 0 ? t.length() : t.indexOf('^'));
        return stripMd(t.substring(0, hash).trim().replace('\\', '/'));
    }

    /** What a link shows: its alias, else the last path segment. */
    public static String label(String raw) {
        int pipe = raw.indexOf('|');
        if (pipe >= 0 && pipe + 1 < raw.length()) return raw.substring(pipe + 1).trim();
        String t = target(raw);
        return t.substring(t.lastIndexOf('/') + 1);
    }

    /** Frontmatter keys and the offset where the body starts. Unknown or broken frontmatter yields an empty map. */
    static Map<String, String> frontmatter(String text, int[] bodyAt) {
        Map<String, String> map = new LinkedHashMap<>();
        bodyAt[0] = 0;
        if (!text.startsWith("---\n") && !text.startsWith("---\r\n")) return map;
        int at = text.indexOf('\n') + 1;
        String last = null;
        while (at < text.length()) {
            int nl = text.indexOf('\n', at), end = nl < 0 ? text.length() : nl;
            String line = text.substring(at, end).replaceAll("\\s+$", "");
            at = nl < 0 ? text.length() : nl + 1;
            if (line.equals("---")) { bodyAt[0] = at; return map; }
            Matcher m = KEY.matcher(line);
            if (m.matches()) { last = m.group(1).toLowerCase(Locale.ROOT); map.put(last, unquote(m.group(2).trim())); }
            else if (last != null && !line.trim().isEmpty()) map.put(last, (map.get(last) + " " + line.trim().replaceFirst("^-\\s*", "")).trim());
        }
        map.clear(); // never closed: treat the whole file as body
        return map;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'') && v.charAt(v.length() - 1) == v.charAt(0)) return v.substring(1, v.length() - 1);
        return v;
    }

    /** Names listed under a frontmatter key: wikilinks if there are any, else comma-separated words. */
    private static void names(String value, List<String> out) {
        Matcher m = LINK.matcher(value);
        boolean any = false;
        while (m.find()) { any = true; add(out, label(m.group(2))); }
        if (any) return;
        for (String part : value.replaceAll("[\\[\\]\"']", "").split(",")) add(out, part.trim());
    }

    private static void add(List<String> list, String s) { if (!s.isEmpty() && !list.contains(s)) list.add(s); }

    public static Note parse(String path, long mtime, String text) {
        if (text.startsWith("﻿")) text = text.substring(1);
        text = text.replace("\r\n", "\n");
        int[] bodyAt = new int[1];
        Map<String, String> fm = frontmatter(text, bodyAt);
        String body = text.substring(bodyAt[0]);

        String title = "";
        String description = fm.containsKey("description") ? fm.get("description") : "";
        boolean fence = false, claims = false, claimed = false;
        String prose = "";
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.startsWith("```")) { fence = !fence; continue; }
            if (fence) continue;
            if (title.isEmpty() && line.startsWith("# ")) { title = t.substring(2).trim(); continue; }
            if (t.startsWith("#")) claims = t.toLowerCase(Locale.ROOT).contains("key claims");
            else if (claims && !claimed && t.startsWith("- ") && t.length() > 12) { if (description.isEmpty()) description = t.substring(2).trim(); claimed = true; } // a Source's first claim says more than its capture line
            else if (prose.isEmpty() && !title.isEmpty() && t.length() > 30 && "#>|-*!<`".indexOf(t.charAt(0)) < 0 && !t.startsWith("Original capture") && !t.startsWith("Retrieved on")) prose = t;
        }
        if (description.isEmpty()) description = prose;
        if (title.isEmpty()) title = stripMd(path.substring(path.lastIndexOf('/') + 1)).replaceFirst("^\\d{4}-\\d{2}-\\d{2}\\s*-\\s*", ""); // the date shows as its own field
        title = plain(title);
        description = plain(description);
        if (description.length() > 200) description = description.substring(0, 200);

        List<String> topics = new ArrayList<>();
        for (String key : TOPIC_KEYS) if (fm.containsKey(key)) names(fm.get(key), topics);

        List<String> links = new ArrayList<>();
        Matcher m = LINK.matcher(text);
        while (m.find()) {
            if (m.group(1).equals("!")) continue; // embeds are images and transclusions, not references
            String target = target(m.group(2));
            if (!target.isEmpty() && !links.contains(target)) links.add(target);
        }

        String updated = "";
        for (String key : new String[]{"last-updated", "ingested", "captured"}) if (updated.isEmpty() && fm.containsKey(key)) updated = fm.get(key);

        String flat = plain(body).replaceAll("\\s+", " ").trim();
        String digest = (title + ". " + description + " " + (fm.containsKey("type") ? fm.get("type") + ". " : "") + (topics.isEmpty() ? "" : "Topics: " + topics + ". ") + flat).trim();
        if (digest.length() > DIGEST) digest = digest.substring(0, DIGEST);
        return new Note(path, mtime, title, fm.containsKey("type") ? fm.get("type") : "", description, updated, digest, topics, links);
    }

    /** Text with [[a|b]] shown as b and bold/code marks dropped, for titles, previews and embedding. */
    static String plain(String s) {
        Matcher m = LINK.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(label(m.group(2))));
        m.appendTail(out);
        return out.toString().replaceAll("[*`]", "").replace("\\|", " ");
    }

    public static Graph build(Collection<Note> notes) { return new Graph(notes); }

    /** Notes keyed by path and by name, with every link resolved once. */
    public static final class Graph {
        private final Map<String, Note> byPath = new HashMap<>();
        private final Map<String, List<Note>> byName = new HashMap<>();
        private final Map<Note, List<Note>> out = new HashMap<>(), in = new HashMap<>();
        public final List<Note> notes;
        public int links, unresolved, ambiguous;

        Graph(Collection<Note> all) {
            notes = new ArrayList<>(all);
            for (Note n : notes) {
                byPath.put(stripMd(n.path).toLowerCase(Locale.ROOT), n);
                List<Note> same = byName.get(name(n.path));
                if (same == null) byName.put(name(n.path), same = new ArrayList<>());
                same.add(n);
            }
            for (Note n : notes) { out.put(n, new ArrayList<Note>()); in.put(n, new ArrayList<Note>()); }
            for (Note n : notes) for (String link : n.links) {
                links++;
                Note to = resolve(link);
                if (to == null) { List<Note> same = byName.get(name(link)); if (same != null && same.size() > 1 && link.indexOf('/') < 0) ambiguous++; else unresolved++; continue; }
                if (to == n || out.get(n).contains(to)) continue;
                out.get(n).add(to);
                in.get(to).add(n);
            }
        }

        /** Exact path first, then a name that only one note has. An ambiguous name stays unresolved rather than guessed. */
        public Note resolve(String target) {
            String t = stripMd(target.replace('\\', '/')).toLowerCase(Locale.ROOT);
            Note exact = byPath.get(t);
            if (exact != null) return exact;
            List<Note> same = byName.get(name(t));
            return same != null && same.size() == 1 && t.indexOf('/') < 0 ? same.get(0) : null;
        }

        public Note byPath(String path) { return byPath.get(stripMd(path).toLowerCase(Locale.ROOT)); }
        public List<Note> out(Note n) { List<Note> l = out.get(n); return l == null ? new ArrayList<Note>() : l; }
        public List<Note> in(Note n) { List<Note> l = in.get(n); return l == null ? new ArrayList<Note>() : l; }

        /** Notes with no links in or out. */
        public List<Note> orphans() {
            List<Note> found = new ArrayList<>();
            for (Note n : notes) if (!n.catalog && out(n).isEmpty() && in(n).isEmpty()) found.add(n);
            return found;
        }
    }
}
