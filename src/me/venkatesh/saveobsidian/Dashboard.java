package me.venkatesh.saveobsidian;

import java.time.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure inventory reducers. Uses the same parsed notes, cards and graph as Feed and Spark. */
public final class Dashboard {
    private Dashboard() { }
    public static final class Cohort {
        public final List<JSONObject> operations = new ArrayList<>(), priced = new ArrayList<>(), tokenKnown = new ArrayList<>();
        public final Map<String, List<JSONObject>> requests = new HashMap<>();
        public int excluded;
    }
    /** Whole-operation cohort. Request-date totals and operation-start unit averages are different scopes. */
    public static Cohort cohort(JSONObject data, long since, String workflow, String model) {
        Cohort out = new Cohort();
        JSONArray requests = data.optJSONArray("requests"), ops = data.optJSONArray("operations");
        for (int i = 0; requests != null && i < requests.length(); i++) {
            JSONObject r = requests.optJSONObject(i); if (r == null) continue;
            out.requests.computeIfAbsent(r.optString("operationId"), k -> new ArrayList<>()).add(r);
        }
        for (int i = 0; ops != null && i < ops.length(); i++) {
            JSONObject op = ops.optJSONObject(i);
            if (op == null || op.optLong("startedAt") <= 0 || op.optLong("startedAt") < since || (!workflow.equals("All") && !workflow.equals(op.optString("workflow")))) continue;
            List<JSONObject> rows = out.requests.getOrDefault(op.optString("operationId"), Collections.emptyList());
            boolean whole = model.equals("All") || !rows.isEmpty() && rows.stream().allMatch(r -> model.equals(r.optString("actualModel").isEmpty() ? r.optString("model") : r.optString("actualModel")));
            if (!whole) { out.excluded++; continue; }
            out.operations.add(op);
            if (rows.isEmpty() || !Arrays.asList("completed", "complete", "saved").contains(op.optString("status"))) continue;
            if (rows.stream().allMatch(r -> r.optJSONObject("usage") != null && !r.optJSONObject("usage").isNull("cost"))) out.priced.add(op);
            if (rows.stream().allMatch(r -> r.optJSONObject("usage") != null && !r.optJSONObject("usage").isNull("prompt_tokens") && !r.optJSONObject("usage").isNull("completion_tokens"))) out.tokenKnown.add(op);
        }
        return out;
    }
    private static void count(JSONObject counts, String key) throws Exception { counts.put(key, counts.optInt(key) + 1); }
    static boolean content(Vault.Note n) { return !n.catalog && !n.path.endsWith("/Inbox.md") && !n.path.endsWith("/My cards.md"); }
    public static JSONObject inventory(Vault.Graph graph, List<Cards.Card> cards) throws Exception {
        JSONObject counts = new JSONObject(), quality = new JSONObject(), categories = new JSONObject();
        JSONArray notes = new JSONArray(), unresolved = new JSONArray(), isolated = new JSONArray();
        Map<String, Integer> cardCounts = new HashMap<>();
        for (Cards.Card c : cards) cardCounts.merge(c.path, 1, Integer::sum);
        int sources = 0, withCards = 0;
        for (Vault.Note n : graph.notes) {
            if (!content(n)) continue;
            String kind = Layout.inSources(n.path) ? "Sources" : n.path.startsWith("Knowledge/Topics/") ? "Topics" : n.path.startsWith("Knowledge/People/") ? "People" : n.path.startsWith("Knowledge/Entities/") ? "Entities" : n.path.startsWith("Knowledge/Learnings/") ? "Learnings" : "Other";
            count(counts, kind);
            int nc = cardCounts.getOrDefault(n.path, 0);
            notes.put(new JSONObject().put("path", n.path).put("title", n.title).put("kind", kind).put("captured", n.meta("captured"))
                .put("quality", n.meta("quality")).put("cards", nc).put("kept", n.meta("kept").equalsIgnoreCase("true")).put("topics", new JSONArray(n.topics)));
            if (kind.equals("Sources")) {
                sources++; if (nc > 0) withCards++;
                count(quality, n.meta("quality").isEmpty() ? "unknown" : n.meta("quality"));
                String base = n.path.substring((n.path.startsWith(Layout.NEW) ? Layout.NEW : Layout.OLD).length() + 1);
                count(categories, base.contains("/") ? base.substring(0, base.indexOf('/')) : "Uncategorised");
            }
            for (String link : n.links) if (graph.resolve(link) == null) unresolved.put(new JSONObject().put("path", n.path).put("target", link));
            if (graph.in(n).isEmpty() && graph.out(n).isEmpty()) isolated.put(n.path);
        }
        return new JSONObject().put("counts", counts).put("notes", notes).put("sourceCount", sources).put("sourceCards", withCards)
            .put("quality", quality).put("categories", categories).put("unresolved", unresolved).put("isolated", isolated);
    }
    public static JSONObject recall(List<Cards.Card> cards, long now, ZoneId zone) throws Exception {
        int fresh = 0, due = 0, parked = 0, flagged = 0, reviewed = 0, attempts = 0, weak = 0, take = 0;
        JSONObject grades = new JSONObject(), days = new JSONObject();
        for (Cards.Card c : cards) {
            if (c.flag) flagged++; else if (c.parked > now) parked++; else { if (c.state == null) fresh++; else if (c.state.due <= now) due++; if (Study.weak(c)) weak++; }
            if (c.state != null) reviewed++; if (!c.take.isEmpty()) take++;
            for (String log : c.log.split(";")) {
                String[] parts = log.trim().split("\\s+"); if (parts.length < 2) continue;
                long time = Cards.parseTime(parts[0]); if (time <= 0) continue;
                attempts++; count(grades, parts[1]); count(days, Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toString());
            }
        }
        return new JSONObject().put("total", cards.size()).put("new", fresh).put("due", due).put("parked", parked).put("flagged", flagged)
            .put("reviewed", reviewed).put("attempts", attempts).put("grades", grades).put("days", days).put("weak", weak).put("withTake", take);
    }
}
