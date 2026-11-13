package me.venkatesh.saveobsidian;

import org.json.JSONObject;

/**
 * Port of pi packages/agent/src/types.ts AgentTool @ 3e23dd9. Failures: throw, or return Result.error. A tool must keep its own output
 * small (see Truncate). details is for the UI and the log; the model only sees text. Pure Java.
 */
public abstract class Tool {
    public final String name, description;
    /** JSON Schema of the arguments (type object). */
    public final JSONObject parameters;
    /** Forces the whole batch of calls in one turn to run one after another (tools that touch the same file). */
    public final boolean sequential;

    protected Tool(String name, String description, JSONObject parameters, boolean sequential) {
        this.name = name; this.description = description; this.parameters = parameters; this.sequential = sequential;
    }

    public static final class Result {
        public final String text;
        public final JSONObject details;
        public final boolean error, terminate;
        public Result(String text, JSONObject details, boolean error, boolean terminate) { this.text = text; this.details = details; this.error = error; this.terminate = terminate; }
        public static Result ok(String text) { return new Result(text, null, false, false); }
        public static Result error(String text) { return new Result(text, null, true, false); }
        public Result withText(String t) { return new Result(t, details, error, terminate); }
    }

    /** Arguments as the model sent them, before validation (shims for older argument shapes). */
    public JSONObject prepareArguments(JSONObject args) { return args; }

    public abstract Result execute(JSONObject args, Cancel cancel) throws Exception;

    /** The OpenAI-format entry for the request's tools array. */
    public JSONObject spec() throws Exception {
        return new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name).put("description", description).put("parameters", parameters));
    }
}
