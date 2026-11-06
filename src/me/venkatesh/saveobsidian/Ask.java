package me.venkatesh.saveobsidian;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Ask your notes: a small tool loop over OpenRouter chat completions (no agent SDK). The model searches, reads and follows
 * links across Knowledge, Learnings, Sources and the inbox, then answers with citations. Read-only.
 */
final class Ask {
    static final String DEFAULT = "anthropic/claude-haiku-5.5"; // cheap, 1M context, reliable tool calls on OpenRouter
    static final String INBOX = "Sources/Inbox";
    private static final int STEPS = 8, READ = 12000, LIST = 60;

    interface Status { void say(String line); }

    static String model(Context c) { return c.getSharedPreferences("capture", 0).getString("askModel", DEFAULT); }

    private static final String SYSTEM = "You answer questions from the user's personal knowledge base: Markdown notes in the folders Knowledge, Learnings and Sources, "
        + "plus the inbox, a file of recent captures (links and thoughts) not yet filed into notes. Lines the user wrote in the inbox under why and ideas are their own words. "
        + "Look things up before you answer. Search first, read the notes that matter, follow links when a note points to another, and check the inbox for recent captures. "
        + "Answer only from what the notes and captures say, in short plain prose. After each claim cite where it came from, written exactly as [[path|Title]] in the form the tools return. "
        + "If the knowledge base does not cover the question, say \"Not in your knowledge base\" and name the closest notes you found. Never invent a note, a capture or a path. "
        + "Say so when a note is marked low confidence or has a single source.";

    /** Messages is the conversation so far ending in the user's question. Returns the answer; tool traffic stays inside this call. */
    static String run(Context c, VaultStore.Snap snap, String key, JSONArray messages, Status status) throws Exception {
        JSONArray work = new JSONArray().put(new JSONObject().put("role", "system").put("content", SYSTEM));
        for (int i = 0; i < messages.length(); i++) work.put(messages.get(i));
        String model = model(c);
        for (int step = 0; step < STEPS; step++) {
            boolean last = step == STEPS - 1;
            JSONObject request = new JSONObject().put("model", model).put("max_tokens", 1800).put("messages", work);
            if (!last) request.put("tools", tools());
            else work.put(new JSONObject().put("role", "user").put("content", "Answer now with what you have found."));
            JSONObject reply = new JSONObject(Model.chat(key, request)).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            JSONArray calls = reply.optJSONArray("tool_calls");
            String content = reply.isNull("content") ? "" : reply.optString("content", "");
            if (calls == null || calls.length() == 0) return content.trim();
            work.put(new JSONObject().put("role", "assistant").put("content", content).put("tool_calls", calls));
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.getJSONObject(i), fn = call.getJSONObject("function");
                String raw = fn.optString("arguments", "{}"), result;
                try { result = tool(c, snap, fn.getString("name"), new JSONObject(raw.isEmpty() ? "{}" : raw), status); }
                catch (Exception e) { result = "Error: " + e.getMessage(); }
                work.put(new JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", result));
            }
        }
        return "";
    }

    private static JSONArray tools() throws Exception {
        return new JSONArray()
            .put(tool("search", "Find notes and inbox captures by meaning and keywords. Returns the best matches with a one-line description.", prop("query", "string", "What to look for"), "query"))
            .put(tool("read", "Read one note by its path, or the whole inbox with the path Sources/Inbox. Long files come in parts: pass offset to continue.",
                prop("path", "string", "Note path as returned by the other tools").put("offset", new JSONObject().put("type", "integer").put("description", "Character to start from, default 0")), "path"))
            .put(tool("list", "List the folders and notes directly inside a folder. An empty path lists the top level.", prop("path", "string", "Folder path, for example Knowledge/Topics"), null))
            .put(tool("links", "Notes this note links to, and notes that link to it.", prop("path", "string", "Note path"), "path"))
            .put(tool("inbox", "List captures in the inbox, newest first, with the link, when it was captured, why it was saved and the user's own ideas. Optionally filter by words.",
                prop("query", "string", "Only captures containing all of these words").put("limit", new JSONObject().put("type", "integer").put("description", "How many, default 12")), null));
    }

    private static JSONObject prop(String name, String type, String description) throws Exception {
        return new JSONObject().put(name, new JSONObject().put("type", type).put("description", description));
    }

    private static JSONObject tool(String name, String description, JSONObject properties, String required) throws Exception {
        JSONObject params = new JSONObject().put("type", "object").put("properties", properties);
        if (required != null) params.put("required", new JSONArray().put(required));
        return new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name).put("description", description).put("parameters", params));
    }

    private static String tool(Context c, VaultStore.Snap snap, String name, JSONObject args, Status status) throws Exception {
        switch (name) {
            case "search": return search(c, snap, args.getString("query"), status);
            case "read": return read(c, snap, args.getString("path"), args.optInt("offset", 0), status);
            case "list": return list(c, snap, args.optString("path", ""), status);
            case "links": return links(snap, args.getString("path"), status);
            case "inbox": return inbox(c, args.optString("query", ""), args.optInt("limit", 12), status);
            default: return "Error: unknown tool " + name;
        }
    }

    // ---- inbox as a source ----

    private static List<InboxFile.Entry> entries(Context c) {
        try { return InboxFile.parse(CaptureService.readText(c)); } catch (Exception none) { return new ArrayList<>(); } // no inbox chosen or unreadable
    }

    static String cite(InboxFile.Entry e) { return "[[" + INBOX + "|" + e.shownTitle().replaceAll("[\\[\\]|]", " ").trim() + "]]"; }

    private static String entryLine(InboxFile.Entry e, boolean full) {
        StringBuilder s = new StringBuilder("- " + cite(e) + " (inbox" + (e.time.isEmpty() ? "" : ", captured " + e.time) + (e.type.isEmpty() ? "" : ", " + e.type) + ")");
        if (!e.tldr.isEmpty()) s.append(" — ").append(e.tldr);
        if (full) {
            if (!e.url.isEmpty()) s.append("\n  link: ").append(e.url);
            if (e.text && !e.body.isEmpty()) s.append("\n  text: ").append(e.body.length() > 600 ? e.body.substring(0, 600) : e.body);
            if (!e.why.isEmpty()) s.append("\n  why saved (user's words): ").append(e.why);
            if (!e.ideas.isEmpty()) s.append("\n  ideas (user's words): ").append(e.ideas);
        } else if (e.tldr.isEmpty() && !e.why.isEmpty()) s.append(" — ").append(e.why.length() > 140 ? e.why.substring(0, 140) : e.why);
        return s.toString();
    }

    private static String inbox(Context c, String query, int limit, Status status) {
        status.say(query.isEmpty() ? "Looking at the inbox" : "Looking in the inbox for “" + query + "”");
        List<InboxFile.Entry> all = entries(c);
        if (all.isEmpty()) return "The inbox is empty or not available.";
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (int i = all.size() - 1; i >= 0 && n < Math.max(1, Math.min(limit, 30)); i--) {
            InboxFile.Entry e = all.get(i);
            if (!query.trim().isEmpty() && !e.contains(query.trim())) continue;
            out.append(entryLine(e, true)).append('\n');
            n++;
        }
        return n == 0 ? "No inbox captures contain those words." : out.toString();
    }

    // ---- notes ----

    private static String search(Context c, VaultStore.Snap snap, String query, Status status) {
        status.say("Searching “" + query + "”");
        float[] q = null;
        try { q = VaultStore.vector(c, query); } catch (Exception offline) { } // keyword results still work without the network
        List<Vault.Note> words = VaultStore.keyword(snap, query, 12);
        List<Vault.Note> found = q == null ? words : Vault.blend(VaultStore.nearest(c, snap, q, 12, Index.MIN_QUERY, null), words, query, 12);
        List<InboxFile.Entry> all = entries(c), hits = new ArrayList<>();
        if (q != null) hits.addAll(Index.load(new java.io.File(c.getFilesDir(), "index.bin")).rank(q, all, 4, Index.MIN_QUERY, 0));
        for (InboxFile.Entry e : all) if (hits.size() < 6 && e.contains(query) && !hits.contains(e)) hits.add(e);
        if (found.isEmpty() && hits.isEmpty()) return "No notes or captures found. Try other words.";
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (Vault.Note note : found) { if (++n > 8) break; out.append(line(note)).append('\n'); }
        for (InboxFile.Entry e : hits) out.append(entryLine(e, false)).append('\n');
        return out.toString();
    }

    private static String line(Vault.Note n) {
        return "- " + n.cite() + (n.type.isEmpty() ? "" : " (" + n.type + ")") + (n.description.isEmpty() ? "" : " — " + (n.description.length() > 140 ? n.description.substring(0, 140) : n.description));
    }

    private static Vault.Note find(VaultStore.Snap snap, String path) { return snap.graph.resolve(Vault.target(path)); }

    private static String read(Context c, VaultStore.Snap snap, String path, int offset, Status status) throws Exception {
        String text, head;
        if (Vault.target(path).equalsIgnoreCase(INBOX)) {
            status.say("Reading the inbox");
            text = CaptureService.readText(c);
            head = "[[" + INBOX + "|Inbox]]";
        } else {
            Vault.Note n = find(snap, path);
            if (n == null) return "Error: no note at \"" + path + "\". Use a path from search or list.";
            status.say("Reading " + n.title);
            text = VaultStore.text(c, snap.uri(n));
            head = n.cite();
        }
        int from = Math.max(0, Math.min(offset, text.length())), to = Math.min(text.length(), from + READ);
        return head + " — characters " + from + " to " + to + " of " + text.length() + "\n\n" + text.substring(from, to)
            + (to < text.length() ? "\n\n(continues: call read with offset " + to + ")" : "");
    }

    private static String list(Context c, VaultStore.Snap snap, String path, Status status) {
        String p = path.replaceAll("^/+|/+$", "");
        status.say(p.isEmpty() ? "Looking at the folders" : "Looking in " + p);
        java.util.Map<String, Integer> folders = new java.util.TreeMap<>();
        List<Vault.Note> notes = new ArrayList<>();
        for (Vault.Note n : snap.graph.notes) {
            String rest;
            if (p.isEmpty()) rest = n.path;
            else if (n.path.toLowerCase(Locale.ROOT).startsWith(p.toLowerCase(Locale.ROOT) + "/")) rest = n.path.substring(p.length() + 1);
            else continue;
            int slash = rest.indexOf('/');
            if (slash >= 0) { String f = rest.substring(0, slash); Integer old = folders.get(f); folders.put(f, old == null ? 1 : old + 1); }
            else notes.add(n);
        }
        if (folders.isEmpty() && notes.isEmpty()) return "Error: no folder \"" + path + "\".";
        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> f : folders.entrySet()) out.append("folder ").append(p.isEmpty() ? "" : p + "/").append(f.getKey()).append(" (").append(f.getValue()).append(" notes)\n");
        if (p.equalsIgnoreCase("Sources")) out.append("- [[").append(INBOX).append("|Inbox]] (inbox, ").append(entries(c).size()).append(" captures not yet filed)\n");
        int shown = 0;
        for (Vault.Note n : notes) { if (++shown > LIST) { out.append("… ").append(notes.size() - LIST).append(" more\n"); break; } out.append(line(n)).append('\n'); }
        return out.toString();
    }

    private static String links(VaultStore.Snap snap, String path, Status status) {
        Vault.Note n = find(snap, path);
        if (n == null) return "Error: no note at \"" + path + "\".";
        status.say("Following links of " + n.title);
        StringBuilder out = new StringBuilder("Links to:\n");
        int shown = 0;
        for (Vault.Note to : snap.graph.out(n)) { if (++shown > 40) break; out.append(line(to)).append('\n'); }
        out.append("\nLinked from:\n");
        shown = 0;
        for (Vault.Note from : snap.graph.in(n)) { if (++shown > 40) break; out.append(line(from)).append('\n'); }
        return out.toString();
    }
}
