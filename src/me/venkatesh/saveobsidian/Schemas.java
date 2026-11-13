package me.venkatesh.saveobsidian;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JSON Schemas for the replies the app asks models for. They go to OpenRouter as response_format json_schema (strict), so the provider constrains
 * the reply to this shape instead of the app hoping for parseable text. (Pydantic is the Python way to write the same thing; this is plain JSON Schema.)
 */
final class Schemas {
    private Schemas() { }

    private static JSONObject str() throws Exception { return new JSONObject().put("type", "string"); }
    private static JSONObject arr(JSONObject items) throws Exception { return new JSONObject().put("type", "array").put("items", items); }
    private static JSONObject strEnum(String... values) throws Exception { return new JSONObject().put("type", "string").put("enum", new JSONArray(java.util.Arrays.asList(values))); }

    /** Strict mode wants every property listed as required and no extras. */
    private static JSONObject obj(Object... nameAndSchema) throws Exception {
        JSONObject props = new JSONObject();
        JSONArray required = new JSONArray();
        for (int i = 0; i < nameAndSchema.length; i += 2) { props.put((String) nameAndSchema[i], nameAndSchema[i + 1]); required.put((String) nameAndSchema[i]); }
        return new JSONObject().put("type", "object").put("properties", props).put("required", required).put("additionalProperties", false);
    }

    private static JSONObject claim() throws Exception { return obj("claim", str(), "confidence", strEnum("high", "medium", "low")); }

    /** The ingest answer: tags and title for the capture, then claims, topics, people, entities and the recall cards. */
    static JSONObject plan() throws Exception {
        return obj(
            "type", strEnum(Enrich.TYPES.toArray(new String[0])),
            "tags", arr(str()),
            "tldr", str(),
            "title", str(),
            "category", strEnum(IngestText.CATEGORIES),
            "author", str(),
            "key_claims", arr(claim()),
            "topics", arr(obj("title", str(), "core", str(), "claims", arr(claim()), "related", arr(str()), "open_questions", arr(str()))),
            "people", arr(obj("name", str(), "role", str())),
            "entities", arr(obj("name", str(), "kind", strEnum("model", "tool", "org", "dataset", "paper", "other"), "what", str())),
            "curiosity_question", str(),
            "recall_cards", arr(obj("q", str(), "a", str(), "basis", str(), "confidence", strEnum("high", "medium"))),
            "relevance_flags", arr(str()));
    }

    /** The lane and folder decision. */
    static JSONObject lane() throws Exception {
        String[] folders = new String[Lane.CRAFT_FOLDERS.length + Lane.LIFE_FOLDERS.length];
        System.arraycopy(Lane.CRAFT_FOLDERS, 0, folders, 0, Lane.CRAFT_FOLDERS.length);
        System.arraycopy(Lane.LIFE_FOLDERS, 0, folders, Lane.CRAFT_FOLDERS.length, Lane.LIFE_FOLDERS.length);
        return obj("lane", strEnum("craft", "life"), "category", strEnum(folders), "reason", str());
    }
}
