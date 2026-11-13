import java.io.File;
import java.util.Arrays;
import me.venkatesh.saveobsidian.Agent;
import me.venkatesh.saveobsidian.Cancel;
import me.venkatesh.saveobsidian.Compact;
import me.venkatesh.saveobsidian.OpenRouter;
import me.venkatesh.saveobsidian.Session;
import me.venkatesh.saveobsidian.Tool;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The real harness against a real model (scripts/live-agent.sh). Harness failures exit 1; what the model chooses to do is printed as INFO,
 * because a small free model may or may not call tools well. Not part of ./gradlew check: it needs a key and the network.
 */
public final class LiveAgent {
    static final String SYSTEM = "You answer questions from the user's notes. Use the tools search_notes and read_note when the answer needs the notes. Cite the note path you used. Keep answers short.";
    static final String[][] NOTES = {
        {"Knowledge/Geography/Brittany.md", "Brittany is a region in north-west France. Its capital is Rennes. The region is known for its Celtic heritage and the Breton language."},
        {"Knowledge/Cooking/Galette.md", "A galette is a savoury buckwheat crepe from Brittany, filled with ham, egg and cheese."},
        {"Knowledge/Tech/Rust.md", "Rust is a systems language with ownership and borrowing, no garbage collector."}};
    static int problems;

    static Tool search = new Tool("search_notes", "Search the notes by words. Returns matching note paths with a short excerpt.",
        schema("query", "string", "Words to look for"), false) {
        public Result execute(JSONObject args, Cancel c) {
            StringBuilder out = new StringBuilder();
            for (String word : args.getString("query").toLowerCase().split("\\W+"))
                for (String[] n : NOTES) if (!word.isEmpty() && n[1].toLowerCase().contains(word) && out.indexOf(n[0]) < 0) out.append("- ").append(n[0]).append(": ").append(n[1], 0, Math.min(60, n[1].length())).append("...\n");
            return Result.ok(out.length() == 0 ? "No notes match." : out.toString());
        }
    };
    static Tool read = new Tool("read_note", "Read one note by its path.", schema("path", "string", "Note path as returned by search_notes"), false) {
        public Result execute(JSONObject args, Cancel c) {
            for (String[] n : NOTES) if (n[0].equals(args.getString("path"))) return Result.ok(n[1]);
            return Result.error("No note at " + args.getString("path"));
        }
    };

    static JSONObject schema(String name, String type, String description) {
        return new JSONObject().put("type", "object").put("required", new JSONArray().put(name))
            .put("properties", new JSONObject().put(name, new JSONObject().put("type", type).put("description", description)));
    }

    static Agent agent(Agent.Provider provider, String model, Cancel cancel) {
        Agent a = new Agent(provider, model, SYSTEM, Arrays.asList(search, read), cancel);
        a.subscribe((type, data) -> {
            if (type.equals("tool_execution_start")) System.out.println("   tool> " + data.optString("name"));
            if (type.equals("tool_execution_end") && data.optBoolean("isError")) System.out.println("   tool error from " + data.optString("name"));
            if (type.equals("retry")) System.out.println("   retry " + data);
            if (type.equals("session_error")) { System.out.println("   PROBLEM session write failed: " + data); problems++; }
            if (type.equals("compaction_end")) System.out.println("   compaction ok=" + data.optBoolean("ok") + " " + data.optString("why"));
        });
        a.retryBaseMs = 4000; // free models rate-limit; be patient
        a.hooks = new Agent.Hooks() { public void prepareRequest(JSONObject request) { if (System.getenv("EFFORT") != null) request.put("reasoning", new JSONObject().put("effort", System.getenv("EFFORT"))); // opt-in: the default run shows the harness as it is } };
        return a;
    }

    static void ask(Agent a, String question) {
        System.out.println("\n> " + question);
        JSONObject last = a.prompt(question);
        System.out.println("   stopReason: " + last.optString("stopReason") + (last.has("errorMessage") ? " (" + last.optString("errorMessage") + ")" : ""));
        System.out.println("   answer: " + last.optString("content").replace('\n', ' '));
        if (last.has("usage")) System.out.println("   usage: " + last.get("usage"));
        if (!last.optString("stopReason").equals("stop")) { System.out.println("   PROBLEM run did not end with stop"); problems++; }
    }

    public static void main(String[] args) throws Exception {
        String key = System.getenv("OPENROUTER_API_KEY"), model = System.getenv().getOrDefault("MODEL", "liquid/lfm-3-1.7b:free");
        Agent.Provider provider = (request, live, cancel) -> OpenRouter.stream(key, request, live, cancel);
        System.out.println("model: " + model);

        System.out.println("\n== 1. plain answer, no tool needed");
        ask(agent(provider, model, new Cancel()), "Reply with the single word: pong");

        System.out.println("\n== 2. tool use over a tiny notes set");
        Agent a = agent(provider, model, new Cancel());
        ask(a, "What is the capital of Brittany according to my notes?");
        boolean usedTool = false;
        for (JSONObject m : a.messages()) usedTool |= m.optString("role").equals("tool");
        System.out.println("   INFO model called a tool: " + usedTool);

        System.out.println("\n== 3. session log, compaction and resume (window shrunk to force it)");
        File file = File.createTempFile("live-session", ".jsonl");
        file.deleteOnExit();
        Session session = Session.open(file);
        Agent c = agent(provider, model, new Cancel());
        c.attach(session);
        Compact compact = new Compact(provider, model, session, 1500);
        compact.reserve = 300; compact.keepRecent = 300;
        c.hooks = new Agent.Hooks() { // reasoning effort like agent(), plus compaction
            public void prepareRequest(JSONObject request) { if (System.getenv("EFFORT") != null) request.put("reasoning", new JSONObject().put("effort", System.getenv("EFFORT"))); // opt-in: the default run shows the harness as it is }
            public void prepareNextTurn(Agent x) { compact.prepareNextTurn(x); }
            public boolean onOverflow(Agent x) { return compact.onOverflow(x); }
        };
        ask(c, "My name is Ada and I am planning a trip to Brittany in May. Write two sentences about what to see there.");
        ask(c, "Now write two sentences about food I should try, and mention my name.");
        ask(c, "Write two sentences about the weather in May.");
        ask(c, "Write two sentences about getting around without a car.");
        String raw = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
        boolean compacted = raw.contains("\"type\":\"compaction\"");
        System.out.println("\n   compaction happened: " + compacted + ", totals: " + session.totals());
        if (!compacted) System.out.println("   INFO no compaction: the model's answers stayed under the shrunk window");
        else for (JSONObject m : session.context()) if (m.optBoolean("summary")) System.out.println("   summary: " + m.optString("content").replace('\n', ' '));

        Agent resumed = agent(provider, model, new Cancel());
        resumed.attach(Session.open(file));
        ask(resumed, "What is my name, and which month am I traveling?");


        System.out.println("\n== 4. follow-up queue: queued before the run, delivered after the first answer");
        Agent q = agent(provider, model, new Cancel());
        q.followUp("Now reply with the single word: done");
        ask(q, "Reply with the single word: ready");
        long users = 0;
        for (JSONObject m : q.messages()) if (m.optString("role").equals("user")) users++;
        System.out.println("   INFO user messages in transcript: " + users + " (2 expected)");
        if (users != 2) { System.out.println("   PROBLEM follow-up was not delivered"); problems++; }

        System.out.println("\n== 5. abort mid-stream over the real connection, then reuse the same agent");
        Agent ab = new Agent(provider, model, "You are a helpful writer.", java.util.Collections.<Tool>emptyList(), new Cancel());
        ab.maxTokens = 8000; // no tools: the model must write text, so there is a stream to cut
        ab.subscribe((type, data) -> { if (type.equals("message_update") && data.optString("text").length() > 40) ab.abort(); });
        long began = System.currentTimeMillis();
        JSONObject stopped = ab.prompt("Write a 500 word essay about the history of Brittany.");
        System.out.println("   stopReason: " + stopped.optString("stopReason") + " after " + (System.currentTimeMillis() - began) + " ms, running=" + ab.running());
        if (!stopped.optString("stopReason").equals("aborted") || ab.running()) { System.out.println("   PROBLEM abort did not end the run"); problems++; }
        JSONObject again = ab.prompt("Reply with the single word: back");
        System.out.println("   after abort: " + again.optString("stopReason") + " / " + again.optString("content").replace('\n', ' '));
        if (!again.optString("stopReason").equals("stop")) { System.out.println("   PROBLEM agent not reusable after abort"); problems++; }

        System.out.println(problems == 0 ? "\nLive run finished with no harness problems." : "\nLive run found " + problems + " problem(s).");
        System.exit(problems == 0 ? 0 : 1);
    }
}
