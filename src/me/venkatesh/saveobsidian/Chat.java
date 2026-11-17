package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What a chat screen shows for a stored conversation: the user's messages, one line per tool call ("Searching …"), and the final answers. Tool results, hidden
 * correction requests and answers that were corrected stay in the log only. Also tells whether a run ended without a final answer (the app was killed, or it
 * was stopped), so the screen can offer to continue. Pure Java.
 */
public final class Chat {
    /** Marks a message the app sent on the model's behalf (the citation check): never shown. */
    public static final String CHECK = "[check] ";

    private Chat() { }

    /** kind: user | work | answer. A work row is a turn's tool steps (text, one per line); secs is how long the turn took, UNKNOWN, or RUNNING for a turn with no answer yet. */
    public static final class Row {
        public final String kind, text;
        public String id = "";
        public final long secs;
        Row(String kind, String text, long secs) { this.kind = kind; this.text = text; this.secs = secs; }
    }

    public static final long UNKNOWN = -1, RUNNING = -2;

    /** 83 seconds as "1:23". */
    public static String clock(long secs) { return (secs / 60) + ":" + String.format(java.util.Locale.US, "%02d", secs % 60); }

    /** An attempt is not evidence of a successful operation. */
    public static String step(String tool, JSONObject a) {
        if (a == null) a = new JSONObject();
        switch (tool) {
            case "search": return "Search “" + a.optString("query") + "”";
            case "grep": return "Find text “" + a.optString("pattern") + "”";
            case "find": return "Find notes " + a.optString("pattern");
            case "ls": return "List " + (a.optString("path").isEmpty() ? "vault" : a.optString("path"));
            case "read": return "Read " + a.optString("path");
            case "inbox": return "Read inbox";
            case "edit": return "Edit " + a.optString("path");
            case "write": return "Write " + a.optString("path");
            default: return "Call " + (tool.startsWith("mcp__") ? tool.substring(5).replace("__", ": ") : tool);
        }
    }

    private static String outcome(List<JSONObject> messages, int at, String id, java.util.Map<String, String> live) {
        for (int i = at + 1; i < messages.size(); i++) {
            JSONObject m = messages.get(i);
            if (m.optString("role").equals("assistant") || m.optString("role").equals("user")) break;
            if (!m.optString("role").equals("tool") || !id.equals(m.optString("tool_call_id"))) continue;
            if (!m.has("isError")) return "Result received";
            if (!m.optBoolean("isError")) return "Done";
            return m.optString("content").toLowerCase(java.util.Locale.ROOT).contains("declined") ? "Declined" : "Failed";
        }
        return live.getOrDefault(id, "");
    }

    public static List<Row> rows(List<JSONObject> messages) { return rows(messages, java.util.Collections.emptyMap()); }

    public static List<Row> rows(List<JSONObject> messages, java.util.Map<String, String> live) {
        List<Row> out = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        long userAt = 0;
        for (int i = 0; i < messages.size(); i++) {
            JSONObject m = messages.get(i);
            String role = m.optString("role"), content = m.optString("content", "");
            if (m.optBoolean("summary")) continue;
            if (role.equals("user")) {
                if (content.startsWith(CHECK)) continue;
                if (!steps.isEmpty()) { out.add(new Row("work", String.join("\n", steps), UNKNOWN)); steps.clear(); } // steps of a turn that a new message cut short
                Row user = new Row("user", content, 0); user.id = m.optString("entryId"); out.add(user);
                userAt = m.optLong("ts");
                continue;
            }
            if (!role.equals("assistant")) continue;
            JSONArray calls = m.optJSONArray("tool_calls");
            if (calls != null && calls.length() > 0) {
                for (int c = 0; c < calls.length(); c++) {
                    JSONObject fn = calls.optJSONObject(c) == null ? null : calls.optJSONObject(c).optJSONObject("function");
                    if (fn != null) {
                        String status = outcome(messages, i, calls.optJSONObject(c).optString("id"), live);
                        steps.add((status.isEmpty() ? "" : status + ": ") + step(fn.optString("name"), JsonFix.object(fn.optString("arguments", "{}"))));
                    }
                }
                continue;
            }
            String stop = m.optString("stopReason", "stop");
            if (stop.equals("error") || stop.equals("aborted") || stop.equals("max-steps")) {
                long end = m.optLong("ts");
                if (!steps.isEmpty()) out.add(new Row("work", String.join("\n", steps), userAt > 0 && end >= userAt ? (end - userAt) / 1000 : UNKNOWN));
                steps.clear();
            }
            boolean corrected = i + 1 < messages.size() && messages.get(i + 1).optString("content").startsWith(CHECK);
            if (!content.isEmpty() && (m.optString("stopReason", "stop").equals("stop") || m.optString("stopReason").equals("length")) && !corrected) {
                long end = m.optLong("ts");
                if (!steps.isEmpty()) out.add(new Row("work", String.join("\n", steps), userAt > 0 && end >= userAt ? (end - userAt) / 1000 : UNKNOWN));
                steps.clear();
                Row answer = new Row("answer", content, 0); answer.id = m.optString("entryId"); out.add(answer);
            }
        }
        if (!steps.isEmpty()) out.add(new Row("work", String.join("\n", steps), RUNNING)); // a turn still going, or cut off
        return out;
    }

    /** True when the conversation does not end in a final answer: nothing is running for it, so it was cut off or stopped. */
    public static boolean interrupted(List<JSONObject> messages) {
        if (messages.isEmpty()) return false;
        JSONObject last = messages.get(messages.size() - 1);
        if (last.optString("role").equals("assistant")) {
            JSONArray calls = last.optJSONArray("tool_calls");
            return (calls != null && calls.length() > 0) || !last.optString("stopReason", "stop").equals("stop");
        }
        return true; // a user message or a tool result with no answer after it
    }
}
