import java.util.*;
import me.venkatesh.saveobsidian.Cancel;
import me.venkatesh.saveobsidian.Tool;
import me.venkatesh.saveobsidian.VaultTools;
import org.json.JSONObject;

/** Checks for the vault tools (ported from pi's read/ls/find/grep/edit/write behaviour) over an in-memory vault. */
final class VaultToolsTest {
    static int count;
    private static void check(boolean ok) { count++; if (!ok) throw new AssertionError("vault tools check #" + count); }

    static final class Mem implements VaultTools.Files {
        final Map<String, String> notes = new TreeMap<>();
        public List<String> paths() { return new ArrayList<>(notes.keySet()); }
        public String read(String p) { return notes.get(p); }
        public void write(String p, String before, String after) throws Exception {
            if (before == null ? notes.containsKey(p) : !before.equals(notes.get(p))) throw new Exception(p + " changed while it was being edited.");
            notes.put(p, after);
        }
        public String search(String q) { return "- [[Knowledge/Topics/Agents|Agents]] matches " + q; }
    }

    static Map<String, Tool> tools;
    static List<String> asked = new ArrayList<>();
    static boolean yes = true;

    static String run(String tool, String json) throws Exception {
        Tool t = tools.get(tool);
        JSONObject args = t.prepareArguments(new JSONObject(json));
        String bad = me.venkatesh.saveobsidian.Validate.check(t.parameters, args);
        if (bad != null) return "Invalid arguments: " + bad;
        return t.execute(args, new Cancel()).text;
    }

    static void run() throws Exception {
        Mem m = new Mem();
        m.notes.put("Knowledge/Topics/Agents.md", "# Agents\nline two\nThe loop runs tools.\nline four\n");
        m.notes.put("Knowledge/Topics/Memory.md", "# Memory\nAgents keep memory.\nagents again\n");
        m.notes.put("Knowledge/Sources/Paper.md", "# Paper\nunrelated\n");
        m.notes.put("Other/Secret.md", "# Secret\n");
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= 2500; i++) big.append("row ").append(i).append('\n');
        m.notes.put("Knowledge/Big.md", big.toString());
        tools = new LinkedHashMap<>();
        for (Tool t : VaultTools.create(m, (title, detail) -> { asked.add(title); return yes; }, "Knowledge")) tools.put(t.name, t);

        check(tools.keySet().toString().equals("[read, grep, find, ls, search, edit, write]"));
        // read: path forms, offsets, truncation footers, errors
        check(run("read", "{\"path\":\"Knowledge/Topics/Agents\"}").equals(m.notes.get("Knowledge/Topics/Agents.md")));
        check(run("read", "{\"path\":\"/knowledge/topics/agents.md\",\"offset\":2,\"limit\":1}").equals("line two\n\n[3 more lines in file. Use offset=3 to continue.]"));
        check(run("read", "{\"path\":\"Knowledge/Big.md\"}").endsWith("[Showing lines 1-2000 of 2501. Use offset=2001 to continue.]"));
        check(run("read", "{\"path\":\"Knowledge/Big.md\",\"offset\":2400}").startsWith("row 2400\n"));
        check(run("read", "{\"path\":\"Agents.md\"}").startsWith("File not found"));
        check(run("read", "{\"path\":\"Knowledge/Topics/Agents\",\"offset\":99}").startsWith("Offset 99 is beyond end of file (5 lines total)"));
        check(run("read", "{}").equals("Invalid arguments: path: required"));
        // ls
        check(run("ls", "{}").equals("Knowledge/\nOther/"));
        check(run("ls", "{\"path\":\"knowledge\"}").equals("Big.md\nSources/\nTopics/"));
        check(run("ls", "{\"path\":\"Knowledge\",\"limit\":1}").equals("Big.md\n\n[1 entries limit reached. Use limit=2 for more]"));
        check(run("ls", "{\"path\":\"Nope\"}").equals("Path not found: Nope"));
        // find
        check(run("find", "{\"pattern\":\"*.md\",\"path\":\"Knowledge/Topics\"}").equals("Agents.md\nMemory.md"));
        check(run("find", "{\"pattern\":\"**/Sources/*.md\"}").equals("Knowledge/Sources/Paper.md"));
        check(run("find", "{\"pattern\":\"*{agents,paper}*\"}").equals("Knowledge/Sources/Paper.md\nKnowledge/Topics/Agents.md"));
        check(run("find", "{\"pattern\":\"*.pdf\"}").equals("No files found matching pattern"));
        // grep
        check(run("grep", "{\"pattern\":\"agents\",\"ignoreCase\":true,\"path\":\"Knowledge/Topics\"}").equals("Agents.md:1: # Agents\nMemory.md:2: Agents keep memory.\nMemory.md:3: agents again"));
        check(run("grep", "{\"pattern\":\"loop\",\"context\":1}").equals("Knowledge/Topics/Agents.md-2- line two\nKnowledge/Topics/Agents.md:3: The loop runs tools.\nKnowledge/Topics/Agents.md-4- line four"));
        check(run("grep", "{\"pattern\":\"a.b\",\"literal\":true}").equals("No matches found") && run("grep", "{\"pattern\":\"(\"}").startsWith("Invalid pattern"));
        check(run("grep", "{\"pattern\":\"row\",\"path\":\"Knowledge/Big.md\",\"limit\":3}").equals("Knowledge/Big.md:1: row 1\nKnowledge/Big.md:2: row 2\nKnowledge/Big.md:3: row 3\n\n[3 matches limit reached. Use limit=6 for more, or refine pattern]"));
        check(run("grep", "{\"pattern\":\"Secret\",\"glob\":\"Topics/*.md\"}").equals("No matches found"));
        // search is delegated
        check(run("search", "{\"query\":\"loops\"}").endsWith("matches loops"));
        // edit: exact, unique, multi, shims, errors; every change is confirmed
        check(run("edit", "{\"path\":\"Knowledge/Topics/Agents\",\"edits\":[{\"oldText\":\"line two\",\"newText\":\"2\"},{\"oldText\":\"line four\",\"newText\":\"4\"}]}").equals("Successfully replaced 2 block(s) in Knowledge/Topics/Agents.md."));
        check(m.notes.get("Knowledge/Topics/Agents.md").equals("# Agents\n2\nThe loop runs tools.\n4\n") && asked.toString().equals("[Edit Knowledge/Topics/Agents.md]"));
        check(run("read", "{\"path\":\"Knowledge/Topics/Agents\"}").contains("\n2\n")); // the cache was dropped by the write
        check(run("edit", "{\"path\":\"Knowledge/Topics/Memory\",\"oldText\":\"agents again\",\"newText\":\"x\"}").startsWith("Successfully")); // legacy shape
        check(run("edit", "{\"path\":\"Knowledge/Topics/Memory\",\"edits\":\"{\\\"oldText\\\":\\\"x\\\",\\\"newText\\\":\\\"y\\\"}\"}").startsWith("Successfully")); // edits as a string
        check(run("edit", "{\"path\":\"Knowledge/Topics/Memory\",\"edits\":[{\"oldText\":\"zzz\",\"newText\":\"y\"}]}").startsWith("Could not find the text in"));
        check(run("edit", "{\"path\":\"Knowledge/Topics/Memory\",\"edits\":[{\"oldText\":\"m\",\"newText\":\"E\"}]}").startsWith("Found 3 occurrences of the text"));
        check(run("edit", "{\"path\":\"Knowledge/Topics/Memory\",\"edits\":[{\"oldText\":\"# Memory\",\"newText\":\"a\"},{\"oldText\":\"Memory\\nAgents\",\"newText\":\"b\"}]}").contains("overlap"));
        check(run("edit", "{\"path\":\"Knowledge/Topics/Memory\",\"edits\":[{\"oldText\":\"# Memory\",\"newText\":\"# Memory\"}]}").startsWith("No changes made"));
        check(run("edit", "{\"path\":\"Other/Secret\",\"edits\":[{\"oldText\":\"Secret\",\"newText\":\"x\"}]}").startsWith("File not found") || m.notes.get("Other/Secret.md").equals("# Secret\n"));
        m.notes.put("Knowledge/Crlf.md", "a\r\nb\r\n");
        check(run("edit", "{\"path\":\"Knowledge/Crlf\",\"edits\":[{\"oldText\":\"a\\nb\",\"newText\":\"c\\nd\"}]}").startsWith("Successfully") && m.notes.get("Knowledge/Crlf.md").equals("c\r\nd\r\n"));
        // declined changes write nothing
        yes = false;
        int before = asked.size();
        check(run("write", "{\"path\":\"Knowledge/New.md\",\"content\":\"hi\"}").startsWith("The user declined") && !m.notes.containsKey("Knowledge/New.md") && asked.size() == before + 1);
        check(run("edit", "{\"path\":\"Knowledge/Topics/Agents\",\"edits\":[{\"oldText\":\"2\",\"newText\":\"two\"}]}").startsWith("The user declined") && m.notes.get("Knowledge/Topics/Agents.md").contains("\n2\n"));
        yes = true;
        // write: create with folders, overwrite, roots and extension enforced
        check(run("write", "{\"path\":\"Knowledge/Deep/New\",\"content\":\"hi\\n\"}").equals("Successfully wrote to Knowledge/Deep/New.md") && m.notes.get("Knowledge/Deep/New.md").equals("hi\n"));
        check(run("write", "{\"path\":\"knowledge/deep/new.md\",\"content\":\"over\"}").startsWith("Successfully") && m.notes.get("Knowledge/Deep/New.md").equals("over"));
        check(run("write", "{\"path\":\"Other/X.md\",\"content\":\"x\"}").startsWith("Cannot write") && run("write", "{\"path\":\"Knowledge/../Other/X.md\",\"content\":\"x\"}").startsWith("Cannot write"));
        check(run("write", "{\"path\":\"Knowledge/x.txt\",\"content\":\"x\"}").startsWith("Knowledge/x.txt") || !m.notes.containsKey("Knowledge/x.txt"));
        check(tools.get("edit").sequential && tools.get("write").sequential && !tools.get("read").sequential);
        System.out.println("Vault tools checks passed (" + count + ").");
    }
}
