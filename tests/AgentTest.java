import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import me.venkatesh.saveobsidian.Agent;
import me.venkatesh.saveobsidian.Cancel;
import me.venkatesh.saveobsidian.Compact;
import me.venkatesh.saveobsidian.Session;
import me.venkatesh.saveobsidian.Tool;
import me.venkatesh.saveobsidian.Truncate;
import me.venkatesh.saveobsidian.Validate;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The agent loop against a scripted fake model (Pi's faux provider idea). Scenarios follow pi packages/agent/test/agent-loop.test.ts @ 3e23dd9:
 * events, custom messages and transformContext, tool calls and results, truncated calls, prepareArguments, completion vs source order,
 * sequential forcing, terminate, beforeToolCall / afterToolCall / finishTurn; plus retry, abort and error contract, validation and truncation.
 * Called from CaptureTest.main.
 */
final class AgentTest {
    static int count;
    private static void check(boolean ok) { count++; if (!ok) throw new AssertionError("agent check #" + count); }

    /** Scripted replies, popped in order. A Throwable entry is thrown; the requests the model saw are kept. */
    static final class Faux implements Agent.Provider {
        final ConcurrentLinkedQueue<Object> script = new ConcurrentLinkedQueue<>();
        final List<JSONObject> requests = Collections.synchronizedList(new ArrayList<>());
        Faux then(Object... replies) { script.addAll(Arrays.asList(replies)); return this; }
        public JSONObject stream(JSONObject request, java.util.function.Consumer<String> live, Cancel cancel) throws Exception {
            requests.add(new JSONObject(request.toString())); // snapshot: the agent keeps editing its own messages
            Object next = script.poll();
            if (next == null) throw new IllegalStateException("script ran out");
            if (next instanceof Exception) throw (Exception) next;
            JSONObject reply = (JSONObject) next;
            live.accept(reply.optString("content", ""));
            return reply;
        }
    }

    static JSONObject say(String text) throws Exception { return new JSONObject().put("content", text).put("finish_reason", "stop"); }
    static JSONObject calls(String... nameAndArgs) throws Exception {
        JSONArray c = new JSONArray();
        for (int i = 0; i < nameAndArgs.length; i += 2)
            c.put(new JSONObject().put("id", "c" + i / 2).put("type", "function").put("function", new JSONObject().put("name", nameAndArgs[i]).put("arguments", nameAndArgs[i + 1])));
        return new JSONObject().put("content", "").put("tool_calls", c).put("finish_reason", "tool_calls");
    }

    static JSONObject schema(String... nameAndType) throws Exception {
        JSONObject props = new JSONObject();
        JSONArray req = new JSONArray();
        for (int i = 0; i < nameAndType.length; i += 2) { props.put(nameAndType[i], new JSONObject().put("type", nameAndType[i + 1])); req.put(nameAndType[i]); }
        return new JSONObject().put("type", "object").put("properties", props).put("required", req);
    }

    interface Body { Tool.Result run(JSONObject args) throws Exception; }
    static Tool tool(String name, JSONObject params, boolean sequential, Body body) {
        return new Tool(name, name + " tool", params, sequential) { public Result execute(JSONObject a, Cancel c) throws Exception { return body.run(a); } };
    }

    static Agent agent(Faux f, Cancel cancel, Tool... tools) {
        Agent a = new Agent(f, "test/model", "SYS", Arrays.asList(tools), cancel);
        a.retryBaseMs = 0;
        return a;
    }

    static void run() throws Exception {
        // plain answer: events in order, system prompt first, no tools key without tools
        Faux f = new Faux().then(say("hi"));
        Agent a = agent(f, new Cancel());
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        a.listener = (type, data) -> events.add(type);
        JSONObject last = a.prompt("hello");
        check(last.getString("content").equals("hi") && last.getString("stopReason").equals("stop"));
        check(events.toString().equals("[agent_start, turn_start, message_start, message_update, message_end, turn_end, agent_end]"));
        check(f.requests.get(0).getJSONArray("messages").getJSONObject(0).getString("role").equals("system") && !f.requests.get(0).has("tools"));

        // tool call, result, answer; the model sees the tool result but none of the bookkeeping keys
        Tool echo = tool("echo", schema("text", "string"), false, args -> Tool.Result.ok("echo:" + args.getString("text")));
        f = new Faux().then(calls("echo", "{\"text\":\"a\"}"), say("done"));
        a = agent(f, new Cancel(), echo);
        last = a.prompt("go");
        check(last.getString("content").equals("done") && f.requests.size() == 2);
        JSONArray second = f.requests.get(1).getJSONArray("messages");
        check(second.length() == 4 && second.getJSONObject(2).getString("role").equals("assistant") && second.getJSONObject(3).getString("content").equals("echo:a"));
        check(!second.getJSONObject(2).has("stopReason") && !second.getJSONObject(3).has("isError") && second.getJSONObject(3).getString("tool_call_id").equals("c0"));
        check(f.requests.get(0).getJSONArray("tools").length() == 1);

        // unknown tool and bad arguments come back as errors the model can read; the run continues
        f = new Faux().then(calls("nope", "{}", "echo", "{}", "echo", "not json at all"), say("ok"));
        a = agent(f, new Cancel(), echo);
        a.prompt("go");
        List<JSONObject> m = a.messages();
        check(m.get(2).getString("content").startsWith("Unknown tool nope") && m.get(3).getString("content").equals("Invalid arguments: text: required"));
        check(m.get(4).getString("content").equals("The arguments are not valid JSON.") && m.get(4).getBoolean("isError"));

        // a response cut off by the token limit never runs its tool calls
        int[] ran = {0};
        Tool counted = tool("counted", schema(), false, args -> { ran[0]++; return Tool.Result.ok("x"); });
        f = new Faux().then(calls("counted", "{}").put("finish_reason", "length"), say("ok"));
        a = agent(f, new Cancel(), counted);
        a.prompt("go");
        check(ran[0] == 0 && a.messages().get(2).getString("content").contains("token limit") && a.messages().get(1).getString("stopReason").equals("length"));

        // prepareArguments shim runs before validation; coercion turns "5" into 5
        Tool shim = new Tool("shim", "shim", schema("count", "integer"), false) {
            public JSONObject prepareArguments(JSONObject args) throws org.json.JSONException { if (args.has("n")) args.put("count", args.remove("n")); return args; }
            public Result execute(JSONObject a2, Cancel c) { return Result.ok("count=" + a2.getInt("count")); }
        };
        f = new Faux().then(calls("shim", "{\"n\":\"5\"}"), say("ok"));
        a = agent(f, new Cancel(), shim);
        a.prompt("go");
        check(a.messages().get(2).getString("content").equals("count=5"));

        // parallel batch: results persist in call order even when the first call finishes last; events fire in completion order
        Tool slow = tool("slow", schema(), false, args -> { Thread.sleep(150); return Tool.Result.ok("slow"); });
        Tool quick = tool("quick", schema(), false, args -> Tool.Result.ok("quick"));
        f = new Faux().then(calls("slow", "{}", "quick", "{}"), say("ok"));
        a = agent(f, new Cancel(), slow, quick);
        List<String> ends = Collections.synchronizedList(new ArrayList<>());
        a.listener = (type, data) -> { if (type.equals("tool_execution_end")) ends.add(data.optString("name")); };
        a.prompt("go");
        check(ends.toString().equals("[quick, slow]") && a.messages().get(2).getString("content").equals("slow") && a.messages().get(3).getString("content").equals("quick"));

        // one sequential tool forces the whole batch to run one after another
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Tool s1 = tool("s1", schema(), true, args -> { order.add("s1+"); Thread.sleep(100); order.add("s1-"); return Tool.Result.ok("1"); });
        Tool s2 = tool("s2", schema(), false, args -> { order.add("s2+"); order.add("s2-"); return Tool.Result.ok("2"); });
        f = new Faux().then(calls("s1", "{}", "s2", "{}"), say("ok"));
        agent(f, new Cancel(), s1, s2).prompt("go");
        check(order.toString().equals("[s1+, s1-, s2+, s2-]"));

        // terminate: stops only when every result in the batch asks for it
        Tool stop = new Tool("stop", "stop", schema(), false) { public Result execute(JSONObject a2, Cancel c) { return new Result("end", null, false, true); } };
        f = new Faux().then(calls("stop", "{}"), say("never"));
        agent(f, new Cancel(), stop).prompt("go");
        check(f.requests.size() == 1);
        f = new Faux().then(calls("stop", "{}", "echo", "{\"text\":\"z\"}"), say("after"));
        check(agent(f, new Cancel(), stop, echo).prompt("go").getString("content").equals("after"));

        // hooks: transformContext runs before convertToLlm and custom messages are dropped; beforeToolCall blocks; afterToolCall patches; finishTurn ends
        f = new Faux().then(calls("echo", "{\"text\":\"a\"}", "echo", "{\"text\":\"blocked\"}"), say("ok"));
        a = agent(f, new Cancel(), echo);
        a.messages().add(new JSONObject().put("role", "custom").put("content", "UI card"));
        a.hooks = new Agent.Hooks() {
            public List<JSONObject> transformContext(List<JSONObject> msgs) { return msgs.subList(Math.max(0, msgs.size() - 6), msgs.size()); }
            public String beforeToolCall(String name, JSONObject args) { return args.optString("text").equals("blocked") ? "Blocked by policy" : null; }
            public Tool.Result afterToolCall(String name, JSONObject args, Tool.Result r) { return r.withText(r.text.toUpperCase()); }
        };
        a.prompt("go");
        check(!f.requests.get(0).toString().contains("UI card") && a.messages().get(3).getString("content").equals("ECHO:A") && a.messages().get(4).getString("content").equals("Blocked by policy"));
        f = new Faux().then(calls("echo", "{\"text\":\"a\"}"), say("never"));
        a = agent(f, new Cancel(), echo);
        a.hooks = new Agent.Hooks() { public boolean finishTurn(List<JSONObject> results) { return false; } };
        a.prompt("go");
        check(f.requests.size() == 1);

        // step cap ends with stopReason max-steps
        f = new Faux().then(calls("echo", "{\"text\":\"1\"}"), calls("echo", "{\"text\":\"2\"}"), calls("echo", "{\"text\":\"3\"}"));
        a = agent(f, new Cancel(), echo);
        a.maxTurns = 3;
        check(a.prompt("go").getString("stopReason").equals("max-steps"));

        // provider failures never throw: retry the retryable, stop at once on a bad key, hint on a 400 with tools
        f = new Faux().then(new java.io.IOException("Smart tags paused: OpenRouter answered 429"), new java.io.IOException("offline"), say("third time"));
        check(agent(f, new Cancel()).prompt("go").getString("content").equals("third time") && f.requests.size() == 3);
        f = new Faux().then(new java.io.IOException("Smart tags paused: OpenRouter answered 401 (check the key)"), say("never"));
        last = agent(f, new Cancel()).prompt("go");
        check(last.getString("stopReason").equals("error") && f.requests.size() == 1 && last.getString("errorMessage").contains("401"));
        f = new Faux().then(new java.io.IOException("OpenRouter answered 400"));
        last = agent(f, new Cancel(), echo).prompt("go");
        check(last.getString("errorMessage").contains("may not support tool calls"));
        f = new Faux().then(new java.io.IOException("boom"), new java.io.IOException("boom"), new java.io.IOException("boom"), new java.io.IOException("boom"), say("never"));
        check(agent(f, new Cancel()).prompt("go").getString("stopReason").equals("error") && f.requests.size() == 4); // 1 try + 3 retries
        // a failed message stays in the transcript but is not sent to the model next time
        a = agent(new Faux().then(new java.io.IOException("OpenRouter answered 401"), say("fine")), new Cancel());
        a.prompt("one");
        a.prompt("two");
        check(a.messages().size() == 4);

        // abort: a stopped run ends with an aborted assistant message, and a tool that was waiting is told so
        Cancel cancel = new Cancel();
        Tool stopper = tool("stopper", schema(), false, args -> { cancel.halt(); return Tool.Result.ok("x"); });
        f = new Faux().then(calls("stopper", "{}"), say("never"));
        a = agent(f, cancel, stopper);
        check(a.prompt("go").getString("stopReason").equals("aborted") && f.requests.size() == 1);
        Cancel before = new Cancel();
        before.halt();
        check(agent(new Faux(), before).prompt("go").getString("stopReason").equals("aborted"));

        // validation: coercion and dotted error paths
        JSONObject nested = new JSONObject().put("type", "object").put("required", new JSONArray().put("edits")).put("properties", new JSONObject()
            .put("edits", new JSONObject().put("type", "array").put("items", schema("oldText", "string", "n", "integer"))));
        JSONObject args = new JSONObject("{\"edits\":[{\"oldText\":\"a\",\"n\":\"2\"},{\"n\":1}]}");
        check(Validate.check(nested, args).equals("edits.1.oldText: required") && args.getJSONArray("edits").getJSONObject(0).get("n").equals(2L));
        check(Validate.check(nested, new JSONObject("{\"edits\":[{\"oldText\":\"a\",\"n\":\"x\"}]}")).equals("edits.0.n: expected integer"));
        check(Validate.check(schema("flag", "boolean"), new JSONObject("{\"flag\":\"true\"}")) == null && Validate.check(schema("s", "string"), new JSONObject("{\"s\":7}")) == null);

        // truncation: lines or bytes, whichever first; never mid-line; footer says what is left
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= 5000; i++) big.append("line ").append(i).append('\n');
        Truncate.Cut cut = Truncate.head(big.toString());
        check(cut.truncated && cut.shownLines == 2000 && cut.text.endsWith("line 2000") && cut.totalLines == 5001);
        Truncate.Cut bytes = Truncate.head("aaaa\nbbbb\ncccc", 100, 9);
        check(bytes.text.equals("aaaa\nbbbb") && bytes.truncated);
        check(Truncate.tail("a\nb\nc\nd", 2, 100).text.equals("c\nd") && !Truncate.head("short").truncated);
        memory();
        System.out.println("Agent checks passed (" + count + ").");
        check(Truncate.withFooter(Truncate.head("a\nb\nc", 2, 100), true, "Use offset=3 to continue").equals("a\nb\n\n[Showing lines 1-2 of 3. Use offset=3 to continue]"));
    }

    static JSONObject msg(String role, String content) throws Exception { return new JSONObject().put("role", role).put("content", content); }
    static String pad(String head, int chars) { StringBuilder s = new StringBuilder(head); while (s.length() < chars) s.append(' ').append("filler"); return s.toString(); }
    static JSONObject withUsage(JSONObject reply, int prompt, int completion, double cost) throws Exception { return reply.put("usage", new JSONObject().put("prompt_tokens", prompt).put("completion_tokens", completion).put("cost", cost)); }

    /** Session log, context_edit, compaction (cut point, summary request, projection), overflow retry, token estimate, file lists. */
    static void memory() throws Exception {
        Tool echo = tool("echo", schema("text", "string"), false, args -> Tool.Result.ok("echo:" + args.getString("text")));
        File dir = java.nio.file.Files.createTempDirectory("session").toFile();
        File file = new File(dir, "s.jsonl");

        // every message is appended as it happens; reopening the file gives the same context; usage is summed
        Session s = Session.open(file);
        Faux f = new Faux().then(withUsage(calls("echo", "{\"text\":\"a\"}"), 10, 5, 0.5), withUsage(say("done"), 20, 5, 0.25));
        Agent a = agent(f, new Cancel(), echo);
        a.attach(s);
        a.prompt("go");
        check(s.context().size() == 4 && Session.open(file).context().size() == 4);
        JSONObject totals = s.totals();
        check(totals.getLong("prompt_tokens") == 30 && totals.getLong("completion_tokens") == 10 && totals.getDouble("cost") == 0.75 && totals.getLong("calls") == 2);

        // a fresh agent continues from the stored conversation
        f = new Faux().then(say("second"));
        a = agent(f, new Cancel(), echo);
        a.attach(Session.open(file));
        a.prompt("again");
        check(f.requests.get(0).getJSONArray("messages").length() == 6 && Session.open(file).context().size() == 6); // system + 4 stored + new user

        // a torn last line (process killed mid-write) is skipped and the next entry starts on its own line
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, true)) { out.write("{\"type\":\"message\",\"mess".getBytes("UTF-8")); }
        Session torn = Session.open(file);
        check(torn.context().size() == 6);
        torn.appendMessage(msg("user", "after the kill"));
        check(Session.open(file).context().size() == 7);

        // context_edit changes future context only; the raw entry stays in the file
        String toolEntry = torn.context().get(2).getString("entryId");
        torn.editContext(toolEntry, "replace", "[cleared]");
        check(torn.context().get(2).getString("content").equals("[cleared]") && new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8").contains("echo:a"));
        torn.editContext(toolEntry, "omit", null);
        check(torn.context().size() == 6);

        // cut point: a user message, never a tool result; an assistant message when a single turn is too big; nothing when it all fits
        List<JSONObject> turns = new ArrayList<>();
        String forty = pad("x", 40);
        for (String role : new String[] {"user", "assistant", "tool", "user", "assistant", "tool", "user", "assistant"}) turns.add(msg(role, forty));
        int cut = Compact.cutIndex(turns, 30);
        check(cut == 3 && turns.get(cut).getString("role").equals("user"));
        List<JSONObject> oneTurn = new ArrayList<>();
        for (String role : new String[] {"user", "assistant", "tool", "assistant", "tool", "assistant", "tool"}) oneTurn.add(msg(role, forty));
        cut = Compact.cutIndex(oneTurn, 30);
        check(cut > 0 && oneTurn.get(cut).getString("role").equals("assistant"));
        check(Compact.cutIndex(turns, 100_000) == 0);

        // estimate: what the provider reported for the last assistant message plus chars/4 after it
        List<JSONObject> est = new ArrayList<>();
        est.add(withUsage(msg("assistant", "hi"), 100, 20, 0));
        est.add(msg("user", pad("q", 40)));
        check(Compact.estimate(est) == 120 + 11);

        // note paths read and changed are carried across compactions
        JSONObject readCall = calls("read", "{\"path\":\"Knowledge/A.md\"}", "edit", "{\"path\":\"Knowledge/B.md\"}");
        JSONObject old = msg("assistant", "").put("tool_calls", readCall.getJSONArray("tool_calls"));
        String lists = Compact.files("summary\n\n<read-files>\nKnowledge/Z.md\n</read-files>", Arrays.asList(old));
        check(lists.contains("<read-files>\nKnowledge/Z.md\nKnowledge/A.md\n</read-files>") && lists.contains("<modified-files>\nKnowledge/B.md\n</modified-files>"));

        // compaction end to end: the model is asked for a summary of the older part, the entry is stored, the original messages stay in the file
        File file2 = new File(dir, "c.jsonl");
        Session s2 = Session.open(file2);
        for (int i = 0; i < 6; i++) { s2.appendMessage(msg("user", pad("question " + i, 400))); s2.appendMessage(msg("assistant", pad("answer " + i, 400))); }
        f = new Faux().then(withUsage(say("## Goal\nfind the notes"), 50, 20, 0.001), say("final answer"));
        a = agent(f, new Cancel());
        a.attach(s2);
        Compact compact = new Compact(f, "test/model", s2, 1000);
        compact.reserve = 100; compact.keepRecent = 300;
        a.hooks = compact;
        check(a.prompt("next question").getString("content").equals("final answer"));
        String summaryAsk = f.requests.get(0).getJSONArray("messages").getJSONObject(1).getString("content");
        check(summaryAsk.contains("[User]: question 0") && summaryAsk.contains("## Goal") && !summaryAsk.contains("question 5"));
        JSONArray sent = f.requests.get(1).getJSONArray("messages");
        check(sent.getJSONObject(1).getString("content").startsWith(Compact.PREFIX) && sent.getJSONObject(1).getString("content").contains("find the notes") && sent.length() < 14);
        check(new String(java.nio.file.Files.readAllBytes(file2.toPath()), "UTF-8").contains("question 0") && Session.open(file2).context().get(0).optBoolean("summary"));
        check(s2.totals().getDouble("cost") == 0.001); // the summary call is counted

        // second compaction updates the previous summary instead of starting over
        for (int i = 6; i < 12; i++) { a.messages(); s2.appendMessage(msg("user", pad("question " + i, 400))); s2.appendMessage(msg("assistant", pad("answer " + i, 400))); }
        f = new Faux().then(say("## Goal\nupdated"), say("later"));
        a = agent(f, new Cancel());
        a.attach(s2);
        compact = new Compact(f, "test/model", s2, 1000);
        compact.reserve = 100; compact.keepRecent = 300;
        a.hooks = compact;
        a.prompt("and now");
        String update = f.requests.get(0).getJSONArray("messages").getJSONObject(1).getString("content");
        check(update.contains("<previous-summary>") && update.contains("find the notes"));

        // a provider that refuses the request as too long: compact once, build the request again, send it again
        File file3 = new File(dir, "o.jsonl");
        Session s3 = Session.open(file3);
        for (int i = 0; i < 6; i++) { s3.appendMessage(msg("user", pad("question " + i, 400))); s3.appendMessage(msg("assistant", pad("answer " + i, 400))); }
        f = new Faux().then(new java.io.IOException("Smart tags paused: OpenRouter answered 400: This endpoint's maximum context length is 1000 tokens."), say("## Goal\nshort"), say("recovered"));
        a = agent(f, new Cancel());
        a.attach(s3);
        compact = new Compact(f, "test/model", s3, 1_000_000);
        compact.keepRecent = 300;
        a.hooks = compact;
        check(a.prompt("big question").getString("content").equals("recovered") && f.requests.size() == 3);
        f = new Faux().then(new java.io.IOException("OpenRouter answered 400: maximum context length"), new java.io.IOException("OpenRouter answered 400: maximum context length"));
        check(agent(f, new Cancel()).prompt("x").getString("stopReason").equals("error") && f.requests.size() == 1); // no hook, no retry
        check(Compact.overflow("This model's maximum context length is 8192 tokens") && Compact.overflow("prompt is too long: 250000 tokens") && !Compact.overflow("OpenRouter answered 429"));
    }
}
