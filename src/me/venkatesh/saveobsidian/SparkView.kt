package me.venkatesh.saveobsidian

import android.content.Context
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

class SparkButton(val label: String, val primary: Boolean, val onClick: Runnable)
class Grade(val name: String, val next: String, val onClick: Runnable)

sealed interface SparkPage
class SparkMessage(val title: String, val body: String, val buttons: List<SparkButton>) : SparkPage

/**
 * One card. type: classic, cloze, quiz, tf, why, teach, apply, sort, spark, connect. options: the choices of a quiz or tf, the shuffled steps of a sort.
 * expect: what a typed answer is compared with (the blanks of a cloze, else the answer). grades: Again/Hard/Good/Easy, or the buttons of an inspire card.
 */
class SparkCard(
    val id: String, val lane: String, val from: String, val type: String, val question: String, val answer: String, val expect: String, val take: String,
    val options: List<String>, val done: Int, val total: Int, val canReview: Boolean, val hasSource: Boolean, val askConf: Boolean, val canAi: Boolean, val grades: List<Grade>, val canBack: Boolean, val canForward: Boolean, val pos: Int,
) : SparkPage

/** One tile on the topic picker: a smart deck (due, new, weak, recent) or a topic with its counts. */
class PickTile(val kind: String, val key: String, val title: String, val cards: Int, val due: Int, val fresh: Int, val mastery: Float, val lane: String)
class SparkPicker(val total: Int, val due: Int, val fresh: Int, val reviewed: Int, val streak: Int, val smart: List<PickTile>, val topics: List<PickTile>, val resume: String) : SparkPage

interface SparkActions {
    fun choose(kind: String, key: String); fun surprise(); fun topics()

    fun reveal(conf: String); fun park(); fun flag(); fun source(); fun edit(); fun take(); fun explain(); fun saveTake(text: String); fun aiCheck(typed: String); fun mode(m: String); fun back(); fun forward()
}

class SparkState {
    var page by mutableStateOf<SparkPage?>(null); private set
    var subtitle by mutableStateOf(""); private set
    var canWrite by mutableStateOf(true); private set
    var mode by mutableStateOf("today"); private set
    var feedback by mutableStateOf(""); private set
    /** The scope being studied ("" on the picker). */
    var scope by mutableStateOf(""); private set
    fun publish(p: SparkPage, subtitle: String, canWrite: Boolean, mode: String, scope: String) { page = p; this.subtitle = subtitle; this.canWrite = canWrite; this.mode = mode; this.scope = scope }
    fun showFeedback(text: String) { feedback = text }
}

object SparkView {
    @JvmStatic fun create(c: Context, ui: Ui, s: SparkState, a: SparkActions): View = ComposeView(c).apply {
        setContent { SparkTheme(ui) { SparkUi(s, a) } }
    }
}

private val MODES = listOf("today" to "Today", "quick" to "Quick", "deep" to "Deep", "all" to "All", "inspire" to "Inspire")

@Composable
private fun SparkUi(s: SparkState, a: SparkActions) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            TopBar("Review", null)
            val picker = s.page as? SparkPicker
            if (picker != null) {
                Picker(picker, a, s)
                return@Column
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.scope.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { a.topics() }) { Text("\u2039 Topics") }
                        Text(s.scope, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { MODES.forEach { (id, label) -> Pill(label, s.mode == id) { a.mode(id) } } }
                }
                if (s.subtitle.isNotEmpty()) Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!s.canWrite) BlockUi(Banner("Allow write access to your vault folder in Settings to review cards.", false))
                when (val p = s.page) {
                    is SparkMessage -> Message(p)
                    is SparkCard -> Card(p, s, a)
                    is SparkPicker -> Unit
                    null -> {}
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh, modifier = Modifier.clip(CircleShape).clickable { onClick() }) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

@Composable
private fun Message(p: SparkMessage) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(p.title, style = MaterialTheme.typography.headlineSmall)
        Text(p.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        p.buttons.forEachIndexed { i, b ->
            val m = Modifier.padding(top = if (i == 0) 20.dp else 8.dp)
            if (b.primary) Button(onClick = { b.onClick.run() }, modifier = m) { Text(b.label) } else OutlinedButton(onClick = { b.onClick.run() }, modifier = m) { Text(b.label) }
        }
    }
}

private val CONF = listOf("sure" to "Sure", "unsure" to "Not sure", "guess" to "Guessing")

/** The loop: predict (guess, say how sure), reveal (flip, compare), grade. Swipe right is Good, left is Again; the buttons do the same. */
@Composable
private fun Card(p: SparkCard, s: SparkState, a: SparkActions) = key(p.id) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    var revealed by remember { mutableStateOf(false) }
    var conf by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(listOf<String>()) }
    val choice = p.type == "quiz" || p.type == "tf"
    val rot by animateFloatAsState(if (revealed) 180f else 0f, tween(380), label = "flip")
    fun reveal(then: String? = null) { if (revealed) return; revealed = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress); a.reveal(conf); if (then != null) a.saveTake(then) }

    // what the answer looks like against the key: auto-checked for choices and sorts, an on-device word match for typed answers
    val score = if (!choice && p.type != "sort" && typed.isNotBlank()) Spark.match(typed, p.expect) else -1.0
    val right: Boolean? = when {
        choice -> picked.firstOrNull()?.let { it == p.expect }
        p.type == "sort" -> picked.joinToString(" > ").let { if (picked.size == p.options.size) it == p.expect else null }
        score >= 0 -> score >= 0.6
        else -> null
    }
    val suggest = when { right == true -> "good"; right == false && score < 0 -> "again"; score >= 0.6 -> "good"; score >= 0.3 -> "hard"; score >= 0 -> "again"; else -> "" }
    val surprise = revealed && conf == "sure" && suggest == "again"

    LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { a.back() }, enabled = p.canBack) { Text("\u2190", style = MaterialTheme.typography.headlineSmall) }
        Text("Card ${p.pos} of ${p.total}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        TextButton(onClick = { a.forward() }, enabled = p.canForward) { Text("\u2192", style = MaterialTheme.typography.headlineSmall) }
    }
    Box(Modifier.pointerInput(revealed) {
        if (revealed && p.grades.size == 4) {
            var dx = 0f
            detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                if (dx > 160f) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); p.grades[2].onClick.run() } else if (dx < -160f) p.grades[0].onClick.run()
            }, onHorizontalDrag = { _, d -> dx += d })
        }
    }) {
        Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerLow, border = BorderStroke(1.dp, cs.outlineVariant),
            modifier = Modifier.fillMaxWidth().graphicsLayer { rotationY = rot; cameraDistance = 14f * density }) {
            if (rot <= 90f) Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Header(p)
                Text(p.question, style = MaterialTheme.typography.headlineSmall)
                if (p.askConf && p.canReview && p.type != "spark") Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("How sure are you?", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CONF.forEach { (id, label) -> Pill(label, conf == id) { conf = if (conf == id) "" else id } } }
                }
                when {
                    choice -> p.options.forEach { o -> OutlinedButton(onClick = { picked = listOf(o); reveal() }, enabled = p.canReview, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(o, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth()) } }
                    p.type == "sort" -> {
                        Text("Tap the steps in order", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        p.options.forEach { o ->
                            val at = picked.indexOf(o)
                            OutlinedButton(onClick = { picked = if (at >= 0) picked - o else picked + o }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                Text((if (at >= 0) "${at + 1}. " else "") + o, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Button(onClick = { reveal() }, enabled = p.canReview && picked.size == p.options.size, modifier = Modifier.fillMaxWidth()) { Text("Check order") }
                    }
                    p.type == "spark" -> Button(onClick = { reveal() }, modifier = Modifier.fillMaxWidth()) { Text("Show the note") }
                    else -> {
                        val ask = p.type in setOf("why", "teach", "apply", "connect")
                        OutlinedTextField(typed, { typed = it }, modifier = Modifier.fillMaxWidth(), minLines = if (ask) 3 else 1,
                            label = { Text(when (p.type) { "teach" -> "Explain it in two sentences"; "apply" -> "Where will you use it this week?"; "cloze" -> "Fill the blank"; else -> if (ask) "Your answer" else "Your guess (optional)" }) })
                        Button(onClick = { reveal(if (p.type == "apply") typed.trim() else null) }, enabled = p.canReview, modifier = Modifier.fillMaxWidth()) { Text(if (ask || typed.isNotBlank()) "Check" else "Show answer") }
                    }
                }
            } else Box(Modifier.graphicsLayer { rotationY = 180f }) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Header(p)
                    Text(p.question, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                    if (surprise) BlockUi(Banner("You were sure. This one sticks best when it surprises you.", false))
                    if (right != null) Text(if (right) "Right." else "Not quite.", style = MaterialTheme.typography.titleMedium, color = if (right) cs.primary else cs.error)
                    BlockUi(Panel("Answer", p.answer, Origin.NONE))
                    if (typed.isNotBlank()) BlockUi(Panel("Your answer" + if (score >= 0) " (" + Math.round(score * 100) + "% of the key words)" else "", typed, Origin.NONE))
                    if (choice && picked.isNotEmpty() && right == false) BlockUi(Panel("You chose", picked[0], Origin.NONE))
                    if (p.canAi && typed.isNotBlank()) {
                        if (s.feedback.isEmpty()) OutlinedButton(onClick = { a.aiCheck(typed) }) { Text("Check with AI") }
                        else BlockUi(Panel("Feedback", s.feedback, Origin.NONE))
                    }
                    if (p.type != "spark" && p.type != "connect") Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).clickable { a.take() }.padding(14.dp)) {
                        Text("Your note", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Text(p.take.ifEmpty { "Add your own words" }, style = MaterialTheme.typography.bodyLarge,
                            color = if (p.take.isEmpty()) cs.onSurfaceVariant else cs.onSurface, fontStyle = if (p.take.isEmpty()) null else FontStyle.Italic, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
    if (revealed) {
        if (p.grades.size == 4) Text("How well did you recall it? Swipe right for Good, left for Again.", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            p.grades.forEach { g ->
                val m = Modifier.weight(1f).heightIn(min = 56.dp)
                val pad = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                val inner: @Composable () -> Unit = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(g.name, style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
                        if (g.next.isNotEmpty()) Text(g.next, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                if (g.name.lowercase() == suggest) FilledTonalButton(onClick = { g.onClick.run() }, shape = RoundedCornerShape(14.dp), contentPadding = pad, modifier = m) { inner() }
                else OutlinedButton(onClick = { g.onClick.run() }, shape = RoundedCornerShape(14.dp), contentPadding = pad, modifier = m) { inner() }
            }
        }
    }
    if (p.type != "spark" && p.type != "connect") androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { a.park() }) { Text("Snooze 24h") }
        TextButton(onClick = { a.flag() }) { Text("Pause card") }
        if (p.hasSource) TextButton(onClick = { a.source() }) { Text("Source") }
        if (p.hasSource && revealed) TextButton(onClick = { a.explain() }) { Text("Ask") }
        TextButton(onClick = { a.edit() }) { Text("Edit") }
    }
}

@Composable
private fun Header(p: SparkCard) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (p.lane.isNotEmpty()) { ToneChip(Tag(p.lane, 0)); Text(" ", style = MaterialTheme.typography.bodySmall) }
        Text(p.from, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- topic picker ----

@Composable
private fun Picker(p: SparkPicker, a: SparkActions, s: SparkState) {
    val cs = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var lane by rememberSaveable { mutableStateOf("") }
    val topics = p.topics.filter { (lane.isEmpty() || it.lane == lane) && it.title.contains(query, ignoreCase = true) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            if (s.subtitle.isNotEmpty()) Text(s.subtitle, style = MaterialTheme.typography.bodySmall)
            if (!s.canWrite) BlockUi(Banner("Allow write access to your vault folder in Settings to review cards.", false))
            Text("Today’s review", style = MaterialTheme.typography.headlineSmall)
            Text("${p.total} cards · ${p.fresh} new" + if (p.streak > 0) " · ${p.streak} day streak" else "", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        if (p.resume.isNotEmpty()) item {
            FilledTonalButton(onClick = { a.choose("resume", "") }, modifier = Modifier.fillMaxWidth()) { Text("Continue · ${p.resume}") }
        }
        item {
            val due = p.smart.firstOrNull { it.kind == "due" }
            if (due != null && due.cards > 0) {
                Button(onClick = { a.choose(due.kind, due.key) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (due.cards == 1) "Review 1 due card" else "Review ${due.cards} due cards")
                }
            } else Text("No cards due today", style = MaterialTheme.typography.bodyLarge)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.smart.filter { it.kind != "due" }.forEach { deck ->
                    OutlinedButton(onClick = { a.choose(deck.kind, deck.key) }, enabled = deck.cards > 0) { Text("${deck.title} · ${deck.cards}") }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Topics", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = a::surprise, enabled = p.topics.isNotEmpty()) { Text("Surprise me") }
            }
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search topics") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOf("" to "All", "Craft" to "Craft", "Life" to "Life").forEach { (id, name) -> Pill(name, lane == id) { lane = id } }
            }
        }
        if (topics.isEmpty()) item { Text("No matching topics", style = MaterialTheme.typography.bodyMedium) }
        items(topics, key = { it.key }) { topic ->
            Surface(onClick = { a.choose(topic.kind, topic.key) }, shape = RoundedCornerShape(12.dp), color = cs.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(topic.title, style = MaterialTheme.typography.titleLarge)
                    Text("${topic.cards} cards · ${topic.due} due", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    LinearProgressIndicator(progress = { topic.mastery }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth(), color = cs.primary)
                }
            }
        }
    }
}
