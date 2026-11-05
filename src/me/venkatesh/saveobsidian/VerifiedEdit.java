package me.venkatesh.saveobsidian;

import java.io.IOException;
import java.util.Arrays;

/** Checked read/backup/write sequence, also exercised with failing storage in JVM checks. */
public final class VerifiedEdit {
    public interface Read { byte[] get() throws Exception; }
    public interface Write { void put(byte[] bytes) throws Exception; }
    public interface Backup { void save(byte[] before, byte[] after) throws Exception; }

    public static void apply(byte[] expected, byte[] after, Read read, Write write, Backup backup) throws Exception {
        if (!Arrays.equals(expected, read.get())) throw new IOException("Inbox changed. Refresh and try again. Your draft is kept.");
        if (Arrays.equals(expected, after)) return;
        backup.save(expected, after);
        if (!Arrays.equals(expected, read.get())) throw new IOException("Inbox changed. No edit written. Export the recovery copies if needed.");
        // SAF cannot provide a transaction shared with Obsidian. Never roll back over another writer.
        try {
            write.put(after);
            if (!Arrays.equals(after, read.get())) throw new IOException("Read-back differs");
        } catch (Exception failure) {
            throw new IOException("Edit could not be verified. Check the inbox; original and attempted edit are kept in Edit recovery. No automatic restore was attempted.", failure);
        }
    }
}
