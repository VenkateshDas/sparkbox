package me.venkatesh.saveobsidian

import android.content.Context
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
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
/** One line for a tool call Scout made ("Searched …"). */
class Step(val text: String) : AskItem
/** A change or call waiting for the user's OK; the run waits for it, whenever the user comes back. */
class Approval(val title: String, val detail: String) : AskItem

interface AskActions {
    fun ask(q: String)
    fun cancel()
    fun retry()
    fun newChat()
    fun searchVault()
    fun addNote()
    fun draft(text: String)
    fun approve(yes: Boolean)
    fun drawer(open: Boolean)
    fun openChat(file: String)
    fun renameChat(file: String)
    fun deleteChat(file: String)
    fun searchChats(query: String)
}

/** One line of the chats drawer. status: "", "Working…" or "Needs your OK". */
class ChatRow(val file: String, val title: String, val status: String, val current: Boolean)

class AskState {
    var items by mutableStateOf(emptyList<AskItem>()); private set
    var running by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var live by mutableStateOf(""); private set
    var input by mutableStateOf(""); private set
    var subtitle by mutableStateOf(""); private set
    var offline by mutableStateOf(false); private set
    var title by mutableStateOf(""); private set
    var drawer by mutableStateOf(false); private set
    var chats by mutableStateOf(emptyList<ChatRow>()); private set
    var query by mutableStateOf(""); private set
    fun title(t: String) { title = t }
    fun drawer(on: Boolean) { drawer = on; if (!on) query = "" }
    fun chats(list: List<ChatRow>) { chats = list }
    fun query(q: String) { query = q }
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
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(44.dp)) { Box(contentAlignment = Alignment.Center) { content() } }
}

/** Two bars, like the reference's menu button. */
@Composable
private fun MenuIcon(color: Color) = Canvas(Modifier.size(22.dp)) {
    val w = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, Offset(2.dp.toPx(), 7.dp.toPx()), Offset(20.dp.toPx(), 7.dp.toPx()), w.width, StrokeCap.Round)
    drawLine(color, Offset(2.dp.toPx(), 15.dp.toPx()), Offset(13.dp.toPx(), 15.dp.toPx()), w.width, StrokeCap.Round)
}

/** A square with a pen stroke: new chat. */
@Composable
private fun NewChatIcon(color: Color) = Canvas(Modifier.size(22.dp)) {
    val w = 2.2.dp.toPx()
    drawRoundRect(color, androidx.compose.ui.geometry.Offset(2.dp.toPx(), 4.dp.toPx()), androidx.compose.ui.geometry.Size(15.dp.toPx(), 15.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), Stroke(w))
    drawLine(color, Offset(9.dp.toPx(), 13.dp.toPx()), Offset(19.dp.toPx(), 3.dp.toPx()), w, StrokeCap.Round)
}

@Composable
private fun SearchIcon(color: Color) = Canvas(Modifier.size(22.dp)) {
    val w = 2.2.dp.toPx()
    drawCircle(color, 7.dp.toPx(), Offset(9.5.dp.toPx(), 9.5.dp.toPx()), style = Stroke(w))
    drawLine(color, Offset(15.dp.toPx(), 15.dp.toPx()), Offset(20.dp.toPx(), 20.dp.toPx()), w, StrokeCap.Round)
}

@Composable
private fun AskUi(s: AskState, a: AskActions) {
    val list = rememberLazyListState()
    val count = s.items.size + (if (s.running) 1 else 0)
    LaunchedEffect(count, s.live.length / 200) { if (count > 0) list.animateScrollToItem(count - 1) }
    val cs = MaterialTheme.colorScheme
    val intro = s.items.singleOrNull() as? Intro
    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Box(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton({ a.drawer(true) }) { MenuIcon(cs.onSurface) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.title.ifEmpty { "Scout" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.subtitle.isNotEmpty()) Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    RoundButton({ a.newChat() }) { NewChatIcon(cs.onSurface) }
                }
                if (s.offline) Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) { BlockUi(Banner("You are offline. Earlier answers stay readable. Scout needs a network connection.", false)) }
                if (intro != null) {
                    Column(Modifier.weight(1f).padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
                        Text("Scout", style = MaterialTheme.typography.headlineMedium)
                        Text(intro.text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                    intro.suggestions.forEach { q ->
                        Row(Modifier.fillMaxWidth().clickable { a.ask(q) }.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(cs.primary))
                            Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp))
                        }
                    }
                } else LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(s.items) { _, it -> Item(it, a) }
                    if (s.running) item { Running(s, a) }
                }
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val send = { val q = s.input.trim(); if (q.isNotEmpty() && !s.offline) a.ask(q) } // while it runs, a message steers Scout
                    OutlinedTextField(value = s.input, onValueChange = { s.input(it); a.draft(it) }, enabled = !s.offline,
                        placeholder = { Text(if (s.offline) "You are offline" else if (s.running) "Steer Scout…" else "Ask Scout") }, maxLines = 4, shape = RoundedCornerShape(28.dp),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = cs.surfaceContainerHigh, unfocusedContainerColor = cs.surfaceContainerHigh, disabledContainerColor = cs.surfaceContainerHigh,
                            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }), modifier = Modifier.weight(1f))
                    val stop = s.running && s.input.isBlank()
                    FilledIconButton(onClick = { if (stop) a.cancel() else send() }, enabled = !s.offline && (stop || s.input.isNotBlank()), modifier = Modifier.padding(start = 8.dp).size(48.dp)) {
                        Text(if (stop) "■" else "↑", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            AnimatedVisibility(s.drawer, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { a.drawer(false) } })
            }
            AnimatedVisibility(s.drawer, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) { ChatsDrawer(s, a) }
        }
    }
}

@Composable
private fun ChatsDrawer(s: AskState, a: AskActions) {
    val cs = MaterialTheme.colorScheme
    var searching by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxHeight().width(320.dp), color = cs.surfaceContainerLow, shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chats", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                RoundButton({ searching = !searching; if (!searching) a.searchChats("") }) { SearchIcon(cs.onSurface) }
            }
            if (searching) OutlinedTextField(value = s.query, onValueChange = { a.searchChats(it) }, placeholder = { Text("Search chats") }, singleLine = true, shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                if (s.chats.isEmpty()) item { Text(if (s.query.isEmpty()) "No chats yet." else "No chat matches.", color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
                itemsIndexed(s.chats) { _, c ->
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (c.current) cs.surfaceContainerHigh else Color.Transparent).clickable { a.openChat(c.file) }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (c.current) cs.primary else cs.onSurface)
                            if (c.status.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(cs.primary))
                                Text(c.status, style = MaterialTheme.typography.bodySmall, color = cs.primary, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        Box {
                            TextButton(onClick = { menu = true }) { Text("⋯", style = MaterialTheme.typography.titleLarge, color = cs.onSurfaceVariant) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; a.renameChat(c.file) })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; a.deleteChat(c.file) })
                            }
                        }
                    }
                }
            }
            Button(onClick = { a.newChat() }, shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(top = 8.dp)) {
                Text("New chat", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
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
        is Step -> Text("•  " + it.text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        is Approval -> Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerHigh, border = androidx.compose.foundation.BorderStroke(1.dp, cs.primary)) {
            Column(Modifier.padding(16.dp)) {
                Text("Scout needs your OK", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                Text(it.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                if (it.detail.isNotEmpty()) Text(it.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerLow).padding(10.dp))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { a.approve(true) }, modifier = Modifier.weight(1f)) { Text("Allow") }
                    OutlinedButton(onClick = { a.approve(false) }, modifier = Modifier.weight(1f)) { Text("Decline") }
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
                    Text("No notes cover this. Scout does not guess.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
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
