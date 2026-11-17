import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import me.venkatesh.saveobsidian.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deterministic retrieval/cancellation fixtures. No provider, real vault, or Android runtime. */
final class RetrievalTest {
    static int count;
    static void check(boolean ok) { count++; if (!ok) throw new AssertionError("Retrieval check #" + count); }
    interface Throwing { void run() throws Exception; }
    static void rejects(Throwing action) throws Exception { try { action.run(); } catch (Exception expected) { check(true); return; } check(false); }
    static float[] vector(float x, float y) { float[] v = new float[Index.DIMS]; v[0] = x; v[1] = y; return v; }

    static void run() throws Exception {
        QueryVectors cache = new QueryVectors(2, 2);
        Cancel token = new Cancel(); AtomicInteger calls = new AtomicInteger();
        Callable<float[]> load = () -> { calls.incrementAndGet(); return new float[]{3, 4}; };
        float[] first = cache.get("model-a", "editing", token, load);
        check(Math.abs(first[0] - 0.6f) < 0.0001);
        first[0] = 99;
        check(cache.get("model-a", "editing", token, load)[0] < 1 && calls.get() == 1);
        cache.get("model-b", "editing", token, load);
        check(calls.get() == 2); // An encoder change must never reuse another vector space.
        cache.get("model-a", "other", token, load);
        cache.get("model-a", "editing", token, load);
        check(calls.get() == 4); // Bounded LRU eviction, not unbounded query history.
        rejects(() -> cache.get("a", "failure", token, () -> { throw new java.io.IOException("offline"); }));
        check(cache.get("a", "failure", token, load).length == 2);
        rejects(() -> cache.get("a", "wrong size", token, () -> new float[]{1}));
        rejects(() -> cache.get("a", "nan", token, () -> new float[]{Float.NaN, 1}));
        rejects(() -> cache.get("a", "zero", token, () -> new float[]{0, 0}));
        check(cache.get("a", "tiny", token, () -> new float[]{Float.MIN_NORMAL, Float.MIN_NORMAL})[0] > 0.7f);
        check(cache.get("a", "huge", token, () -> new float[]{Float.MAX_VALUE, Float.MAX_VALUE})[0] > 0.7f);
        rejects(() -> cache.get("a", "  ", token, load));
        Cancel stopped = new Cancel(); stopped.stop();
        rejects(() -> cache.get("a", "editing", stopped, load));

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            QueryVectors concurrent = new QueryVectors(8, 2);
            AtomicInteger loads = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            Callable<float[]> blocked = () -> { loads.incrementAndGet(); entered.countDown(); if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("fixture timeout"); return new float[]{1, 0}; };
            Future<float[]> a = pool.submit(() -> concurrent.get("m", "same", token, blocked));
            check(entered.await(1, TimeUnit.SECONDS));
            Future<float[]> b = pool.submit(() -> concurrent.get("m", "same", token, blocked));
            Future<float[]> unrelated = pool.submit(() -> concurrent.get("m", "different", token, () -> new float[]{0, 1}));
            check(unrelated.get(1, TimeUnit.SECONDS)[1] == 1); // No network call holds the cache lock.
            release.countDown();
            check(a.get(1, TimeUnit.SECONDS)[0] == 1 && b.get(1, TimeUnit.SECONDS)[0] == 1 && loads.get() == 1);

            QueryVectors isolated = new QueryVectors(8, 2);
            Cancel cancelledChat = new Cancel(), otherChat = new Cancel();
            CountDownLatch began = new CountDownLatch(1), finish = new CountDownLatch(1);
            Future<float[]> cancelled = pool.submit(() -> isolated.get("m", "same", cancelledChat, () -> { began.countDown(); finish.await(2, TimeUnit.SECONDS); return new float[]{1, 0}; }));
            check(began.await(1, TimeUnit.SECONDS));
            Future<float[]> independent = pool.submit(() -> isolated.get("m", "same", otherChat, () -> new float[]{0, 1}));
            check(independent.get(1, TimeUnit.SECONDS)[1] == 1);
            cancelledChat.stop(); finish.countDown();
            rejects(() -> cancelled.get(1, TimeUnit.SECONDS));
            check(isolated.get("m", "same", otherChat, load)[1] == 1); // Cancelled work cannot overwrite a valid result.
        } finally { pool.shutdownNow(); }

        File dir = Files.createTempDirectory("scout-retrieval").toFile();
        File file = new File(dir, "index.bin");
        Session session = Session.create(dir);
        try {
            Index disk = new Index(); disk.put("one", vector(1, 0)); disk.save(file);
            Index resident = Index.cached(file);
            check(resident == Index.cached(file));
            long stamp = file.lastModified();
            disk.put("one", vector(0, 1)); disk.save(file); file.setLastModified(stamp);
            check(Index.cached(file) != resident && Index.cached(file).vector("one")[1] == 1); // Same-size, same-time rewrites still invalidate.
            check(Index.load(file) != Index.cached(file)); // Writers never mutate the resident instance.

            Vault.Note both = Vault.parse("Knowledge/Both.md", 1, "# Video editing\nTimeline assembly and cutting footage");
            Vault.Note semantic = Vault.parse("Knowledge/Meaning.md", 1, "# Cutting footage\nTimeline assembly");
            Vault.Note lexical = Vault.parse("Knowledge/Words.md", 1, "# Video editing checklist\nA vocabulary list");
            List<Vault.Note> ranked = Vault.fuse(List.of(both, semantic), List.of(lexical, both), 1, 3);
            check(ranked.get(0) == both && ranked.contains(semantic) && ranked.contains(lexical));
            check(Vault.fuse(List.of(semantic), List.of(), 1, 3).equals(List.of(semantic))); // Synonyms survive with no word overlap.
            check(Vault.fuse(List.of(), List.of(lexical, both), 1, 1).equals(List.of(lexical))); // Offline fallback preserves lexical order.
            check(Vault.fuse(List.of(semantic), List.of(lexical), 1, 2).equals(Vault.fuse(List.of(lexical), List.of(semantic), 1, 2))); // Stable ties.

            check(Vault.fuse(List.of("both", "meaning"), List.of("words", "both"), 1, 3, Comparator.naturalOrder()).get(0).equals("both"));

            class Notes implements VaultTools.Files {
                String body = "# Video editing\nCut on action. Preserve pacing.";
                int reads;
                public List<String> paths() { return List.of("Knowledge/Edit.md"); }
                public String read(String path) { reads++; return body; }
                public void write(String path, String before, String after) { throw new AssertionError("Read-only fixture"); }
                public String search(String query) { return "Found Edit.md"; }
                public Tool.Result searchResult(String query) throws Exception { return new Tool.Result(search(query), new JSONObject().put("matches", new JSONArray().put("Knowledge/Edit.md")), false, false); }
            }
            Notes notes = new Notes();
            List<Tool> tools = ScoutEvidence.wrap(session, VaultTools.create(notes, (title, detail) -> false, "Knowledge"));
            Tool search = tools.stream().filter(t -> t.name.equals("search")).findFirst().orElseThrow();
            check(search.execute(new JSONObject().put("query", "  "), token).error);
            Tool.Result result = search.execute(new JSONObject().put("query", "editing"), token);
            check(result.text.contains("Cut on action") && result.text.contains("[[cite:"));
            check(ScoutEvidence.sources(session).size() == 1);
            JSONObject source = ScoutEvidence.sources(session).values().iterator().next();
            check(source.getString("coverage").equals("full") && source.getString("hash").equals(ScoutChanges.hash(notes.body)));
            notes.body = "# Video editing\nUpdated advice";
            Tool.Result current = search.execute(new JSONObject().put("query", "editing"), token);
            check(current.text.contains("Updated advice") && notes.reads == 2); // Search evidence cannot be a stale cached answer.
            int before = notes.reads;
            check(!search.execute(new JSONObject().put("query", "editing").put("read", 0), token).text.contains("[[cite:") && notes.reads == before);
            notes.body = "Long note ".repeat(300);
            Tool.Result excerpt = search.execute(new JSONObject().put("query", "editing"), token);
            check(excerpt.details.getJSONArray("evidence").getJSONObject(0).getString("coverage").equals("excerpt"));
            check(excerpt.details.getJSONArray("evidence").getJSONObject(0).getString("excerpt").length() == 1600);
            Files.write(file.toPath(), new byte[]{0, 1});
            check(Index.cached(file).size() == 0); // Corrupt and missing caches degrade to lexical retrieval.
            file.delete(); check(Index.cached(file).size() == 0);
        } finally {
            session.close();
            File[] files = dir.listFiles(); if (files != null) for (File item : files) item.delete(); dir.delete();
        }
        System.out.println("Retrieval checks passed (" + count + ").");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { run(); return; }
        File file = Files.createTempFile("scout-retrieval-benchmark", ".bin").toFile();
        try {
            Index index = new Index(); Random random = new Random(41);
            for (int n = 0; n < 1000; n++) { float[] v = new float[Index.DIMS]; for (int d = 0; d < v.length; d++) v[d] = random.nextFloat(); index.put("note " + n, v); }
            index.save(file); float[] q = index.vector("note 7");
            score(file, q, false, 5); score(file, q, true, 5);
            long start = System.nanoTime(); double before = score(file, q, false, 30); double oldMs = (System.nanoTime() - start) / 30_000_000.0;
            start = System.nanoTime(); double after = score(file, q, true, 30); double newMs = (System.nanoTime() - start) / 30_000_000.0;
            if (Math.abs(before - after) > 0.00001) throw new AssertionError("Caching changed vector scores");
            System.out.printf(Locale.US, "Desktop JVM; 1000 notes x 1024 dimensions; 30 warm searches: reload %.2f ms/search, resident %.2f ms/search. Identical scores. Excludes embedding/provider latency.%n", oldMs, newMs);
        } finally { file.delete(); }
    }
    private static double score(File file, float[] q, boolean resident, int rounds) {
        double total = 0;
        for (int r = 0; r < rounds; r++) { Index index = resident ? Index.cached(file) : Index.load(file); for (int n = 0; n < 1000; n++) total += Index.dot(index.vector("note " + n), q); }
        return total;
    }
}
