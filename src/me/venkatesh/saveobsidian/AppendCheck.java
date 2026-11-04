package me.venkatesh.saveobsidian;
public final class AppendCheck {
    public static boolean matches(byte[] before, byte[] addition, byte[] after) {
        if (after.length != before.length + addition.length) return false;
        for (int i = 0; i < before.length; i++) if (before[i] != after[i]) return false;
        for (int i = 0; i < addition.length; i++) if (addition[i] != after[before.length + i]) return false;
        return true;
    }
}
