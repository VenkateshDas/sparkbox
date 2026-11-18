import me.venkatesh.saveobsidian.Metrics;
import me.venkatesh.saveobsidian.Session;
import me.venkatesh.saveobsidian.SessionUsage;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Local accounting and privacy checks. No Android, owner files, or provider calls. */
public class MetricsTest {
    private static int checks;
    private static void check(boolean ok, String reason) {
        checks++;
        if (!ok) throw new AssertionError("Metrics check " + checks + ": " + reason);
    }
    private static JSONObject object(Object... pairs) throws Exception {
        JSONObject out = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }
    private static JSONObject event(String type, String time, Object... pairs) throws Exception {
        return object(pairs).put("type", type).put("timestamp", time);
    }
    private static List<JSONObject> rows(JSONArray array) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) out.add(array.getJSONObject(i));
        return out;
    }
    private static JSONObject totals() throws Exception { return SessionUsage.sum(rows(Metrics.read().getJSONArray("requests"))); }
    private static JSONObject usage(String cost) throws Exception {
        JSONObject out = object("prompt_tokens", 100, "completion_tokens", 30,
            "prompt_tokens_details", object("cached_tokens", 80, "cache_write_tokens", 10),
            "completion_tokens_details", object("reasoning_tokens", 20));
        if (cost != null) out.put("cost", cost);
        return out;
    }
    public static void run() throws Exception {
        checks = 0;
        Path temporary = Files.createTempDirectory("sparkbox-metrics-test");
        File journal = temporary.resolve("metrics.jsonl").toFile();
        Metrics.configure(journal, true);
        try {
            String secret = "SECRET-NEVER-PERSIST-TEST";
            JSONObject dirty = usage("0.1").put("api_key", secret).put("content", secret);
            dirty.getJSONObject("prompt_tokens_details").put("private_url", secret);
            JSONObject clean = Metrics.usage(dirty);
            check(!clean.toString().contains(secret), "usage metadata whitelist drops unknown strings");
            check(clean.getJSONObject("prompt_tokens_details").getLong("cached_tokens") == 80, "cache reads retained");
            check(clean.getJSONObject("completion_tokens_details").getLong("reasoning_tokens") == 20, "reasoning retained");
            check(!Metrics.usage(object("cost", "-1", "prompt_tokens", "invalid")).has("cost"), "negative cost omitted");
            check(!Metrics.usage(object("prompt_tokens", "invalid")).has("prompt_tokens"), "invalid tokens omitted");

            JSONObject request = object("model", "test/model", "messages", new JSONArray().put(object("role", "user", "content", secret)), "api_key", secret);
            String before = request.toString();
            JSONObject response = object("content", secret, "usage", dirty, "finish_reason", "stop");
            JSONObject result = Metrics.measure(request, "ingest", 0, metadata -> {
                JSONObject first = object("id", "generation-test", "model", "actual/model", "finish_reason", "stop", "content", secret);
                metadata.accept(first); metadata.accept(first);
                metadata.accept(object("usage", dirty));
                return response;
            });
            check(result == response && request.toString().equals(before), "meter preserves response and request");
            JSONArray recorded = Metrics.read().getJSONArray("requests");
            check(recorded.length() == 1, "metadata updates do not become extra requests");
            JSONObject measured = recorded.getJSONObject(0);
            check(measured.getString("generationId").equals("generation-test") && measured.getString("actualModel").equals("actual/model"), "provider receipt preserved");
            check(measured.getString("status").equals("completed") && measured.getLong("durationMs") >= 0, "completion and duration recorded");
            check(Files.readAllLines(journal.toPath()).size() == 4, "identical streamed metadata is not written repeatedly");
            check(!Files.readString(journal.toPath()).contains(secret), "journal excludes request/response content and keys");

            Metrics.measure(request, "cards", 0, metadata -> object("usage", usage("0.2")));
            check(new BigDecimal(totals().getString("cost")).compareTo(new BigDecimal("0.3")) == 0, "decimal sums do not acquire floating-point error");
            JSONObject count = totals();
            check(count.getLong("prompt_tokens") + count.getLong("completion_tokens") == 260, "cache and reasoning subsets are not added twice");
            check(count.getLong("cached") == 160 && count.getLong("reasoning") == 40, "subsets remain independently inspectable");

            Metrics.clear();
            Metrics.measure(request, "audio", 0, metadata -> new JSONObject());
            Metrics.measure(request, "local-free-model", 0, metadata -> object("usage", object("cost", "0")));
            count = totals();
            check(count.getInt("knownCost") == 1 && count.getInt("missingCost") == 1, "known zero differs from unavailable cost");
            check(count.getInt("knownTokens") == 0, "unsupported tokens remain unavailable");
            check(new BigDecimal(count.getString("cost")).signum() == 0, "known zero remains zero");

            Metrics.clear();
            try {
                Metrics.measure(request, "ingest", 0, metadata -> {
                    metadata.accept(object("id", "generation-interrupted"));
                    throw new IOException(secret);
                });
                throw new AssertionError("transport exception was swallowed");
            } catch (IOException expected) { check(expected.getMessage().equals(secret), "transport exception preserved"); }
            Metrics.measure(request, "ingest", 1, metadata -> object("usage", usage("0.01")));
            recorded = Metrics.read().getJSONArray("requests");
            check(recorded.length() == 2, "retry is another actual attempt");
            check(recorded.getJSONObject(0).getString("status").equals("error") && recorded.getJSONObject(1).getInt("attempt") == 1, "attempt outcomes stay separate");
            check(!recorded.getJSONObject(0).getString("requestId").equals(recorded.getJSONObject(1).getString("requestId")), "attempt IDs are unique");
            check(!Files.readString(journal.toPath()).contains(secret), "provider error text is not recorded");

            Metrics.clear();
            try (AutoCloseable ignored = Metrics.suppress()) {
                Metrics.measure(request, "inner", 0, metadata -> object("usage", usage("9")));
                try (AutoCloseable nested = Metrics.suppress()) {
                    Metrics.measure(request, "inner", 0, metadata -> new JSONObject());
                }
                Metrics.measure(request, "inner", 0, metadata -> new JSONObject());
            }
            check(Metrics.read().getJSONArray("requests").length() == 0, "nested suppression prevents duplicate meters");
            Session session = Session.create(temporary.resolve("sessions").toFile());
            SessionUsage.measure(session, "run", "compaction", request, 10000, changed -> {}, metadata ->
                Metrics.measure(request, "inner", 0, unused -> {
                    JSONObject reply = object("usage", usage("0.02"));
                    metadata.accept(reply);
                    return reply;
                }));
            check(Metrics.read().getJSONArray("requests").length() == 1 && SessionUsage.requests(session).size() == 1, "Scout and shared meter count one attempt");
            check(Metrics.read().getJSONArray("requests").getJSONObject(0).getString("stage").equals("compaction"), "Scout update preserves original purpose");

            String firstTime = "2026-11-01T00:00:00Z", laterTime = "2026-11-02T00:00:00Z";
            List<JSONObject> updates = List.of(
                event("request", firstTime, "requestId", "r", "operationId", "op", "stage", "naming", "status", "error"),
                event("request_update", laterTime, "requestId", "r", "usage", usage("0.4"), "recovered", true));
            JSONObject folded = Metrics.reduce(updates, 0).getJSONArray("requests").getJSONObject(0);
            check(folded.getString("timestamp").equals(firstTime), "recovered charge stays on original request date");
            check(folded.getString("stage").equals("naming") && folded.getString("status").equals("error"), "charge recovery does not change attribution or execution outcome");
            check(Metrics.reduce(updates, Instant.parse(laterTime).toEpochMilli()).getJSONArray("requests").length() == 0, "cutoff does not resurrect expired request from a later update");

            Metrics.clear();
            try (Metrics.Scope ignored = Metrics.stage("wrong-new-stage")) {
                Metrics.scout(event("request", Instant.now().toString(), "requestId", "scout-r", "purpose", "naming", "status", "completed"), "s");
            }
            try (Metrics.Scope ignored = Metrics.stage("other-stage")) {
                Metrics.scout(object("type", "request_update", "requestId", "scout-r", "usage", usage("0.5")), "s");
            }
            check(Metrics.read().getJSONArray("requests").getJSONObject(0).getString("stage").equals("naming"), "update outside original scope cannot overwrite stage");

            Metrics.clear();
            try (Metrics.Operation operation = Metrics.operation("Card creation", "job", "cards")) {
                Metrics.cards(8, 5);
                operation.done();
            }
            JSONObject operation = Metrics.read().getJSONArray("operations").getJSONObject(0);
            check(operation.getString("status").equals("completed") && operation.getInt("cardsDrafted") == 8 && operation.getInt("cardsSaved") == 5, "terminal operation retains card-count patch");
            check(operation.getLong("startedAt") > 0 && operation.getLong("endedAt") >= operation.getLong("startedAt"), "patch does not reset operation start");

            Metrics.clear();
            try (Metrics.Operation op = Metrics.operation("Scout interaction", "interaction", "conversation")) {
                op.session("deleted-session");
                Metrics.scout(object("type", "request", "requestId", "delete-r", "purpose", "conversation"), "deleted-session");
                op.done();
            }
            Metrics.configure(journal, false);
            Metrics.forgetSession("deleted-session");
            JSONObject deleted = Metrics.read();
            check(deleted.getJSONArray("requests").length() == 0 && deleted.getJSONArray("operations").length() == 0, "session deletion works when collection disabled");
            Metrics.configure(journal, true);
            Metrics.clear();
            Files.writeString(journal.toPath(), "not-json\n{\"type\":", StandardCharsets.UTF_8);
            Metrics.measure(request, "ingest", 0, metadata -> object("usage", usage("0.01")));
            JSONObject damaged = Metrics.read();
            check(damaged.getInt("malformed") == 2 && damaged.getJSONArray("requests").length() == 1, "torn line does not swallow later valid records");
            check(!Metrics.problem().contains(secret), "storage diagnostics contain no payload");
            Metrics.clear();
            check(!journal.exists() && Metrics.read().getJSONArray("requests").length() == 0, "clear removes retained statistics");

            List<JSONObject> retention = List.of(
                event("request", firstTime, "requestId", "old"), event("request", laterTime, "requestId", "new"),
                event("event", laterTime, "eventId", "event-1", "feature", "capture"));
            JSONObject kept = Metrics.reduce(retention, Instant.parse(laterTime).toEpochMilli());
            check(kept.getJSONArray("requests").length() == 1 && kept.getJSONArray("requests").getJSONObject(0).getString("requestId").equals("new"), "retention cutoff is inclusive at its start");
            check(kept.getLong("coverageStart") == Instant.parse(laterTime).toEpochMilli(), "retained coverage reports actual oldest observation");
            Metrics.clear();
            Metrics.Operation inflight = Metrics.operation("Card creation", "cleared-job", "cards");
            Metrics.clear(); inflight.done(); inflight.close();
            check(Metrics.read().getJSONArray("operations").length() == 0, "in-flight operation cannot recreate cleared statistics");

            try (Metrics.Operation op = Metrics.operation("Scout interaction", "cards-proposal", "cards")) {
                op.session("proposal-session"); op.cards(4, 0);
                Metrics.proposal("p1", "proposal-session"); Metrics.proposal("p2", "proposal-session"); op.outcome("awaiting_approval");
            }
            String note = "# Note\n", first = note + "\n## Cards\n### c_one\nQ: One?\nA: One.\n";
            String next = first + "### c_two\nQ: Two?\nA: Two.\n### c_three\nQ: Three?\nA: Three.\n";
            Metrics.proposalSaved("p1", "Knowledge/Topics/Test.md", note, first);
            Metrics.proposalSaved("p1", "Knowledge/Topics/Test.md", note, first);
            check(Metrics.read().getJSONArray("operations").getJSONObject(0).getInt("cardsSaved") == 1, "replayed verified write receipt does not count cards twice");
            Metrics.proposalSaved("p2", "Knowledge/Topics/Test.md", first, next);
            check(Metrics.read().getJSONArray("operations").getJSONObject(0).getInt("cardsSaved") == 3, "multiple proposals sum verified new card IDs");
            Metrics.prune();
            check(Metrics.read().getJSONArray("operations").getJSONObject(0).getInt("cardsSaved") == 3, "retention compaction preserves idempotent card receipts");

            me.venkatesh.saveobsidian.Vault.Graph graph = me.venkatesh.saveobsidian.Vault.build(List.of(
                me.venkatesh.saveobsidian.Vault.parse("Knowledge/Sources/AI Systems/2026/Test.md", 1, "---\ntype: source\nquality: full\n---\n# Test\n[[Missing]]"),
                me.venkatesh.saveobsidian.Vault.parse("Knowledge/Sources/index.md", 1, "# Index"),
                me.venkatesh.saveobsidian.Vault.parse("Knowledge/Sources/My cards.md", 1, "# My cards"),
                me.venkatesh.saveobsidian.Vault.parse("Knowledge/Topics/Topic.md", 1, "# Topic")));
            JSONObject inventory = me.venkatesh.saveobsidian.Dashboard.inventory(graph, List.of());
            check(inventory.getJSONArray("notes").length() == 2 && inventory.getInt("sourceCount") == 1, "inventory excludes catalogs and drill file from source counts");
            check(inventory.getJSONArray("unresolved").length() == 1, "inventory uses actual graph resolution");
            System.out.println("Metrics checks passed (" + checks + ").");
        } finally {
            Metrics.configure(null, false);
            try (java.util.stream.Stream<Path> files = Files.walk(temporary)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
            }
        }
    }
    public static void main(String[] args) throws Exception { run(); }
}
