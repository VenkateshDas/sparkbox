package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Port of pi packages/coding-agent/src/core/compaction/compaction.ts + docs/compaction.md @ 3e23dd9, as an Agent hook. When the context nears the
 * window the older part of the conversation is summarised by the model into a fixed template, stored as a compaction entry in the Session (the
 * original entries stay in the file), and the agent continues from summary + recent messages. Differences: one summary call also covers the start
 * of a turn that is itself too big (Pi makes two and merges them); the context-overflow patterns are the ones OpenRouter and the usual providers
 * send. Pure Java.
 */
public final class Compact implements Agent.Hooks {
    public static final String PREFIX = "The earlier conversation was compacted into this summary. Continue from it.\n\n";
    /** Pi's defaults: compact when tokens exceed window - reserve; keep about this many recent tokens. */
    public int reserve = 16_384, keepRecent = 20_000, summaryTokens = 2000;
    private static final int TOOL_CAP = 2000;
    private static final Pattern OVERFLOW = Pattern.compile("context[ _]length|maximum context|context window|context_length_exceeded|too many tokens|prompt is too long|input is too long|exceeds the limit of|request entity too large|payload too large| 413", Pattern.CASE_INSENSITIVE);
    private static final String SYSTEM = "You summarise a conversation between a user and an assistant that works with the user's notes, so the assistant can continue it later. "
        + "Do not continue the conversation and do not answer anything in it. Output only the summary.";
    private static final String TEMPLATE = "Write the summary in exactly this structure:\n## Goal\n## Constraints & Preferences\n## Progress\n### Done\n### In Progress\n### Blocked\n## Key Decisions\n## Next Steps\n## Critical Context\n"
        + "Keep exact note paths, titles, names and quoted facts. Be concise.";

    private final Agent.Provider provider;
    private final String model;
    private final Session session;
    private final int window;

    public Compact(Agent.Provider provider, String model, Session session, int window) {
        this.provider = provider; this.model = model; this.session = session; this.window = window;
        reserve = Math.min(reserve, Math.max(512, window / 4));
        keepRecent = Math.min(keepRecent, Math.max(256, window / 4));
        summaryTokens = Math.min(summaryTokens, Math.max(256, window / 8));
    }

    @Override public void prepareNextTurn(Agent agent) {
        try {
            long input = SessionUsage.estimate(SessionUsage.describe(agent.contextRequest(), window), SessionUsage.requests(session));
            if (input > window - Math.max(reserve, agent.maxTokens)) run(agent, "threshold", null);
        } catch (Exception unavailable) { if (estimate(agent.messages()) > window - reserve) run(agent, "threshold", null); }
    }

    /** Provider refused the request as too long: compact once, then the agent retries. */
    @Override public boolean onOverflow(Agent agent) { return run(agent, "overflow", null); }

    /** The owner asked for it (optionally with what the summary should focus on). Not while the agent is running. */
    public boolean now(Agent agent, String instructions) {
        if (agent.running()) throw new IllegalStateException("Agent is running");
        return run(agent, "manual", instructions);
    }

    public static boolean overflow(String message) { return message != null && OVERFLOW.matcher(message).find(); }

    // ---- sizes ----

    private static int chars(JSONObject m) {
        int n = m.optString("content", "").length();
        JSONArray calls = m.optJSONArray("tool_calls");
        for (int i = 0; calls != null && i < calls.length(); i++) { JSONObject fn = calls.optJSONObject(i) == null ? null : calls.optJSONObject(i).optJSONObject("function"); if (fn != null) n += fn.optString("name").length() + fn.optString("arguments").length(); }
        return n;
    }

    /** Pi's estimate: what the provider last reported for the context, plus chars/4 for everything after that message. */
    public static int estimate(List<JSONObject> messages) {
        int last = -1, tokens = 0;
        for (int i = messages.size() - 1; i >= 0 && last < 0; i--) {
            JSONObject u = messages.get(i).optString("role").equals("assistant") ? messages.get(i).optJSONObject("usage") : null;
            if (u != null && u.optInt("prompt_tokens") > 0) { last = i; tokens = u.optInt("prompt_tokens") + u.optInt("completion_tokens"); }
        }
        for (int i = last + 1; i < messages.size(); i++) tokens += chars(messages.get(i)) / 4 + 1;
        return tokens;
    }

    /**
     * Index of the first message to keep, or 0 when there is nothing worth cutting. Walks back until keepRecent tokens are kept, then moves to a user
     * message at or before that point (never a tool result, so a call stays with its result); with no user message in reach, an assistant message.
     */
    public static int cutIndex(List<JSONObject> msgs, int keepRecent) {
        int first = !msgs.isEmpty() && msgs.get(0).optBoolean("summary") ? 1 : 0;
        int tokens = 0, point = -1;
        for (int i = msgs.size() - 1; i >= first; i--) { tokens += chars(msgs.get(i)) / 4 + 1; if (tokens >= keepRecent) { point = i; break; } }
        if (point < 0) return 0; // everything fits in what we keep
        for (int i = point; i > first; i--) if (msgs.get(i).optString("role").equals("user")) return i;
        for (int i = point; i > first; i--) if (msgs.get(i).optString("role").equals("assistant")) return i;
        return 0;
    }

    // ---- summary ----

    private boolean run(Agent agent, String reason, String instructions) {
        agent.emit("compaction_start", wrap("reason", reason));
        String why = compact(agent, instructions);
        try { agent.emit("compaction_end", new JSONObject().put("ok", why == null).put("why", why == null ? "" : why)); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return why == null;
    }

    private static JSONObject wrap(String key, Object v) { try { return new JSONObject().put(key, v); } catch (Exception impossible) { throw new IllegalStateException(impossible); } }

    /** Null when the conversation was compacted; otherwise why it was not. */
    private String compact(Agent agent, String instructions) {
        List<JSONObject> msgs = agent.messages();
        int cut = cutIndex(msgs, keepRecent);
        if (cut <= 0 || msgs.get(cut).optString("entryId").isEmpty()) return "nothing old enough to cut";
        boolean hasPrevious = msgs.get(0).optBoolean("summary");
        String previous = hasPrevious ? msgs.get(0).optString("content").substring(PREFIX.length()) : null;
        List<JSONObject> old = new ArrayList<>(msgs.subList(hasPrevious ? 1 : 0, cut));
        int before = estimate(msgs);
        try {
            JSONObject request = new JSONObject().put("model", model).put("max_tokens", summaryTokens).put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", SYSTEM))
                .put(new JSONObject().put("role", "user").put("content", prompt(previous, old) + (instructions == null || instructions.trim().isEmpty() ? "" : "\n\nThe user asks the summary to focus on: " + instructions.trim()))));
            JSONObject reply = provider.stream(request, text -> { }, agent.cancel());
            String summary = reply.optString("content", "").trim();
            if (summary.isEmpty() || reply.optString("finish_reason").equals("length")) return "the summary came back empty or cut off"; // a cut-off summary is worse than none
            session.appendUsage("compaction", reply.optJSONObject("usage"));
            session.appendCompaction(summary + files(previous, old), msgs.get(cut).optString("entryId"), before);
            agent.setMessages(session.context());
            return null;
        } catch (Exception failed) { return "the summary call failed: " + failed.getMessage(); } // keep going uncompacted; the provider's own overflow error is the backstop
    }

    public static String prompt(String previous, List<JSONObject> old) {
        StringBuilder s = new StringBuilder("<conversation>\n").append(serialize(old)).append("</conversation>\n\n");
        if (previous != null) s.append("<previous-summary>\n").append(previous.replaceAll("(?s)\\n*<(read|modified)-files>.*?</\\1-files>", "")).append("\n</previous-summary>\n\nUpdate the previous summary with what happened in the conversation: keep what still matters, move finished items to Done, add new decisions and next steps.\n\n");
        return s.append(TEMPLATE).toString();
    }

    /** Labelled text, so the summariser reads it as material and does not continue it. Tool results are capped. */
    public static String serialize(List<JSONObject> msgs) {
        StringBuilder s = new StringBuilder();
        for (JSONObject m : msgs) {
            String role = m.optString("role"), content = m.optString("content", "");
            if (role.equals("user")) s.append("[User]: ").append(content).append("\n\n");
            else if (role.equals("assistant")) {
                if (!content.isEmpty()) s.append("[Assistant]: ").append(content).append("\n\n");
                JSONArray calls = m.optJSONArray("tool_calls");
                for (int i = 0; calls != null && i < calls.length(); i++) { JSONObject fn = calls.optJSONObject(i).optJSONObject("function"); if (fn != null) s.append("[Assistant tool call]: ").append(fn.optString("name")).append("(").append(fn.optString("arguments")).append(")\n\n"); }
            } else if (role.equals("tool")) s.append("[Tool result]: ").append(content.length() > TOOL_CAP ? content.substring(0, TOOL_CAP) + "…" : content).append("\n\n");
        }
        return s.toString();
    }

    private static final Pattern LIST = Pattern.compile("(?s)<(read|modified)-files>\\n(.*?)\\n</\\1-files>");

    /** Note paths read and changed, carried across compactions (read, edit and write tool calls with a path). */
    public static String files(String previous, List<JSONObject> old) {
        Set<String> read = new LinkedHashSet<>(), modified = new LinkedHashSet<>();
        if (previous != null) {
            Matcher m = LIST.matcher(previous);
            while (m.find()) for (String p : m.group(2).split("\n")) if (!p.isEmpty()) (m.group(1).equals("read") ? read : modified).add(p);
        }
        for (JSONObject msg : old) {
            JSONArray calls = msg.optJSONArray("tool_calls");
            for (int i = 0; calls != null && i < calls.length(); i++) {
                JSONObject fn = calls.optJSONObject(i).optJSONObject("function");
                if (fn == null) continue;
                JSONObject args = JsonFix.object(fn.optString("arguments"));
                String path = args == null ? "" : args.optString("path");
                if (path.isEmpty()) continue;
                if (fn.optString("name").equals("read")) read.add(path);
                else if (fn.optString("name").equals("edit") || fn.optString("name").equals("write")) modified.add(path);
            }
        }
        read.removeAll(modified);
        StringBuilder s = new StringBuilder();
        if (!read.isEmpty()) s.append("\n\n<read-files>\n").append(String.join("\n", read)).append("\n</read-files>");
        if (!modified.isEmpty()) s.append("\n\n<modified-files>\n").append(String.join("\n", modified)).append("\n</modified-files>");
        return s.toString();
    }
}
