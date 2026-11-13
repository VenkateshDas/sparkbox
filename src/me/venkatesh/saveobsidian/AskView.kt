package me.venkatesh.saveobsidian

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

class SourceRef(val num: String, val title: String, val meta: String, val onClick: Runnable)

sealed interface AskItem
class Question(val text: String) : AskItem
class Answer(val text: String, val singleSource: Boolean, val sources: List<SourceRef>) : AskItem
class NotFound(val near: List<SourceRef>) : AskItem
class Failure(val message: String) : AskItem
/** A stopped answer: what had arrived is kept, never sent again by itself. */
class Stopped(val partial: String) : AskItem
class Intro(val text: String, val suggestions: List<String>) : AskItem

interface AskActions {
    fun ask(q: String)
    fun cancel()
    fun retry()
    fun newChat()
    fun searchVault()
    fun addNote()
    fun draft(text: String)
}

class AskState {
    var items by mutableStateOf(emptyList<AskItem>()); private set
    var running by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var live by mutableStateOf(""); private set
    var input by mutableStateOf(""); private set
    var subtitle by mutableStateOf(""); private set
    var offline by mutableStateOf(false); private set
    fun publish(items: List<AskItem>) { this.items = items }
    fun running(on: Boolean) { running = on; if (!on) { status = ""; live = "" } }
    fun status(s: String) { status = s }
    fun live(s: String) { live = s }
    fun input(s: String) { input = s }
    fun header(subtitle: String, offline: Boolean) { this.subtitle = subtitle; this.offline = offline }
}

object AskView {
    @JvmStatic fun create(c: Context, ui: Ui, state: AskState, a: AskActions): View = ComposeView(c).apply {
        setContent { SparkTheme(ui) { AskUi(state, a) } }
    }
}

@Composable
private fun AskUi(s: AskState, a: AskActions) {
    val list = rememberLazyListState()
    val count = s.items.size + (if (s.running) 1 else 0)
    LaunchedEffect(count, s.live.length / 200) { if (count > 0) list.animateScrollToItem(count - 1) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            TopBar("Ask", null) { if (s.items.any { it is Answer || it is Question } && !s.running) TextButton(onClick = { a.newChat() }) { Text("New chat") } }
            if (s.subtitle.isNotEmpty()) Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
            if (s.offline) Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) { BlockUi(Banner("You are offline. Earlier answers stay readable. Ask needs a network connection.", false)) }
            LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(s.items) { _, it -> Item(it, a) }
                if (s.running) item { Running(s, a) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val send = { val q = s.input.trim(); if (q.isNotEmpty() && !s.running && !s.offline) a.ask(q) }
                OutlinedTextField(value = s.input, onValueChange = { s.input(it); a.draft(it) }, enabled = !s.offline,
                    placeholder = { Text(if (s.offline) "You are offline" else "Ask your vault") }, maxLines = 4, shape = RoundedCornerShape(24.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }), modifier = Modifier.weight(1f))
                FilledIconButton(onClick = send, enabled = !s.running && !s.offline && s.input.isNotBlank(), modifier = Modifier.padding(start = 8.dp).size(48.dp)) {
                    Text("↑", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun Item(it: AskItem, a: AskActions) {
    val cs = MaterialTheme.colorScheme
    when (it) {
        is Intro -> Column {
            Text(it.text, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            if (it.suggestions.isNotEmpty()) Text("Try", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            it.suggestions.forEach { q ->
                OutlinedButton(onClick = { a.ask(q) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(q, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        is Question -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(it.text, style = MaterialTheme.typography.bodyLarge, color = cs.onPrimaryContainer,
                modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(cs.primaryContainer).padding(horizontal = 14.dp, vertical = 10.dp))
        }
        is Answer -> Column {
            Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerLow, border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant)) {
                Column(Modifier.padding(16.dp)) {
                    if (it.singleSource) Box(Modifier.padding(bottom = 10.dp)) { BlockUi(Banner("Single source: 1 note supports this.", false)) }
                    SelectionContainer { Text(rich(it.text, cs.primary), style = MaterialTheme.typography.bodyLarge) }
                }
            }
            Sources("Sources", it.sources)
        }
        is NotFound -> Column {
            Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerLow, border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Not in your knowledge base", style = MaterialTheme.typography.titleMedium)
                    Text("No notes cover this. Ask does not guess.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { a.searchVault() }, modifier = Modifier.weight(1f)) { Text("Search vault") }
                        OutlinedButton(onClick = { a.addNote() }, modifier = Modifier.weight(1f)) { Text("Add a note") }
                    }
                }
            }
            Sources("Closest notes", it.near)
        }
        is Failure -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(cs.errorContainer).padding(12.dp)) {
            Text(it.message, style = MaterialTheme.typography.bodyMedium, color = cs.error)
            Button(onClick = { a.retry() }, modifier = Modifier.padding(top = 8.dp)) { Text("Retry") }
        }
        is Stopped -> Column {
            if (it.partial.isNotEmpty()) Text(it.partial, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("Stopped. Not checked against your notes.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { a.retry() }) { Text("Ask again") }
            }
        }
    }
}

@Composable
private fun Sources(label: String, refs: List<SourceRef>) {
    if (refs.isEmpty()) return
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    NavCard(refs.map { Nav("${it.num}  ${it.title}", it.meta, "", false, it.onClick) })
}

/** The answer while it streams: a draft, not yet checked for citations. */
@Composable
private fun Running(s: AskState, a: AskActions) {
    val cs = MaterialTheme.colorScheme
    Column {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.status.ifEmpty { "Thinking…" }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { a.cancel() }) { Text("Cancel") }
        }
        if (s.live.isNotEmpty()) Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).padding(14.dp)) {
            Text("Draft, still checking sources", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(s.live, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private val MARK = Regex("\\[\\d+]|\\*\\*[^*]+\\*\\*")

/** Citation markers in the accent colour, **bold** as bold; everything else as written. */
private fun rich(text: String, accent: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    var at = 0
    for (m in MARK.findAll(text)) {
        append(text.substring(at, m.range.first))
        if (m.value.startsWith("**")) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.value.removeSurrounding("**")) }
        else withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(m.value) }
        at = m.range.last + 1
    }
    append(text.substring(at))
}
