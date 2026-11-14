package me.venkatesh.saveobsidian

import android.content.Context
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class SessionUsageState {
    var json by mutableStateOf("{}"); private set
    var refreshing by mutableStateOf(false); private set
    fun publish(value: String) { json = value }
    fun refreshing(value: Boolean) { refreshing = value }
}

object SessionUsageView {
    @JvmStatic fun create(c: Context, ui: Ui, state: SessionUsageState, back: Runnable, refresh: Runnable): View = ComposeView(c).apply {
        setContent { SparkTheme(ui) { UsagePage(state, back, refresh) } }
    }
}

private fun JSONArray.rows(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun number(n: Long) = String.format(Locale.US, "%,d", n)
private fun field(o: JSONObject?, key: String): String = if (o == null || o.isNull(key)) "Unavailable" else number(o.optLong(key))
private fun charge(o: JSONObject?): String = if (o == null || o.isNull("cost")) "Cost unavailable" else SessionUsage.dollars(o.optString("cost"))
private fun purpose(r: JSONObject) = when (r.optString("purpose")) { "naming" -> "Chat naming"; "compaction" -> "Compaction"; else -> "Conversation" }

@Composable
private fun UsagePage(s: SessionUsageState, back: Runnable, refresh: Runnable) {
    val data = remember(s.json) { JSONObject(s.json) }
    val totals = data.optJSONObject("totals") ?: JSONObject()
    val context = data.optJSONObject("context") ?: JSONObject()
    val requests = data.optJSONArray("requests")?.rows().orEmpty()
    val history = data.optJSONArray("history")?.rows().orEmpty()
    val cs = MaterialTheme.colorScheme
    var selectedRequest by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(selectedRequest) {
        val index = requests.indexOfFirst { it.optString("requestId") == selectedRequest }
        if (index >= 0) list.animateScrollToItem(6 + index)
    }
    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back.run() }) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
                Text("Session usage", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider(color = cs.outlineVariant)
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text(data.optString("title"), style = MaterialTheme.typography.titleMedium)
                    if (data.has("error")) Text(data.optString("error"), color = cs.error)
                }
                item { ContextCard(context) }
                item { ContextHistory(history, requests) { selectedRequest = it } }
                item {
                    UsageCard("OpenRouter cost") {
                        Text(SessionUsage.money(totals), style = MaterialTheme.typography.headlineMedium)
                        Text(if (totals.optBoolean("historical")) "Partial historical usage. Older requests and charges may be missing."
                            else if (totals.optBoolean("partial")) "Some requests are pending or have missing charges. The total includes reported charges only."
                            else "Includes conversation, compaction and chat naming.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Text("Independently billed tools and credit purchase fees are excluded. Amounts are in USD.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        if (totals.optInt("missingCost") > 0) TextButton(onClick = { refresh.run() }, enabled = !s.refreshing) {
                            Text(if (s.refreshing) "Checking missing usage…" else "Refresh missing usage")
                        }
                    }
                }
                item { Totals(totals, requests) }
                item {
                    Text("Requests", style = MaterialTheme.typography.titleMedium)
                    Text("Input is processed again on each request. Cumulative tokens can exceed the context window.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    if (requests.isEmpty()) Text("No detailed request records yet. History is collected from this version onward.", modifier = Modifier.padding(top = 8.dp))
                }
                itemsIndexed(requests, key = { _, r -> r.optString("requestId") }) { index, request ->
                    RequestCard(index, request, selectedRequest == request.optString("requestId"), data.optString("activeRun"))
                }
            }
        }
    }
}

@Composable
private fun UsageCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ContextCard(c: JSONObject) {
    val used = c.optLong("estimate")
    val window = c.optLong("window")
    val reserve = c.optLong("reserve")
    UsageCard("Current context") {
        if (!c.has("estimate")) Text("Unavailable until Scout makes a request.")
        else {
            Text("≈${number(used)}" + if (window > 0) " / ${number(window)} tokens" else " tokens", style = MaterialTheme.typography.headlineSmall)
            if (window > 0) {
                LinearProgressIndicator(progress = { (used.toFloat() / window).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Metric("Window used", "${kotlin.math.round(used * 100.0 / window).toInt()}%")
                Metric("Output allowance", number(reserve))
                Metric("Remaining input room", "≈${number((window - used - reserve).coerceAtLeast(0))}")
            } else Text("Model capacity unavailable; no percentage is assumed.")
            Metric("Model", c.optString("model"))
            Text("Last saved estimate of next-request input, including instructions and tools. Refreshed as Scout works. Output allowance is the request’s maximum response size. Advertised model capacity may vary by routed provider.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Totals(t: JSONObject, requests: List<JSONObject>) {
    var open by rememberSaveable { mutableStateOf(false) }
    UsageCard("Session totals") {
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide totals ⌃" else "Expand totals ⌄") }
        if (open) {
            Metric("Requests recorded", t.optString("calls", "0"))
            Metric("Input tokens", field(t, "prompt_tokens"))
            Metric("Output tokens", field(t, "completion_tokens"))
            Metric("Total tokens processed", number(t.optLong("prompt_tokens") + t.optLong("completion_tokens")))
            Text("Token totals cover ${t.optInt("knownTokens")} requests with both input and output reported.", style = MaterialTheme.typography.bodySmall)
            Metric("Cached reads · of input", if (t.optInt("cacheKnown") > 0) number(t.optLong("cached")) else "Unavailable")
            Metric("Cache writes · of input", if (t.optInt("writeKnown") > 0) number(t.optLong("cacheWrites")) else "Unavailable")
            Metric("Reasoning · of output", if (t.optInt("reasoningKnown") > 0) number(t.optLong("reasoning")) else "Unavailable")
            Text("Optional breakdowns include reporting requests only; unavailable fields are not zero.", style = MaterialTheme.typography.bodySmall)
            Metric("Recorded model-call time", "${t.optLong("durationMs") / 1000}s")
            Metric("Retries", requests.count { it.optInt("attempt") > 0 }.toString())
            Metric("Continuations", requests.count { it.optBoolean("continuation") }.toString())
            Metric("Compaction requests", requests.count { it.optString("purpose") == "compaction" }.toString())
            Text("Cost by purpose", style = MaterialTheme.typography.titleSmall)
            requests.groupBy { purpose(it) }.forEach { (label, rows) -> GroupTotal(label, rows) }
            Text("Cost by model", style = MaterialTheme.typography.titleSmall)
            requests.groupBy { it.optString("actualModel").ifEmpty { it.optString("model") } }.forEach { (label, rows) -> GroupTotal(label, rows) }
        }
    }
}

@Composable
private fun GroupTotal(label: String, rows: List<JSONObject>) {
    val total = SessionUsage.sum(rows).apply { put("partial", optInt("missingCost") > 0) }
    Metric(label, SessionUsage.money(total))
}

@Composable
private fun RequestCard(index: Int, r: JSONObject, selected: Boolean, activeRun: String) {
    var expanded by rememberSaveable(r.optString("requestId")) { mutableStateOf(false) }
    LaunchedEffect(selected) { if (selected) expanded = true }
    val u = r.optJSONObject("usage")
    UsageCard("Request ${index + 1} · ${purpose(r)}") {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            val status = if (r.optString("status") == "pending" && r.optString("run") != activeRun) "Interrupted · usage pending" else r.optString("status")
            Text("$status · ${charge(u)} ${if (expanded) "⌃" else "⌄"}")
        }
        if (expanded) {
            Metric("Started", r.optString("timestamp"))
            Metric("Requested model", r.optString("model"))
            Metric("Returned model", r.optString("actualModel").ifEmpty { "Unavailable" })
            Metric("Provider", r.optString("provider").ifEmpty { "Unavailable" })
            Metric("Input tokens", field(u, "prompt_tokens"))
            Metric("Output tokens", field(u, "completion_tokens"))
            Metric("Cached reads · of input", field(u?.optJSONObject("prompt_tokens_details"), "cached_tokens"))
            Metric("Cache writes · of input", field(u?.optJSONObject("prompt_tokens_details"), "cache_write_tokens"))
            Metric("Reasoning · of output", field(u?.optJSONObject("completion_tokens_details"), "reasoning_tokens"))
            Metric("Context capacity", if (r.optInt("window") > 0) number(r.optLong("window")) else "Unavailable")
            Metric("Output allowance", number(r.optLong("reserve")))
            Metric("Duration", if (r.has("durationMs")) "${r.optLong("durationMs")} ms" else "Unavailable")
            Metric("Finish reason", r.optString("finish_reason").ifEmpty { "Unavailable" })
            val costs = u?.optJSONObject("cost_details")
            for ((key, label) in listOf("upstream_inference_prompt_cost" to "Provider input cost", "upstream_inference_completions_cost" to "Provider output cost")) {
                Metric(label, if (costs == null || costs.isNull(key)) "Unavailable" else SessionUsage.dollars(costs.optString(key)))
            }
            Text("Provider cost details are explanatory; they are not added again to the account charge.", style = MaterialTheme.typography.bodySmall)
            Metric("Usage source", if (r.optBoolean("recovered")) "Recovered from OpenRouter" else if (u != null) "Response" else "Not reported")
            Metric("Retry attempt", r.optInt("attempt").toString())
            Metric("Continuation", if (r.optBoolean("continuation")) "Yes" else "No")
            if (r.optString("relatedRequestId").isNotEmpty()) Metric("Related request", r.optString("relatedRequestId").take(8))
            Metric("Run", r.optString("run").take(8))
            if (r.optString("generationId").isNotEmpty()) Metric("Generation", r.optString("generationId"))
        }
    }
}

@Composable
private fun ContextHistory(history: List<JSONObject>, requests: List<JSONObject>, select: (String) -> Unit) {
    val snapshots = history.filter { it.optString("type") == "context_snapshot" }
    // Show request inputs and meaningful changes, rather than every intermediate redraw.
    val hasReported = snapshots.any { it.optBoolean("reported") }
    val points = snapshots.filterIndexed { index, p ->
        !hasReported || p.optBoolean("reported") || p.optString("reason") == "compaction_end" || index == snapshots.lastIndex ||
            index > 0 && p.optString("model") != snapshots[index - 1].optString("model")
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val cs = MaterialTheme.colorScheme
    UsageCard("Context history") {
        if (points.isEmpty()) Text("History appears as Scout works.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        else {
            val current = snapshots.last().optLong("estimate")
            val peak = snapshots.maxOf { it.optLong("estimate") }
            Text("Current ≈${SessionUsage.tokens(current)} · Peak ${SessionUsage.tokens(peak)} tokens", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "Hide history ⌃" else "Show history ⌄")
            }
            if (expanded) {
                // Capacity already has its own card. Scaling to observed usage makes small changes visible.
                val ceiling = (kotlin.math.ceil(peak.coerceAtLeast(1) * 1.15 / 1000.0) * 1000).toLong().coerceAtLeast(1000)
                val at = selected.takeIf { it in points.indices } ?: points.lastIndex
                Text("Input tokens · scale 0–${SessionUsage.tokens(ceiling)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Canvas(Modifier.fillMaxWidth().height(88.dp).padding(6.dp)
                    .semantics { contentDescription = "Context history, scaled from zero to $ceiling tokens. Use Previous and Next to inspect requests." }
                    .pointerInput(points.size) { detectTapGestures { offset ->
                        selected = kotlin.math.round((offset.x / size.width.coerceAtLeast(1)) * (points.size - 1)).toInt().coerceIn(points.indices)
                    } }) {
                    fun x(i: Int) = if (points.size == 1) size.width / 2 else size.width * i / (points.size - 1)
                    fun y(v: Long) = size.height - size.height * v / ceiling.toFloat()
                    drawLine(cs.outlineVariant, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    val path = Path()
                    points.forEachIndexed { i, p ->
                        val xx = x(i); val yy = y(p.optLong("estimate"))
                        if (i == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy)
                    }
                    drawPath(path, cs.primary, style = Stroke(2.dp.toPx()))
                    points.forEachIndexed { i, p ->
                        val event = p.optString("reason") == "compaction_end" || i > 0 && p.optString("model") != points[i - 1].optString("model")
                        drawCircle(if (event) cs.tertiary else cs.primary, if (i == at || event) 4.dp.toPx() else 3.dp.toPx(),
                            Offset(x(i), y(p.optLong("estimate"))), style = if (p.optBoolean("reported")) Fill else Stroke(1.5.dp.toPx()))
                    }
                }
                Text("Filled: reported · Outlined: estimated", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                val p = points[at]
                val requestIndex = requests.indexOfFirst { it.optString("requestId") == p.optString("requestId") }
                val label = when {
                    p.optString("reason") == "compaction_end" -> "After compaction"
                    p.optBoolean("reported") && requestIndex >= 0 -> "Request ${requestIndex + 1}"
                    at == points.lastIndex -> "Current estimate"
                    else -> "Earlier estimate"
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selected = at - 1 }, enabled = at > 0) { Text("Previous") }
                    Text("$label\n${if (p.optBoolean("reported")) "" else "≈"}${number(p.optLong("estimate"))} tokens",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(onClick = { selected = at + 1 }, enabled = at < points.lastIndex) { Text("Next") }
                }
                if (requestIndex >= 0) TextButton(onClick = { select(requests[requestIndex].optString("requestId")) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Request ${requestIndex + 1} details")
                }
                val compactions = history.count { it.optString("type") == "compaction" }
                if (compactions > 0) Text("$compactions compactions · accent markers show compaction or model changes", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}
