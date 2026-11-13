package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * Gets the JSON object out of a model reply that is almost JSON: wrapped in a Markdown fence or a sentence, ending in trailing commas,
 * holding raw line breaks inside strings, or cut off by the token limit. Pure Java so the JVM checks cover it.
 */
public final class JsonFix {
    private JsonFix() { }

    /** The first JSON object in the reply, repaired if needed. Null when nothing usable can be made of it. */
    public static JSONObject object(String reply) {
        if (reply == null) return null;
        int from = reply.indexOf('{');
        if (from < 0) return null;
        String body = reply.substring(from);
        int to = body.lastIndexOf('}');
        JSONObject o = tryParse(to > 0 ? body.substring(0, to + 1) : body);
        if (o != null) return o;
        o = tryParse(clean(body));
        if (o != null) return o;
        return tryParse(clean(close(body)));
    }

    private static JSONObject tryParse(String s) {
        try { return new JSONObject(s); } catch (Exception bad) { return null; }
    }

    /** Trailing commas before a closing bracket, and raw line breaks or tabs inside strings, which JSON does not allow. */
    static String clean(String s) {
        StringBuilder out = new StringBuilder();
        boolean in = false, escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (in) {
                if (escape) { escape = false; out.append(c); }
                else if (c == '\\') { escape = true; out.append(c); }
                else if (c == '"') { in = false; out.append(c); }
                else if (c == '\n') out.append("\\n");
                else if (c == '\r') { /* dropped */ }
                else if (c == '\t') out.append(' ');
                else out.append(c);
                continue;
            }
            if (c == '"') { in = true; out.append(c); continue; }
            if ((c == '}' || c == ']')) {
                int k = out.length() - 1;
                while (k >= 0 && Character.isWhitespace(out.charAt(k))) k--;
                if (k >= 0 && out.charAt(k) == ',') out.delete(k, out.length());
            }
            out.append(c);
        }
        return out.toString();
    }

    /** A reply cut off in the middle: drops the unfinished last item and closes what is still open. */
    static String close(String s) {
        List<Character> open = new ArrayList<>();
        boolean in = false, escape = false;
        int safe = -1;
        String closers = "";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (in) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') in = false;
                continue;
            }
            if (c == '"') in = true;
            else if (c == '{') open.add('}');
            else if (c == '[') open.add(']');
            else if ((c == '}' || c == ']') && !open.isEmpty()) open.remove(open.size() - 1);
            else if (c == ',' && !open.isEmpty()) { safe = i; StringBuilder b = new StringBuilder(); for (int k = open.size() - 1; k >= 0; k--) b.append(open.get(k)); closers = b.toString(); }
        }
        if (open.isEmpty() && !in) return s;
        if (safe < 0) return s;
        return s.substring(0, safe) + closers;
    }
}
