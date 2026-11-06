package me.venkatesh.saveobsidian;

import android.content.Context;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Ask your notes: the model searches, reads and follows links in the vault with four read-only tools, then answers with citations. */
final class Ask {
    static final String DEFAULT = "anthropic/claude-haiku-5.5"; // cheap, 1M context, reliable tool calls on OpenRouter
    private static final int STEPS = 8, READ = 12000, LIST = 60;

    interface Status { void say(String line); }

    static String model(Context c) { return c.getSharedPreferences("capture", 0).getString("askModel", DEFAULT); }

    private static final String SYSTEM = "You answer questions from the user's personal knowledge base: Markdown notes in the folders Knowledge, Learnings and Sources. "
        + "Look things up before you answer. Search first, read the notes that matter, and follow links when a note points to another. "
        + "Answer only from what the notes say, in short plain prose. After each claim cite the note it came from, written exactly as [[path|Title]] in the form the tools return. "
        + "If the notes do not cover the question, say \"Not in your knowledge base\" and name the closest notes you found. Never invent a note or a path. "
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
                String result;
                try { result = tool(c, snap, fn.getString("name"), new JSONObject(fn.optString("arguments", "{}").isEmpty() ? "{}" : fn.optString("arguments", "{}")), status); }
                catch (Exception e) { result = "Error: " + e.getMessage(); }
                work.put(new JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", result));
            }
        }
        return "";
    }

    private static JSONArray tools() throws Exception {
        return new JSONArray()
            .put(tool("search", "Find notes by meaning and keywords. Returns up to 8 notes with a one-line description.", prop("query", "string", "What to look for"), "query"))
            .put(tool("read", "Read one note by its path. Long notes come in parts: pass offset to continue.",
                prop("path", "string", "Note path as returned by the other tools").put("offset", new JSONObject().put("type", "integer").put("description", "Character to start from, default 0")), "path"))
            .put(tool("list", "List the folders and notes directly inside a folder. An empty path lists the top level.", prop("path", "string", "Folder path, for example Knowledge/Topics"), null))
            .put(tool("links", "Notes this note links to, and notes that link to it.", prop("path", "string", "Note path"), "path"));
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
            case "list": return list(snap, args.optString("path", ""), status);
            case "links": return links(snap, args.getString("path"), status);
            default: return "Error: unknown tool " + name;
        }
    }

    private static String search(Context c, VaultStore.Snap snap, String query, Status status) {
        status.say("Searching “" + query + "”");
        Set<Vault.Note> found = new LinkedHashSet<>();
        try { found.addAll(VaultStore.semantic(c, snap, query, 8)); } catch (Exception offline) { } // keyword results still work without the network
        found.addAll(VaultStore.keyword(snap, query, 8));
        if (found.isEmpty()) return "No notes found. Try other words.";
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (Vault.Note note : found) { if (++n > 8) break; out.append(line(note)).append('\n'); }
        return out.toString();
    }

    private static String line(Vault.Note n) {
        return "- " + n.cite() + (n.type.isEmpty() ? "" : " (" + n.type + ")") + (n.description.isEmpty() ? "" : " — " + (n.description.length() > 140 ? n.description.substring(0, 140) : n.description));
    }

    private static Vault.Note find(VaultStore.Snap snap, String path) {
        Vault.Note n = snap.graph.resolve(Vault.target(path));
        return n;
    }

    private static String read(Context c, VaultStore.Snap snap, String path, int offset, Status status) throws Exception {
        Vault.Note n = find(snap, path);
        if (n == null) return "Error: no note at \"" + path + "\". Use a path from search or list.";
        status.say("Reading " + n.title);
        String text = VaultStore.text(c, snap.uri(n));
        int from = Math.max(0, Math.min(offset, text.length())), to = Math.min(text.length(), from + READ);
        return n.cite() + " — characters " + from + " to " + to + " of " + text.length() + "\n\n" + text.substring(from, to)
            + (to < text.length() ? "\n\n(continues: call read with offset " + to + ")" : "");
    }

    private static String list(VaultStore.Snap snap, String path, Status status) {
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
        int shown = 0;
        for (Vault.Note n : notes) { if (++shown > LIST) { out.append("… ").append(notes.size() - LIST).append(" more\n"); break; } out.append(line(n)).append('\n'); }
        return out.toString();
    }

    private static String links(VaultStore.Snap snap, String path, Status status) {
        Vault.Note n = find(snap, path);
        if (n == null) return "Error: no note at \"" + path + "\".";
        status.say("Following links of " + n.title);
        StringBuilder out = new StringBuilder("Links to:\n");
        for (Vault.Note to : snap.graph.out(n)) out.append(line(to)).append('\n');
        out.append("\nLinked from:\n");
        for (Vault.Note from : snap.graph.in(n)) out.append(line(from)).append('\n');
        return out.toString();
    }
}
