package me.venkatesh.saveobsidian

import android.content.Context
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Native dashboard state. Loading never discards the last useful snapshot. */
class DashboardState {
    var json by mutableStateOf("{}"); private set
    var loading by mutableStateOf(false); private set
    fun publish(value: String) { json = value }
    fun loading(value: Boolean) { loading = value }
}

interface DashboardActions {
    fun back()
    fun refresh()
    fun export(json: String)
    fun openNote(path: String)
    fun openSession(file: String)
    fun settings()
    fun tracking(enabled: Boolean)
    fun clear()
}

object DashboardView {
    @JvmStatic fun create(c: Context, ui: Ui, state: DashboardState, actions: DashboardActions): View = ComposeView(c).apply {
        setContent { SparkTheme(ui) { DashboardPage(state, actions) } }
    }
}

private val dashboardSections = listOf("Overview", "Vault", "Habits", "Recall", "Scout", "Costs", "Health")
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.array(key: String) = optJSONArray(key)?.objects().orEmpty()
private fun JSONObject.objectOrEmpty(key: String) = optJSONObject(key) ?: JSONObject()
private fun JSONObject.labels(): List<Pair<String, Long>> = keys().asSequence().map { it to optLong(it) }.toList()
private fun dn(n: Long) = String.format(Locale.getDefault(), "%,d", n)
private fun requestTime(r: JSONObject): Long = r.optLong("at").takeIf { it > 0 }
    ?: runCatching { Instant.parse(r.optString("timestamp")).toEpochMilli() }.getOrDefault(0)
private fun dayOf(time: Long): String = if (time > 0) Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().toString() else "Undated"
private fun periodStart(days: Int): Long = if (days == 0) 0 else LocalDate.now().minusDays(days.toLong() - 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
private fun requestModel(r: JSONObject) = r.optString("actualModel").ifBlank { r.optString("model", "Unknown model") }
private fun requestStage(r: JSONObject) = r.optString("stage").ifBlank { r.optString("purpose", "Unattributed") }
private fun requestWorkflow(r: JSONObject) = r.optString("workflow").ifBlank { "Unattributed historical usage" }
private fun pretty(raw: String) = raw.replace('_', ' ').replaceFirstChar { it.titlecase() }
private fun money(amount: BigDecimal?) = amount?.let { SessionUsage.dollars(it.toPlainString()) } ?: "Unavailable"
private fun decimal(o: JSONObject?, key: String): BigDecimal? = if (o == null || o.isNull(key)) null else runCatching { BigDecimal(o.get(key).toString()) }.getOrNull()
private fun sumCost(rows: List<JSONObject>): BigDecimal? {
    val values = rows.mapNotNull { decimal(it.optJSONObject("usage"), "cost") }
    return if (values.isEmpty()) null else values.fold(BigDecimal.ZERO, BigDecimal::add)
}
private fun tokenSum(rows: List<JSONObject>, field: String): Long? {
    val values = rows.mapNotNull { it.optJSONObject("usage")?.takeUnless { u -> u.isNull(field) }?.optLong(field) }
    return if (values.isEmpty()) null else values.sum()
}
private fun tokenText(rows: List<JSONObject>, field: String) = tokenSum(rows, field)?.let(::dn) ?: "Unavailable"
private fun knownCharge(rows: List<JSONObject>) = rows.count { decimal(it.optJSONObject("usage"), "cost") != null }
private fun knownTokens(rows: List<JSONObject>) = rows.count { val u = it.optJSONObject("usage"); u != null && !u.isNull("prompt_tokens") && !u.isNull("completion_tokens") }
private fun opId(op: JSONObject) = op.optString("operationId").ifBlank { op.optString("id") }
private fun completed(op: JSONObject) = op.optString("status").lowercase(Locale.ROOT) in listOf("completed", "complete", "saved")

private data class OperationCohort(val operations: List<JSONObject>, val priced: List<Pair<JSONObject, List<JSONObject>>>, val tokenKnown: List<Pair<JSONObject, List<JSONObject>>>, val excluded: Int) {
    val meanCost: BigDecimal? get() = if (priced.isEmpty()) null else priced.fold(BigDecimal.ZERO) { a, (_, r) -> a + (sumCost(r) ?: BigDecimal.ZERO) }.divide(BigDecimal(priced.size), 12, RoundingMode.HALF_UP)
    val meanTokens: Long? get() = if (tokenKnown.isEmpty()) null else tokenKnown.sumOf { (_, r) -> (tokenSum(r, "prompt_tokens") ?: 0L) + (tokenSum(r, "completion_tokens") ?: 0L) } / tokenKnown.size
}

/** Operation-start cohort; fetch all its requests, including requests which crossed midnight. */
private fun operationCohort(data: JSONObject, start: Long, workflow: String, model: String): OperationCohort {
    val reduced = Dashboard.cohort(data, start, workflow, model)
    return OperationCohort(reduced.operations, reduced.priced.map { it to reduced.requests[opId(it)].orEmpty() },
        reduced.tokenKnown.map { it to reduced.requests[opId(it)].orEmpty() }, reduced.excluded)
}

@Composable
private fun DashboardPage(state: DashboardState, actions: DashboardActions) {
    val data = remember(state.json) { runCatching { JSONObject(state.json) }.getOrDefault(JSONObject()) }
    var section by rememberSaveable { mutableStateOf("Overview") }
    var days by rememberSaveable { mutableStateOf(30) }
    var query by rememberSaveable { mutableStateOf("") }
    var noteKind by rememberSaveable { mutableStateOf("All") }
    var model by rememberSaveable { mutableStateOf("All") }
    var workflow by rememberSaveable { mutableStateOf("All") }
    var detailType by rememberSaveable { mutableStateOf("") }
    var detailId by rememberSaveable { mutableStateOf("") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var visibleRequests by rememberSaveable { mutableStateOf(30) }
    val start = periodStart(days)
    val allRequests = remember(state.json) { data.array("requests").distinctBy { it.optString("requestId") } }
    val requests = remember(allRequests, days, model, workflow, section) { allRequests.filter { r ->
        requestTime(r) >= start && (model == "All" || requestModel(r) == model) &&
            (section != "Scout" || requestWorkflow(r).lowercase(Locale.ROOT).contains("scout") || r.optString("sessionId").isNotBlank()) &&
            (workflow == "All" || requestWorkflow(r) == workflow)
    } }
    val cohort = remember(state.json, days, workflow, model, section) { operationCohort(data, start, if (section == "Scout") "Scout interaction" else workflow, model) }
    val noteDetail: (String) -> Unit = { detailType = "note"; detailId = it }
    val requestDetail: (String) -> Unit = { detailType = "request"; detailId = it }
    val operationDetail: (String) -> Unit = { detailType = "operation"; detailId = it }
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column {
            TopBar("Dashboard", Runnable { actions.back() }) {
                TextButton(onClick = actions::refresh, enabled = !state.loading) { Text(if (state.loading) "Loading…" else "Refresh") }
                TextButton(onClick = { showSettings = true }) { Text("Options") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dashboardSections.forEach { name -> DashboardPill(name, section == name) { section = name; model = "All"; workflow = "All"; visibleRequests = 30 } }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(when (section) { "Overview" -> "Library overview"; "Costs" -> "AI costs"; "Scout" -> "Scout activity"; else -> section }, style = MaterialTheme.typography.headlineSmall)
                    Text("Local snapshot · ${if (data.optLong("asOf") > 0) DateTimeFormatter.ofPattern("dd MMM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(data.optLong("asOf"))) else "Waiting for data"}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    if (data.has("error")) Text(data.optString("error"), color = cs.error)
                }
                if (data.optLong("asOf") == 0L) return@LazyColumn
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", 0 to "All retained").forEach { (n, label) -> DashboardPill(label, days == n) { days = n; visibleRequests = 30 } }
                    }
                    Text("Event period · ${ZoneId.systemDefault().id}. Current inventory ignores this filter.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                if (!data.optBoolean("tracking")) item { DashboardNotice("Detailed local tracking is off. Saved history remains visible. Enable it in Options to record future activity.") }
                if (data.optLong("coverageStart") > 0) item { Text("Detailed app tracking starts ${dayOf(data.optLong("coverageStart"))}. Older Scout history can have partial coverage.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                when (section) {
                    "Overview" -> overviewItems(data, requests, { section = it }, noteDetail)
                    "Vault" -> {
                        val vault = data.objectOrEmpty("vault")
                        item { InventoryMetrics(vault) }
                        item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search note titles") }, singleLine = true) }
                        item { DashboardChoice("Kind", noteKind, listOf("All") + vault.objectOrEmpty("counts").keys().asSequence().toList()) { noteKind = it } }
                        val notes = vault.array("notes").filter { (noteKind == "All" || it.optString("kind") == noteKind) && (it.optString("title") + it.optString("path")).contains(query, true) }
                        item { Text("${dn(notes.size.toLong())} current notes", style = MaterialTheme.typography.labelMedium) }
                        items(notes, key = { it.optString("path") }) { note -> NoteTile(note, noteDetail) }
                        if (notes.isEmpty()) item { DashboardNotice("No notes match. Change the kind or search text.") }
                    }
                    "Habits" -> habitItems(data, start)
                    "Recall" -> recallItems(data)
                    "Scout", "Costs" -> {
                        item { DashboardChoice("Model", model, listOf("All") + allRequests.map(::requestModel).distinct().sorted()) { model = it; visibleRequests = 30 } }
                        if (section == "Costs") item { DashboardChoice("Workflow", workflow, listOf("All") + allRequests.map(::requestWorkflow).distinct().sorted()) { workflow = it; visibleRequests = 30 } }
                        item { CostMetrics(requests) }
                        if (requests.isEmpty()) item { DashboardNotice("No recorded requests match. Missing history is not zero spending. Future requests appear as they run; older non-Scout charges cannot be reconstructed.") }
                        if (section == "Scout") {
                            val sessions = data.array("sessions")
                            item { DashboardPanel("Conversations") {
                                if (sessions.isEmpty()) Muted("No retained Scout conversations.")
                                sessions.forEach { session ->
                                    val rows = requests.filter { it.optString("sessionId") == session.optString("id") || it.optString("sessionFile") == session.optString("file") }
                                    TextButton(onClick = { actions.openSession(session.optString("file")) }, modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.fillMaxWidth()) { Text(session.optString("name", "Scout chat")); Muted("${rows.size} requests · ${money(sumCost(rows))}${if (session.optBoolean("partial")) " · partial history" else ""}") }
                                    }
                                }
                            } }
                            item { DashboardNotice("Context is the next-input estimate, not cumulative tokens. Open a conversation’s Usage screen for context history. Scout schedules remain disabled pending qualification.") }
                        }
                        item { TokenAnatomy(requests) }
                        item { DashboardPanel("Daily recorded cost") {
                            val groups = requests.groupBy { dayOf(requestTime(it)) }.toSortedMap().entries.toList().takeLast(14)
                            if (groups.isEmpty()) Muted("No daily charge records.")
                            else { val maximum = groups.maxOf { sumCost(it.value)?.toDouble() ?: 0.0 }; groups.forEach { (day, rs) -> DashboardBar(day, money(sumCost(rs)), sumCost(rs)?.toDouble() ?: 0.0, maximum) } }
                            Muted("Known charges only · last 14 observed dates. Missing charges remain unknown.")
                        } }
                        item { DashboardPanel("Daily token consumption") {
                            val groups = requests.groupBy { dayOf(requestTime(it)) }.toSortedMap().entries.toList().takeLast(14)
                            val maximum = groups.maxOfOrNull { (tokenSum(it.value, "prompt_tokens") ?: 0L) + (tokenSum(it.value, "completion_tokens") ?: 0L) }?.toDouble() ?: 0.0
                            groups.forEach { (day, rs) -> val value = (tokenSum(rs, "prompt_tokens") ?: 0L) + (tokenSum(rs, "completion_tokens") ?: 0L); DashboardBar(day, if (knownTokens(rs) == 0) "Unavailable" else dn(value), value.toDouble(), maximum) }
                            if (groups.isEmpty()) Muted("No reported token history.")
                            Muted("Reported input + output. ${knownTokens(requests)}/${requests.size} requests report both fields.")
                        } }
                        item { DashboardPanel("Workflow consumption") {
                            requests.groupBy(::requestWorkflow).toSortedMap().forEach { (name, rs) ->
                                Text(pretty(name), style = MaterialTheme.typography.titleSmall)
                                DashboardValue("Known charge", money(sumCost(rs)))
                                DashboardValue("Input / output", "${tokenText(rs, "prompt_tokens")} / ${tokenText(rs, "completion_tokens")}")
                                Muted("${knownCharge(rs)}/${rs.size} charges reported")
                                HorizontalDivider(color = cs.outlineVariant)
                            }
                            Muted("Workflow and stage totals describe the same requests. Do not add these views together.")
                        } }
                        item { DashboardPanel("From capture to usable knowledge") {
                            Muted("Saving locally uses 0 AI tokens and has $0 model charge. Optional transcription, extraction and processing are separate stages.")
                            requests.groupBy(::requestStage).toSortedMap().forEach { (stage, rs) ->
                                Text(pretty(stage), style = MaterialTheme.typography.titleSmall)
                                DashboardValue("Charge · ${rs.size} attempts", money(sumCost(rs)))
                                DashboardValue("Input / output", "${tokenText(rs, "prompt_tokens")} / ${tokenText(rs, "completion_tokens")}")
                                Muted("${knownCharge(rs)}/${rs.size} charges known")
                            }
                        } }
                        item { OperationMetrics(cohort) }
                        item { CardEconomics(cohort) }
                        item { RetryMetrics(requests) }
                        val ops = cohort.operations.sortedByDescending { it.optLong("startedAt") }.take(15)
                        if (ops.isNotEmpty()) item { DashboardPanel("Operation timelines") {
                            ops.forEach { op -> TextButton(onClick = { operationDetail(opId(op)) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) { Text(pretty(requestWorkflow(op))); Muted("${dayOf(op.optLong("startedAt"))} · ${op.optString("status")} · ${opId(op).takeLast(12)}") } } }
                        } }
                        item { Text("Requests · ${requests.size}", style = MaterialTheme.typography.titleMedium) }
                        items(requests.sortedByDescending(::requestTime).take(visibleRequests), key = { it.optString("requestId") }) { row -> RequestTile(row, requestDetail) }
                        if (requests.size > visibleRequests) item { TextButton(onClick = { visibleRequests += 50 }) { Text("Show 50 more requests") } }
                        item { TextButton(onClick = {
                            val export = JSONObject().put("view", section).put("asOf", data.optLong("asOf")).put("timezone", ZoneId.systemDefault().id).put("periodDays", days)
                                .put("model", model).put("workflow", workflow).put("coverageStart", data.optLong("coverageStart"))
                                .put("requests", JSONArray(requests)).put("operations", JSONArray(cohort.operations))
                            actions.export(export.toString(2))
                        }) { Text("Export filtered usage") } }
                    }
                    "Health" -> healthItems(data, actions, noteDetail)
                }
            }
        }
    }
    if (detailType.isNotEmpty()) DashboardDetail(data, detailType, detailId, actions, { detailType = "" }, { type, id -> detailType = type; detailId = id })
    if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("Dashboard options") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Record dashboard metadata", Modifier.weight(1f)); Switch(data.optBoolean("tracking"), onCheckedChange = actions::tracking) }
            Muted("Only request metadata, token/cost receipts and activity counters. No new copies of prompts, note bodies or recordings. Stays on this device.")
            Muted("Detailed records retain 90 days. Older periods may be incomplete. Clearing dashboard history does not delete notes or existing Scout chats.")
            Muted("Scout’s existing per-chat Usage continues when dashboard recording is paused. Those retained receipts remain visible.")
            TextButton(onClick = { showSettings = false; confirmClear = true }) { Text("Clear dashboard history", color = cs.error) }
            TextButton(onClick = { showSettings = false; actions.settings() }) { Text("Open app settings") }
        }
    }, confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear dashboard history?") }, text = { Text("This removes the dashboard’s local usage records. Your notes and Scout conversations stay intact; existing Scout request history can still appear from those chats.") }, confirmButton = { TextButton(onClick = { confirmClear = false; actions.clear() }) { Text("Clear history") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

@Composable private fun DashboardPill(label: String, selected: Boolean, action: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh, shape = CircleShape, modifier = Modifier.clip(CircleShape).clickable(onClick = action)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}
@Composable private fun DashboardChoice(label: String, value: String, choices: List<String>, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { open = true }) { Text("$label: ${pretty(value)} ▾", maxLines = 2) }; DropdownMenu(open, onDismissRequest = { open = false }) { choices.forEach { option -> DropdownMenuItem(text = { Text(pretty(option)) }, onClick = { open = false; choose(option) }) } } }
}
@Composable private fun DashboardPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() }
    }
}
@Composable private fun Muted(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun DashboardNotice(text: String) { DashboardPanel("Data coverage") { Muted(text) } }
@Composable private fun DashboardValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)); Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)) }
}
@Composable private fun DashboardMetrics(values: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { values.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { (label, value) ->
        Surface(Modifier.weight(1f), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Muted(label); Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) } }
    }; if (row.size == 1) Spacer(Modifier.weight(1f)) } } }
}
@Composable private fun DashboardBar(label: String, value: String, amount: Double, maximum: Double) {
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "$label: $value" }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        DashboardValue(label, value)
        LinearProgressIndicator(progress = { if (maximum > 0) (amount / maximum).toFloat().coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), gapSize = 0.dp, drawStopIndicator = {})
    }
}
@Composable private fun InventoryMetrics(vault: JSONObject) {
    val counts = vault.objectOrEmpty("counts")
    DashboardMetrics(listOf("Content notes" to dn(counts.labels().sumOf { it.second }), "Source records" to dn(vault.optLong("sourceCount")), "Topics" to dn(counts.optLong("Topics", counts.optLong("topics"))), "People" to dn(counts.optLong("People", counts.optLong("people")))))
}
private fun LazyListScope.overviewItems(data: JSONObject, requests: List<JSONObject>, navigate: (String) -> Unit, note: (String) -> Unit) {
    val vault = data.objectOrEmpty("vault"); val recall = data.objectOrEmpty("recall")
    val health = data.objectOrEmpty("health")
    val pending = health.optInt("pendingCaptures")
    val recoveries = health.optInt("recoveries")
    if (pending > 0 || recoveries > 0 || health.optInt("pendingCards") > 0) item {
        DashboardPanel("Needs attention") {
            if (pending > 0) DashboardValue("Pending captures", dn(pending.toLong()))
            if (recoveries > 0) DashboardValue("Recovery copies", dn(recoveries.toLong()))
            if (health.optInt("pendingCards") > 0) DashboardValue("Pending card saves", dn(health.optLong("pendingCards")))
            TextButton(onClick = { navigate("Health") }) { Text("Open health details") }
        }
    }
    item { InventoryMetrics(vault) }
    item { DashboardMetrics(listOf("Cards with review state" to dn(recall.optLong("reviewed")), "Recorded AI spend" to money(sumCost(requests)))) }
    item { DashboardPanel("Captured · connected · revisited") { DashboardValue("Source records", dn(vault.optLong("sourceCount"))); DashboardValue("Source notes with cards", dn(vault.optLong("sourceCards"))); DashboardValue("Cards reviewed", dn(recall.optLong("reviewed"))); Muted("Current inventory, not a conversion funnel. Accumulation is not a learning score.") } }
    item { DashboardPanel("Where curiosity collects") { val rows = vault.objectOrEmpty("categories").labels().sortedByDescending { it.second }; val max = rows.maxOfOrNull { it.second } ?: 0; rows.forEach { (name, value) -> DashboardBar(name, dn(value), value.toDouble(), max.toDouble()) }; if (rows.isEmpty()) Muted("No source categories recorded.") } }
    item { DashboardPanel("Source extraction") { vault.objectOrEmpty("quality").labels().forEach { (name, value) -> DashboardValue(pretty(name), dn(value)) }; Muted("Extraction completeness does not establish factual accuracy.") } }
    item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Recall", "Scout", "Costs").forEach { destination -> OutlinedButton(onClick = { navigate(destination) }, modifier = Modifier.weight(1f)) { Text(destination) } } } }
    val recent = vault.array("notes").sortedByDescending { it.optString("captured") }.take(5)
    items(recent, key = { "recent:" + it.optString("path") }) { NoteTile(it, note) }
}
@Composable private fun NoteTile(note: JSONObject, open: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open(note.optString("path")) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Muted("${note.optString("kind")} · ${note.optString("captured").ifBlank { "Undated" }}"); Text(note.optString("title"), style = MaterialTheme.typography.titleSmall); Muted("${note.optInt("cards")} cards${note.optString("quality").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}") }
    }
}
private fun LazyListScope.habitItems(data: JSONObject, start: Long) {
    val events = data.array("events").filter { it.optLong("at") >= start && it.optString("feature") !in listOf("capture", "ingest", "filed", "processed", "processing", "direct_note_saved", "scout_write_saved") }
    item { DashboardMetrics(listOf("Recorded actions" to dn(events.size.toLong()), "Active dates" to dn(events.map { dayOf(it.optLong("at")) }.distinct().size.toLong()))) }
    if (events.isEmpty()) item { DashboardNotice("No detailed activity records for this period. Legacy lifetime counters cannot reconstruct past daily habits.") }
    item { DashboardPanel("What gets used") { val rows = events.groupingBy { it.optString("feature", "Unknown") }.eachCount().entries.sortedByDescending { it.value }; val max = rows.maxOfOrNull { it.value } ?: 0; rows.forEach { DashboardBar(pretty(it.key), dn(it.value.toLong()), it.value.toDouble(), max.toDouble()) } } }
    item { DashboardPanel("When you return") { val rows = events.groupingBy { dayOf(it.optLong("at")) }.eachCount().toSortedMap().entries.toList().takeLast(14); val max = rows.maxOfOrNull { it.value } ?: 0; rows.forEach { DashboardBar(it.key, dn(it.value.toLong()), it.value.toDouble(), max.toDouble()) }; Muted("Local dates of deliberate user actions. Background processing does not count as an active day.") } }
}
private fun LazyListScope.recallItems(data: JSONObject) {
    val r = data.objectOrEmpty("recall")
    item { DashboardMetrics(listOf("All cards" to dn(r.optLong("total")), "Due now" to dn(r.optLong("due")), "New cards" to dn(r.optLong("new")), "Weak spots" to dn(r.optLong("weak")))) }
    item { DashboardPanel("Current review inventory") { listOf("reviewed", "parked", "flagged", "withTake").forEach { DashboardValue(pretty(it), dn(r.optLong(it))) }; Muted("Due/new counts use the current scheduler. Weak spots are a study aid, not an accuracy score.") } }
    item { DashboardPanel("Retained review grades") { r.objectOrEmpty("grades").labels().forEach { DashboardValue(pretty(it.first), dn(it.second)) }; DashboardValue("Retained attempts", dn(r.optLong("attempts"))); Muted("All retained history, independent of event-period filter. At most 50 entries per surviving card; deleted cards and unsynced changes are absent.") } }
    item { DashboardPanel("Review dates") { val rows = r.objectOrEmpty("days").labels().sortedBy { it.first }.takeLast(14); val max = rows.maxOfOrNull { it.second } ?: 0; rows.forEach { DashboardBar(it.first, dn(it.second), it.second.toDouble(), max.toDouble()) } } }
}
@Composable private fun CostMetrics(rows: List<JSONObject>) {
    DashboardMetrics(listOf("Recorded USD spend" to money(sumCost(rows)), "Charge coverage" to "${knownCharge(rows)} / ${rows.size}", "Input tokens" to tokenText(rows, "prompt_tokens"), "Output tokens" to tokenText(rows, "completion_tokens")))
    Muted("Known charges only. Missing charges are not free. Repeated history counts again on every request. Independently billed tools and purchase fees are excluded.")
}
@Composable private fun TokenAnatomy(rows: List<JSONObject>) {
    DashboardPanel("Token anatomy") {
        DashboardValue("Reported token pairs", "${knownTokens(rows)} / ${rows.size}")
        for ((label, group, field) in listOf(Triple("Cached input · subset", "prompt_tokens_details", "cached_tokens"), Triple("Cache writes", "prompt_tokens_details", "cache_write_tokens"), Triple("Reasoning output · subset", "completion_tokens_details", "reasoning_tokens"))) {
            val values = rows.mapNotNull { it.optJSONObject("usage")?.optJSONObject(group)?.takeUnless { d -> d.isNull(field) }?.optLong(field) }
            DashboardValue(label, if (values.isEmpty()) "Unavailable" else "${dn(values.sum())} · ${values.size} requests")
        }
        Muted("Cached input is already included in input. Reasoning is already included in output. Missing modality token fields remain unknown.")
    }
}
@Composable private fun OperationMetrics(cohort: OperationCohort) {
    val costs = cohort.priced.mapNotNull { sumCost(it.second) }.sorted()
    fun quantile(q: Double): BigDecimal? = if (costs.isEmpty()) null else costs[(kotlin.math.ceil(costs.size * q).toInt() - 1).coerceAtLeast(0)]
    DashboardPanel("Cost per completed operation") {
        DashboardValue("Mean known cost", money(cohort.meanCost))
        DashboardValue("Cost-complete sample", "${cohort.priced.size} completions")
        DashboardValue("Mean reported tokens", cohort.meanTokens?.let(::dn) ?: "Unavailable")
        DashboardValue("Token-complete sample", "${cohort.tokenKnown.size} completions")
        DashboardValue("Median / p95 operation cost", "${money(quantile(.5))} / ${money(quantile(.95))}")
        val charges = cohort.priced.map { (_, rs) -> sumCost(rs) ?: BigDecimal.ZERO }.sorted()
        DashboardValue("Median / p95 cost", if (charges.isEmpty()) "Unavailable" else "${money(charges[(charges.size - 1) / 2])} / ${money(charges[(kotlin.math.ceil(charges.size * .95).toInt() - 1).coerceAtLeast(0)])}")
        Muted("Whole completed operations only, grouped by operation start. Charges from requests crossing midnight stay in that cohort. ${cohort.excluded} partly model-filtered operations excluded. Failed/interrupted costs remain in request totals.")
    }
}
@Composable private fun CardEconomics(cohort: OperationCohort) {
    val cardCohort = cohort.priced.filter { (op, rs) -> op.optInt("cardsSaved") > 0 && rs.any { requestStage(it).contains("card", true) } }
    val cardRows = cardCohort.flatMap { (_, rs) -> rs.filter { requestStage(it).contains("card", true) } }
    val saved = cardCohort.sumOf { it.first.optLong("cardsSaved") }
    val cost = sumCost(cardRows)?.takeIf { saved > 0 }?.divide(BigDecimal(saved), 12, RoundingMode.HALF_UP)
    DashboardPanel("Card creation economics") {
        DashboardValue("Drafted / saved · priced cohort", "${dn(cardCohort.sumOf { it.first.optLong("cardsDrafted") })} / ${dn(saved)}")
        DashboardValue("Card-stage cost / saved card", money(cost))
        DashboardValue("Card-stage tokens / saved card", if (saved > 0 && knownTokens(cardRows) == cardRows.size) dn(((tokenSum(cardRows, "prompt_tokens") ?: 0L) + (tokenSum(cardRows, "completion_tokens") ?: 0L)) / saved) else "Unavailable")
        Muted("Includes drafting and repairs. Uses ${cardCohort.size} whole completed operations with verified saved cards. Embedded cards stay in their original capture or Scout workflow; never count them again as standalone spend.")
    }
}
@Composable private fun RetryMetrics(rows: List<JSONObject>) {
    val retries = rows.filter { it.optInt("attempt") > 0 }
    val failed = rows.filter { it.optString("status") in listOf("error", "failed", "cancelled", "interrupted") }
    DashboardPanel("Retries and failed work") {
        DashboardValue("Retry-attempt known cost", money(sumCost(retries)))
        DashboardValue("Retry attempts", retries.size.toString())
        DashboardValue("Retry input / output tokens", "${tokenText(retries, "prompt_tokens")} / ${tokenText(retries, "completion_tokens")}")
        DashboardValue("Failed/cancelled known cost", money(sumCost(failed)))
        DashboardValue("Failed/cancelled attempts", failed.size.toString())
        DashboardValue("Failed input / output tokens", "${tokenText(failed, "prompt_tokens")} / ${tokenText(failed, "completion_tokens")}")
        Muted("These sets can overlap. Do not add their totals. A retry can be necessary; failed work can still be charged.")
    }
}
@Composable private fun RequestTile(row: JSONObject, open: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open(row.optString("requestId")) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(pretty(requestStage(row)), style = MaterialTheme.typography.titleSmall)
            Muted("${pretty(requestWorkflow(row))} · ${row.optString("status")}")
            DashboardValue("Charge", money(decimal(row.optJSONObject("usage"), "cost")))
            Muted("${requestModel(row)} · ${dayOf(requestTime(row))}")
            Muted("Input ${tokenText(listOf(row), "prompt_tokens")} · output ${tokenText(listOf(row), "completion_tokens")}")
        }
    }
}
private fun LazyListScope.healthItems(data: JSONObject, actions: DashboardActions, openNote: (String) -> Unit) {
    val health = data.objectOrEmpty("health"); val vault = data.objectOrEmpty("vault")
    item { DashboardMetrics(listOf("Pending captures" to dn(health.optLong("pendingCaptures")), "Recovery copies" to dn(health.optLong("recoveries")), "Pending card saves" to dn(health.optLong("pendingCards")), "Unresolved references" to dn(vault.array("unresolved").size.toLong()))) }
    if (health.optString("problem").isNotBlank()) item { DashboardNotice(health.optString("problem")) }
    val warnings = data.optJSONArray("warnings")
    if (warnings != null) items((0 until warnings.length()).map { warnings.optString(it) }) { DashboardNotice(it) }
    item { DashboardPanel("Freshness and access") { DashboardValue("Vault cache", if (health.optBoolean("scanFresh")) "Current scan" else "Cached / partial"); Muted("Missing checks never imply healthy data. Refresh is read-only and does not reprocess captures."); TextButton(onClick = actions::settings) { Text("Open recovery and vault settings") } } }
    val unresolved = vault.array("unresolved").take(30)
    if (unresolved.isNotEmpty()) item { DashboardPanel("Unresolved links") { unresolved.forEach { row -> TextButton(onClick = { openNote(row.optString("path")) }) { Column(Modifier.fillMaxWidth()) { Text(row.optString("target")); Muted(row.optString("path")) } } } } }
    val isolated = vault.optJSONArray("isolated")
    if (isolated != null && isolated.length() > 0) item { DashboardPanel("Isolated notes") { Muted("No resolved inbound or outbound links. This is a navigation signal, not a deletion recommendation."); (0 until minOf(isolated.length(), 20)).forEach { i -> val path = isolated.optString(i); TextButton(onClick = { openNote(path) }) { Text(path.substringAfterLast('/').removeSuffix(".md")) } } } }
}

@Composable private fun DashboardDetail(data: JSONObject, type: String, id: String, actions: DashboardActions, close: () -> Unit, select: (String, String) -> Unit) {
    val request = data.array("requests").firstOrNull { it.optString("requestId") == id }
    val op = data.array("operations").firstOrNull { opId(it) == id }
    val note = data.objectOrEmpty("vault").array("notes").firstOrNull { it.optString("path") == id }
    AlertDialog(onDismissRequest = close, title = { Text(when (type) { "request" -> "Request receipt"; "operation" -> pretty(op?.let(::requestWorkflow).orEmpty()).ifBlank { "Operation" }; else -> note?.optString("title") ?: "Note" }) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (type) {
                "request" -> if (request == null) Muted("This request is no longer retained.") else {
                    DashboardValue("ID", id)
                    DashboardValue("Workflow / stage", "${pretty(requestWorkflow(request))} / ${pretty(requestStage(request))}")
                    DashboardValue("Model", requestModel(request))
                    DashboardValue("Execution outcome", request.optString("status", "Unknown"))
                    DashboardValue("Known charge", money(decimal(request.optJSONObject("usage"), "cost")))
                    DashboardValue("Input / output", "${tokenText(listOf(request), "prompt_tokens")} / ${tokenText(listOf(request), "completion_tokens")}")
                    DashboardValue("Attempt / elapsed", "${request.optInt("attempt") + 1} / ${request.optLong("durationMs")} ms")
                    DashboardValue("Generation ID", request.optString("generationId").ifBlank { "Unavailable" })
                    val operation = request.optString("operationId")
                    if (operation.isNotBlank()) TextButton(onClick = { select("operation", operation) }) { Text("Open full operation") }
                    val file = request.optString("sessionFile")
                    if (file.isNotBlank()) TextButton(onClick = { close(); actions.openSession(file) }) { Text("Open Scout conversation") }
                    Muted("Repeated input history is processed on each request. Reasoning and cached tokens are subsets, not extra totals.")
                }
                "operation" -> if (op == null) Muted("No operation record is retained. Older requests may not have operation attribution.") else {
                    val rows = data.array("requests").filter { it.optString("operationId") == id }
                    DashboardValue("ID / outcome", "$id / ${op.optString("status")}")
                    DashboardValue("Known AI charge", money(sumCost(rows)))
                    DashboardValue("Charge coverage", "${knownCharge(rows)} / ${rows.size}")
                    DashboardValue("Input / output", "${tokenText(rows, "prompt_tokens")} / ${tokenText(rows, "completion_tokens")}")
                    DashboardValue("Cards drafted / saved", "${op.optLong("cardsDrafted")} / ${op.optLong("cardsSaved")}")
                    if (requestWorkflow(op).contains("capture", true)) Muted("Local capture: $0 model charge, 0 AI tokens. Processing requests follow below.")
                    rows.sortedBy(::requestTime).forEach { row -> TextButton(onClick = { select("request", row.optString("requestId")) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) { Text(pretty(requestStage(row))); Muted("${money(sumCost(listOf(row)))} · ${requestModel(row)} · ${row.optString("status")}") } } }
                    Muted("Complete operation timeline, independent of active request filters. Local validation and writes make no AI request.")
                }
                else -> {
                    Muted(id)
                    if (note != null) { DashboardValue("Kind", note.optString("kind")); DashboardValue("Captured", note.optString("captured").ifBlank { "Unknown" }); DashboardValue("Cards", note.optInt("cards").toString()); DashboardValue("Extraction quality", note.optString("quality").ifBlank { "Not applicable" }) }
                    TextButton(onClick = { close(); actions.openNote(id) }) { Text("Open note") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Done") } })
}
