package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Validated catalog rows; capability metadata narrows selection, not runtime guarantees. */
public final class ModelCatalog {
    public static JSONArray parse(JSONObject response) throws Exception {
        JSONArray data = response.getJSONArray("data"), out = new JSONArray();
        for (int i = 0; i < data.length(); i++) {
            JSONObject m = data.optJSONObject(i);
            if (m == null || m.optString("id").trim().isEmpty()) continue;
            out.put(m);
        }
        if (out.length() == 0) throw new IllegalArgumentException("Model catalog is empty");
        return out;
    }
    public static List<JSONObject> search(JSONArray data, String query, boolean tools) {
        List<JSONObject> out = new ArrayList<>();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < data.length(); i++) {
            JSONObject m = data.optJSONObject(i);
            if (m == null) continue;
            if (tools && !contains(m.optJSONArray("supported_parameters"), "tools")) continue;
            if (!(m.optString("name") + " " + m.optString("id")).toLowerCase(Locale.ROOT).contains(q)) continue;
            JSONObject a = m.optJSONObject("architecture");
            if (a != null && a.has("output_modalities") && !contains(a.optJSONArray("output_modalities"), "text")) continue;
            out.add(m);
        }
        out.sort((a, b) -> a.optString("name", a.optString("id")).compareToIgnoreCase(b.optString("name", b.optString("id"))));
        return out;
    }
    private static boolean contains(JSONArray a, String value) {
        for (int i = 0; a != null && i < a.length(); i++) if (value.equals(a.optString(i))) return true;
        return false;
    }
}
