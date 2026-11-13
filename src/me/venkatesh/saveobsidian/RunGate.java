package me.venkatesh.saveobsidian;

import java.util.concurrent.atomic.AtomicBoolean;

/** At most one run at a time, each with its own Cancel. A run silent past hungMs is asked to stop; a new one starts only once its thread is gone. */
public final class RunGate {
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile long began;
    private volatile Thread owner;
    private volatile Cancel token;
    public volatile boolean again;

    /** The new run's token, or null when another run still holds the gate (then again is set, so that run goes round once more). */
    public Cancel enter(long hungMs, long waitMs) throws InterruptedException {
        if (!busy.compareAndSet(false, true)) {
            if (System.currentTimeMillis() - began < hungMs) { again = true; return null; }
            Cancel old = token; Thread t = owner;
            if (old != null) old.stop();
            if (t != null) t.join(waitMs);
            if (!busy.compareAndSet(false, true)) { again = true; return null; }
        }
        began = System.currentTimeMillis();
        again = false;
        owner = Thread.currentThread();
        token = new Cancel();
        Cancel.use(token);
        return token;
    }

    public void exit() { Cancel.use(null); owner = null; token = null; busy.set(false); }

    /** Stop the current run after its call in flight (Android ended the job). */
    public void halt() { Cancel t = token; if (t != null) t.halt(); }
}
