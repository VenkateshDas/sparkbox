package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Checks the cards a model proposes and writes them, with the curiosity question and quality, into a Source note. Pure Java so the JVM checks cover it. */
public final class CardPrompt {
    public static final int MAX_CARDS = 4;
    public static final String FULL = "full", PARTIAL = "partial", TITLE_ONLY = "title_only", FAILED = "failed";

    private CardPrompt() { }

    /** The prompt text that asks for the two fields, shared by ingest and bulk generation. */
    public static final String ASK = "\"curiosity_question\": \"one question under 140 characters that opens a gap the page answers; empty if the page is thin\", "
        + "\"recall_cards\": [{\"q\": \"a specific question answerable from the page alone, one idea, never 'what is the main idea'\", "
        + "\"a\": \"one or two sentences in your own words, no quote over 12 words\", \"basis\": \"the key claim this tests\", \"confidence\": \"high|medium\"}] (0 to 4; empty for a thin page; never pad; "
        + "only from claims you rate high or medium)";

    /** Quality from the length of the page text. */
    public static String quality(int chars) { return chars >= 1500 ? FULL : chars >= 300 ? PARTIAL : TITLE_ONLY; }

    private static String flat(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }

    private static boolean bad(String s) { return s.contains("[[") || s.matches("(?s).*https?://.*") || s.startsWith("###") || s.contains("\n###"); }

    /** raw: {q, a, basis, confidence}. Returns the cards that pass every rule, at most four, with stable ids for this note. */
    public static List<Cards.Card> validate(String notePath, List<String[]> raw) {
        List<Cards.Card> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String[] r : raw) {
            if (out.size() >= MAX_CARDS) break;
            String q = flat(r[0]), a = flat(r[1]), basis = flat(r.length > 2 ? r[2] : ""), conf = flat(r.length > 3 ? r[3] : "").toLowerCase(Locale.ROOT);
            if (q.isEmpty() || a.isEmpty() || q.length() > 240 || a.length() > 600 || a.equalsIgnoreCase(q)) continue;
            if (!conf.equals("high") && !conf.equals("medium")) continue;
            if (bad(q) || bad(a) || bad(basis)) continue;
            Cards.Card c = new Cards.Card();
            c.path = notePath; c.q = q; c.a = a; c.basis = basis.isEmpty() ? "" : basis + " (" + conf + ")";
            c.id = Cards.id(notePath, q);
            if (ids.add(c.id)) out.add(c);
        }
        return out;
    }

    /** A curiosity question that is a question, short, and free of links; otherwise "". */
    public static String curiosity(String s) {
        String q = flat(s);
        return q.endsWith("?") && q.length() < 160 && !bad(q) ? q : "";
    }

    /** The note with curiosity, quality and cards added. Existing cards and keys are kept; running it twice changes nothing more. */
    public static String apply(String note, String curiosity, String quality, List<Cards.Card> cards) {
        String t = note;
        String q = curiosity(curiosity);
        if (!q.isEmpty() && !quality.equals(TITLE_ONLY) && !quality.equals(FAILED)) t = NoteEdit.setKey(t, "curiosity", "\"" + q.replace("\"", "'") + "\"");
        t = NoteEdit.setKey(t, "quality", quality);
        if (!cards.isEmpty() && !quality.equals(TITLE_ONLY) && !quality.equals(FAILED)) t = Cards.add(t, cards);
        return t;
    }
}
