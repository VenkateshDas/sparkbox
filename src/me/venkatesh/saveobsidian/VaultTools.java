package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Port of pi packages/coding-agent/src/core/tools/{read,ls,find,grep,edit,write}.ts @ 3e23dd9 over the vault instead of a disk: same names, arguments,
 * limits and messages, plus one extra tool, search (meaning + keywords, which grep cannot do). Paths are vault paths ("Knowledge/Topics/X.md"; ".md" may be
 * left out). Reads work on Files.paths() and Files.read(), which the app backs with the in-memory scan and the (slow) vault provider; texts are cached for
 * the life of this object and dropped on every write. edit and write ask Confirm first and only touch .md files under the write roots. Differences from Pi:
 * no fuzzy matching in edit (exact text only), no images, no .gitignore, no dotfiles (the vault listing has none). Pure Java.
 */
public final class VaultTools {
    public interface Files {
        /** Every note path that may be read, "Knowledge/Topics/X.md". */
        List<String> paths();
        /** Text of the note, or null when it does not exist. */
        String read(String path) throws Exception;
        /** Creates the note (before == null) or replaces it if it still holds exactly "before". Throws when it changed meanwhile. */
        void write(String path, String before, String after) throws Exception;
        /** Notes by meaning and keywords, formatted for the model. */
        String search(String query) throws Exception;
        /** Structured matches allow the search tool to open a few results without another model turn. */
        default Tool.Result searchResult(String query) throws Exception { return Tool.Result.ok(search(query)); }
        /** False for notes the app manages itself (the inbox): shown to the model as read-only. */
        default boolean canWrite(String path) { return true; }
    }

    /** Asked before every change; false means the user said no. May block on the user. */
    public interface Confirm { boolean ok(String title, String detail); }

    public static final int LS = 500, FIND = 1000, GREP = 100, LINE = 500;
    private static final String DECLINED = "The user declined this change. Do not retry it; ask what they would like instead.";

    private final Files fs;
    private final Confirm confirm;
    private final String[] roots;
    private final java.util.concurrent.ConcurrentHashMap<String, String> cache = new java.util.concurrent.ConcurrentHashMap<>();

    private VaultTools(Files fs, Confirm confirm, String[] roots) { this.fs = fs; this.confirm = confirm; this.roots = roots; }

    /** The tools for one agent. roots: where edit and write may touch, e.g. {"Knowledge", "Sources"}. */
    public static List<Tool> create(Files fs, Confirm confirm, String... roots) {
        final VaultTools v = new VaultTools(fs, confirm, roots);
        return Arrays.<Tool>asList(v.read(), v.grep(), v.find(), v.ls(), v.search(), v.edit(), v.write());
    }

    // ---- helpers ----

    private static JSONObject obj(Object... kv) {
        try { JSONObject o = new JSONObject(); for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]); return o; }
        catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static JSONObject prop(String type, String description) { return obj("type", type, "description", description); }

    private static JSONObject schema(JSONObject properties, String... required) {
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);
        return obj("type", "object", "properties", properties, "required", req);
    }

    private static String size(int bytes) { return bytes < 1024 ? bytes + "B" : String.format(Locale.US, "%.1fKB", bytes / 1024.0); }

    /** The path as the model wrote it, made vault-relative: no leading "/" or "./", slashes forward. */
    private static String clean(String p) {
        String s = p == null ? "" : p.trim().replace('\\', '/');
        while (s.startsWith("./")) s = s.substring(2);
        return s.replaceAll("^/+|/+$", "");
    }

    /** The note path that p names (exact, with ".md", or case-insensitive), or null. */
    private String note(String p) {
        String s = clean(p);
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        String found = null;
        for (String path : fs.paths()) {
            if (path.equals(s) || path.equals(s + ".md")) return path;
            String l = path.toLowerCase(Locale.ROOT);
            if (found == null && (l.equals(lower) || l.equals(lower + ".md"))) found = path;
        }
        return found;
    }

    private String text(String path) throws Exception {
        try {
            // Concurrent scans share each cold read; unrelated notes can still load in parallel.
            return cache.computeIfAbsent(path, key -> {
                try {
                    String text = fs.read(key);
                    if (text == null) throw new java.io.IOException("File not found: " + key);
                    return text;
                } catch (Exception failed) { throw new java.util.concurrent.CompletionException(failed); }
            });
        } catch (java.util.concurrent.CompletionException failed) {
            throw (Exception) failed.getCause();
        }
    }

    /** Notes under dir ("" = the whole vault), sorted. Empty list when nothing is there. */
    private List<String> under(String dir) {
        List<String> out = new ArrayList<>();
        String prefix = dir.isEmpty() ? "" : dir.toLowerCase(Locale.ROOT) + "/";
        for (String path : fs.paths()) if (path.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(path);
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** The folder a path argument names, resolved to its real spelling; "" for the vault root; null when no such folder. */
    private String folder(String p) {
        String s = clean(p);
        if (s.isEmpty() || s.equals(".")) return "";
        for (String path : fs.paths()) {
            String[] parts = path.split("/");
            StringBuilder at = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                at.append(i == 0 ? "" : "/").append(parts[i]);
                if (at.toString().equalsIgnoreCase(s)) return at.toString();
            }
        }
        return null;
    }

    /** Glob to regex: ** crosses folders, * and ? stay inside one, {a,b} alternatives. Case-insensitive like the notes' own file systems. */
    static Pattern glob(String g) {
        StringBuilder re = new StringBuilder();
        int braces = 0;
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') { i++; if (i + 1 < g.length() && g.charAt(i + 1) == '/') { i++; re.append("(?:.*/)?"); } else re.append(".*"); }
            else if (c == '*') re.append("[^/]*");
            else if (c == '?') re.append("[^/]");
            else if (c == '{') { braces++; re.append("(?:"); }
            else if (c == '}' && braces > 0) { braces--; re.append(")"); }
            else if (c == ',' && braces > 0) re.append("|");
            else re.append(Pattern.quote(String.valueOf(c)));
        }
        while (braces-- > 0) re.append(")");
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }

    /** Pi's rule: a pattern without "/" matches the file name at any depth; one with "/" matches the path below the search folder. */
    private static boolean matches(Pattern glob, String pattern, String rel) {
        return glob.matcher(pattern.indexOf('/') < 0 ? rel.substring(rel.lastIndexOf('/') + 1) : rel).matches();
    }

    private static String rel(String path, String dir) { return dir.isEmpty() ? path : path.substring(dir.length() + 1); }

    // ---- read ----

    private Tool read() {
        return new Tool("read", "Read a note. Output is truncated to " + Truncate.LINES + " lines or " + Truncate.BYTES / 1024 + "KB (whichever is hit first). Use offset/limit for large notes. When you need the full note, continue with offset until complete.",
            schema(obj("path", prop("string", "Path of the note to read (relative to the vault; .md may be left out)"),
                "offset", prop("number", "Line number to start reading from (1-indexed)"),
                "limit", prop("number", "Maximum number of lines to read")), "path"), false) {
            @Override public Result execute(JSONObject args, Cancel cancel) throws Exception {
                String path = note(args.getString("path"));
                if (path == null) return Result.error("File not found: " + clean(args.getString("path")) + ". Use find or ls to see what exists.");
                String[] lines = text(path).split("\n", -1);
                int start = args.has("offset") ? Math.max(0, args.getInt("offset") - 1) : 0;
                if (start >= lines.length) return Result.error("Offset " + args.getInt("offset") + " is beyond end of file (" + lines.length + " lines total)");
                int end = args.has("limit") ? Math.min(lines.length, start + Math.max(0, args.getInt("limit"))) : lines.length;
                Truncate.Cut cut = Truncate.head(String.join("\n", Arrays.copyOfRange(lines, start, end)));
                if (cut.truncated && cut.shownLines == 0)
                    return Result.ok("[Line " + (start + 1) + " is " + size(lines[start].getBytes("UTF-8").length) + ", exceeds " + size(Truncate.BYTES) + " limit. Use grep to find the part you need.]");
                if (cut.truncated) return evidence(path, lines, start, end, cut.text + "\n\n[Showing lines " + (start + 1) + "-" + (start + cut.shownLines) + " of " + lines.length + ". Use offset=" + (start + cut.shownLines + 1) + " to continue.]", true);
                if (end < lines.length) return evidence(path, lines, start, end, cut.text + "\n\n[" + (lines.length - end) + " more lines in file. Use offset=" + (end + 1) + " to continue.]", true);
                return evidence(path, lines, start, end, cut.text, false);
            }
        };
    }

    private static Tool.Result evidence(String path, String[] lines, int start, int end, String text, boolean truncated) throws Exception {
        JSONObject detail = obj("evidencePath", path, "coverage", !truncated && start == 0 && end == lines.length ? "full" : "excerpt",
            "range", "lines " + (start + 1) + "-" + end + (truncated ? " (truncated)" : ""), "hash", ScoutChanges.hash(String.join("\n", lines)));
        return new Tool.Result(text, detail, false, false);
    }

    // ---- ls ----

    private Tool ls() {
        return new Tool("ls", "List a folder of the vault. Returns entries sorted alphabetically, with '/' suffix for folders. Output is truncated to " + LS + " entries or " + Truncate.BYTES / 1024 + "KB (whichever is hit first).",
            schema(prop2("path", "Folder to list (default: the vault root)", "limit", "Maximum number of entries to return (default: " + LS + ")")), false) {
            @Override public Result execute(JSONObject args, Cancel cancel) {
                String dir = folder(args.optString("path", ""));
                if (dir == null) return Result.error("Path not found: " + clean(args.optString("path", "")));
                TreeSet<String> entries = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (String path : under(dir)) { String r = rel(path, dir); int slash = r.indexOf('/'); entries.add(slash < 0 ? r : r.substring(0, slash) + "/"); }
                if (entries.isEmpty()) return Result.ok("(empty directory)");
                int limit = Math.max(1, args.optInt("limit", LS));
                List<String> all = new ArrayList<>(entries), shown = all.subList(0, Math.min(limit, all.size()));
                String out = String.join("\n", shown);
                Truncate.Cut cut = Truncate.head(out, Integer.MAX_VALUE, Truncate.BYTES);
                List<String> notices = new ArrayList<>();
                if (all.size() > limit) notices.add(limit + " entries limit reached. Use limit=" + limit * 2 + " for more");
                if (cut.truncated) notices.add(size(Truncate.BYTES) + " limit reached");
                return Result.ok(cut.text + (notices.isEmpty() ? "" : "\n\n[" + String.join(". ", notices) + "]"));
            }
        };
    }

    private static JSONObject prop2(String a, String da, String b, String db) { return obj(a, prop("string", da), b, prop("number", db)); }

    // ---- find ----

    private Tool find() {
        return new Tool("find", "Search for notes by glob pattern. Returns matching paths relative to the search folder. Output is truncated to " + FIND + " results or " + Truncate.BYTES / 1024 + "KB (whichever is hit first).",
            schema(obj("pattern", prop("string", "Glob pattern to match files, e.g. '*.md', '**/topics/*.md', or '*agent*'. Without '/', it matches note names at any depth"),
                "path", prop("string", "Folder to search in (default: the vault root)"),
                "limit", prop("number", "Maximum number of results (default: " + FIND + ")")), "pattern"), false) {
            @Override public Result execute(JSONObject args, Cancel cancel) throws Exception {
                String dir = folder(args.optString("path", ""));
                if (dir == null) return Result.error("Path not found: " + clean(args.optString("path", "")));
                String pattern = args.getString("pattern");
                Pattern g = glob(pattern);
                List<String> hits = new ArrayList<>();
                for (String path : under(dir)) if (matches(g, pattern, rel(path, dir))) hits.add(rel(path, dir));
                if (hits.isEmpty()) return Result.ok("No files found matching pattern");
                int limit = Math.max(1, args.optInt("limit", FIND));
                Truncate.Cut cut = Truncate.head(String.join("\n", hits.subList(0, Math.min(limit, hits.size()))), Integer.MAX_VALUE, Truncate.BYTES);
                List<String> notices = new ArrayList<>();
                if (hits.size() > limit) notices.add(limit + " results limit reached. Use limit=" + limit * 2 + " for more, or refine pattern");
                if (cut.truncated) notices.add(size(Truncate.BYTES) + " limit reached");
                return Result.ok(cut.text + (notices.isEmpty() ? "" : "\n\n[" + String.join(". ", notices) + "]"));
            }
        };
    }

    // ---- grep ----

    private Tool grep() {
        return new Tool("grep", "Search note contents for a pattern. For topic discovery use search first; scope path or glob to the relevant notes instead of scanning the whole vault. Returns matching lines with note paths and line numbers. Output is truncated to " + GREP + " matches or " + Truncate.BYTES / 1024 + "KB (whichever is hit first). Long lines are truncated to " + LINE + " chars.",
            schema(obj("pattern", prop("string", "Search pattern (regex or literal string)"),
                "path", prop("string", "Folder or note to search (default: the vault root)"),
                "glob", prop("string", "Filter notes by glob pattern relative to path, e.g. '*.md'. Without '/', it matches note names at any depth"),
                "ignoreCase", prop("boolean", "Case-insensitive search (default: false)"),
                "literal", prop("boolean", "Treat pattern as literal string instead of regex (default: false)"),
                "context", prop("number", "Number of lines to show before and after each match (default: 0)"),
                "limit", prop("number", "Maximum number of matches to return (default: " + GREP + ")")), "pattern"), false) {
            @Override public Result execute(JSONObject args, Cancel cancel) throws Exception {
                String where = clean(args.optString("path", "")), single = where.isEmpty() ? null : note(where), dir = single != null ? "" : folder(where);
                if (single == null && dir == null) return Result.error("Path not found: " + where);
                Pattern re;
                try { re = Pattern.compile(args.optBoolean("literal") ? Pattern.quote(args.getString("pattern")) : args.getString("pattern"), args.optBoolean("ignoreCase") ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0); }
                catch (java.util.regex.PatternSyntaxException bad) { return Result.error("Invalid pattern: " + bad.getDescription()); }
                String filter = args.optString("glob", "");
                Pattern g = filter.isEmpty() ? null : glob(filter);
                int ctx = Math.max(0, args.optInt("context", 0)), limit = Math.max(1, args.optInt("limit", GREP));
                List<String> files = single != null ? Collections.singletonList(single) : under(dir), out = new ArrayList<>();
                int matches = 0;
                boolean more = false, cut = false;
                for (String path : files) {
                    if (cancel.stopped()) return Result.error("Operation aborted");
                    String name = single != null ? path : rel(path, dir);
                    if (g != null && !matches(g, filter, name)) continue;
                    String[] lines = text(path).split("\n", -1);
                    int shownUpTo = -1;
                    for (int i = 0; i < lines.length; i++) {
                        if (!re.matcher(lines[i]).find()) continue;
                        if (matches >= limit) { more = true; break; }
                        matches++;
                        for (int j = Math.max(shownUpTo + 1, i - ctx); j <= Math.min(lines.length - 1, i + ctx); j++) {
                            String t = lines[j].replaceAll("\r$", "");
                            if (t.length() > LINE) { t = t.substring(0, LINE) + "... [truncated]"; cut = true; }
                            out.add(name + (j == i ? ":" + (j + 1) + ": " : "-" + (j + 1) + "- ") + t);
                            shownUpTo = j;
                        }
                    }
                    if (more) break;
                }
                if (matches == 0) return Result.ok("No matches found");
                Truncate.Cut bytes = Truncate.head(String.join("\n", out), Integer.MAX_VALUE, Truncate.BYTES);
                List<String> notices = new ArrayList<>();
                if (more) notices.add(limit + " matches limit reached. Use limit=" + limit * 2 + " for more, or refine pattern");
                if (bytes.truncated) notices.add(size(Truncate.BYTES) + " limit reached");
                if (cut) notices.add("Some lines truncated to " + LINE + " chars. Use read tool to see full lines");
                return Result.ok(bytes.text + (notices.isEmpty() ? "" : "\n\n[" + String.join(". ", notices) + "]"));
            }
        };
    }

    // ---- search ----

    private Tool search() {
        return new Tool("search", "Hybrid retrieval by meaning and keywords. Returns ranked matches and freshly read, citable excerpts from the best two notes. Listings alone are not evidence. Read more only if the excerpts do not answer the question.",
            schema(obj("query", prop("string", "What to look for"), "read", prop("number", "Notes to open for excerpts: 0 to 3, default 2; use 0 for listings only")), "query"), false) {
            @Override public Result execute(JSONObject args, Cancel cancel) throws Exception {
                String query = args.getString("query").trim();
                if (query.isEmpty()) return Result.error("Search query is empty. Use a topic or a few relevant words.");
                Result result = fs.searchResult(query);
                if (result.error || result.details == null) return result;
                JSONArray matches = result.details.optJSONArray("matches"), evidence = new JSONArray();
                StringBuilder out = new StringBuilder(result.text);
                int count = Math.max(0, Math.min(3, args.optInt("read", 2)));
                for (int i = 0; matches != null && i < Math.min(count, matches.length()); i++) {
                    if (cancel.stopped()) return Result.error("Operation aborted");
                    String path = note(matches.getString(i));
                    if (path == null) continue;
                    try {
                        String full = fs.read(path);
                        if (full == null) throw new java.io.IOException("File no longer exists");
                        cache.put(path, full);
                        String excerpt = full.substring(0, Math.min(1600, full.length()));
                        String range = "characters 0-" + excerpt.length() + " of " + full.length();
                        out.append("\n\nRead excerpt: ").append(path).append(" · ").append(range).append("\n").append(excerpt);
                        evidence.put(obj("evidencePath", path, "coverage", excerpt.length() == full.length() ? "full" : "excerpt", "range", range, "hash", ScoutChanges.hash(full), "excerpt", excerpt));
                    } catch (Exception unreadable) { out.append("\nCould not read ").append(path).append(": ").append(unreadable.getMessage()); }
                }
                JSONObject detail = new JSONObject(result.details.toString()).put("evidence", evidence);
                return new Result(out.toString(), detail, false, false);
            }
        };
    }

    // ---- edit and write ----

    private boolean writable(String path) {
        if (!path.toLowerCase(Locale.ROOT).endsWith(".md") || path.contains("..") || path.contains("//") || !fs.canWrite(path)) return false;
        for (String r : roots) if (path.startsWith(r + "/")) return true;
        return false;
    }

    private String where() { return String.join("/ and ", roots) + "/"; }

    private static int count(String text, String find) {
        int n = 0;
        for (int at = text.indexOf(find); at >= 0; at = text.indexOf(find, at + find.length())) n++;
        return n;
    }

    private static String clip(String s) { return s.length() > 300 ? s.substring(0, 300) + "…" : s; }

    private Tool edit() {
        JSONObject one = obj("type", "object", "properties", obj("oldText", prop("string", "Exact text for one targeted replacement. It must be unique in the original note and must not overlap with any other edits[].oldText in the same call."),
            "newText", prop("string", "Replacement text for this targeted edit.")), "required", new JSONArray().put("oldText").put("newText"));
        return new Tool("edit", "Edit a note using exact text replacement. Every edits[].oldText is matched against the original note, not incrementally. Do not include overlapping or nested edits; merge nearby changes into one edit. The user is asked to confirm before the change is written.",
            schema(obj("path", prop("string", "Path of the note to edit"), "edits", obj("type", "array", "items", one, "description", "One or more targeted replacements, each matched against the original note.")), "path", "edits"), true) {
            /** Pi's shims: edits sent as a JSON string, as one object, or the old top-level oldText/newText. */
            @Override public JSONObject prepareArguments(JSONObject args) {
                try {
                    Object edits = args.opt("edits");
                    if (edits instanceof String) {
                        Object parsed = new org.json.JSONTokener((String) edits).nextValue();
                        if (parsed instanceof JSONArray) args.put("edits", parsed);
                        else if (parsed instanceof JSONObject && ((JSONObject) parsed).has("oldText")) args.put("edits", new JSONArray().put(parsed));
                    } else if (edits instanceof JSONObject && ((JSONObject) edits).has("oldText")) args.put("edits", new JSONArray().put(edits));
                    if (args.opt("oldText") instanceof String && args.opt("newText") instanceof String) {
                        JSONArray list = args.opt("edits") instanceof JSONArray ? (JSONArray) args.get("edits") : new JSONArray();
                        list.put(obj("oldText", args.get("oldText"), "newText", args.get("newText")));
                        args.put("edits", list); args.remove("oldText"); args.remove("newText");
                    }
                } catch (org.json.JSONException unreadable) { } // Validate reports it
                return args;
            }

            @Override public Result execute(JSONObject args, Cancel cancel) throws Exception {
                String path = note(args.getString("path"));
                if (path == null) return Result.error("File not found: " + clean(args.getString("path")));
                if (!writable(path)) return Result.error("Cannot edit " + path + ": only .md notes under " + where() + " can be changed.");
                String raw = fs.read(path);
                if (raw == null) return Result.error("File not found: " + path);
                boolean crlf = raw.contains("\r\n");
                String base = raw.replace("\r\n", "\n");
                JSONArray edits = args.getJSONArray("edits");
                if (edits.length() == 0) return Result.error("edits must contain at least one replacement.");
                int[][] spans = new int[edits.length()][];
                String[] news = new String[edits.length()];
                for (int i = 0; i < edits.length(); i++) {
                    String label = edits.length() == 1 ? "the text" : "edits[" + i + "]";
                    String old = edits.getJSONObject(i).getString("oldText").replace("\r\n", "\n");
                    news[i] = edits.getJSONObject(i).getString("newText").replace("\r\n", "\n");
                    if (old.isEmpty()) return Result.error((edits.length() == 1 ? "oldText" : "edits[" + i + "].oldText") + " must not be empty in " + path + ".");
                    int n = count(base, old);
                    if (n == 0) return Result.error("Could not find " + label + " in " + path + ". The oldText must match exactly including all whitespace and newlines."); // ponytail: exact only, no fuzzy quotes/whitespace match like Pi
                    if (n > 1) return Result.error("Found " + n + " occurrences of " + label + " in " + path + ". The text must be unique. Please provide more context to make it unique.");
                    spans[i] = new int[]{base.indexOf(old), old.length(), i};
                }
                Arrays.sort(spans, (a, b) -> Integer.compare(a[0], b[0]));
                for (int i = 1; i < spans.length; i++)
                    if (spans[i - 1][0] + spans[i - 1][1] > spans[i][0]) return Result.error("edits[" + spans[i - 1][2] + "] and edits[" + spans[i][2] + "] overlap in " + path + ". Merge them into one edit or target disjoint regions.");
                StringBuilder next = new StringBuilder();
                int at = 0;
                for (int[] s : spans) { next.append(base, at, s[0]).append(news[s[2]]); at = s[0] + s[1]; }
                String after = next.append(base.substring(at)).toString();
                if (after.equals(base)) return Result.error("No changes made to " + path + ". The replacements produced identical content.");
                StringBuilder diff = new StringBuilder();
                for (int[] s : spans) diff.append("- ").append(clip(edits.getJSONObject(s[2]).getString("oldText"))).append("\n+ ").append(clip(news[s[2]])).append("\n");
                if (!confirm.ok("Edit " + path, diff.toString().trim())) return Result.error(DECLINED);
                if (cancel.stopped()) return Result.error("Operation aborted");
                cache.remove(path);
                fs.write(path, raw, crlf ? after.replace("\n", "\r\n") : after);
                return Result.ok("Successfully replaced " + edits.length() + " block(s) in " + path + ".");
            }
        };
    }

    private Tool write() {
        return new Tool("write", "Write content to a note, creating it (and its folders) if it does not exist and overwriting it if it does. Only .md notes under " + where() + ". Prefer edit for changes to an existing note. The user is asked to confirm before the change is written.",
            schema(obj("path", prop("string", "Path of the note to write, e.g. Knowledge/Topics/Example.md"), "content", prop("string", "Content to write to the note")), "path", "content"), true) {
            @Override public Result execute(JSONObject args, Cancel cancel) throws Exception {
                String path = note(args.getString("path"));
                if (path == null) path = clean(args.getString("path")).replaceFirst("(?i)(\\.md)?$", ".md");
                if (!writable(path)) return Result.error("Cannot write " + path + ": only .md notes under " + where() + " can be written.");
                String before = fs.read(path), content = args.getString("content");
                int old = before == null ? 0 : before.split("\n", -1).length, now = content.split("\n", -1).length;
                if (!confirm.ok((before == null ? "Create " : "Overwrite ") + path, before == null ? now + " lines:\n" + clip(content) : old + " lines replaced by " + now + " lines:\n" + clip(content))) return Result.error(DECLINED);
                if (cancel.stopped()) return Result.error("Operation aborted");
                cache.remove(path);
                fs.write(path, before, content);
                return Result.ok("Successfully wrote to " + path);
            }
        };
    }
}
