import me.venkatesh.saveobsidian.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Accounting invariants: actual requests, unknown vs zero, recovery, context and old sessions. */
public class SessionUsageTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Usage check " + checks); }
    private static JSONObject request(String model, String user) throws Exception {
        return new JSONObject().put("model", model).put("max_tokens", 3000).put("messages", new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", "Rules"))
            .put(new JSONObject().put("role", "user").put("content", user)))
            .put("tools", new JSONArray().put(new JSONObject().put("type", "function").put("function", new JSONObject().put("name", "read"))));
    }
    private static JSONObject usage(int in, int out, String cost) throws Exception {
        JSONObject u = new JSONObject().put("prompt_tokens", in).put("completion_tokens", out);
        if (cost != null) u.put("cost", cost);
        return u;
    }
    private static JSONObject call(Session s, String purpose, JSONObject req, JSONObject u) throws Exception {
        return SessionUsage.measure(s, "run1", purpose, req, 200000, e -> { }, metadata -> {
            metadata.accept(new JSONObject().put("id", "gen-known").put("model", req.optString("model")));
            JSONObject reply = new JSONObject().put("content", "answer");
            if (u != null) reply.put("usage", u);
            return reply;
        });
    }
    public static void run() throws Exception {
        File dir = Files.createTempDirectory("scout-usage").toFile();
        Session s = Session.create(dir);
        JSONObject req = request("test/model", "Question");
        JSONObject reply = call(s, "conversation", req, usage(100, 20, "0.1"));
        s.appendMessage(new JSONObject().put("role", "assistant").put("content", "answer").put("usage", reply.get("usage")));
        check(s.totals().getLong("calls") == 1 && s.totals().getDouble("cost") == .1);
        call(s, "naming", req, usage(10, 2, "0.2"));
        JSONObject summary = call(s, "compaction", req, usage(50, 10, "0.03"));
        s.appendUsage("compaction", summary.getJSONObject("usage"));
        check(s.totals().getLong("calls") == 3 && s.totals().getString("cost").equals("0.33"));
        check(!s.totals().getBoolean("partial"));
        check(Session.open(new File(s.exportPath())).totals().getString("cost").equals("0.33"));
        check(s.context().size() == 1); // telemetry is never conversation context

        Session unknown = Session.create(dir);
        call(unknown, "conversation", req, usage(7, 4, null));
        check(unknown.totals().getInt("missingCost") == 1 && SessionUsage.money(unknown.totals()).equals("Cost unavailable"));
        String rid = SessionUsage.requests(unknown).get(0).getString("requestId");
        JSONObject recovered = new JSONObject().put("type", "request_update").put("requestId", rid).put("usage", usage(7, 4, "0"));
        unknown.append(recovered); unknown.append(new JSONObject(recovered.toString()));
        check(unknown.totals().getLong("calls") == 1 && SessionUsage.money(unknown.totals()).equals("$0.00"));
        call(unknown, "conversation", req, null);
        check(unknown.totals().getBoolean("partial") && SessionUsage.money(unknown.totals()).contains("partial"));

        Session interrupted = Session.create(dir);
        try {
            SessionUsage.measure(interrupted, "run", "conversation", req, 200000, e -> { }, metadata -> {
                metadata.accept(new JSONObject().put("id", "gen-interrupted"));
                throw new java.io.IOException("Disconnected");
            });
            throw new AssertionError("must preserve the transport exception");
        } catch (java.io.IOException expected) { }
        JSONObject failed = SessionUsage.requests(Session.open(new File(interrupted.exportPath()))).get(0);
        check(failed.getString("generationId").equals("gen-interrupted") && failed.getString("status").equals("error"));
        check(interrupted.totals().getBoolean("partial"));
        check(s.totals().getString("cost").equals("0.33")); // separate session isolation

        JSONObject point = SessionUsage.describe(req, 200000);
        check(point.getInt("window") == 200000 && point.getInt("reserve") == 3000);
        check(SessionUsage.estimate(point, SessionUsage.requests(s)) == 100);
        JSONObject next = new JSONObject(req.toString());
        next.getJSONArray("messages").put(new JSONObject().put("role", "assistant").put("content", "Visible answer"));
        check(SessionUsage.estimate(SessionUsage.describe(next, 200000), SessionUsage.requests(s)) > 100);
        JSONObject changed = request("different/model", "Question");
        JSONObject different = SessionUsage.describe(changed, 10000);
        check(SessionUsage.estimate(different, SessionUsage.requests(s)) == different.getLong("estimate"));
        JSONObject compacted = SessionUsage.describe(request("test/model", "A new summary"), 200000);
        check(SessionUsage.estimate(compacted, SessionUsage.requests(s)) == compacted.getLong("estimate"));
        next.put("tools", new JSONArray());
        JSONObject toolsChanged = SessionUsage.describe(next, 200000);
        check(SessionUsage.estimate(toolsChanged, SessionUsage.requests(s)) == toolsChanged.getLong("estimate"));
        SessionUsage.snapshot(s, req, 200000, "request");
        check(SessionUsage.context(s).getLong("estimate") == 100 && SessionUsage.history(s).size() == 1);
        check(!s.log().toString().contains("\"content\":\"Question\"")); // payload hashes, not duplicated user/note text

        Session legacy = Session.create(dir);
        legacy.appendMessage(new JSONObject().put("role", "assistant").put("content", "old").put("usage", usage(10, 2, "0.01")));
        call(legacy, "conversation", req, usage(30, 4, "0.02"));
        check(legacy.totals().getBoolean("historical") && legacy.totals().getString("cost").equals("0.03"));
        check(SessionUsage.context(legacy).length() == 0);

        Session dropped = Session.create(dir);
        dropped.append(new JSONObject().put("type", "request").put("requestId", "killed").put("status", "pending"));
        check(Session.open(new File(dropped.exportPath())).totals().getInt("pending") == 1);

        Agent.Provider fake = (request, live, cancel) -> new JSONObject().put("content", "done").put("usage", usage(1, 1, "0.001"));
        Session continuation = Session.create(dir);
        final int[] calls = {0};
        Agent.Provider metered = (request, live, cancel) -> SessionUsage.measure(continuation, "run", "conversation", request, 200000, e -> { }, metadata -> {
            JSONObject r = fake.stream(request, live, cancel);
            if (calls[0]++ == 0) r.put("finish_reason", "length");
            return r;
        });
        Agent agent = new Agent(metered, "test/model", "Rules", java.util.Collections.emptyList(), new Cancel());
        agent.attach(continuation); agent.prompt("Continue");
        check(continuation.totals().getLong("calls") == 2 && continuation.totals().getString("cost").equals("0.002"));
        check(continuation.context().size() == 2); // two calls, one displayed answer
        Session retry = Session.create(dir);
        Agent.Provider retrying = new Agent.Provider() {
            public JSONObject stream(JSONObject request, java.util.function.Consumer<String> live, Cancel cancel) throws Exception {
                return streamAttempt(request, live, cancel, 0, false);
            }
            public JSONObject streamAttempt(JSONObject request, java.util.function.Consumer<String> live, Cancel cancel, int attempt, boolean continued) throws Exception {
                return SessionUsage.measure(retry, "retry-run", "conversation", request, 200000, e -> { }, attempt, continued, metadata -> {
                    if (attempt == 0) throw new java.io.IOException("temporary failure");
                    return new JSONObject().put("content", "done").put("usage", usage(2, 1, "0.01"));
                });
            }
        };
        Agent retryAgent = new Agent(retrying, "test/model", "Rules", java.util.Collections.emptyList(), new Cancel());
        retryAgent.attach(retry); retryAgent.retryBaseMs = 0; retryAgent.prompt("Try");
        List<JSONObject> attempts = SessionUsage.requests(retry);
        check(attempts.size() == 2 && attempts.get(1).getInt("attempt") == 1);
        check(attempts.get(1).getString("relatedRequestId").equals(attempts.get(0).getString("requestId")));
        check(retry.totals().getBoolean("partial") && retry.totals().getString("cost").equals("0.01"));

        Session failedSummary = Session.create(dir);
        for (int i = 0; i < 6; i++) {
            failedSummary.appendMessage(new JSONObject().put("role", "user").put("content", new String(new char[400]).replace('\0', 'q')));
            failedSummary.appendMessage(new JSONObject().put("role", "assistant").put("content", new String(new char[400]).replace('\0', 'a')));
        }
        Agent.Provider summaryProvider = (request, live, cancel) -> SessionUsage.measure(failedSummary, "summary-run", "compaction", request, 200000, e -> { }, metadata ->
            new JSONObject().put("content", "").put("usage", usage(50, 20, "0.02")));
        Agent summaryAgent = new Agent(fake, "test/model", "Rules", java.util.Collections.emptyList(), new Cancel());
        summaryAgent.attach(failedSummary);
        Compact compact = new Compact(summaryProvider, "test/model", failedSummary, 1000);
        compact.keepRecent = 300;
        check(!compact.now(summaryAgent, null));
        check(failedSummary.totals().getString("cost").equals("0.02") && failedSummary.stats().getInt("compactions") == 0);

        Session details = Session.create(dir);
        JSONObject detailed = usage(100, 30, "0.001").put("prompt_tokens_details", new JSONObject().put("cached_tokens", 80).put("cache_write_tokens", 10))
            .put("completion_tokens_details", new JSONObject().put("reasoning_tokens", 20));
        call(details, "conversation", req, detailed);
        JSONObject counts = details.totals();
        check(counts.getLong("prompt_tokens") + counts.getLong("completion_tokens") == 130);
        check(counts.getLong("cached") == 80 && counts.getLong("cacheWrites") == 10 && counts.getLong("reasoning") == 20);
        check(SessionUsage.context(details).length() == 0);
        System.out.println("Session usage checks passed (" + checks + ").");
    }
    public static void main(String[] args) throws Exception { run(); }
}
