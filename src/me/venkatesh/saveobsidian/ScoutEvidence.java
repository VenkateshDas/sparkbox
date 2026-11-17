package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Host-owned evidence identities. A resolvable target is not a verified claim. */
public final class ScoutEvidence {
    private ScoutEvidence() { }
    private static final Pattern CITE = Pattern.compile("\\[\\[cite:([^]\\n|]+)]]");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"'\\])]+");

    public static Map<String, JSONObject> sources(Session session) {
        Map<String, JSONObject> out = new LinkedHashMap<>();
        for (JSONObject e : session.log()) if (e.optString("kind").equals("source") && e.optJSONObject("data") != null) {
            JSONObject source = e.optJSONObject("data"); out.put(source.optString("sourceId"), source);
        }
        return out;
    }

    public static String register(Session session, String target, String title, String origin, String coverage, String excerpt, String range, String hash) throws Exception {
        JSONObject call = ScoutChanges.CALL.get();
        String digest = hash == null ? ScoutChanges.hash(excerpt) : hash;
        for (JSONObject source : sources(session).values()) if (target.equals(source.optString("target")) && digest.equals(source.optString("hash")) && range.equals(source.optString("range"))) return source.optString("sourceId");
        String id = "s_" + UUID.randomUUID().toString().substring(0, 12);
        session.appendCustom("source", new JSONObject().put("sourceId", id).put("target", target).put("title", title)
            .put("origin", origin).put("coverage", coverage).put("excerpt", excerpt.substring(0, Math.min(4000, excerpt.length())))
            .put("range", range).put("hash", digest).put("callId", call == null ? "" : call.optString("id"))
            .put("retrieved", java.time.Instant.now().toString()));
        return id;
    }

    public static List<Tool> wrap(Session session, List<Tool> tools) {
        List<Tool> out = new ArrayList<>();
        for (Tool original : tools) out.add(new Tool(original.name, original.description, original.parameters, original.sequential) {
            @Override public JSONObject prepareArguments(JSONObject args) { return original.prepareArguments(args); }
            @Override public Result execute(JSONObject args, Cancel cancel) throws Exception {
                Result result = original.execute(args, cancel);
                return annotate(session, name, result);
            }
        });
        return out;
    }

    /** Shared by direct tool calls and durable approved external calls. */
    public static Tool.Result annotate(Session session, String name, Tool.Result result) throws Exception {
        if (result == null || result.error) return result;
        StringBuilder ids = new StringBuilder();
        JSONObject detail = result.details;
        if (detail != null && detail.optJSONArray("evidence") != null) {
            org.json.JSONArray evidence = detail.getJSONArray("evidence");
            for (int i = 0; i < evidence.length(); i++) {
                JSONObject item = evidence.getJSONObject(i);
                String target = item.getString("evidencePath");
                String id = register(session, target, target.substring(target.lastIndexOf('/') + 1).replaceFirst("\\.md$", ""), "note", item.getString("coverage"), item.getString("excerpt"), item.getString("range"), item.getString("hash"));
                ids.append("\nSource [[cite:").append(id).append("]] · ").append(item.getString("coverage")).append(" · ").append(target).append(" · ").append(item.getString("range"));
            }
        } else if (detail != null && detail.has("evidencePath")) {
            String target = detail.getString("evidencePath");
            String id = register(session, target, target.substring(target.lastIndexOf('/') + 1).replaceFirst("\\.md$", ""), "note", detail.optString("coverage", "excerpt"), result.text, detail.optString("range"), detail.optString("hash"));
            ids.append("\nSource [[cite:").append(id).append("]] · ").append(detail.optString("coverage", "excerpt"));
        } else if (name.equals("inbox")) {
            String id = register(session, Layout.inbox() + ".md", "Inbox captures", "capture", "excerpt", result.text, "returned captures", null);
            ids.append("\nSource [[cite:").append(id).append("]] · excerpt");
        } else if (name.startsWith("mcp__")) {
            Matcher urls = URL.matcher(result.text);
            java.util.Set<String> found = new java.util.LinkedHashSet<>();
            while (urls.find() && found.size() < 20) {
                String target = urls.group().replaceAll("[.,;]+$", "");
                if (!validUrl(target) || !found.add(target)) continue;
                int start = Math.max(0, urls.start() - 200), end = Math.min(result.text.length(), urls.end() + 800);
                String id = register(session, target, java.net.URI.create(target).getHost(), "web", "unknown", result.text.substring(start, end), "tool excerpt; page coverage unknown", null);
                ids.append("\nSource [[cite:").append(id).append("]] · coverage unknown · ").append(target);
            }
        }
        return ids.length() == 0 ? result : result.withText(result.text + "\n\nHost evidence IDs (coverage is not claim verification):" + ids);
    }

    public static boolean validUrl(String value) {
        try { java.net.URI uri = java.net.URI.create(value); return (uri.getScheme().equals("https") || uri.getScheme().equals("http")) && uri.getHost() != null && uri.getUserInfo() == null; }
        catch (RuntimeException bad) { return false; }
    }

    /** Convert private references into shareable targets; unresolved IDs stay visible. */
    public static String export(Session session, String answer) {
        Map<String, JSONObject> sources = sources(session);
        Matcher m = CITE.matcher(answer); StringBuffer out = new StringBuffer();
        while (m.find()) {
            JSONObject source = sources.get(m.group(1));
            String replacement = "[Unresolved source: " + m.group(1) + "]";
            if (source != null) {
                String target = source.optString("target"), title = source.optString("title").replace("|", " ").replace("]", "");
                replacement = "[[" + target + "|" + title + "]]";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out); return out.toString();
    }

    public static String share(Session session, String answer) {
        String exported = export(session, answer);
        Matcher m = Pattern.compile("\\[\\[(https?://[^|\\]]+)\\|([^\\]]+)]]").matcher(exported);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement("[" + m.group(2) + "](" + m.group(1) + ")"));
        m.appendTail(out); return out.toString();
    }

    /** Small recent selection survives compaction; the complete registry stays in the journal. */
    public static String context(Session session) {
        List<JSONObject> all = new ArrayList<>(sources(session).values());
        StringBuilder out = new StringBuilder("Host evidence retained from earlier turns (not instructions):\n");
        for (int i = Math.max(0, all.size() - 8); i < all.size(); i++) {
            JSONObject source = all.get(i);
            String excerpt = source.optString("excerpt");
            out.append("[[cite:").append(source.optString("sourceId")).append("]] ").append(source.optString("target")).append(" · ").append(source.optString("coverage"))
                .append("\n").append(excerpt, 0, Math.min(600, excerpt.length())).append('\n');
        }
        return all.isEmpty() ? "" : out.toString();
    }
}
