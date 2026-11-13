package me.venkatesh.saveobsidian;

import java.util.concurrent.Semaphore;

/**
 * How much runs at once. Source fetching shares a small limit; background model calls (ingest, tags, cards, embeddings) run two at a time;
 * interactive work (Ask, search embeddings) never waits behind them. Vault edits have their own lock (VaultLock). Start values; tune from measurements.
 */
public final class Limits {
    public static final Semaphore FETCH = new Semaphore(3, true), AI = new Semaphore(2, true);
    private static final ThreadLocal<Boolean> INTERACTIVE = new ThreadLocal<>();
    private Limits() { }

    public interface Work<T> { T run() throws Exception; }

    /** Runs work holding one permit of gate; waits for a permit first. */
    public static <T> T with(Semaphore gate, Work<T> work) throws Exception {
        gate.acquire();
        try { return work.run(); } finally { gate.release(); }
    }

    /** Model calls on this thread are the owner's own request: they skip the background queue. */
    public static void interactive(boolean on) { if (on) INTERACTIVE.set(true); else INTERACTIVE.remove(); }
    public static boolean interactive() { return Boolean.TRUE.equals(INTERACTIVE.get()); }
}
