package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Port of pi packages/coding-agent/src/core/skills.ts and prompt-templates.ts @ 3e23dd9. A skill is a note "Knowledge/Skills/<name>/SKILL.md" with
 * frontmatter name (lowercase, digits, hyphens, at most 64; falls back to the folder name), description (required, at most 1024) and optionally
 * disable-model-invocation; the prompt lists name, description and location, and the model loads the file with read when a task matches (progressive
 * disclosure). "/skill:name args" loads it up front. A prompt template is "Knowledge/Prompts/<name>.md"; "/name args" expands it, with $1..$9 and
 * $ARGUMENTS (or $@) replaced. Differences: skills come from the vault only (no scripts, no packages, no allowed-tools). Pure Java.
 */
public final class Skills {
    private Skills() { }

    public static final String SKILLS = "Knowledge/Skills/", PROMPTS = "Knowledge/Prompts/";
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    public static final class Skill {
        public final String name, description, path, body;
        public final boolean hidden;
        Skill(String name, String description, String path, String body, boolean hidden) { this.name = name; this.description = description; this.path = path; this.body = body; this.hidden = hidden; }
    }

    /** The skill in this SKILL.md, or null (no description, or an invalid name) so a broken file is skipped, not fatal. */
    public static Skill parse(String path, String text) {
        if (!path.startsWith(SKILLS) || !path.toLowerCase().endsWith("/skill.md")) return null;
        int[] at = new int[1];
        Map<String, String> fm = Vault.frontmatter(text.replace("\r\n", "\n"), at);
        String[] parts = path.split("/");
        String name = fm.containsKey("name") ? fm.get("name") : parts[parts.length - 2];
        String description = fm.containsKey("description") ? fm.get("description").trim() : "";
        if (description.isEmpty() || name.length() > 64 || !NAME.matcher(name).matches()) return null;
        if (description.length() > 1024) description = description.substring(0, 1024);
        return new Skill(name, description, path, text.replace("\r\n", "\n").substring(at[0]).trim(), fm.getOrDefault("disable-model-invocation", "").equalsIgnoreCase("true"));
    }

    /** Skills by name; the first of a duplicate name wins (paths in the order given). */
    public static Map<String, Skill> index(List<String> paths, java.util.function.Function<String, String> read) {
        Map<String, Skill> out = new LinkedHashMap<>();
        for (String p : paths) {
            if (!p.startsWith(SKILLS) || !p.toLowerCase().endsWith("/skill.md")) continue;
            String text = read.apply(p);
            Skill s = text == null ? null : parse(p, text);
            if (s != null && !out.containsKey(s.name)) out.put(s.name, s);
        }
        return out;
    }

    private static String xml(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    /** Pi's formatSkillsForPrompt: empty when no skill may be invoked by the model. */
    public static String block(Map<String, Skill> skills) {
        StringBuilder s = new StringBuilder();
        for (Skill k : skills.values()) {
            if (k.hidden) continue;
            s.append("  <skill>\n    <name>").append(xml(k.name)).append("</name>\n    <description>").append(xml(k.description)).append("</description>\n    <location>").append(xml(k.path)).append("</location>\n  </skill>\n");
        }
        if (s.length() == 0) return "";
        return "The following skills provide specialized instructions for specific tasks.\nUse the read tool to load a skill's note when the task matches its description.\n"
            + "When a skill refers to a relative path, resolve it against the skill's folder.\n\n<available_skills>\n" + s + "</available_skills>";
    }

    /** Words of an argument string, "double quoted" ones kept together. */
    static List<String> words(String args) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(args);
        while (m.find()) out.add(m.group(1) != null ? m.group(1) : m.group(2));
        return out;
    }

    /**
     * "/skill:name args" becomes the skill's text in a block followed by args; "/name args" becomes the template named name (Knowledge/Prompts/name.md,
     * frontmatter dropped); anything else is returned as it is. templates: path to text, read on demand.
     */
    public static String expand(String input, Map<String, Skill> skills, List<String> paths, java.util.function.Function<String, String> read) {
        if (!input.startsWith("/")) return input;
        int space = input.indexOf(' ');
        String command = (space < 0 ? input : input.substring(0, space)).substring(1), args = space < 0 ? "" : input.substring(space + 1).trim();
        if (command.startsWith("skill:")) {
            Skill k = skills.get(command.substring(6));
            if (k == null) return input;
            String folder = k.path.substring(0, k.path.lastIndexOf('/'));
            return "<skill name=\"" + xml(k.name) + "\" location=\"" + xml(k.path) + "\">\nReferences are relative to " + folder + ".\n\n" + k.body + "\n</skill>" + (args.isEmpty() ? "" : "\n\n" + args);
        }
        String want = PROMPTS + command + ".md";
        for (String p : paths) {
            if (!p.equalsIgnoreCase(want)) continue;
            String text = read.apply(p);
            if (text == null) return input;
            int[] at = new int[1];
            Vault.frontmatter(text.replace("\r\n", "\n"), at);
            String body = text.replace("\r\n", "\n").substring(at[0]).trim();
            List<String> w = words(args);
            body = body.replace("$ARGUMENTS", args).replace("$@", args);
            for (int i = 9; i >= 1; i--) body = body.replace("$" + i, i <= w.size() ? w.get(i - 1) : "");
            return body;
        }
        return input;
    }
}
