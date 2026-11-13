package me.venkatesh.saveobsidian;

import java.net.HttpURLConnection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One request's (or one run's) stop switch. Stopping one never touches another. stop() also drops open connections; halt() only lets the loops end after the call in flight. */
public final class Cancel {
    private volatile boolean stopped;
    private final Set<HttpURLConnection> open = ConcurrentHashMap.newKeySet();
    /** The token model calls on this thread (and threads it starts) belong to. */
    static final InheritableThreadLocal<Cancel> CURRENT = new InheritableThreadLocal<>();
    private static final Cancel NONE = new Cancel();

    public boolean stopped() { return stopped; }
    public void halt() { if (this != NONE) stopped = true; }
    public void stop() { halt(); for (HttpURLConnection c : open) c.disconnect(); }
    void attach(HttpURLConnection c) { if (this == NONE) return; open.add(c); if (stopped) c.disconnect(); }
    void detach(HttpURLConnection c) { open.remove(c); }
    public static Cancel current() { Cancel c = CURRENT.get(); return c == null ? NONE : c; }
    public static void use(Cancel c) { if (c == null) CURRENT.remove(); else CURRENT.set(c); }
}
