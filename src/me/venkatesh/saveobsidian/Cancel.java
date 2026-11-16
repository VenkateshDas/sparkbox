package me.venkatesh.saveobsidian;

import java.net.HttpURLConnection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One request's (or one run's) stop switch. Stopping one never touches another. stop() also drops open connections; halt() only lets the loops end after the call in flight. */
public final class Cancel {
    private volatile boolean stopped;
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "scout-transport-deadline"); t.setDaemon(true); return t; });
    private final java.util.Map<HttpURLConnection, java.util.concurrent.Future<?>> deadlines = new ConcurrentHashMap<>();
    private final Set<HttpURLConnection> open = ConcurrentHashMap.newKeySet();
    /** The token model calls on this thread (and threads it starts) belong to. */
    static final InheritableThreadLocal<Cancel> CURRENT = new InheritableThreadLocal<>();
    private static final Cancel NONE = new Cancel();

    public boolean stopped() { return stopped; }
    public synchronized void halt() { if (this != NONE) { stopped = true; notifyAll(); } }
    public void stop() { halt(); for (HttpURLConnection c : open) c.disconnect(); }
    void attach(HttpURLConnection c) { attach(c, 180_000); }
    void attach(HttpURLConnection c, long totalMs) {
        open.add(c);
        deadlines.put(c, DEADLINES.schedule(c::disconnect, totalMs, java.util.concurrent.TimeUnit.MILLISECONDS));
        if (stopped) c.disconnect();
    }
    void detach(HttpURLConnection c) { open.remove(c); java.util.concurrent.Future<?> timer = deadlines.remove(c); if (timer != null) timer.cancel(false); }
    /** Cancellation wakes retry backoff immediately. */
    public synchronized boolean pause(long millis) throws InterruptedException {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
        while (!stopped) {
            long left = until - System.nanoTime();
            if (left <= 0) return true;
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return false;
    }
    public static Cancel current() { Cancel c = CURRENT.get(); return c == null ? NONE : c; }
    public static void use(Cancel c) { if (c == null) CURRENT.remove(); else CURRENT.set(c); }
}
