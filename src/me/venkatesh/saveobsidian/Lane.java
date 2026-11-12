package me.venkatesh.saveobsidian;

import java.util.Locale;
import java.util.Map;

/** Craft or Life for a Source note: a `lane` frontmatter key wins, else the subject folder. Pure Java so the JVM checks cover it. */
public final class Lane {
    public static final String CRAFT = "Craft", LIFE = "Life";

    private Lane() { }

    /** "Craft", "Life", or "" when the path is not a Source note and no key says otherwise. */
    public static String of(String path, Map<String, String> meta) {
        String key = meta == null || meta.get("lane") == null ? "" : meta.get("lane").trim().toLowerCase(Locale.ROOT);
        if (key.equals("craft")) return CRAFT;
        if (key.equals("life")) return LIFE;
        for (String root : new String[]{Layout.NEW + "/", Layout.OLD + "/"}) {
            if (!path.startsWith(root)) continue;
            String rest = path.substring(root.length());
            int slash = rest.indexOf('/');
            if (slash < 0) return "";
            String folder = rest.substring(0, slash);
            if (folder.equals("Health & Biology") || folder.equals("People & Society")) return LIFE;
            for (String craft : IngestText.CATEGORIES) if (craft.equals(folder)) return CRAFT;
        }
        return "";
    }
}
