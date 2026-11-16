package me.venkatesh.saveobsidian;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.File;
import org.json.JSONObject;

/** Safe on-device storage/prompt smoke checks. No selected-vault writes or provider calls. */
final class ScoutDeviceTest {
    static void run(Instrumentation instrumentation, Bundle arguments, Bundle result) throws Exception {
        android.content.Context context = instrumentation.getTargetContext();
        String action = arguments.getString("action", "smoke");
        if (action.equals("prepare")) { prepare(context, result); return; }
        if (action.equals("restore")) { restore(context, result); return; }
        if (action.equals("guard")) { guard(context, result); return; }
        if (action.equals("style")) { guard(context, result); context.getSharedPreferences("capture", 0).edit().putString("theme", arguments.getString("theme", "dark")).commit(); return; }
        if (action.equals("running")) { running(instrumentation, context, result); return; }
        if (action.equals("saf")) { saf(context, result); return; }
        if (action.equals("revoke")) {
            String tree = VaultStore.tree(context);
            if (!android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(tree)).endsWith("/ScoutDeviceTest-20261116")) throw new AssertionError("Not the disposable vault");
            context.getContentResolver().releasePersistableUriPermission(android.net.Uri.parse(tree), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            result.putString("scout", "Disposable SAF persisted grant released"); return;
        }
        if (action.equals("revoked")) {
            String tree = VaultStore.tree(context);
            if (!android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(tree)).endsWith("/ScoutDeviceTest-20261116")) throw new AssertionError("Not the disposable vault");
            try { VaultWrite.readPinned(context, tree, "Knowledge/Fixture.md"); throw new AssertionError("Read still allowed after test grant revocation; inspect overlapping grants"); }
            catch (SecurityException expected) { result.putString("scout", "PASS: revoked SAF access rejects reads"); }
            catch (java.io.IOException expected) {
                if (!expected.getMessage().toLowerCase(java.util.Locale.ROOT).matches("(?s).*(permission|denied|not granted).*")) throw expected;
                result.putString("scout", "PASS: revoked SAF access rejects reads");
            }
            return;
        }
        File dir = new File(context.getCacheDir(), "scout-device-" + java.util.UUID.randomUUID());
        if (!dir.mkdirs()) throw new AssertionError("Could not create disposable fixture directory");
        Session session = Session.create(dir);
        try {
            session.appendMessage(new JSONObject().put("role", "user").put("content", "Fixture question"));
            String retained = session.appendMessage(new JSONObject().put("role", "assistant").put("content", "Fixture answer").put("stopReason", "stop"));
            session.appendCompaction("Fixture summary", retained, 50);
            if (Session.open(new File(session.exportPath())).retained().size() != 2) throw new AssertionError("Compaction hid retained history");
            android.util.AtomicFile draft = new android.util.AtomicFile(new File(dir, "draft.json"));
            java.io.FileOutputStream out = draft.startWrite();
            out.write("{\"text\":\"fixture draft\",\"caret\":4}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); draft.finishWrite(out);
            if (!new JSONObject(new String(draft.readFully(), java.nio.charset.StandardCharsets.UTF_8)).getString("text").equals("fixture draft")) throw new AssertionError("Draft did not survive AtomicFile read-back");
            if (!Scout.SYSTEM.contains("[[cite:sourceId]]") || !Scout.RESEARCH.contains("Reuse retained evidence")) throw new AssertionError("Host evidence prompt contract missing");
            if (ScoutJob.QUALIFIED) throw new AssertionError("Do not unlock schedules before device qualification is recorded");
            result.putString("scout", "PASS: retained JSONL, AtomicFile draft, prompt contract, schedule gate");
            result.putString("scope", "Storage smoke only. Keyboard, lifecycle kills, SAF races, screenshots, TalkBack and schedule qualification remain separate device scenarios.");
        } finally {
            session.close();
            File[] files = dir.listFiles(); if (files != null) for (File file : files) file.delete(); dir.delete();
        }
    }
    private static File stateFile(android.content.Context context) { return new File(context.getFilesDir(), "scout-device-state.json"); }

    /** Backup remains inside app-private storage; credentials never appear in test output. */
    private static void prepare(android.content.Context context, Bundle result) throws Exception {
        if (stateFile(context).exists()) throw new AssertionError("Restore the previous fixture before preparing another");
        android.content.SharedPreferences prefs = context.getSharedPreferences("capture", 0);
        JSONObject state = new JSONObject();
        for (String key : new String[]{"aiKey", "scoutSession", "inbox"}) state.put(key, prefs.contains(key) ? prefs.getString(key, "") : JSONObject.NULL);
        android.content.SharedPreferences cards = context.getSharedPreferences("cardgen", 0);
        state.put("cardsOn", cards.contains("on") ? cards.getBoolean("on", false) : JSONObject.NULL);
        java.nio.file.Files.write(stateFile(context).toPath(), state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Session session = Session.create(ScoutRuns.dir(context));
        String file = new File(session.exportPath()).getName(); session.setName("SCOUT DEVICE FIXTURE");
        session.appendMessage(new JSONObject().put("role", "user").put("content", "Show a synthetic comparison and code. No real notes or network."));
        String source = ScoutEvidence.register(session, "https://example.org/scout-fixture", "Synthetic source", "web", "excerpt", "Synthetic evidence used only for device layout testing.", "fixture", null);
        StringBuilder answer = new StringBuilder("## A useful comparison\n\nSynthetic answer for layout testing. Evidence [[cite:" + source + "]]\n\n| Choice | Benefit | Limit |\n|---|---|---|\n");
        for (int i = 0; i < 8; i++) answer.append("| Option ").append(i).append(" | Predictable behavior | A long cell that must wrap inside its own column |\n");
        answer.append("\n```java\n  first line\n\n  veryLongIdentifierThatMustNotWidenTheWholeScreen = 42;\n```\n\nThis answer ends with an honest limitation.\n");
        session.appendMessage(new JSONObject().put("role", "assistant").put("content", answer.toString()).put("stopReason", "stop"));
        Session other = Session.create(ScoutRuns.dir(context)); other.setName("SCOUT OTHER FIXTURE");
        other.appendMessage(new JSONObject().put("role", "user").put("content", "Other synthetic chat"));
        other.appendMessage(new JSONObject().put("role", "assistant").put("content", "Other chat answer").put("stopReason", "stop"));
        state.put("fixture", file).put("other", new File(other.exportPath()).getName());
        java.nio.file.Files.write(stateFile(context).toPath(), state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File drafts = new File(context.getFilesDir(), "scout-drafts"); drafts.mkdirs();
        JSONObject draft = new JSONObject().put("text", "Unsent synthetic draft").put("caret", 6).put("expanded", false).put("research", true).put("web", false)
            .put("path", "quote:Synthetic attached quote").put("title", "Synthetic quote").put("tree", "");
        java.nio.file.Files.write(new File(drafts, file + ".json").toPath(), draft.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        prefs.edit().remove("aiKey").putString("inbox", "").putString("scoutSession", file).commit();
        cards.edit().putBoolean("on", false).commit();
        context.getSystemService(android.app.job.JobScheduler.class).cancel(1);
        result.putString("fixture", file); result.putString("other", new File(other.exportPath()).getName());
        result.putString("scout", "Prepared synthetic chats; model calls and automatic processing disabled; private restore checkpoint saved");
    }

    private static void restore(android.content.Context context, Bundle result) throws Exception {
        if (!stateFile(context).exists()) { result.putString("scout", "No fixture state to restore"); return; }
        JSONObject state = new JSONObject(new String(java.nio.file.Files.readAllBytes(stateFile(context).toPath()), java.nio.charset.StandardCharsets.UTF_8));
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences("capture", 0).edit();
        for (String key : new String[]{"aiKey", "scoutSession", "inbox", "vault", "theme", "name"}) { if (!state.has(key)) continue; if (state.isNull(key)) editor.remove(key); else editor.putString(key, state.getString(key)); }
        editor.commit();
        android.content.SharedPreferences.Editor cards = context.getSharedPreferences("cardgen", 0).edit();
        if (state.isNull("cardsOn")) cards.remove("on"); else cards.putBoolean("on", state.getBoolean("cardsOn")); cards.commit();
        for (String key : new String[]{"fixture", "other"}) if (state.has(key)) {
            String file = state.getString(key);
            new File(ScoutRuns.dir(context), file).delete();
            new android.util.AtomicFile(new File(context.getFilesDir(), "scout-drafts/" + file + ".json")).delete();
        }
        if (state.has("vault")) { VaultStore.last = null; VaultStore.scan(context); }
        stateFile(context).delete(); result.putString("scout", "Original settings restored; synthetic sessions and drafts removed");
    }

    private static void guard(android.content.Context context, Bundle result) throws Exception {
        if (!stateFile(context).exists()) throw new AssertionError("Prepare fixtures first");
        JSONObject state = new JSONObject(new String(java.nio.file.Files.readAllBytes(stateFile(context).toPath()), java.nio.charset.StandardCharsets.UTF_8));
        android.content.SharedPreferences prefs = context.getSharedPreferences("capture", 0);
        for (String key : new String[]{"vault", "theme", "name"}) if (!state.has(key)) state.put(key, prefs.contains(key) ? prefs.getString(key, "") : JSONObject.NULL);
        java.nio.file.Files.write(stateFile(context).toPath(), state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        result.putString("scout", "Private restore checkpoint extended; no credentials exposed");
    }

    private static void saf(android.content.Context context, Bundle result) throws Exception {
        String tree = VaultStore.tree(context);
        if (!android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(tree)).endsWith("/ScoutDeviceTest-20261116")) throw new AssertionError("Refusing to test writes outside disposable vault");
        String path = "Knowledge/Fixture.md", before = VaultWrite.readPinned(context, tree, path);
        if (before == null || !before.contains("SCOUT_DISPOSABLE_FIXTURE")) throw new AssertionError("Disposable vault marker missing");
        String normalized = before.replace("Mechanism: after.", "Mechanism: before.");
        if (!normalized.equals(before)) { VaultWrite.writePinned(context, tree, path, before, normalized); before = normalized; }
        Session session = Session.create(new File(context.getCacheDir(), "scout-saf"));
        ScoutChanges.Notes notes = new ScoutChanges.Notes() {
            public String tree() { return VaultStore.tree(context); }
            public String read(String pinned, String target) throws Exception { return VaultWrite.readPinned(context, pinned, target); }
            public void write(String pinned, String target, String old, String next) throws Exception { VaultWrite.writePinned(context, pinned, target, old, next); }
        };
        try {
            ScoutChanges.CALL.set(new JSONObject().put("id", "manual_saf").put("name", "test"));
            String after = before.replace("Mechanism: before.", "Mechanism: after.");
            JSONObject proposal = ScoutChanges.prepareNote(session, tree, path, before, after, new org.json.JSONArray(), false);
            ScoutChanges.applyNote(session, proposal, true, notes, new Cancel());
            ScoutChanges.applyNote(session, proposal, true, notes, new Cancel());
            if (!after.equals(VaultWrite.readPinned(context, tree, path))) throw new AssertionError("SAF verified save mismatch");
            if (!ScoutChanges.event(session, "write_receipt", proposal.getString("proposalId"))) throw new AssertionError("Receipt missing");
            JSONObject stale = ScoutChanges.prepareNote(session, tree, path, after, after.replace("Mechanism: after.", "Mechanism: stale proposal."), new org.json.JSONArray(), false);
            String concurrent = after.replace("Mechanism: after.", "Mechanism: concurrent edit.");
            VaultWrite.writePinned(context, tree, path, after, concurrent);
            ScoutChanges.applyNote(session, stale, true, notes, new Cancel());
            if (!concurrent.equals(VaultWrite.readPinned(context, tree, path))) throw new AssertionError("Concurrent edit overwritten");
            JSONObject stopped = ScoutChanges.prepareNote(session, tree, path, concurrent, concurrent.replace("Mechanism: concurrent edit.", "Mechanism: must not save."), new org.json.JSONArray(), false);
            Cancel cancel = new Cancel(); cancel.stop(); ScoutChanges.applyNote(session, stopped, true, notes, cancel);
            if (ScoutChanges.event(session, "write_intent", stopped.getString("proposalId"))) throw new AssertionError("Stop did not fence write admission");
            VaultWrite.writePinned(context, tree, path, concurrent, before);
            result.putString("scout", "PASS: real SAF verified write, receipt/idempotence, protected owner sections, concurrent edit conflict, Stop admission; fixture restored");
        } finally { ScoutChanges.CALL.remove(); session.close(); new File(session.exportPath()).delete(); }
    }

    /** Runs the real runner/UI bridge with a fake provider; no external request is made. */
    @SuppressWarnings("unchecked")
    private static void running(Instrumentation instrumentation, android.content.Context context, Bundle result) throws Exception {
        JSONObject state = new JSONObject(new String(java.nio.file.Files.readAllBytes(stateFile(context).toPath()), java.nio.charset.StandardCharsets.UTF_8));
        if (!Model.key(context).isEmpty()) throw new AssertionError("Disable live model calls before fake-run test");
        String file = state.getString("fixture");
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(new android.content.Intent(context, MainActivity.class).putExtra(MainActivity.TAB, 3).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> activity.openSession(file));
        ScoutRuns.Run run = new ScoutRuns.Run(file); run.session = ScoutRuns.session(context, file); run.config = new JSONObject();
        java.lang.reflect.Field runsField = ScoutRuns.class.getDeclaredField("runs"); runsField.setAccessible(true);
        java.util.Map<String, ScoutRuns.Run> runs = (java.util.Map<String, ScoutRuns.Run>) runsField.get(null); runs.put(file, run);
        java.lang.reflect.Method events = ScoutRuns.class.getDeclaredMethod("events", android.content.Context.class, ScoutRuns.Run.class, String.class, JSONObject.class); events.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger published = new java.util.concurrent.atomic.AtomicInteger();
        ScoutRuns.Watcher watcher = new ScoutRuns.Watcher() {
            public void changed(String owner) { }
            public void live(String owner, String text) { if (owner.equals(file) && !text.isEmpty()) published.incrementAndGet(); }
        };
        ScoutRuns.watch(watcher);
        Agent agent = run.agent = new Agent((request, live, cancel) -> {
            long until = System.currentTimeMillis() + 60_000; int n = 0; boolean offline = false;
            while (!cancel.stopped() && System.currentTimeMillis() < until) {
                live.accept("Synthetic streaming answer " + (++n) + ". Stop must remain available while typing.");
                if (!offline && !ScoutState.pending(run.session).isEmpty()) {
                    offline = true;
                    instrumentation.runOnMainSync(() -> {
                        try {
                            java.lang.reflect.Field tabs = MainActivity.class.getDeclaredField("tabs"); tabs.setAccessible(true);
                            AskScreen screen = (AskScreen) ((Screen[]) tabs.get(activity))[3];
                            java.lang.reflect.Field view = AskScreen.class.getDeclaredField("view"); view.setAccessible(true);
                            ((AskState) view.get(screen)).header("Synthetic offline state", true);
                        } catch (Exception e) { throw new IllegalStateException(e); }
                    });
                }
                cancel.pause(100);
            }
            return new JSONObject().put("content", "Synthetic partial answer").put("finish_reason", cancel.stopped() ? "cancelled" : "stop");
        }, "fake-device-provider", "Synthetic device test", java.util.List.of(), run.cancel);
        agent.attach(run.session); agent.retries = 0;
        agent.subscribe((type, data) -> { try { events.invoke(null, context, run, type, data); } catch (Exception e) { throw new IllegalStateException(e); } });
        instrumentation.runOnMainSync(() -> ScoutService.sync(context));
        long began = System.currentTimeMillis();
        try {
            agent.prompt("Synthetic active run: queue a direction, type another draft, then Stop.");
            if (!run.cancel.stopped()) throw new AssertionError("Stop was not exercised before timeout");
            if (ScoutState.pending(run.session).isEmpty()) throw new AssertionError("Stop lost the queued direction");
            long duration = System.currentTimeMillis() - began;
            if (published.get() > duration / 100 + 3) throw new AssertionError("Live updates exceeded coalescing bound");
            result.putString("scout", "PASS: fake streaming, durable direction, offline Stop with draft, queued direction preserved, bounded UI publication");
            result.putInt("livePublications", published.get());
        } finally { run.cancel.stop(); run.done = true; ScoutRuns.unwatch(watcher); ScoutService.sync(context); }
    }

}
