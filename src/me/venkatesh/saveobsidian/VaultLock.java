package me.venkatesh.saveobsidian;

import java.util.concurrent.locks.ReentrantLock;

/** The one in-process lock for vault and inbox edits. Held over read, compute, compare, write and verify. Obsidian and sync apps are outside it, so the checks stay. */
public final class VaultLock {
    public static final ReentrantLock L = new ReentrantLock();
    private VaultLock() {}
}
