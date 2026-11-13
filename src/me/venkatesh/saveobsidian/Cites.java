package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/** Turns the model's [[path|Title]] citations into numbered markers and a source list. Pure Java so the JVM checks cover it. */
public final class Cites {
    public static final String NOT_IN_KB = "Not in your knowledge base";

    /** The answer with each citation replaced by [n], and the cited notes in order of first appearance as {n, path, title}. */
    public static final class Result {
        public final String text;
        public final List<String[]> sources;
        Result(String text, List<String[]> sources) { this.text = text; this.sources = sources; }
    }

    private Cites() { }

    public static Result number(String answer) {
        Map<String, Integer> seen = new HashMap<>();
        List<String[]> sources = new ArrayList<>();
        Matcher m = Vault.LINK.matcher(answer);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            if (m.group(1).equals("!")) { m.appendReplacement(out, Matcher.quoteReplacement(m.group())); continue; } // an embed, not a citation
            String path = Vault.target(m.group(2));
            if (path.isEmpty()) { m.appendReplacement(out, Matcher.quoteReplacement(m.group())); continue; }
            String key = path.toLowerCase(Locale.ROOT);
            Integer n = seen.get(key);
            if (n == null) {
                n = sources.size() + 1;
                seen.put(key, n);
                sources.add(new String[]{String.valueOf(n), path, Vault.label(m.group(2))});
            }
            m.appendReplacement(out, Matcher.quoteReplacement("[" + n + "]"));
        }
        m.appendTail(out);
        return new Result(out.toString(), sources);
    }

    /** True when the model said the vault does not cover the question (the exact phrase the system prompt demands, at the start). */
    public static boolean notInKnowledge(String answer) {
        String t = answer.trim().replaceFirst("^[\\s\"'“”*_>#-]+", "");
        return t.regionMatches(true, 0, NOT_IN_KB, 0, NOT_IN_KB.length());
    }
}
