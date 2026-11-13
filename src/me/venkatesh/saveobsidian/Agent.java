package me.venkatesh.saveobsidian;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Port of pi packages/agent/src/agent-loop.ts + agent.ts @ 3e23dd9 (queues, abort-while-running and compaction come in later milestones).
 * One turn is one model request plus its tool calls; the loop repeats while the model asks for tools. Messages are OpenAI-format JSON
 * plus bookkeeping keys (stopReason, usage, errorMessage, details) that convertToLlm strips before every request. Pure Java.
 *
 * Contract (as in Pi): prompt never throws for a provider or tool failure. A failed or stopped run still ends with an assistant message
 * whose stopReason is error or aborted, so the transcript stays well-formed. Stop reasons: stop, toolUse, length, error, aborted, max-steps.
 */
public final class Agent {
    /** One streamed chat completion. Returns {content, tool_calls?, finish_reason?, usage?}. Model.provider(key) is the OpenRouter one. */
    public interface Provider { JSONObject stream(JSONObject request, Consumer<String> live, Cancel cancel) throws Exception; }

    /** Events: agent_start, turn_start, message_start, message_update{text}, message_end, tool_execution_start, tool_execution_end, retry, turn_end, agent_end. */
    public interface Listener { void on(String type, JSONObject data); }

    /** Extension points of Pi's loop as plain Java (no dynamic loading on Android). */
    public interface Hooks {
        /** Before every request: filter or rewrite the transcript messages (custom ones included). Must not throw. */
        default List<JSONObject> transformContext(List<JSONObject> messages) { return messages; }
        /** Last look at the request (model, messages, tools) before it is sent. */
        default void prepareRequest(JSONObject request) { }
        /** Start of every turn, after the user's message and any tool results are in; compaction runs here. */
        default void prepareNextTurn(Agent agent) { }
        /** The provider refused the request as too long. True after the context was shrunk: the request is built again and sent once more. */
        default boolean onOverflow(Agent agent) { return false; }
        /** Non-null blocks the call; the text goes to the model as the tool's error. */
        default String beforeToolCall(String name, JSONObject args) { return null; }
        /** May patch a result. */
        default Tool.Result afterToolCall(String name, JSONObject args, Tool.Result result) { return result; }
        /** After a turn's tool results; false ends the run. */
        default boolean finishTurn(List<JSONObject> toolResults) { return true; }
    }

    private static final int PARALLEL = 6;
    private static final String TRUNCATED = "The response hit the token limit, so this call's arguments may be cut off and it was not run. Call it again with complete arguments.";

    private final Provider provider;
    private final String model;
    private final String system;
    private final List<Tool> tools;
    private final List<JSONObject> messages = new ArrayList<>();
    private final Cancel cancel;
    public Hooks hooks = new Hooks() { };
    /** When set, every message is appended to it as it happens (attach(session) loads what is already there). */
    private Session session;
    public Listener listener = (type, data) -> { };
    public int maxTokens = 1800, maxTurns = 8;
    /** Pi's agent-level retry: 3 retries, doubling from 2 s, capped at 60 s. Tests set base to 0. */
    public int retries = 3;
    public long retryBaseMs = 2000, retryMaxMs = 60_000;

    public Agent(Provider provider, String model, String system, List<Tool> tools, Cancel cancel) {
        this.provider = provider; this.model = model; this.system = system; this.tools = tools; this.cancel = cancel;
    }

    public List<JSONObject> messages() { return messages; }
    public Cancel cancel() { return cancel; }

    /** Continues a stored conversation: the agent's messages become the session's context, and new ones are appended to it. */
    public void attach(Session s) { session = s; setMessages(s.context()); }

    /** Replaces the transcript the next request is built from (after a compaction). */
    public void setMessages(List<JSONObject> m) { messages.clear(); messages.addAll(m); }

    private void add(JSONObject m) {
        messages.add(m);
        if (session != null) try { session.appendMessage(m); } catch (java.io.IOException e) { emit("session_error", wrap("error", String.valueOf(e.getMessage()))); } // the run goes on; only resume is lost
    }

    /** Adds the user's message, runs turns until the model stops asking for tools, returns the last assistant message. */
    public JSONObject prompt(String text) {
        try { add(new JSONObject().put("role", "user").put("content", text)); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        emit("agent_start", new JSONObject());
        JSONObject last = null;
        boolean done = false;
        for (int turn = 0; turn < maxTurns && !done; turn++) {
            if (cancel.stopped()) { last = failed("aborted", "Stopped"); add(last); break; }
            hooks.prepareNextTurn(this);
            emit("turn_start", new JSONObject());
            last = streamAssistant();
            add(last);
            emit("message_end", wrap("message", last));
            String stop = last.optString("stopReason");
            JSONArray calls = last.optJSONArray("tool_calls");
            if (stop.equals("error") || stop.equals("aborted") || calls == null || calls.length() == 0) { emit("turn_end", new JSONObject()); break; }
            List<JSONObject> results = execute(calls, stop.equals("length"));
            for (JSONObject r : results) add(r);
            emit("turn_end", new JSONObject());
            boolean allTerminate = true;
            for (JSONObject r : results) allTerminate &= r.optBoolean("terminate");
            if (allTerminate || !hooks.finishTurn(results)) done = true;
            else if (turn == maxTurns - 1) { last = failed("max-steps", "Stopped after " + maxTurns + " steps"); last.remove("errorMessage"); add(last); }
        }
        emit("agent_end", new JSONObject());
        return last;
    }

    // ---- context ----

    /** What the model gets: transcript messages after transformContext, minus UI-only (custom) messages, failed assistant messages and bookkeeping keys. */
    JSONArray convertToLlm() {
        List<JSONObject> view = hooks.transformContext(new ArrayList<>(messages));
        JSONArray out = new JSONArray();
        try {
            out.put(new JSONObject().put("role", "system").put("content", system));
            for (JSONObject m : view) {
                String role = m.optString("role");
                if (role.equals("custom")) continue;
                String stop = m.optString("stopReason");
                if (role.equals("assistant") && (stop.equals("error") || stop.equals("aborted") || stop.equals("max-steps"))) continue;
                JSONObject c = new JSONObject();
                for (String k : new String[] {"role", "content", "tool_calls", "tool_call_id"}) if (m.has(k)) c.put(k, m.get(k));
                out.put(c);
            }
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return out;
    }

    // ---- model call (never throws) ----

    private JSONObject streamAssistant() {
        JSONObject request = new JSONObject();
        try {
            request.put("model", model).put("max_tokens", maxTokens).put("messages", convertToLlm());
            if (!tools.isEmpty()) { JSONArray specs = new JSONArray(); for (Tool t : tools) specs.put(t.spec()); request.put("tools", specs); }
            hooks.prepareRequest(request);
        } catch (Exception e) { return failed("error", String.valueOf(e.getMessage())); }
        emit("message_start", new JSONObject());
        boolean compacted = false;
        for (int attempt = 0; ; attempt++) {
            try {
                JSONObject reply = provider.stream(request, text -> emit("message_update", text(text)), cancel);
                return assistant(reply);
            } catch (Exception e) {
                if (cancel.stopped()) return failed("aborted", "Stopped");
                String message = String.valueOf(e.getMessage()), kind = Failures.kind(message);
                if (!compacted && Compact.overflow(message) && hooks.onOverflow(this)) { // too long: shrink once, rebuild, send again
                    compacted = true;
                    try { request.put("messages", convertToLlm()); } catch (Exception bad) { return failed("error", message); }
                    continue;
                }
                boolean retry = attempt < retries && (kind.equals(Failures.OFFLINE) || kind.equals(Failures.RATE) || kind.equals(Failures.OTHER) && !message.matches("(?s).* 4\\d\\d.*")); // a 4xx other than rate limit will not fix itself
                if (!retry) return failed("error", message.contains(" 400") && !tools.isEmpty() ? message + " (this model may not support tool calls)" : message);
                long wait = Math.min(retryMaxMs, retryBaseMs << attempt);
                try { emit("retry", new JSONObject().put("attempt", attempt + 1).put("waitMs", wait).put("error", message)); } catch (Exception ignore) { }
                try { Thread.sleep(wait); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return failed("aborted", "Stopped"); }
            }
        }
    }

    private JSONObject assistant(JSONObject reply) {
        try {
            JSONObject m = new JSONObject().put("role", "assistant").put("content", reply.optString("content", ""));
            JSONArray calls = reply.optJSONArray("tool_calls");
            if (calls != null && calls.length() > 0) {
                for (int i = 0; i < calls.length(); i++) { JSONObject c = calls.getJSONObject(i); if (c.optString("id").isEmpty()) c.put("id", "call_" + messages.size() + "_" + i); }
                m.put("tool_calls", calls);
            }
            String finish = reply.optString("finish_reason", "");
            m.put("stopReason", cancel.stopped() ? "aborted" : finish.equals("length") ? "length" : calls != null && calls.length() > 0 ? "toolUse" : "stop");
            if (reply.has("usage")) m.put("usage", reply.get("usage"));
            return m;
        } catch (Exception e) { return failed("error", String.valueOf(e.getMessage())); }
    }

    private static JSONObject failed(String stopReason, String message) {
        try { return new JSONObject().put("role", "assistant").put("content", "").put("stopReason", stopReason).put("errorMessage", message); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    // ---- tools ----

    private static final class Call { String id, name; Tool tool; JSONObject args; String error; Tool.Result result; }

    private List<JSONObject> execute(JSONArray calls, boolean truncated) {
        List<Call> batch = new ArrayList<>();
        boolean sequential = false;
        for (int i = 0; i < calls.length(); i++) {
            Call c = new Call();
            JSONObject raw = calls.optJSONObject(i), fn = raw == null ? null : raw.optJSONObject("function");
            c.id = raw == null ? "" : raw.optString("id");
            c.name = fn == null ? "" : fn.optString("name");
            for (Tool t : tools) if (t.name.equals(c.name)) c.tool = t;
            if (truncated) c.error = TRUNCATED;
            else if (c.tool == null) c.error = "Unknown tool " + c.name + ". Available: " + names() + ".";
            else prepare(c, fn.optString("arguments", ""));
            sequential |= c.tool != null && c.tool.sequential;
            batch.add(c);
        }
        if (sequential || batch.size() == 1) for (Call c : batch) run(c);
        else {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(batch.size(), PARALLEL));
            List<Future<?>> running = new ArrayList<>();
            for (Call c : batch) running.add(pool.submit(() -> run(c)));
            pool.shutdown();
            for (Future<?> f : running) { try { f.get(); } catch (Exception ignore) { } } // run() never throws; results are read from the calls, in call order
        }
        List<JSONObject> out = new ArrayList<>();
        try {
            for (Call c : batch) {
                out.add(new JSONObject().put("role", "tool").put("tool_call_id", c.id).put("name", c.name).put("content", c.result.text)
                    .put("isError", c.result.error).put("terminate", c.result.terminate).put("details", c.result.details == null ? JSONObject.NULL : c.result.details));
            }
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return out;
    }

    private String names() { StringBuilder s = new StringBuilder(); for (Tool t : tools) s.append(s.length() > 0 ? ", " : "").append(t.name); return s.toString(); }

    /** Arguments to an object, the tool's shim, then the schema (coercing); a hook may still block the call. */
    private void prepare(Call c, String rawArgs) {
        try {
            JSONObject args = rawArgs.trim().isEmpty() ? new JSONObject() : JsonFix.object(rawArgs);
            if (args == null) { c.error = "The arguments are not valid JSON."; return; }
            c.args = c.tool.prepareArguments(args);
            String problem = Validate.check(c.tool.parameters, c.args);
            if (problem != null) { c.error = "Invalid arguments: " + problem; return; }
            String blocked = hooks.beforeToolCall(c.name, c.args);
            if (blocked != null) c.error = blocked;
        } catch (Exception e) { c.error = "Invalid arguments: " + e.getMessage(); }
    }

    /** Never throws: an error, an exception or a stop becomes an error result the model can read. */
    private void run(Call c) {
        try {
            emit("tool_execution_start", new JSONObject().put("id", c.id).put("name", c.name));
            if (c.error != null) c.result = Tool.Result.error(c.error);
            else if (cancel.stopped()) c.result = Tool.Result.error("Operation aborted");
            else {
                try { c.result = c.tool.execute(c.args, cancel); }
                catch (Exception e) { c.result = Tool.Result.error(String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); }
                if (c.result == null) c.result = Tool.Result.error("The tool returned nothing.");
                else c.result = hooks.afterToolCall(c.name, c.args, c.result);
            }
            emit("tool_execution_end", new JSONObject().put("id", c.id).put("name", c.name).put("isError", c.result.error));
        } catch (Exception e) { if (c.result == null) c.result = Tool.Result.error(String.valueOf(e.getMessage())); }
    }

    // ---- events ----

    private void emit(String type, JSONObject data) { try { listener.on(type, data); } catch (RuntimeException ignore) { } } // a listener must not break the run

    private static JSONObject text(String t) { return wrap("text", t); }

    private static JSONObject wrap(String key, Object v) { try { return new JSONObject().put(key, v); } catch (Exception impossible) { throw new IllegalStateException(impossible); } }
}
