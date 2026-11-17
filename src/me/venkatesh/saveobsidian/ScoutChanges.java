package me.venkatesh.saveobsidian;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONObject;

/** Individual, durable proposals. No network or storage effect is executed by proposing a change. */
public final class ScoutChanges {
    private ScoutChanges() { }
    public static final class Suspended extends RuntimeException {
        public final JSONObject proposal;
        Suspended(JSONObject proposal) { super("Awaiting approval"); this.proposal = proposal; }
    }
    public interface Notes {
        String tree();
        String read(String pinnedTree, String path) throws Exception;
        void write(String pinnedTree, String path, String before, String after) throws Exception;
    }
    public static final ThreadLocal<JSONObject> CALL = new ThreadLocal<>();

    public static String hash(String text) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest((text == null ? "<missing>" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static void note(Session session, String tree, String path, String before, String after) throws Exception {
        JSONObject p = prepareNote(session, tree, path, before, after, new org.json.JSONArray(), false);
        throw new Suspended(p);
    }

    public static JSONObject prepareNote(Session session, String tree, String path, String before, String after, org.json.JSONArray dependencies, boolean cards) throws Exception {
        if (!(path.startsWith("Knowledge/") || path.startsWith("Sources/")) || path.contains("..") || path.contains("\\") || path.contains("\n") || !path.endsWith(".md") || path.endsWith("/My cards.md") || Layout.isInbox(path))
            throw new IOException("Invalid or protected Scout note path");
        protect(before, after, cards);
        JSONObject p = base("note").put("tree", tree).put("path", path).put("before", before == null ? JSONObject.NULL : before)
            .put("after", after).put("beforeHash", hash(before)).put("afterHash", hash(after)).put("dependencies", dependencies)
            .put("sourceIds", new org.json.JSONArray(ScoutEvidence.sources(session).keySet())).put("cards", cards);
        session.appendCustom("proposal", p);
        return p;
    }

    public static void external(Session session, String server, String endpoint, String revision, String tool, JSONObject args) throws Exception {
        String argumentsHash = hash(args.toString());
        org.json.JSONArray unknown = new org.json.JSONArray();
        for (JSONObject entry : session.log()) {
            JSONObject prior = entry.optJSONObject("data");
            if (!entry.optString("kind").equals("proposal") || prior == null || !prior.optString("kind").equals("external")) continue;
            String id = prior.optString("proposalId");
            if (server.equals(prior.optString("server")) && endpoint.equals(prior.optString("endpoint")) && tool.equals(prior.optString("tool"))
                && event(session, "dispatch_intent", id) && !event(session, "dispatch_result", id)) unknown.put(id);
        }
        suspend(session, base("external").put("server", server).put("endpoint", endpoint).put("revision", revision)
            .put("tool", tool).put("arguments", args).put("argumentsHash", argumentsHash).put("previousUnknown", unknown));
    }

    private static JSONObject base(String kind) throws Exception {
        JSONObject call = CALL.get();
        if (call == null) throw new IOException("Missing originating tool call");
        return new JSONObject().put("proposalId", UUID.randomUUID().toString()).put("kind", kind)
            .put("callId", call.getString("id")).put("name", call.getString("name"));
    }

    private static void suspend(Session session, JSONObject proposal) throws Exception {
        session.appendCustom("proposal", proposal);
        throw new Suspended(proposal);
    }

    public static List<JSONObject> pending(Session session) {
        Map<String, JSONObject> pending = new LinkedHashMap<>();
        for (JSONObject e : session.log()) {
            JSONObject d = e.optJSONObject("data");
            if (!e.optString("type").equals("custom") || d == null) continue;
            if (e.optString("kind").equals("proposal")) pending.put(d.optString("proposalId"), d);
            if (e.optString("kind").equals("proposal_resolved")) pending.remove(d.optString("proposalId"));
        }
        return new ArrayList<>(pending.values());
    }

    /** Only explicit user-selected full access admits automatic approvals. Discovery remains read-only. */
    public static boolean fullAccess(JSONObject config) {
        return config != null && "full".equals(config.optString("approval")) && !config.optBoolean("discovery");
    }

    public interface Apply { void apply(JSONObject proposal) throws Exception; }

    /** Full access executes inside the original tool batch, using the same durable fences as a manual approval. */
    public static JSONObject autoApprove(Session session, JSONObject p, Cancel cancel, Apply apply) throws Exception {
        if (!fullAccess(ScoutState.latest(session, "run_config")) || cancel.stopped()) return null;
        org.json.JSONArray unknown = p.optJSONArray("previousUnknown");
        String id = p.optString("proposalId");
        if (p.optString("callId").startsWith("manual_") || unknown != null && unknown.length() > 0
            || "external".equals(p.optString("kind")) && event(session, "dispatch_intent", id) && !event(session, "dispatch_result", id)) return null;
        apply.apply(p);
        if (!event(session, "proposal_resolved", id)) return null;
        for (JSONObject result : session.retained()) if ("tool".equals(result.optString("role")) && id.equals(result.optString("proposalId"))) return result;
        return null;
    }

    public static boolean event(Session session, String kind, String id) {
        for (JSONObject e : session.log()) {
            JSONObject d = e.optJSONObject("data");
            if (e.optString("type").equals("custom") && e.optString("kind").equals(kind) && d != null && id.equals(d.optString("proposalId"))) return true;
        }
        return false;
    }

    private static boolean declined(Session session, String id) {
        for (JSONObject e : session.log()) {
            JSONObject d = e.optJSONObject("data");
            if (e.optString("kind").equals("proposal_decision") && d != null && id.equals(d.optString("proposalId")) && !d.optBoolean("approved")) return true;
        }
        return false;
    }

    /** Decision is durable before admission. Stop and intent admission serialize on the same Cancel monitor. */
    public static void applyNote(Session session, JSONObject p, boolean yes, Notes notes, Cancel cancel) throws Exception {
        synchronized (session.approvalLock) {
            String id = p.getString("proposalId");
            if (event(session, "proposal_resolved", id)) return;
            yes = yes && !declined(session, id);
            session.appendCustom("proposal_decision", decision(id, yes));
            if (!yes && !event(session, "write_intent", id)) { resolve(session, p, "The user declined this change. Do not retry it.", true); return; }
            org.json.JSONArray dependencies = p.optJSONArray("dependencies");
            for (int i = 0; dependencies != null && i < dependencies.length(); i++) {
                String ancestor = dependencies.getString(i);
                if (!event(session, "write_receipt", ancestor)) {
                    if (event(session, "proposal_resolved", ancestor)) resolve(session, p, "Required note change was declined or conflicted. Prepare a new proposal.", true);
                    return;
                }
            }
            VaultLock.L.lockInterruptibly();
            try {
                String tree = p.getString("tree"), path = p.getString("path"), after = p.getString("after");
                String before = p.isNull("before") ? null : p.getString("before");
                if (!tree.equals(notes.tree())) { resolve(session, p, "Vault changed. Review a new proposal.", true); return; }
                String current = notes.read(tree, path);
                if (event(session, "write_intent", id) && Objects.equals(current, after)) {
                    session.appendCustom("write_receipt", new JSONObject().put("proposalId", id).put("tree", tree).put("recovered", true));
                    resolve(session, p, "Verified saved " + path, false); return;
                }
                if (!yes) { resolve(session, p, "Continuation declined. A prior write intent exists; inspect the note for partial changes.", true); return; }
                if (!Objects.equals(current, before)) { resolve(session, p, "Note changed. Review a new proposal.", true); return; }
                protect(before, after, p.optBoolean("cards"));
                synchronized (cancel) {
                    if (cancel.stopped()) return;
                    if (!tree.equals(notes.tree())) { resolve(session, p, "Vault changed. Review a new proposal.", true); return; }
                    session.appendCustom("write_intent", new JSONObject().put("proposalId", id).put("tree", tree));
                }
                // An admitted write finishes verification even if Stop arrives during storage access.
                notes.write(tree, path, before, after);
                if (!Objects.equals(notes.read(tree, path), after)) throw new IOException("Saved note failed read-back verification");
                session.appendCustom("write_receipt", new JSONObject().put("proposalId", id).put("tree", tree).put("afterHash", hash(after)));
                resolve(session, p, "Verified saved " + path, false);
            } finally { VaultLock.L.unlock(); }
        }
    }

    public interface External {
        String revision();
        void connect(Cancel cancel) throws Exception;
        Tool.Result call(String tool, JSONObject arguments, Cancel cancel) throws Exception;
    }

    /** An uncertain remote dispatch cannot be reconciled by local content hashes or automatically retried. */
    public static void applyExternal(Session session, JSONObject p, boolean yes, External external, Cancel cancel) throws Exception {
        synchronized (session.approvalLock) {
            String id = p.getString("proposalId");
            if (event(session, "proposal_resolved", id)) return;
            yes = yes && !declined(session, id);
            session.appendCustom("proposal_decision", decision(id, yes));
            if (event(session, "dispatch_intent", id)) {
                JSONObject saved = null;
                for (JSONObject e : session.log()) if (e.optString("kind").equals("dispatch_result") && e.optJSONObject("data") != null && id.equals(e.getJSONObject("data").optString("proposalId"))) saved = e.getJSONObject("data");
                resolve(session, p, saved == null ? "External outcome unknown. The approved call may have executed; it was not dispatched again. A new attempt needs explicit user authorization acknowledging this unknown outcome." : saved.getString("text"), saved == null || saved.optBoolean("error"));
                return;
            }
            if (!yes) { resolve(session, p, "The user declined this call. Do not retry it.", true); return; }
            if (!p.getString("revision").equals(external.revision())) { resolve(session, p, "Server configuration changed. Review a new proposal.", true); return; }
            external.connect(cancel);
            synchronized (cancel) {
                if (cancel.stopped()) return;
                if (!p.getString("revision").equals(external.revision())) { resolve(session, p, "Server configuration changed. Review a new proposal.", true); return; }
                session.appendCustom("dispatch_intent", new JSONObject().put("proposalId", id));
            }
            Tool.Result result = ScoutEvidence.annotate(session, p.getString("name"), external.call(p.getString("tool"), p.getJSONObject("arguments"), cancel));
            session.appendCustom("dispatch_result", new JSONObject().put("proposalId", id).put("text", result.text).put("error", result.error));
            resolve(session, p, result.text, result.error);
        }
    }

    public static JSONObject decision(String id, boolean yes) throws Exception { return new JSONObject().put("proposalId", id).put("approved", yes); }

    public static void resolve(Session session, JSONObject p, String text, boolean error) throws Exception {
        // A crash between message and marker must not produce duplicate tool results.
        boolean answered = false;
        for (JSONObject m : session.retained()) if (m.optString("role").equals("tool") && p.optString("proposalId").equals(m.optString("proposalId"))) answered = true;
        if (!answered && !p.optString("callId").startsWith("manual_")) session.appendMessage(new JSONObject().put("role", "tool").put("tool_call_id", p.getString("callId"))
            .put("name", p.getString("name")).put("proposalId", p.getString("proposalId")).put("content", text).put("isError", error));
        session.appendCustom("proposal_resolved", new JSONObject().put("proposalId", p.getString("proposalId")));
        if (error) for (JSONObject dependent : pending(session)) {
            org.json.JSONArray dependencies = dependent.optJSONArray("dependencies");
            for (int i = 0; dependencies != null && i < dependencies.length(); i++) if (p.getString("proposalId").equals(dependencies.optString(i))) {
                resolve(session, dependent, "Required note change was declined or conflicted. Prepare a new proposal.", true); break;
            }
        }
    }

    /** Owner sections and existing card/review blocks are immutable to ordinary Scout edits. */
    public static void protect(String before, String after) throws IOException { protect(before, after, false); }

    private static void protect(String before, String after, boolean cards) throws IOException {
        if (before == null) return;
        if (!frontmatter(before).equals(frontmatter(after))) throw new IOException("Scout cannot replace existing frontmatter");
        for (String heading : new String[]{"My take", "My ideas", "Cards"}) {
            String old = section(before, heading);
            if (cards && heading.equals("Cards")) {
                for (Cards.Card existing : Cards.parse("", before)) {
                    String marker = "### " + existing.id;
                    int start = before.indexOf(marker), end = before.indexOf("\n### ", start + marker.length());
                    if (end < 0) end = before.length();
                    String oldBlock = before.substring(start, end).stripTrailing();
                    if (!after.contains(oldBlock)) throw new IOException("Existing card block changed");
                }
                continue;
            }
            if (!old.isEmpty() && !old.equals(section(after, heading))) throw new IOException("Scout must preserve " + heading + " exactly");
        }
    }
    private static String frontmatter(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\A---\\r?\\n.*?\\r?\\n---(?:\\r?\\n|$)", java.util.regex.Pattern.DOTALL).matcher(text);
        return m.find() ? m.group() : "";
    }
    private static String section(String text, String heading) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^## " + java.util.regex.Pattern.quote(heading) + "[ \\t]*\\r?$(?:\\r?\\n)?").matcher(text);
        if (!m.find()) return "";
        int start = m.start(), end = text.length();
        java.util.regex.Matcher next = java.util.regex.Pattern.compile("(?m)^#{1,2} ").matcher(text);
        if (next.find(m.end())) end = next.start();
        return text.substring(start, end);
    }
}
