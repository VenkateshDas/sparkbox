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
        public final long secs;
        Row(String kind, String text, long secs) { this.kind = kind; this.text = text; this.secs = secs; }
    }

    public static final long UNKNOWN = -1, RUNNING = -2;

    /** 83 seconds as "1:23". */
    public static String clock(long secs) { return (secs / 60) + ":" + String.format(java.util.Locale.US, "%02d", secs % 60); }

    /** The line for one tool call. */
    public static String step(String tool, JSONObject a) {
        if (a == null) a = new JSONObject();
        switch (tool) {
            case "search": return "Searched “" + a.optString("query") + "”";
            case "grep": return "Looked for “" + a.optString("pattern") + "”";
            case "find": return "Found notes matching " + a.optString("pattern");
            case "ls": return "Looked in " + (a.optString("path").isEmpty() ? "the vault" : a.optString("path"));
            case "read": return "Read " + a.optString("path");
            case "inbox": return "Looked at the inbox";
            case "edit": return "Edited " + a.optString("path");
            case "write": return "Wrote " + a.optString("path");
            default: return "Used " + (tool.startsWith("mcp__") ? tool.substring(5).replace("__", ": ") : tool);
        }
    }

    public static List<Row> rows(List<JSONObject> messages) {
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
                out.add(new Row("user", content, 0));
                userAt = m.optLong("ts");
                continue;
            }
            if (!role.equals("assistant")) continue;
            JSONArray calls = m.optJSONArray("tool_calls");
            if (calls != null && calls.length() > 0) {
                for (int c = 0; c < calls.length(); c++) {
                    JSONObject fn = calls.optJSONObject(c) == null ? null : calls.optJSONObject(c).optJSONObject("function");
                    if (fn != null) steps.add(step(fn.optString("name"), JsonFix.object(fn.optString("arguments", "{}"))));
                }
                continue;
            }
            boolean corrected = i + 1 < messages.size() && messages.get(i + 1).optString("content").startsWith(CHECK);
            if (!content.isEmpty() && m.optString("stopReason", "stop").equals("stop") && !corrected) {
                long end = m.optLong("ts");
                if (!steps.isEmpty()) out.add(new Row("work", String.join("\n", steps), userAt > 0 && end >= userAt ? (end - userAt) / 1000 : UNKNOWN));
                steps.clear();
                out.add(new Row("answer", content, 0));
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
