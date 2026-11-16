package me.venkatesh.saveobsidian;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Port of pi packages/coding-agent/src/core/session-manager.ts + docs/session-format.md @ 3e23dd9, linear for now: an append-only JSONL file,
 * one line per entry, so a process kill loses at most the line being written (a torn last line is skipped on load). Line 1 is the header
 * {type:"session", version, id, timestamp}; every other line has id (8 hex), parentId, timestamp and a type: message, usage (spend that is not
 * a conversation message), compaction {summary, firstKeptEntryId, tokensBefore}, context_edit {targetId, action omit|replace, content},
 * custom (app state, never sent to the model). The active branch is leaf to root through parentId; fork/tree can be added without a format change.
 * Pure Java.
 */
public final class Session {
    public static final int VERSION = 1; // migrations: add a step per version in load()

    private final File file;
    private final String id;
    private final List<JSONObject> entries = new ArrayList<>();
    private final Map<String, JSONObject> byId = new HashMap<>();
    private String leaf;

    private Session(File file, String id) { this.file = file; this.id = id; }

    public String id() { return id; }
    public String exportPath() { return file.getPath(); }

    /** Opens the file, or starts a new session in it. */
    public static synchronized Session open(File file) throws IOException {
        if (!file.exists() || file.length() == 0) {
            Session s = new Session(file, UUID.randomUUID().toString());
            try { s.write(new JSONObject().put("type", "session").put("version", VERSION).put("id", s.id).put("timestamp", java.time.Instant.now().toString())); }
            catch (org.json.JSONException impossible) { throw new IOException(impossible); }
            return s;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        Session s = null;
        int validBytes = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) { validBytes += line.getBytes(StandardCharsets.UTF_8).length + 1; continue; }
            JSONObject o;
            try { o = new JSONObject(line); }
            catch (org.json.JSONException bad) {
                if (s == null) throw new IOException("Not a session file", bad);
                if (i == lines.length - 1 && !text.endsWith("\n")) {
                    if (s.storageError == null) try (java.io.RandomAccessFile out = new java.io.RandomAccessFile(file, "rw")) {
                        out.setLength(validBytes); out.getFD().sync();
                    }
                    break;
                }
                s.storageError = "Session contains an invalid interior record; repair required.";
                continue;
            }
            if (s == null) {
                if (!"session".equals(o.optString("type"))) throw new IOException("Not a session file");
                if (o.optInt("version", 0) > VERSION) throw new IOException("Session was written by a newer app version");
                s = new Session(file, o.optString("id"));
            } else {
                String eid = o.optString("id");
                String parent = o.isNull("parentId") ? null : o.optString("parentId");
                if (eid.isEmpty() || s.byId.containsKey(eid) || (parent != null && !s.byId.containsKey(parent))) {
                    s.storageError = "Session contains an invalid record link; repair required.";
                    continue;
                }
                s.entries.add(o); s.byId.put(eid, o); s.leaf = eid;
            }
            validBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
        }
        if (s == null) throw new IOException("Not a session file");
        if (!text.endsWith("\n") && s.storageError == null && file.length() == text.getBytes(StandardCharsets.UTF_8).length) s.write(null);
        return s;
    }

    private volatile boolean closed;
    private volatile String storageError;

    public String storageError() { return storageError; }
    public boolean writable() { return !closed && storageError == null; }

    /** After this, nothing more is written (the chat was deleted while a run was still finishing). */
    public synchronized void close() { closed = true; }

    private void write(JSONObject line) throws IOException {
        if (closed) throw new IOException("Session is closed");
        if (storageError != null) throw new IOException(storageError);
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(((line == null ? "" : line.toString()) + "\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException failed) {
            storageError = "Session could not be saved: " + failed.getMessage();
            throw failed;
        }
    }

    /** Adds an entry under the current leaf; returns its id. */
    public synchronized String append(JSONObject entry) throws IOException {
        try {
            String eid;
            do { eid = UUID.randomUUID().toString().substring(0, 8); } while (byId.containsKey(eid));
            entry.put("id", eid).put("sequence", entries.size() + 1).put("parentId", leaf == null ? JSONObject.NULL : leaf).put("timestamp", java.time.Instant.now().toString());
            checkCapacity(entry);
            write(entry);
            entries.add(entry); byId.put(eid, entry); leaf = eid;
            return eid;
        } catch (org.json.JSONException impossible) { throw new IOException(impossible); }
    }

    /** One logical run, including resumes, leaves room for errors and effect receipts. */
    private void checkCapacity(JSONObject next) throws IOException {
        String group = null;
        long bytes = 0; int records = 0;
        for (JSONObject entry : entries) {
            JSONObject data = entry.optJSONObject("data");
            if (entry.optString("kind").equals("run_config") && data != null) {
                String candidate = data.optString("budgetGroup", data.optString("runId"));
                if (!candidate.equals(group)) { group = candidate; bytes = 0; records = 0; }
            }
            if (group != null) { bytes += entry.toString().getBytes(StandardCharsets.UTF_8).length + 1; records++; }
        }
        if (group == null || next.optString("kind").equals("run_config")) return;
        String kind = next.optString("kind");
        JSONObject message = next.optJSONObject("message");
        boolean terminal = kind.equals("write_intent") || kind.equals("write_receipt") || kind.equals("proposal_decision")
            || kind.equals("proposal_resolved") || kind.equals("proposal_error") || kind.equals("dispatch_intent") || kind.equals("dispatch_result") || kind.equals("run_end")
            || message != null && (message.optString("role").equals("tool") || message.optString("role").equals("assistant") && !message.has("tool_calls"));
        long maximum = 8L * 1024 * 1024 - (terminal ? 0 : 64 * 1024);
        int count = 4096 - (terminal ? 0 : 32);
        if (records >= count || bytes + next.toString().getBytes(StandardCharsets.UTF_8).length + 1 > maximum)
            throw new IOException("Scout journal limit reached; no new effects admitted");
    }

    /** Stores a conversation message and tags the message object with its entryId (agent bookkeeping, stripped before a request). */
    public String appendMessage(JSONObject message) throws IOException {
        try { String eid = append(new JSONObject().put("type", "message").put("message", new JSONObject(message.toString()))); message.put("entryId", eid); return eid; }
        catch (org.json.JSONException impossible) { throw new IOException(impossible); }
    }

    /** Spend that is not a message of the conversation (a compaction summary call): counted in totals(). */
    public void appendUsage(String source, JSONObject usage) throws IOException {
        try { if (usage != null) append(new JSONObject().put("type", "usage").put("source", source).put("usage", usage)); }
        catch (org.json.JSONException impossible) { throw new IOException(impossible); }
    }

    public String appendCompaction(String summary, String firstKeptEntryId, int tokensBefore) throws IOException {
        try { return append(new JSONObject().put("type", "compaction").put("summary", summary).put("firstKeptEntryId", firstKeptEntryId).put("tokensBefore", tokensBefore)); }
        catch (org.json.JSONException impossible) { throw new IOException(impossible); }
    }

    /** Hides (omit) or rewrites (replace) one message in future context only; the raw entry stays in the file. */
    public void editContext(String targetId, String action, String content) throws IOException {
        try { append(new JSONObject().put("type", "context_edit").put("targetId", targetId).put("action", action).put("content", content == null ? "" : content)); }
        catch (org.json.JSONException impossible) { throw new IOException(impossible); }
    }

    /** App state that never reaches the model. */
    public void appendCustom(String kind, JSONObject data) throws IOException {
        try { append(new JSONObject().put("type", "custom").put("kind", kind).put("data", data)); }
        catch (org.json.JSONException impossible) { throw new IOException(impossible); }
    }

    public void appendModelChange(String model) throws IOException {
        try { append(new JSONObject().put("type", "model_change").put("model", model)); } catch (org.json.JSONException impossible) { throw new IOException(impossible); }
    }

    /** Names the conversation (shown in the session list). */
    public void setName(String name) throws IOException {
        try { append(new JSONObject().put("type", "session_info").put("name", name)); } catch (org.json.JSONException impossible) { throw new IOException(impossible); }
    }

    private synchronized String last(String type, String key) {
        for (int i = entries.size() - 1; i >= 0; i--) if (entries.get(i).optString("type").equals(type)) return entries.get(i).optString(key);
        return null;
    }

    /** True when the name or any message of the conversation contains these words (case-insensitive), for searching the chat list. */
    public synchronized boolean contains(String query) {
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) return true;
        String n = name();
        if (n != null && n.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
        for (JSONObject e : entries) {
            if (!e.optString("type").equals("message")) continue;
            JSONObject m = e.optJSONObject("message");
            String role = m == null ? "" : m.optString("role");
            if ((role.equals("user") || role.equals("assistant")) && m.optString("content").toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    /** The model chosen last in this conversation, or null. */
    public String model() { return last("model_change", "model"); }
    public String name() { return last("session_info", "name"); }

    /** Counts for a session screen: messages by role, tool calls, compactions, spend, file size. */
    public synchronized JSONObject stats() {
        int user = 0, assistant = 0, tools = 0, compactions = 0;
        for (JSONObject e : entries) {
            String t = e.optString("type");
            if (t.equals("compaction")) compactions++;
            if (!t.equals("message")) continue;
            JSONObject m = e.optJSONObject("message");
            String role = m == null ? "" : m.optString("role");
            if (role.equals("user")) user++; else if (role.equals("assistant")) assistant++; else if (role.equals("tool")) tools++;
        }
        try { return new JSONObject().put("user", user).put("assistant", assistant).put("toolResults", tools).put("compactions", compactions).put("totals", totals()).put("bytes", file.length()); }
        catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /** A copy of the raw JSONL (every entry, compacted ones included) for backup or sharing. */
    public void exportTo(File target) throws IOException { Files.copy(file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }

    /** A new, empty session file in dir. */
    public static Session create(File dir) throws IOException {
        dir.mkdirs();
        return open(new File(dir, System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".jsonl"));
    }

    /** The sessions in dir, newest first: {file, id, name, preview (first user message), messages, modified}. Reads each file whole; fine for tens of conversations. */
    public static List<JSONObject> list(File dir) {
        List<JSONObject> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".jsonl"));
        if (files == null) return out;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            if (f.length() == 0) continue;
            try {
                Session s = open(f);
                int messages = 0;
                String preview = "";
                for (JSONObject e : s.entries) if (e.optString("type").equals("message")) { messages++; JSONObject m = e.getJSONObject("message"); if (preview.isEmpty() && m.optString("role").equals("user")) preview = m.optString("content"); }
                String name = s.name();
                out.add(new JSONObject().put("file", f.getPath()).put("id", s.id).put("name", name == null ? "" : name)
                    .put("preview", preview.length() > 80 ? preview.substring(0, 80) : preview).put("messages", messages).put("modified", f.lastModified()));
            } catch (Exception skip) { } // not a session file
        }
        return out;
    }

    /** The messages the next request is built from: the active branch after the last compaction (its summary first), minus omitted ones. */
    public synchronized List<JSONObject> context() {
        List<JSONObject> path = new ArrayList<>();
        for (String at = leaf; at != null && byId.containsKey(at); at = byId.get(at).isNull("parentId") ? null : byId.get(at).optString("parentId")) path.add(0, byId.get(at));
        int compaction = -1;
        for (int i = 0; i < path.size(); i++) if (path.get(i).optString("type").equals("compaction")) compaction = i;
        Map<String, JSONObject> edits = new HashMap<>();
        for (JSONObject e : path) if (e.optString("type").equals("context_edit")) edits.put(e.optString("targetId"), e);
        List<JSONObject> out = new ArrayList<>();
        try {
            int from = 0;
            if (compaction >= 0) {
                JSONObject c = path.get(compaction);
                out.add(new JSONObject().put("role", "user").put("summary", true).put("content", Compact.PREFIX + c.optString("summary")));
                from = compaction; // first kept entry not on the path: keep nothing from before the compaction
                for (int i = 0; i < compaction; i++) if (path.get(i).optString("id").equals(c.optString("firstKeptEntryId"))) { from = i; break; }
            }
            for (int i = from; i < path.size(); i++) {
                JSONObject e = path.get(i);
                if (!e.optString("type").equals("message")) continue;
                JSONObject m = new JSONObject(e.getJSONObject("message").toString()).put("entryId", e.optString("id"));
                try { m.put("ts", java.time.Instant.parse(e.optString("timestamp")).toEpochMilli()); } catch (RuntimeException noTime) { } // when it happened, for "worked for m:ss"
                JSONObject edit = edits.get(e.optString("id"));
                if (edit != null && edit.optString("action").equals("omit")) continue;
                if (edit != null) m.put("content", edit.optString("content"));
                out.add(m);
            }
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        return out;
    }

    /** Retained history on the active branch. Context edits and compaction never erase visible history. */
    public synchronized List<JSONObject> retained() {
        List<JSONObject> path = new ArrayList<>();
        for (String at = leaf; at != null && byId.containsKey(at); at = byId.get(at).isNull("parentId") ? null : byId.get(at).optString("parentId")) path.add(0, byId.get(at));
        List<JSONObject> out = new ArrayList<>();
        try {
            for (JSONObject e : path) if (e.optString("type").equals("message")) {
                JSONObject m = new JSONObject(e.getJSONObject("message").toString()).put("entryId", e.optString("id"));
                try { m.put("ts", java.time.Instant.parse(e.optString("timestamp")).toEpochMilli()); } catch (RuntimeException noTime) { }
                out.add(m);
            }
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        return out;
    }

    /** Stable deep copy for local metrics; never fed into context(). */
    public synchronized List<JSONObject> log() {
        List<JSONObject> copy = new ArrayList<>();
        try { for (JSONObject e : entries) copy.add(new JSONObject(e.toString())); }
        catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        return copy;
    }

    /** Spend across all request attempts, including legacy usage without double-counting messages. */
    public synchronized JSONObject totals() { return SessionUsage.totals(this); }
}
