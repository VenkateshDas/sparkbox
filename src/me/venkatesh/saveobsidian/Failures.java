package me.venkatesh.saveobsidian;

import java.util.Locale;

/** What kind of failure an error message is, and what the owner (and the retry ladder) should do about it. Pure Java so the JVM checks cover it. */
public final class Failures {
    public static final String OFFLINE = "offline", KEY = "key", RATE = "rate", MALFORMED = "malformed", CONFLICT = "conflict", OTHER = "other", ACCESS = "access";
    private Failures() { }

    public static String kind(String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (m.startsWith("offline") || m.contains("no connection") || m.contains("unable to resolve host")) return OFFLINE;
        if (m.startsWith("instagram requires account access")) return ACCESS;
        if (m.startsWith("instagram ")) return OTHER;
        if (m.contains(" 401") || m.contains(" 402") || m.contains(" 403") || m.contains("check the key") || m.contains("out of credit")) return KEY;
        if (m.contains(" 429") || m.contains("rate limit") || m.contains("rate-limit")) return RATE;
        if (m.contains("changed while") || m.contains("inbox changed") || m.contains("changed just now") || m.contains("read-back differs")) return CONFLICT;
        if (m.contains("no topic") || m.contains("json") || m.contains("refused this request") || m.contains("returned nothing") || m.contains("incomplete") || m.contains("could not be read as")) return MALFORMED;
        return OTHER;
    }

    /** One plain sentence: what happened and what happens next. */
    public static String recovery(String kind, String message) {
        switch (kind) {
            case ACCESS: return message;
            case OFFLINE: return "No connection to the AI service. It is tried again by itself when you are back online; Retry tries now.";
            case KEY: return message.replaceFirst("^Smart tags paused: ", "") + ". Check the key, account balance and key spending limit. Retry after updating them.";
            case RATE: return "OpenRouter is limiting requests right now. It waits longer before the next try.";
            case MALFORMED: return "The model's answer could not be used. The next try asks again, then switches to the fallback model.";
            case CONFLICT: return "The note changed while it was being saved (Obsidian or sync). The AI answer is kept, so the next try writes it again without a new model call.";
            default: return "Processing failed: " + message;
        }
    }

    /** Whether the retry ladder should keep trying by itself. A bad key never fixes itself. */
    public static boolean retries(String kind) { return !KEY.equals(kind) && !ACCESS.equals(kind); }

    /** Extra wait before the next try: a rate limit waits at least ten minutes. */
    public static long minWaitMs(String kind) { return RATE.equals(kind) ? 10 * 60_000L : 0; }
}
