package me.venkatesh.saveobsidian;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;

/**
 * Tells "the phone cannot reach the internet right now" (a failed DNS lookup, no route, a refused connection) from "this capture is broken".
 * Offline trouble is nobody's fault: it is retried at once, never counted against a capture's tries, and processing resumes by itself.
 */
public final class Net {
    private Net() { }

    /** Thrown when every try failed for lack of a connection. The message keeps the "Smart tags paused" prefix the callers already stop on. */
    public static final class Offline extends IOException { public Offline(String m) { super(m); } }

    /** Temporary provider trouble or a checkpointed work slice; no content attempt is consumed. */
    public static final class Deferred extends IOException { public Deferred(String m) { super(m); } }

    /** True when this error, or what caused it, is a missing or flaky connection rather than a refusal from the server. */
    public static boolean offline(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof Offline || x instanceof UnknownHostException || x instanceof ConnectException || x instanceof NoRouteToHostException) return true;
            String m = String.valueOf(x.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (m.contains("unable to resolve host") || m.contains("network is unreachable") || m.contains("no address associated") || m.contains("software caused connection abort")) return true;
            if (x.getCause() == x) break;
        }
        return false;
    }

    /** Transport interruptions can recover without changing the capture or its model prompt. */
    public static boolean temporary(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof Deferred || x instanceof java.net.SocketTimeoutException
                || x instanceof java.net.SocketException || x instanceof java.io.EOFException) return true;
            if (x.getCause() == x) break;
        }
        return false;
    }

    /** Whether a lookup of the model provider works right now: separates a dead web address from a dead connection. */
    public static boolean online() {
        try { java.net.InetAddress.getByName("openrouter.ai"); return true; }
        catch (Exception down) { return false; }
    }
}
