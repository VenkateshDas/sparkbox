package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text side of Ingest: pure string edits that follow the vault's own formats (Agents.md). Existing pages are only ever added to:
 * rows, bullets and front-matter counters. Nothing is rewritten. Pure Java so the JVM checks cover it.
 */
public final class IngestText {
    public static final String[] CATEGORIES = {"AI Systems", "Models & Training", "Data & Knowledge", "Developer Tools", "Health & Biology", "People & Society", "Research Workflow"};

    /** One of the vault's subject folders, matched ignoring case; AI Systems when the model named something else. */
    public static String category(String said) {
        for (String c : CATEGORIES) if (c.equalsIgnoreCase(said == null ? "" : said.trim())) return c;
        return CATEGORIES[0];
    }

    /** A typed or spoken note shorter than this has nothing worth a Source note. */
    static final int MIN_NOTE = 40;

    public static boolean eligible(InboxFile.Entry entry) {
        return entry.text ? entry.body.trim().length() >= MIN_NOTE : entry.file.isEmpty() ? !entry.url.isEmpty() : !attachmentKind(entry.file).isEmpty();
    }

    static String attachmentKind(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        if (Enrich.IMAGES.contains(ext)) return "image";
        if (Enrich.VIDEOS.contains(ext)) return "video";
        switch (ext) {
            case "pdf": return "pdf";
            case "txt": case "md": case "csv": case "tsv": case "json": case "log": return "text";
            default: return "";
        }
    }

    static boolean knownPdf(InboxFile.Entry entry) {
        if (!entry.file.isEmpty()) return "pdf".equals(attachmentKind(entry.file));
        return !Capture.arxivPdfUrl(entry.url).isEmpty() || Capture.identity(entry.url).split("\\?", 2)[0].toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
    }

    static final int PDF_BYTES = 40 * 1024 * 1024;
    static final String PDF_VISUAL_HEADING = "\n\n### Figure and scan notes (on-device AI)\n";

    /** Keep room for figure evidence even when the paper text exceeds the model's prompt budget. */
    public static String pdfPrompt(String text, int max) {
        if (text.length() <= max) return text;
        int visualAt = text.lastIndexOf(PDF_VISUAL_HEADING);
        if (visualAt < 0) return text.substring(0, max);
        String visual = text.substring(visualAt, Math.min(text.length(), visualAt + max / 3));
        return text.substring(0, Math.min(visualAt, max - visual.length())) + visual;
    }

    /** Sample the beginning, middle and end for a local summary, with a separate budget for visual evidence. */
    public static String pdfSummaryPrompt(String text, int max) {
        if (text.length() <= max) return text;
        int start = text.lastIndexOf(PDF_VISUAL_HEADING);
        String visual = start < 0 ? "" : text.substring(start, Math.min(text.length(), start + max / 4));
        String pages = start < 0 ? text : text.substring(0, start);
        String gap = "\n\n[Later excerpt]\n";
        int budget = max - visual.length() - 2 * gap.length();
        if (budget < 100 || pages.length() <= budget) return pdfPrompt(text, max);
        int middle = budget / 4, tail = budget / 4, head = budget - middle - tail;
        int halfway = (pages.length() - middle) / 2;
        return pages.substring(0, head) + gap + pages.substring(halfway, halfway + middle)
            + gap + pages.substring(pages.length() - tail) + visual;
    }

    static String[] pdfTags(org.json.JSONObject plan) { return mediaTags("document", plan); }

    static String[] mediaTags(String kind, org.json.JSONObject plan) {
        if (plan == null) return null;
        java.util.List<String> tags = new java.util.ArrayList<>();
        org.json.JSONArray values = plan.optJSONArray("tags");
        for (int i = 0; values != null && i < values.length(); i++) tags.add(values.optString(i));
        String[] clean = Enrich.clean(kind, tags, plan.optString("tldr"));
        return new String[]{clean[0], clean[1], clean[2], Capture.clean(plan.optString("title"))};
    }

    /** A title usable as a file name and as a [[link]]: none of \ / : * ? " < > | # [ ] ^. */
    public static String safe(String s) {
        String t = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|#\\[\\]^]", " ").replaceAll("\\s+", " ").trim();
        return t.length() > 80 ? t.substring(0, 80).trim() : t;
    }

    static String cell(String s) { return (s == null ? "" : s).replaceAll("\\s+", " ").replace("|", "\\|").trim(); }

    public static String claimRow(String claim, String sourceLink, String year, String confidence) {
        return "| " + cell(claim) + " | " + sourceLink + " | " + year + " | " + cell(confidence.isEmpty() ? "low" : confidence) + " |";
    }

    private static String quoteList(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (String n : names) out.append(out.length() == 0 ? "" : ", ").append("'[[").append(n).append("]]'");
        return "[" + out + "]";
    }

    /** The Source note: front matter, then Raw Content, Why, Ideas and Claude's Extraction as Agents.md describes. */
    public static String sourceNote(String title, String url, String author, String captured, List<String> topics, List<String> people,
                                    String why, String ideas, String raw, List<String> claims, List<String> flags, String lane) {
        StringBuilder s = new StringBuilder("---\ntype: source\nsource: \"" + url.replace("\"", "%22") + "\"\nauthor: \"" + author.replace("\"", "'") + "\"\ncaptured: " + captured
            + "\nvia: Sparkbox\n" + (lane == null || lane.isEmpty() ? "" : "lane: " + lane.toLowerCase(java.util.Locale.ROOT) + "\n") + "topics: " + quoteList(topics) + "\npeople: " + quoteList(people) + "\nprocessed: true\n---\n\n# " + title + "\n");
        if (!raw.isEmpty()) s.append("\n## Raw Content\n").append(raw).append('\n');
        if (!why.trim().isEmpty()) s.append("\n## Why I captured this\n").append(why.trim()).append('\n');
        if (!ideas.trim().isEmpty()) s.append("\n## My ideas\n").append(ideas.trim()).append('\n');
        s.append("\n## Claude's Extraction\n\n### Key claims\n");
        for (String c : claims) s.append("- ").append(c.replaceAll("\\s+", " ").trim()).append('\n');
        s.append("\n### Topics identified\n");
        for (String t : topics) s.append("- [[").append(t).append("]]\n");
        s.append("\n### People mentioned\n");
        if (people.isEmpty()) s.append("- None identified\n");
        for (String p : people) s.append("- [[").append(p).append("]]\n");
        s.append("\n### Relevance flags\n");
        if (flags.isEmpty()) s.append("- None\n");
        for (String f : flags) s.append("- ").append(f.replaceAll("\\s+", " ").trim()).append('\n');
        return s.toString();
    }

    public static String newTopic(String title, String core, List<String> claimRows, List<String> related, List<String> questions, String sourceLink, String today) {
        StringBuilder s = new StringBuilder("---\ntype: topic\ndescription: " + title + "\nlast-updated: " + today + "\nsource-count: 1\nconfidence: low\nrelated-topics: " + quoteList(related)
            + "\nreferenced-in: []\n---\n\n## Core Mechanism\n" + core.trim() + "\n\n## Key Claims\n| Claim | Source | Year | Confidence |\n|---|---|---|---|\n");
        for (String r : claimRows) s.append(r).append('\n');
        s.append("\n## Contradictions / Open Questions\n");
        if (questions.isEmpty()) s.append("- None recorded yet.\n");
        for (String q : questions) s.append("- ").append(q.replaceAll("\\s+", " ").trim()).append('\n');
        s.append("\n## Sources that built this page\n- ").append(sourceLink).append("\n\n## Referenced in this vault\n");
        return s.toString();
    }

    /** Adds this source's claims, open questions and source link to an existing Topic, and bumps its counters. */
    public static String updateTopic(String page, List<String> claimRows, List<String> questions, String sourceLink, String today) {
        String p = setFront(page, "last-updated", today);
        p = bumpFront(p, "source-count", 1);
        p = addTableRows(p, "## Key Claims", claimRows, "| Claim | Source | Year | Confidence |\n|---|---|---|---|");
        List<String> q = new ArrayList<>();
        for (String x : questions) q.add("- " + x.replaceAll("\\s+", " ").trim());
        if (!q.isEmpty()) p = addBullets(p, "## Contradictions / Open Questions", q);
        return addBullets(p, "## Sources that built this page", java.util.Collections.singletonList("- " + sourceLink));
    }

    public static String newPerson(String name, String role, String sourceLink) {
        String field = role.replaceAll("\\s+", " ").trim();
        if (field.isEmpty()) field = "Captured author / coauthor";
        else if (field.matches("(?s).*(: | #|^[^A-Za-z0-9(]).*") || field.endsWith(":")) field = "\"" + field.replace("\\", " ").replace("\"", "'") + "\""; // a role from a web page must not break the front matter
        return "---\ntype: person\nfield: " + field + "\nkey-works: []\nappears-in: ['" + sourceLink + "']\n---\n\n## Who They Are\nCaptured author / coauthor in "
            + sourceLink + ". Biography unverified.\n\n## Key Ideas\n- None recorded yet.\n\n## Where they appear in this vault\n- " + sourceLink + "\n";
    }

    public static String updatePerson(String page, String sourceLink) {
        return addBullets(page, "## Where they appear in this vault", java.util.Collections.singletonList("- " + sourceLink));
    }

    // ---- indexes and log ----

    /** Knowledge/index.md: header counts and date, new Topic and People rows, and the Sources and date of updated Topics. */
    public static String knowledgeIndex(String text, List<String> newTopicRows, List<String> newPeopleRows, List<String> updatedTopics, int newSources, String today) {
        String t = text;
        Matcher m = Pattern.compile("\\*Last updated: [0-9-]+ \\| (\\d+) topic pages \\| (\\d+) people pages \\| (\\d+) source records").matcher(t);
        if (m.find()) t = t.substring(0, m.start()) + "*Last updated: " + today + " | " + (Integer.parseInt(m.group(1)) + newTopicRows.size()) + " topic pages | "
            + (Integer.parseInt(m.group(2)) + newPeopleRows.size()) + " people pages | " + (Integer.parseInt(m.group(3)) + newSources) + " source records" + t.substring(m.end());
        t = addAfterLastRow(t, "| [[Knowledge/Topics/", newTopicRows);
        t = addAfterLastRow(t, "| [[Knowledge/People/", newPeopleRows);
        for (String title : updatedTopics) {
            Matcher row = Pattern.compile("(?m)^(\\| \\[\\[Knowledge/Topics/" + Pattern.quote(title) + "\\\\\\|.*\\| )(\\d+)( \\| )[0-9-]+( \\|)\\s*$").matcher(t);
            if (row.find()) t = t.substring(0, row.start()) + row.group(1) + (Integer.parseInt(row.group(2)) + 1) + row.group(3) + today + row.group(4) + t.substring(row.end());
        }
        return t;
    }

    /** Sources/index.md: one more note in this category and one more record in total. */
    public static String sourcesIndex(String text, String category) {
        String t = text;
        Matcher m = Pattern.compile("(\\[\\[(?:Knowledge/)?Sources/" + Pattern.quote(category) + "/index\\|" + Pattern.quote(category) + "\\]\\] — )(\\d+)( notes)").matcher(t);
        if (m.find()) t = t.substring(0, m.start()) + m.group(1) + (Integer.parseInt(m.group(2)) + 1) + m.group(3) + t.substring(m.end());
        m = Pattern.compile("\\*(\\d+)( source records)").matcher(t);
        if (m.find()) t = t.substring(0, m.start()) + "*" + (Integer.parseInt(m.group(1)) + 1) + m.group(2) + t.substring(m.end());
        return t;
    }

    /** Sources/<category>/index.md: the new note, once, at the end of the list. */
    public static String categoryIndex(String text, String sourceLink) {
        if (text.contains(sourceLink)) return text;
        return text + (text.endsWith("\n") ? "" : "\n") + "- " + sourceLink + "\n";
    }

    public static String logLine(String sourceLink) { return "- [INGEST] " + sourceLink + " · complete.\n"; }

    public static String appendLine(String text, String line) { return text + (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + line; }

    // ---- entities ----

    public static final String[] ENTITY_KINDS = {"model", "tool", "org", "dataset", "paper", "other"};

    /** One of the entity kinds, "other" when the model named something else. */
    public static String entityKind(String said) {
        String k = said == null ? "" : said.trim().toLowerCase(java.util.Locale.ROOT);
        for (String kind : ENTITY_KINDS) if (kind.equals(k)) return kind;
        return "other";
    }

    /** A new Entity page: what it is, and the one source it appears in so far. */
    public static String newEntity(String name, String kind, String what, String sourceLink, String today) {
        return "---\ntype: entity\nkind: " + entityKind(kind) + "\nappears-in: ['" + sourceLink + "']\nlast-updated: " + today + "\n---\n\n# " + name + "\n\n## What it is\n"
            + (what == null || what.trim().isEmpty() ? "A " + entityKind(kind) + " named in the captured sources." : what.replaceAll("\\s+", " ").trim()) + "\n\n## Where it appears\n- " + sourceLink + "\n";
    }

    /** The page with one more source: its link under "Where it appears" and in appears-in, last-updated moved. Unchanged when the source is already there. */
    public static String updateEntity(String page, String sourceLink, String today) {
        if (page.contains(sourceLink)) return page;
        String out = page;
        Matcher m = Pattern.compile("(?m)^appears-in:\\s*\\[(.*)\\]\\s*$").matcher(out);
        if (m.find()) out = out.substring(0, m.start()) + "appears-in: [" + (m.group(1).trim().isEmpty() ? "" : m.group(1).trim() + ", ") + "'" + sourceLink + "']" + out.substring(m.end());
        out = setFront(out, "last-updated", today);
        return addBullets(out, "## Where it appears", java.util.Collections.singletonList("- " + sourceLink));
    }

    /** One line under "## Entities" in Knowledge/index.md (the section is created once); unchanged when the entity is already listed. */
    public static String entityIndex(String index, String entityLink, String kind, String what) {
        if (index.contains(entityLink)) return index;
        String line = "- " + entityLink + " (" + entityKind(kind) + ")" + (what == null || what.trim().isEmpty() ? "" : " — " + what.replaceAll("\\s+", " ").trim());
        return addBullets(index, "## Entities", java.util.Collections.singletonList(line));
    }

    // ---- small Markdown helpers ----

    static String setFront(String page, String key, String value) {
        int end = frontEnd(page);
        if (end < 0) return page;
        String front = page.substring(0, end);
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + ":.*$").matcher(front);
        front = m.find() ? front.substring(0, m.start()) + key + ": " + value + front.substring(m.end()) : front + (front.endsWith("\n") ? "" : "\n") + key + ": " + value;
        return front + page.substring(end);
    }

    static String bumpFront(String page, String key, int by) {
        int end = frontEnd(page);
        if (end < 0) return page;
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + ":\\s*(\\d+)\\s*$").matcher(page.substring(0, end));
        return m.find() ? setFront(page, key, String.valueOf(Integer.parseInt(m.group(1)) + by)) : page;
    }

    /** Index of the "\n---" that closes front matter (the start of that line), or -1 when the page has none. */
    private static int frontEnd(String page) {
        if (!page.startsWith("---\n")) return -1;
        int close = page.indexOf("\n---", 3);
        return close < 0 ? -1 : close + 1;
    }

    private static int[] section(String[] lines, String heading) {
        int from = -1;
        for (int i = 0; i < lines.length; i++) if (lines[i].trim().equals(heading)) { from = i; break; }
        if (from < 0) return null;
        int to = lines.length;
        for (int i = from + 1; i < lines.length; i++) if (lines[i].startsWith("## ")) { to = i; break; }
        return new int[]{from, to};
    }

    private static String join(List<String> lines) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) s.append(lines.get(i)).append(i + 1 < lines.size() ? "\n" : "");
        return s.toString();
    }

    /** New rows after the last table row of the section (a section may have blank lines inside its table); the section is created when missing. */
    static String addTableRows(String page, String heading, List<String> rows, String header) {
        if (rows.isEmpty()) return page;
        List<String> lines = new ArrayList<>(java.util.Arrays.asList(page.split("\n", -1)));
        int[] at = section(lines.toArray(new String[0]), heading);
        if (at == null) return page + (page.endsWith("\n") ? "" : "\n") + "\n" + heading + "\n" + header + "\n" + join(rows) + "\n";
        int last = -1;
        for (int i = at[0] + 1; i < at[1]; i++) if (lines.get(i).startsWith("|")) last = i;
        if (last < 0) { lines.add(at[0] + 1, header); last = at[0] + 1; if (header.contains("\n")) { String[] h = header.split("\n"); lines.set(at[0] + 1, h[0]); lines.add(at[0] + 2, h[1]); last = at[0] + 2; } }
        lines.addAll(last + 1, rows);
        return join(lines);
    }

    /** Bullets at the end of the section's list, skipping ones already there; the section is created when missing. */
    static String addBullets(String page, String heading, List<String> bullets) {
        List<String> lines = new ArrayList<>(java.util.Arrays.asList(page.split("\n", -1)));
        int[] at = section(lines.toArray(new String[0]), heading);
        List<String> fresh = new ArrayList<>();
        for (String b : bullets) { boolean have = false; if (at != null) for (int i = at[0] + 1; i < at[1]; i++) have |= lines.get(i).trim().equals(b.trim()); if (!have) fresh.add(b); }
        if (fresh.isEmpty()) return page;
        if (at == null) return page + (page.endsWith("\n") ? "" : "\n") + "\n" + heading + "\n" + join(fresh) + "\n";
        int last = at[0];
        for (int i = at[0] + 1; i < at[1]; i++) if (!lines.get(i).trim().isEmpty()) last = i;
        if (lines.get(last).trim().equals("- None recorded yet.")) lines.remove(last--);
        lines.addAll(last + 1, fresh);
        return join(lines);
    }

    private static String addAfterLastRow(String text, String marker, List<String> rows) {
        if (rows.isEmpty()) return text;
        List<String> lines = new ArrayList<>(java.util.Arrays.asList(text.split("\n", -1)));
        int last = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(marker)) last = i;
        if (last < 0) return text;
        lines.addAll(last + 1, rows);
        return join(lines);
    }

    /** A saved plan stays valid while the Why and Ideas it was asked with are unchanged. Plans saved before hashes existed (empty) are kept. */
    public static boolean samePlanInputs(String planHash, String nowHash) { return planHash == null || planHash.isEmpty() || planHash.equals(nowHash); }
}
