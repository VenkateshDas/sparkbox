package me.venkatesh.saveobsidian;

import java.util.Locale;
import java.util.Map;

/** Craft or Life for a Source note: a `lane` frontmatter key wins, else the subject folder. Pure Java so the JVM checks cover it. */
public final class Lane {
    public static final String CRAFT = "Craft", LIFE = "Life";

    public static final String[] CRAFT_FOLDERS = {"AI Systems", "Models & Training", "Data & Knowledge", "Developer Tools", "Research Workflow"};
    public static final String[] LIFE_FOLDERS = {"Health & Biology", "People & Society"};

    private Lane() { }

    /** {lane, folder} from what a model named, made consistent: a valid folder decides its own lane; with no valid folder, "life" gets People & Society. Null when neither is usable. */
    public static String[] pick(String lane, String category) {
        String cat = category == null ? "" : category.trim();
        for (String f : CRAFT_FOLDERS) if (f.equalsIgnoreCase(cat)) return new String[]{CRAFT, f};
        for (String f : LIFE_FOLDERS) if (f.equalsIgnoreCase(cat)) return new String[]{LIFE, f};
        if (lane != null && lane.trim().equalsIgnoreCase("life")) return new String[]{LIFE, LIFE_FOLDERS[1]};
        return null;
    }

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
