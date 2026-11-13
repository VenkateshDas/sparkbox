package me.venkatesh.saveobsidian;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Port of pi packages/ai/src/utils/validation.ts @ 3e23dd9, trimmed to what tool schemas here use: type, properties, required, items.
 * Coerces loose arguments in place ("5" to 5, "true" to true, 5 to "5") and reports the first problem with a dotted path
 * (edits.0.oldText) so the model can fix its call. Pure Java.
 */
public final class Validate {
    private Validate() { }

    /** Null when args fit the schema (after coercion); otherwise one sentence for the model. */
    public static String check(JSONObject schema, JSONObject args) {
        try { return object(schema, args, ""); } catch (org.json.JSONException unreadable) { return "the arguments could not be read"; } // checked on Android, unchecked in the JVM checks
    }

    private static String object(JSONObject schema, JSONObject value, String path) throws org.json.JSONException {
        JSONObject props = schema.optJSONObject("properties");
        JSONArray required = schema.optJSONArray("required");
        for (int i = 0; required != null && i < required.length(); i++)
            if (!value.has(required.optString(i)) || value.isNull(required.optString(i))) return at(path, required.optString(i)) + ": required";
        if (props == null) return null;
        for (java.util.Iterator<String> names = props.keys(); names.hasNext(); ) { // keys(): org.json on older Android has no keySet()
            String key = names.next();
            if (!value.has(key) || value.isNull(key)) continue;
            String problem = field(props.getJSONObject(key), value, key, at(path, key));
            if (problem != null) return problem;
        }
        return null;
    }

    /** Checks (and coerces) holder[key] against its schema. */
    private static String field(JSONObject schema, Object holder, Object key, String path) throws org.json.JSONException {
        Object v = holder instanceof JSONObject ? ((JSONObject) holder).get((String) key) : ((JSONArray) holder).get((Integer) key);
        String type = schema.optString("type", "");
        Object fixed = v;
        switch (type) {
            case "string":
                if (v instanceof Number || v instanceof Boolean) fixed = String.valueOf(v);
                else if (!(v instanceof String)) return path + ": expected string";
                break;
            case "integer":
            case "number":
                if (v instanceof String) {
                    try { double d = Double.parseDouble(((String) v).trim()); fixed = d == Math.rint(d) ? (Object) (long) d : (Object) d; }
                    catch (NumberFormatException bad) { return path + ": expected " + type; }
                } else if (!(v instanceof Number)) return path + ": expected " + type;
                if (type.equals("integer") && fixed instanceof Number && ((Number) fixed).doubleValue() != Math.rint(((Number) fixed).doubleValue())) return path + ": expected integer";
                break;
            case "boolean":
                if (v instanceof String && (v.equals("true") || v.equals("false"))) fixed = Boolean.valueOf((String) v);
                else if (!(v instanceof Boolean)) return path + ": expected boolean";
                break;
            case "array": {
                if (!(v instanceof JSONArray)) return path + ": expected array";
                JSONObject items = schema.optJSONObject("items");
                for (int i = 0; items != null && i < ((JSONArray) v).length(); i++) {
                    String problem = field(items, v, i, path + "." + i);
                    if (problem != null) return problem;
                }
                break;
            }
            case "object":
                if (!(v instanceof JSONObject)) return path + ": expected object";
                return object(schema, (JSONObject) v, path);
            default: break; // no type: anything goes
        }
        if (fixed != v) { if (holder instanceof JSONObject) ((JSONObject) holder).put((String) key, fixed); else ((JSONArray) holder).put((Integer) key, fixed); }
        return null;
    }

    private static String at(String path, String key) { return path.isEmpty() ? key : path + "." + key; }
}
