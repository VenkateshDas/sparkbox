import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.List;
import me.venkatesh.saveobsidian.*;
import org.json.JSONObject;

/** Deterministic Scout failure fixtures. Never opens the owner's vault or a paid provider. */
final class ScoutTest {
    static int count;
    static void check(boolean ok) { count++; if (!ok) throw new AssertionError("Scout check #" + count); }
    interface Throwing { void run() throws Exception; }
    static void rejects(Throwing action) throws Exception { try { action.run(); } catch (Exception expected) { check(true); return; } check(false); }
    static Session session() throws Exception { return Session.create(Files.createTempDirectory("scout-check").toFile()); }
    static JSONObject message(String role, String text) throws Exception { return new JSONObject().put("role", role).put("content", text); }
    static JSONObject proposal(Session s, String before, String after) throws Exception {
        ScoutChanges.CALL.set(new JSONObject().put("id", "call-test").put("name", "edit"));
        try { ScoutChanges.note(s, "tree:A", "Knowledge/Test.md", before, after); throw new AssertionError(); }
        catch (ScoutChanges.Suspended p) { return p.proposal; }
        finally { ScoutChanges.CALL.remove(); }
    }
    static class Notes implements ScoutChanges.Notes {
        String tree = "tree:A", content = "before";
        int writes;
        boolean failAfter;
        public String tree() { return tree; }
        public String read(String pinned, String path) { if (!tree.equals(pinned)) throw new AssertionError(); return content; }
        public void write(String pinned, String path, String before, String after) throws Exception {
            if (!tree.equals(pinned) || !java.util.Objects.equals(content, before)) throw new AssertionError();
            writes++; content = after;
            if (failAfter) throw new java.io.IOException("crash after storage write");
        }
    }
    static String event(JSONObject chunk) { return "data: " + chunk + "\n\n"; }
    static JSONObject chunk(String content, String finish) throws Exception {
        return new JSONObject().put("choices", new org.json.JSONArray().put(new JSONObject().put("delta", new JSONObject().put("content", content)).put("finish_reason", finish == null ? JSONObject.NULL : finish)));
    }
    static void run() throws Exception {
        Session s = session();
        s.appendMessage(message("user", "old question"));
        s.appendMessage(message("assistant", "old answer"));
        String keep = s.appendMessage(message("user", "new question"));
        s.appendCompaction("summary", keep, 100);
        s.editContext(keep, "omit", "");
        check(s.retained().size() == 3 && s.context().size() == 1);
        check(s.retained().get(0).optString("content").equals("old question"));
        String id = ScoutState.accept(s, "direction", false);
        check(ScoutState.pending(Session.open(new File(s.exportPath()))).size() == 1);
        s.appendMessage(message("user", "direction").put("queueId", id));
        check(ScoutState.pending(s).isEmpty());
        s.close(); rejects(() -> s.appendCustom("intent", new JSONObject()));
        Session corrupt = session(); corrupt.appendMessage(message("user", "kept"));
        Files.writeString(new File(corrupt.exportPath()).toPath(), "invalid\n", java.nio.file.StandardOpenOption.APPEND);
        Session readOnly = Session.open(new File(corrupt.exportPath()));
        check(!readOnly.writable() && readOnly.retained().size() == 1);
        rejects(() -> readOnly.appendMessage(message("user", "blocked")));
        Session failed = session();
        File f = new File(failed.exportPath()); check(f.delete() && f.mkdir());
        Agent agent = new Agent((request, live, cancel) -> { throw new AssertionError("provider ran after storage failure"); }, "test", "test", List.of(), new Cancel());
        agent.attach(failed); rejects(() -> agent.prompt("do not lose"));
        check(!failed.writable() && agent.messages().isEmpty());

        Sse events = new Sse(new StringReader(":comment\r\ndata: hello\rdata: world\n\n"));
        check(events.next().equals("hello\nworld") && events.next() == null);
        String complete = event(chunk("你好", null)) + event(chunk("", "stop"));
        check(OpenRouter.decode(new StringReader(complete), text -> {}, new Cancel(), m -> {}).getString("content").equals("你好"));
        rejects(() -> OpenRouter.decode(new StringReader(event(chunk("partial", null))), text -> {}, new Cancel(), m -> {}));
        rejects(() -> OpenRouter.decode(new StringReader("data: [DONE]\n\n"), text -> {}, new Cancel(), m -> {}));
        rejects(() -> new Sse(new StringReader("data: " + "x".repeat(Sse.EVENT_MAX) + "\n\n")).next());
        rejects(() -> new Sse(new StringReader("data: unfinished")).next());
        JSONObject tool = AgentTest.calls("edit", "{\"unfinished\":");
        JSONObject delta = new JSONObject().put("choices", new org.json.JSONArray().put(new JSONObject().put("delta", new JSONObject().put("tool_calls", tool.getJSONArray("tool_calls"))).put("finish_reason", "tool_calls")));
        rejects(() -> OpenRouter.decode(new StringReader(event(delta) + "data: [DONE]\n\n"), text -> {}, new Cancel(), m -> {}));
        Cancel cancel = new Cancel();
        Thread waiter = new Thread(() -> { try { check(!cancel.pause(60_000)); } catch (InterruptedException e) { throw new AssertionError(e); } });
        waiter.start(); cancel.stop(); waiter.join(1000); check(!waiter.isAlive());

        Session approval = session(); Notes notes = new Notes(); JSONObject p = proposal(approval, "before", "after");
        check(ScoutChanges.pending(Session.open(new File(approval.exportPath()))).size() == 1 && notes.writes == 0);
        ScoutChanges.applyNote(approval, p, true, notes, new Cancel());
        ScoutChanges.applyNote(approval, p, true, notes, new Cancel());
        check(notes.writes == 1 && ScoutChanges.pending(approval).isEmpty());
        Session decline = session(); notes = new Notes(); p = proposal(decline, "before", "after");
        ScoutChanges.applyNote(decline, p, false, notes, new Cancel()); check(notes.writes == 0 && ScoutChanges.pending(decline).isEmpty());
        Session otherVault = session(); notes = new Notes(); notes.tree = "tree:B"; p = proposal(otherVault, "before", "after");
        ScoutChanges.applyNote(otherVault, p, true, notes, new Cancel()); check(notes.writes == 0);
        Session stale = session(); notes = new Notes(); notes.content = "concurrent edit"; p = proposal(stale, "before", "after");
        ScoutChanges.applyNote(stale, p, true, notes, new Cancel()); check(notes.writes == 0);
        Session stopped = session(); notes = new Notes(); p = proposal(stopped, "before", "after"); Cancel stop = new Cancel(); stop.stop();
        ScoutChanges.applyNote(stopped, p, true, notes, stop); check(notes.writes == 0 && !ScoutChanges.event(stopped, "write_intent", p.getString("proposalId")));
        Session crash = session(); Notes crashNotes = new Notes(); crashNotes.failAfter = true; JSONObject crashP = proposal(crash, "before", "after");
        rejects(() -> ScoutChanges.applyNote(crash, crashP, true, crashNotes, new Cancel()));
        Session reopened = Session.open(new File(crash.exportPath()));
        ScoutChanges.applyNote(reopened, ScoutChanges.pending(reopened).get(0), true, crashNotes, new Cancel());
        check(crashNotes.writes == 1 && ScoutChanges.pending(reopened).isEmpty());
        rejects(() -> ScoutChanges.protect("---\nx: 1\n---\nBody", "---\nx: 2\n---\nBody"));
        rejects(() -> ScoutChanges.protect("## My take\nOwner\n", "## My take\nChanged\n"));
        ScoutChanges.protect("## My ideas\nOwner\n## Other\nOld", "## My ideas\nOwner\n## Other\nNew"); check(true);
        blocksAndEvidence();
        suspendedBatch();
        detachedUiSnapshot();
        failedWorkStops();
        recoverIncompleteArguments();
        inlineVaultCache();
        boundedArgumentRecovery();
        unattendedApprovals();
        stopWhileWaiting();
        approvalReadersStayResponsive();
        durableDecisionsAndRemote();
        System.out.println("Scout checks passed (" + count + ").");
    }
    static void blocksAndEvidence() throws Exception {
        String raw = "# Heading\n\n| A | B |\n|---|---|\n| x\\|y | `a|b` |\n\n```java\n  first\n\n  last\n```\n";
        List<ScoutMarkdown.Block> blocks = ScoutMarkdown.parse(raw);
        check(blocks.size() == 3 && blocks.get(1).rows.size() == 2);
        check(blocks.get(1).rows.get(1).equals(List.of("x|y", "`a|b`")));
        check(blocks.get(2).text.equals("  first\n\n  last\n") && blocks.get(2).complete);
        check(!ScoutMarkdown.parse("```\n| not | table |\n").get(0).complete);
        check(ScoutMarkdown.parse("~~~text\nabc\n~~~\n").get(0).text.equals("abc\n"));
        Session session = session();
        String id = ScoutEvidence.register(session, "https://example.org/Case?a=1#part", "Example", "web", "unknown", "source excerpt", "returned tool text", null);
        String same = ScoutEvidence.register(session, "https://example.org/Case?a=1#part", "Example", "web", "unknown", "source excerpt", "returned tool text", null);
        check(id.equals(same) && ScoutEvidence.sources(session).size() == 1);
        String exported = ScoutEvidence.share(session, "Claim [[cite:" + id + "]] [[cite:fake]]");
        check(exported.contains("[Example](https://example.org/Case?a=1#part)") && exported.contains("Unresolved source: fake"));
        check(Cites.number(ScoutEvidence.export(session, "[[cite:" + id + "]]")).sources.get(0)[1].equals("https://example.org/Case?a=1#part"));
        check(Cites.number("[[https://example.org/Case|A]] [[https://example.org/case|B]]").sources.size() == 2);
        check(Cites.unseen("https://example.org/Case", "https://example.org/case").size() == 1);
        String entry = session.appendMessage(message("user", "q")); session.appendCompaction("summary", entry, 100);
        check(ScoutEvidence.context(Session.open(new File(session.exportPath()))).contains(id));
        rejects(() -> ScoutChanges.prepareNote(session, "tree:A", "Knowledge/../secret.md", null, "bad", new org.json.JSONArray(), false));
        Session budget = session(); ScoutState.spend(budget, "tools", 1, 42);
        rejects(() -> ScoutState.spend(Session.open(new File(budget.exportPath())), "tools", 1, 50));
        check(ScoutState.budget(budget).optLong("activeMs") == 42);
        Session journal = session();
        journal.appendCustom("run_config", new JSONObject().put("runId", "bounded"));
        rejects(() -> journal.appendCustom("proposal", new JSONObject().put("text", "x".repeat(8 * 1024 * 1024))));
        journal.appendCustom("write_receipt", new JSONObject().put("proposalId", "reserved")); check(journal.writable());
    }

    static void suspendedBatch() throws Exception {
        Session session = session(); int[] writes = {0}, siblings = {0};
        Tool note = new Tool("edit", "edit", AgentTest.schema(), true) {
            public Result execute(JSONObject args, Cancel cancel) throws Exception {
                ScoutChanges.note(session, "tree:A", "Knowledge/Test.md", "before", "after"); writes[0]++; return Result.ok("impossible");
            }
        };
        Tool sibling = AgentTest.tool("sibling", AgentTest.schema(), false, args -> { siblings[0]++; return Tool.Result.ok("ran"); });
        AgentTest.Faux provider = new AgentTest.Faux().then(AgentTest.calls("sibling", "{}", "edit", "{}", "sibling", "{}"));
        Agent agent = new Agent(provider, "test", "test", List.of(note, sibling), new Cancel()); agent.attach(session); agent.prompt("go");
        check(writes[0] == 0 && siblings[0] == 1 && provider.requests.size() == 1);
        check(ScoutChanges.pending(session).size() == 1);
        check(session.retained().stream().filter(m -> m.optString("role").equals("tool")).count() == 2);
        Agent reopened = new Agent(new AgentTest.Faux().then(AgentTest.say("done")), "test", "test", List.of(note, sibling), new Cancel());
        reopened.attach(Session.open(new File(session.exportPath()))); rejects(reopened::resume);
        JSONObject proposal = ScoutChanges.pending(session).get(0);
        ScoutChanges.applyNote(session, proposal, false, new Notes(), new Cancel());
        Agent resumed = new Agent(new AgentTest.Faux().then(AgentTest.say("done")), "test", "test", List.of(note, sibling), new Cancel());
        resumed.attach(session); check(resumed.resume().optString("content").equals("done") && siblings[0] == 1);
        Session queue = session(); String id = ScoutState.accept(queue, "first", false);
        Agent queued = new Agent(new AgentTest.Faux().then(AgentTest.say("done")), "test", "test", List.of(), new Cancel()); queued.attach(queue);
        queued.accepted(id, "first", false); queued.accepted(id, "first", false);
        check(queued.state().optInt("steering") == 1 && queued.editQueued(id, "edited", false));
        queued.resume();
        check(ScoutState.pending(queue).isEmpty() && queue.retained().stream().filter(m -> id.equals(m.optString("queueId"))).count() == 1);
    }

    static void detachedUiSnapshot() throws Exception {
        Session live = session(); live.appendMessage(message("user", "first"));
        Session snapshot = live.snapshot();
        live.appendMessage(message("assistant", "second"));
        check(snapshot.retained().size() == 1 && live.retained().size() == 2);
        rejects(() -> snapshot.appendMessage(message("user", "must not write")));
        snapshot.log().get(0).getJSONObject("message").put("content", "local copy");
        check(live.retained().get(0).optString("content").equals("first"));
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        Thread writer = new Thread(() -> { synchronized (live) {
            locked.countDown(); try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        } });
        writer.start(); check(locked.await(1, java.util.concurrent.TimeUnit.SECONDS));
        try {
            long began = System.nanoTime();
            check(snapshot.retained().size() == 1 && snapshot.log().size() == 1 && snapshot.totals() != null);
            check(System.nanoTime() - began < 200_000_000L);
        }
        finally { release.countDown(); writer.join(1000); }
    }

    static void failedWorkStops() throws Exception {
        JSONObject calls = AgentTest.calls("ls", "{}").put("role", "assistant").put("ts", 2000);
        List<Chat.Row> rows = Chat.rows(List.of(message("user", "save").put("ts", 1000), calls,
            message("tool", "listed").put("tool_call_id", "c0").put("isError", false),
            message("assistant", "").put("stopReason", "error").put("errorMessage", "Incomplete tool arguments").put("ts", 3000)));
        check(rows.get(1).kind.equals("work") && rows.get(1).secs == 2);
    }

    static void recoverIncompleteArguments() throws Exception {
        int[] requests = {0}, writes = {0};
        Tool write = AgentTest.tool("write", AgentTest.schema("content", "string"), true, args -> { writes[0]++; return Tool.Result.ok("saved"); });
        Agent.Provider provider = (request, live, cancel) -> {
            int at = requests[0]++;
            if (at == 2) return AgentTest.say("finished");
            JSONObject call = AgentTest.calls("write", at == 0 ? "{\"content\":\"cut" : "{\"content\":\"complete\"}");
            JSONObject delta = new JSONObject().put("choices", new org.json.JSONArray().put(new JSONObject()
                .put("delta", new JSONObject().put("tool_calls", call.getJSONArray("tool_calls"))).put("finish_reason", "tool_calls")));
            return OpenRouter.decode(new StringReader(event(delta) + "data: [DONE]\n\n"), live, cancel, m -> {});
        };
        Agent agent = new Agent(provider, "test", "test", List.of(write), new Cancel()); agent.retryBaseMs = 0;
        JSONObject result = agent.prompt("save this note");
        System.out.println("Incomplete-arguments recovery: " + result.optString("stopReason") + ", requests=" + requests[0] + ", writes=" + writes[0]);
        check(result.optString("content").equals("finished") && requests[0] == 3 && writes[0] == 1);
    }

    static void boundedArgumentRecovery() throws Exception {
        int[] calls = {0}, writes = {0}, limit = {0};
        Tool write = AgentTest.tool("write", AgentTest.schema(), true, args -> { writes[0]++; return Tool.Result.ok("saved"); });
        Agent agent = new Agent((request, live, cancel) -> { calls[0]++; limit[0] = request.optInt("max_tokens"); throw new OpenRouter.IncompleteArguments(); }, "test", "test", List.of(write), new Cancel());
        agent.maxTokens = 2000; agent.maxOutputTokens = 3000;
        JSONObject result = agent.prompt("save");
        check(calls[0] == 2 && writes[0] == 0 && limit[0] == 3000 && result.optString("stopReason").equals("error"));
        Session session = session(); session.appendCustom("run_config", new JSONObject().put("approval", "full"));
        Tool edit = new Tool("edit", "edit", AgentTest.schema(), true) {
            public Result execute(JSONObject args, Cancel cancel) throws Exception { ScoutChanges.note(session, "tree:A", "Knowledge/Test.md", "before", "after"); return Result.ok("unreachable"); }
        };
        AgentTest.Faux provider = new AgentTest.Faux().then(AgentTest.calls("edit", "{}"));
        Agent broken = new Agent(provider, "test", "test", List.of(edit), new Cancel()); broken.attach(session);
        broken.approvals = proposal -> { throw new java.io.IOException("storage unavailable"); };
        broken.prompt("save");
        check(provider.requests.size() == 1 && ScoutChanges.pending(session).size() == 1 && ScoutState.latest(session, "proposal_error").optString("message").contains("storage unavailable"));
    }

    static void inlineVaultCache() throws Exception {
        Session session = session(); session.appendCustom("run_config", new JSONObject().put("approval", "full"));
        VaultToolsTest.Mem files = new VaultToolsTest.Mem() {
            @Override public void write(String path, String before, String after) throws Exception { ScoutChanges.note(session, "tree:A", path, before, after); }
        };
        files.notes.put("Knowledge/Test.md", "before");
        ScoutChanges.Notes notes = new ScoutChanges.Notes() {
            public String tree() { return "tree:A"; }
            public String read(String tree, String path) { return files.notes.get(path); }
            public void write(String tree, String path, String before, String after) { files.notes.put(path, after); }
        };
        AgentTest.Faux provider = new AgentTest.Faux().then(AgentTest.calls(
            "read", "{\"path\":\"Knowledge/Test.md\"}",
            "edit", "{\"path\":\"Knowledge/Test.md\",\"edits\":[{\"oldText\":\"before\",\"newText\":\"after\"}]}",
            "read", "{\"path\":\"Knowledge/Test.md\"}",
            "write", "{\"path\":\"Knowledge/New.md\",\"content\":\"new note\"}",
            "read", "{\"path\":\"Knowledge/New.md\"}" )).then(AgentTest.say("done"));
        Cancel cancel = new Cancel();
        Agent agent = new Agent(provider, "test", "test", VaultTools.create(files, (t, d) -> true, "Knowledge"), cancel); agent.attach(session);
        agent.approvals = p -> ScoutChanges.autoApprove(session, p, cancel, item -> ScoutChanges.applyNote(session, item, true, notes, cancel));
        check(agent.prompt("save and read").optString("content").equals("done") && provider.requests.size() == 2);
        List<JSONObject> results = new java.util.ArrayList<>();
        for (JSONObject m : session.retained()) if (m.optString("role").equals("tool")) results.add(m);
        check(results.size() == 5 && results.get(0).optString("content").contains("before") && results.get(2).optString("content").contains("after") && results.get(4).optString("content").contains("new note"));
        check(ScoutChanges.pending(session).isEmpty());
    }

    static boolean autoPending(Session session, Cancel cancel, ScoutChanges.Apply apply) throws Exception {
        List<JSONObject> pending = ScoutChanges.pending(session);
        return !pending.isEmpty() && ScoutChanges.autoApprove(session, pending.get(0), cancel, apply) != null;
    }

    static void unattendedApprovals() throws Exception {
        check(!ScoutChanges.fullAccess(null) && !ScoutChanges.fullAccess(new JSONObject()));
        check(!ScoutChanges.fullAccess(new JSONObject().put("approval", "unknown")));
        check(!ScoutChanges.fullAccess(new JSONObject().put("approval", "full").put("discovery", true)));
        Session session = session(); Notes notes = new Notes(); int[] remoteCalls = {0};
        session.appendCustom("run_config", new JSONObject().put("approval", "full"));
        Tool edit = new Tool("edit", "edit", AgentTest.schema(), true) {
            public Result execute(JSONObject args, Cancel cancel) throws Exception {
                ScoutChanges.note(session, "tree:A", "Knowledge/Test.md", "before", "after"); return Result.error("unreachable");
            }
        };
        Tool external = new Tool("mcp__tinyfish__fetch_content", "remote", AgentTest.schema(), true) {
            public Result execute(JSONObject args, Cancel cancel) throws Exception {
                ScoutChanges.external(session, "tinyfish", "https://example.org/mcp", "r1", "fetch_content", args); return Result.error("unreachable");
            }
        };
        ScoutChanges.External remote = new ScoutChanges.External() {
            public String revision() { return "r1"; }
            public void connect(Cancel cancel) { }
            public Tool.Result call(String tool, JSONObject args, Cancel cancel) { remoteCalls[0]++; return Tool.Result.ok("Page content https://example.org/weather"); }
        };
        AgentTest.Faux provider = new AgentTest.Faux().then(AgentTest.calls("edit", "{}", "mcp__tinyfish__fetch_content", "{}" )).then(AgentTest.say("finished"));
        Cancel cancel = new Cancel();
        Agent agent = new Agent(provider, "test", "test", List.of(edit, external), cancel); agent.attach(session);
        List<String> events = new java.util.ArrayList<>();
        agent.subscribe((type, data) -> events.add(type));
        agent.approvals = p -> ScoutChanges.autoApprove(session, p, cancel, item -> {
            if (item.optString("kind").equals("note")) ScoutChanges.applyNote(session, item, true, notes, cancel);
            else ScoutChanges.applyExternal(session, item, true, remote, cancel);
        });
        JSONObject last = agent.prompt("update and fetch");
        System.out.println("Full-access batch: requests=" + provider.requests.size() + ", writes=" + notes.writes + ", remote calls=" + remoteCalls[0]);
        check(!events.contains("tool_suspended") && java.util.Collections.frequency(events, "agent_start") == 1);
        check(session.retained().stream().noneMatch(m -> m.optString("content").startsWith("Deferred:")));
        check(last.optString("content").equals("finished") && notes.writes == 1 && remoteCalls[0] == 1);
        check(provider.requests.size() == 2 && ScoutChanges.pending(session).isEmpty());
        check(ScoutEvidence.sources(session).size() == 1 && provider.requests.get(1).toString().contains("Host evidence IDs"));
        check(session.retained().stream().filter(m -> m.optString("role").equals("tool")).count() == 2);
        check(ScoutChanges.fullAccess(ScoutState.latest(Session.open(new File(session.exportPath())), "run_config")));

        Session ask = session(); proposal(ask, "before", "after");
        check(!autoPending(ask, new Cancel(), p -> { throw new AssertionError("Default must ask"); }));
        ask.appendCustom("run_config", new JSONObject().put("approval", "full"));
        Cancel stopped = new Cancel(); stopped.stop();
        check(!autoPending(ask, stopped, p -> { throw new AssertionError("Stop must win"); }));
        Session unknown = session(); unknown.appendCustom("run_config", new JSONObject().put("approval", "full"));
        ScoutChanges.CALL.set(new JSONObject().put("id", "remote-call").put("name", "remote"));
        JSONObject p;
        try { ScoutChanges.external(unknown, "tinyfish", "https://example.org/mcp", "r1", "fetch", new JSONObject()); throw new AssertionError(); }
        catch (ScoutChanges.Suspended waiting) { p = waiting.proposal; }
        finally { ScoutChanges.CALL.remove(); }
        unknown.appendCustom("dispatch_intent", new JSONObject().put("proposalId", p.getString("proposalId")));
        check(!autoPending(unknown, new Cancel(), item -> { throw new AssertionError("Unknown outcome must not dispatch again"); }));
        ScoutChanges.resolve(unknown, p, "Unknown outcome", true);
        ScoutChanges.CALL.set(new JSONObject().put("id", "retry-call").put("name", "remote"));
        try { ScoutChanges.external(unknown, "tinyfish", "https://example.org/mcp", "r1", "fetch", new JSONObject()); }
        catch (ScoutChanges.Suspended expected) { }
        finally { ScoutChanges.CALL.remove(); }
        check(!autoPending(unknown, new Cancel(), item -> { throw new AssertionError("New proposal must not hide an uncertain dispatch"); }));
        Session manual = session(); manual.appendCustom("run_config", new JSONObject().put("approval", "full"));
        ScoutChanges.CALL.set(new JSONObject().put("id", "manual_save").put("name", "save"));
        try { ScoutChanges.prepareNote(manual, "tree:A", "Knowledge/Test.md", "before", "after", new org.json.JSONArray(), false); }
        finally { ScoutChanges.CALL.remove(); }
        check(!autoPending(manual, new Cancel(), item -> { throw new AssertionError("Manual proposals must remain explicit"); }));
    }

    static void stopWhileWaiting() throws Exception {
        Session session = session(); JSONObject p = proposal(session, "before", "after"); Notes notes = new Notes(); Cancel cancel = new Cancel();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        VaultLock.L.lock();
        Thread worker = new Thread(() -> { try { ScoutChanges.applyNote(session, p, true, notes, cancel); } catch (Throwable e) { failure.set(e); } });
        try {
            worker.start();
            long until = System.nanoTime() + 1_000_000_000L;
            while (!VaultLock.L.hasQueuedThread(worker) && System.nanoTime() < until) Thread.yield();
            check(VaultLock.L.hasQueuedThread(worker)); cancel.stop();
        } finally { VaultLock.L.unlock(); }
        worker.join(1000);
        check(!worker.isAlive() && failure.get() == null && notes.writes == 0);
    }

    static void approvalReadersStayResponsive() throws Exception {
        Session noteSession = session(); JSONObject note = proposal(noteSession, "before", "after");
        java.util.concurrent.CountDownLatch noteEntered = new java.util.concurrent.CountDownLatch(1), noteRelease = new java.util.concurrent.CountDownLatch(1);
        Notes slowNotes = new Notes() {
            @Override public String read(String tree, String path) {
                noteEntered.countDown();
                try { if (!noteRelease.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Storage fixture timed out"); }
                catch (InterruptedException failed) { throw new AssertionError(failed); }
                return content;
            }
        };
        readableDuringApproval(noteSession, () -> ScoutChanges.applyNote(noteSession, note, true, slowNotes, new Cancel()), noteEntered, noteRelease);

        Session remoteSession = session(); JSONObject external;
        ScoutChanges.CALL.set(new JSONObject().put("id", "fetch-call").put("name", "mcp__tinyfish__fetch_content"));
        try { ScoutChanges.external(remoteSession, "tinyfish", "https://example.org/mcp", "r1", "fetch_content", new JSONObject()); throw new AssertionError(); }
        catch (ScoutChanges.Suspended waiting) { external = waiting.proposal; }
        finally { ScoutChanges.CALL.remove(); }
        java.util.concurrent.CountDownLatch remoteEntered = new java.util.concurrent.CountDownLatch(1), remoteRelease = new java.util.concurrent.CountDownLatch(1);
        ScoutChanges.External slowRemote = new ScoutChanges.External() {
            public String revision() { return "r1"; }
            public void connect(Cancel cancel) { }
            public Tool.Result call(String tool, JSONObject args, Cancel cancel) throws Exception {
                remoteEntered.countDown();
                if (!remoteRelease.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Network fixture timed out");
                return Tool.Result.ok("Page content");
            }
        };
        JSONObject captured = external;
        readableDuringApproval(remoteSession, () -> ScoutChanges.applyExternal(remoteSession, captured, true, slowRemote, new Cancel()), remoteEntered, remoteRelease);
    }

    static void readableDuringApproval(Session session, Throwing approve, java.util.concurrent.CountDownLatch entered, java.util.concurrent.CountDownLatch release) throws Exception {
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        boolean responsive = false;
        try {
            java.util.concurrent.Future<?> work = threads.submit(() -> { approve.run(); return null; });
            check(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<?> render = threads.submit(() -> { session.retained(); session.log(); session.totals(); return null; });
            try { render.get(200, java.util.concurrent.TimeUnit.MILLISECONDS); responsive = true; }
            catch (java.util.concurrent.TimeoutException blocked) { }
            finally { release.countDown(); }
            work.get(1, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("Chat readable during approval I/O: " + responsive);
            check(responsive);
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    static void durableDecisionsAndRemote() throws Exception {
        Session declined = session(); Notes notes = new Notes(); JSONObject p = proposal(declined, "before", "after");
        declined.appendCustom("proposal_decision", ScoutChanges.decision(p.getString("proposalId"), false));
        Session reopened = Session.open(new File(declined.exportPath()));
        ScoutChanges.applyNote(reopened, p, true, notes, new Cancel()); check(notes.writes == 0 && ScoutChanges.pending(reopened).isEmpty());
        class Remote implements ScoutChanges.External {
            int calls; String revision = "r1"; boolean fail;
            public String revision() { return revision; }
            public void connect(Cancel cancel) { }
            public Tool.Result call(String tool, JSONObject args, Cancel cancel) throws Exception { calls++; if (fail) throw new java.io.IOException("lost after dispatch"); return Tool.Result.ok("remote result"); }
        }
        Session remote = session(); JSONObject proposal;
        ScoutChanges.CALL.set(new JSONObject().put("id", "remote-call").put("name", "remote"));
        try { ScoutChanges.external(remote, "server", "https://example.org/mcp", "r1", "change", new JSONObject().put("value", 1)); throw new AssertionError(); }
        catch (ScoutChanges.Suspended waiting) { proposal = waiting.proposal; }
        finally { ScoutChanges.CALL.remove(); }
        Remote provider = new Remote(); provider.fail = true; JSONObject captured = proposal;
        rejects(() -> ScoutChanges.applyExternal(remote, captured, true, provider, new Cancel()));
        Session afterDispatch = Session.open(new File(remote.exportPath()));
        ScoutChanges.applyExternal(afterDispatch, captured, true, provider, new Cancel());
        check(provider.calls == 1 && ScoutChanges.pending(afterDispatch).isEmpty());
        ScoutChanges.CALL.set(new JSONObject().put("id", "new-remote-call").put("name", "remote"));
        try { ScoutChanges.external(afterDispatch, "server", "https://example.org/mcp", "r1", "change", new JSONObject().put("value", 2)); throw new AssertionError(); }
        catch (ScoutChanges.Suspended waiting) { check(waiting.proposal.getJSONArray("previousUnknown").length() == 1); }
        finally { ScoutChanges.CALL.remove(); }
        check(afterDispatch.retained().get(0).optString("content").contains("outcome unknown"));
        Session changed = session(); JSONObject changedProposal = new JSONObject(proposal.toString()).put("proposalId", "new-configuration");
        changed.appendCustom("proposal", changedProposal); Remote changedProvider = new Remote(); changedProvider.revision = "r2";
        ScoutChanges.applyExternal(changed, changedProposal, true, changedProvider, new Cancel()); check(changedProvider.calls == 0);
        Session remoteDecline = session(); JSONObject declinedProposal = new JSONObject(proposal.toString()).put("proposalId", "declined-before-crash");
        remoteDecline.appendCustom("proposal", declinedProposal);
        remoteDecline.appendCustom("proposal_decision", ScoutChanges.decision("declined-before-crash", false));
        Remote never = new Remote(); ScoutChanges.applyExternal(remoteDecline, declinedProposal, true, never, new Cancel()); check(never.calls == 0);
    }

}
