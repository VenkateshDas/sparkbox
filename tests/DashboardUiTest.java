import java.util.List;
import me.venkatesh.saveobsidian.Dashboard;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the operation reducer used by the native UI without a device or provider. */
public final class DashboardUiTest {
    private static Dashboard.Cohort cohort(JSONObject data, String workflow, String model) { return Dashboard.cohort(data, 100L, workflow, model); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void run() throws Exception {
        JSONObject capture = new JSONObject().put("operationId", "capture").put("workflow", "Capture processing").put("status", "completed").put("startedAt", 101);
        JSONObject scout = new JSONObject().put("operationId", "scout").put("workflow", "Scout interaction").put("status", "completed").put("startedAt", 102);
        JSONObject empty = new JSONObject().put("operationId", "empty").put("workflow", "Card creation").put("status", "completed").put("startedAt", 103);
        JSONObject request = new JSONObject().put("requestId", "r1").put("operationId", "capture").put("model", "fast").put("usage", new JSONObject().put("cost", "0.1").put("prompt_tokens", 100).put("completion_tokens", 20));
        JSONObject next = new JSONObject(request.toString()).put("requestId", "r2").put("operationId", "scout");
        JSONObject data = new JSONObject().put("operations", new JSONArray(List.of(capture, scout, empty))).put("requests", new JSONArray(List.of(request, next)));
        Dashboard.Cohort all = cohort(data, "All", "All");
        check(all.priced.size() == 2, "Missing operation history is not a known free operation");
        check(all.tokenKnown.size() == 2, "Complete token cohort excludes missing history");
        check(cohort(data, "Scout interaction", "All").operations.size() == 1, "Scout operation scope excludes capture/card work");
        JSONObject hidden = new JSONObject(request.toString()).put("requestId", "r3").put("model", "reasoning");
        data.getJSONArray("requests").put(hidden);
        check(cohort(data, "Capture processing", "fast").priced.isEmpty(), "Partial model-filtered operations excluded");
        data.getJSONArray("requests").getJSONObject(0).getJSONObject("usage").remove("cost");
        check(cohort(data, "Capture processing", "All").priced.isEmpty(), "Missing charge is not zero");
        check(cohort(data, "Capture processing", "All").tokenKnown.size() == 1, "Token and charge coverage are independent");
        System.out.println("Dashboard UI cohort checks passed (6).");
    }
}
