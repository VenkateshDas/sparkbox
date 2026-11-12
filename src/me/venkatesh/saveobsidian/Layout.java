package me.venkatesh.saveobsidian;

import java.util.Collection;

/**
 * Where the Source notes live. The vault keeps them in Knowledge/Sources; older vaults still have Sources at the top. Detected on every scan so the
 * app works before and after the folder is moved. Pure Java so the JVM checks cover it.
 */
public final class Layout {
    public static final String NEW = "Knowledge/Sources", OLD = "Sources";
    private static volatile String sources = NEW;

    private Layout() { }

    /** The Source root now in use, without a trailing slash. */
    public static String sources() { return sources; }

    /** The inbox note as the Ask tools name it: "Knowledge/Sources/Inbox". */
    public static String inbox() { return sources + "/Inbox"; }

    /** Picks the root from the note paths found in a scan: the new one wins, the old one is used only when it alone has notes. */
    public static void detect(Collection<String> paths) {
        boolean fresh = false, old = false;
        for (String p : paths) {
            if (p.startsWith(NEW + "/")) fresh = true;
            else if (p.startsWith(OLD + "/")) old = true;
        }
        sources = !fresh && old ? OLD : NEW;
    }

    /** True for a path inside either Source root. */
    public static boolean inSources(String path) { return path.startsWith(NEW + "/") || path.startsWith(OLD + "/"); }

    /** A link target written for the other layout, or null when it is not a Sources path. "Sources/X" gives "Knowledge/Sources/X" and back. */
    public static String other(String target) {
        if (target.startsWith(NEW + "/")) return OLD + target.substring(NEW.length());
        if (target.startsWith(OLD + "/")) return NEW + target.substring(OLD.length());
        return null;
    }

    /** The inbox under either Source root, so citations written before the move still open it. */
    public static boolean isInbox(String target) { return target.equalsIgnoreCase(NEW + "/Inbox") || target.equalsIgnoreCase(OLD + "/Inbox"); }

    /** Conflict copies Syncthing makes are never notes. */
    public static boolean conflict(String name) { return name.contains(".sync-conflict-"); }
}
