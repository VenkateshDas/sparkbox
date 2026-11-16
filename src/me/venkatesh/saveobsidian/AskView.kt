package me.venkatesh.saveobsidian

import android.content.Context
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

class SourceRef(val num: String, val title: String, val meta: String, val onClick: Runnable)

class QueuedItem(val text: String, val after: Boolean, val edit: Runnable, val remove: Runnable) : AskItem
class DiscoveryItem(val title: String, val detail: String, val open: Runnable, val investigate: Runnable, val save: Runnable, val dismiss: Runnable) : AskItem
sealed interface AskItem
class Question(val text: String) : AskItem
class Answer(val text: String, val singleSource: Boolean, val sources: List<SourceRef>, val raw: String, val keep: Runnable, val topic: Runnable, val cards: Runnable, val retry: Runnable) : AskItem
class NotFound(val near: List<SourceRef>) : AskItem
class Failure @JvmOverloads constructor(val message: String, val partial: String = "") : AskItem
/** A stopped answer: what had arrived is kept, never sent again by itself. */
class Stopped(val partial: String) : AskItem
class Intro(val text: String, val suggestions: List<String>) : AskItem
/** A turn's tool steps, folded under "Worked for m:ss". secs: Chat.UNKNOWN or Chat.RUNNING when there is no time. */
class Work(val secs: Long, val steps: List<String>) : AskItem
/** A change or call waiting for the user's OK; the run waits for it, whenever the user comes back. */
class Approval(val id: String, val title: String, val detail: String, val busy: Boolean) : AskItem

interface AskActions {
    fun ask(q: String)
    fun clearContext()
    fun cancel()
    fun retry()
    fun newChat()
    fun searchVault()
    fun addNote()
    fun draft(text: String)
    fun optionsChanged()
    fun suggest(text: String)
    fun attachNote()
    fun attachQuote()
    fun discover()
    fun approve(id: String, yes: Boolean)
    fun drawer(open: Boolean)
    fun openChat(file: String)
    fun renameChat(file: String)
    fun deleteChat(file: String)
    fun searchChats(query: String)
    fun usage(file: String)
    fun exportChat(file: String)
}

/** One line of the chats drawer. status: "", "Working…" or "Needs your OK". */
class ChatRow(val file: String, val title: String, val status: String, val current: Boolean)

class AskState {
    var afterAnswer by mutableStateOf(false); private set
    fun afterAnswer(on: Boolean) { afterAnswer = on }
    var caret by mutableStateOf(0); private set
    fun caret(at: Int) { caret = at.coerceIn(0, input.length) }
    var expanded by mutableStateOf(false); private set
    fun expanded(on: Boolean) { expanded = on }
    var research by mutableStateOf(false); private set
    fun research(on: Boolean) { research = on }
    var web by mutableStateOf(false); private set
    fun web(on: Boolean) { web = on }
    val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()
    var keys by mutableStateOf(emptyList<String>()); private set
    fun session(file: String) { sessionFile = file }
    var usage by mutableStateOf(""); private set
    var sessionFile by mutableStateOf(""); private set
    fun usage(file: String, text: String) { sessionFile = file; usage = text }
    var items by mutableStateOf(emptyList<AskItem>()); private set
    var running by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var live by mutableStateOf(""); private set
    var input by mutableStateOf(""); private set
    var subtitle by mutableStateOf(""); private set
    var offline by mutableStateOf(false); private set
    var title by mutableStateOf(""); private set
    var context by mutableStateOf(""); private set
    fun context(t: String) { context = t }
    var began by mutableStateOf(0L); private set
    fun began(ms: Long) { began = ms }
    var drawer by mutableStateOf(false); private set
    var chats by mutableStateOf(emptyList<ChatRow>()); private set
    var query by mutableStateOf(""); private set
    fun title(t: String) { title = t }
    fun drawer(on: Boolean) { drawer = on; if (!on) query = "" }
    fun chats(list: List<ChatRow>) { chats = list }
    fun query(q: String) { query = q }
    fun publish(items: List<AskItem>, keys: List<String>) { this.items = items; this.keys = keys }
    fun running(on: Boolean) { running = on; if (!on) { status = ""; live = "" } }
    fun status(s: String) { status = s }
    fun live(s: String) { live = s }
    fun input(s: String) { input = s; caret = caret.coerceAtMost(s.length) }
    fun header(subtitle: String, offline: Boolean) { this.subtitle = subtitle; this.offline = offline }
}

object AskView {
    @JvmStatic fun create(c: Context, ui: Ui, state: AskState, a: AskActions): View = ComposeView(c).apply {
        setContent { SparkTheme(ui) { AskUi(state, a, ui) } }
    }
}

/** The answer's Markdown (headings, lists, tables, bold, code, links) drawn by the same renderer as the note viewer. */
@Composable
private fun Markdown(text: String, sources: List<SourceRef>, ui: Ui) { ScoutAnswer(text, ui, sources) }

@Composable
private fun RoundButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) { Box(contentAlignment = Alignment.Center) { content() } }
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
private fun AskUi(s: AskState, a: AskActions, ui: Ui) {
    val savedScroll = s.scrollPositions[s.sessionFile]
    val list = androidx.compose.runtime.key(s.sessionFile) { rememberLazyListState(savedScroll?.first ?: 0, savedScroll?.second ?: 0) }
    androidx.compose.runtime.DisposableEffect(s.sessionFile, list) {
        val session = s.sessionFile
        onDispose { s.scrollPositions[session] = Pair(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
    }
    val count = s.items.size + (if (s.running) 1 else 0)
    val scope = rememberCoroutineScope()
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    var follow by remember(s.sessionFile) { mutableStateOf(true) }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) follow = atBottom }
    LaunchedEffect(count, s.live.length / 200) { if (follow && count > 0) list.scrollToItem(count) }
    val cs = MaterialTheme.colorScheme
    val intro = s.items.singleOrNull() as? Intro
    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Box(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton("Open chats", { a.drawer(true) }) { MenuIcon(cs.onSurface) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.title.ifEmpty { "Scout" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.subtitle.isNotEmpty()) Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    RoundButton("New chat", { a.newChat() }) { NewChatIcon(cs.onSurface) }
                }
                if (s.offline) Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) { BlockUi(Banner("You are offline. Earlier answers stay readable. Scout needs a network connection.", false)) }
                if (intro != null) {
                    Column(Modifier.weight(1f).padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
                        Text("Scout", style = MaterialTheme.typography.headlineMedium)
                        Text(intro.text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                    intro.suggestions.forEach { q ->
                        Row(Modifier.fillMaxWidth().clickable { a.suggest(q) }.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(cs.primary))
                            Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp))
                        }
                    }
                } else LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    itemsIndexed(s.items, key = { index, _ -> s.keys.getOrElse(index) { "item:$index" } }) { _, it -> Item(it, a, ui) }
                    if (s.running) item { Running(s, a) }
                    // Target a trailing item so tall answers scroll to their end, not their beginning.
                    item(key = "scout-latest") { Spacer(Modifier.height(1.dp)) }
                }
                if (s.sessionFile.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { a.usage(s.sessionFile) }
                        .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.usage, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("›", color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
                if (s.context.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Note in context: " + s.context, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { a.clearContext() }) { Text("Remove") }
                }
                if (!atBottom && intro == null) TextButton(onClick = { follow = true; scope.launch { if (count > 0) list.scrollToItem(count) } }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Jump to latest") }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.research) TextButton(onClick = { s.research(false); a.optionsChanged() }) { Text("Research ×") }
                    if (s.web) TextButton(onClick = { s.web(false); a.optionsChanged() }) { Text("Vault + web ×") }
                }
                Composer(s, a)

            }
            AnimatedVisibility(s.drawer, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { a.drawer(false) } })
            }
            AnimatedVisibility(s.drawer, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) { ChatsDrawer(s, a) }
        }
    }
}

@Composable
private fun Composer(s: AskState, a: AskActions) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val send = { if (s.input.isNotBlank() && !s.offline) a.ask(s.input.trim()) }
    val editor: @Composable (Boolean) -> Unit = { expanded ->
        var field by remember(s.sessionFile, expanded) { mutableStateOf(TextFieldValue(s.input, TextRange(s.caret))) }
        LaunchedEffect(s.input) { if (field.text != s.input) field = TextFieldValue(s.input, TextRange(s.caret)) }
        OutlinedTextField(value = field, onValueChange = {
            if (it.text.length <= 32000) { field = it; s.input(it.text); s.caret(it.selection.end); a.draft(it.text) }
        }, placeholder = { Text(if (s.running) "Send direction…" else "Ask Scout") }, maxLines = if (expanded) Int.MAX_VALUE else 6,
            shape = RoundedCornerShape(24.dp), textStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = cs.surfaceContainerHigh, unfocusedContainerColor = cs.surfaceContainerHigh,
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = if (expanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth().heightIn(max = 210.dp))
    }
    Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
        Box {
            TextButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Add context and options" }) { Text("+", fontSize = 24.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Add note") }, onClick = { menu = false; a.attachNote() })
                DropdownMenuItem(text = { Text("Add quote / capture") }, onClick = { menu = false; a.attachQuote() })
                DropdownMenuItem(text = { Text(if (s.research) "Research: on" else "Research") }, onClick = { menu = false; s.research(!s.research); a.optionsChanged() })
                DropdownMenuItem(text = { Text(if (s.web) "Sources: vault + web" else "Sources: vault only") }, onClick = { menu = false; s.web(!s.web); a.optionsChanged() })
                if (s.running) DropdownMenuItem(text = { Text(if (s.afterAnswer) "Delivery: after answer" else "Delivery: next turn") }, onClick = { menu = false; s.afterAnswer(!s.afterAnswer); a.optionsChanged() })
                DropdownMenuItem(text = { Text("Discover") }, onClick = { menu = false; a.discover() })
                DropdownMenuItem(text = { Text("Expand draft") }, onClick = { menu = false; s.expanded(true); a.optionsChanged() })
            }
        }
        Box(Modifier.weight(1f)) { editor(false) }
        if (s.running) TextButton(onClick = { a.cancel() }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Stop Scout" }) { Text("Stop") }
        if (!s.running || s.input.isNotBlank()) FilledIconButton(onClick = send, enabled = !s.offline && s.input.isNotBlank(), modifier = Modifier.size(48.dp).semantics { contentDescription = if (s.running) "Send direction" else "Send message" }) { Text("↑", fontSize = 22.sp) }
    }
    if (s.expanded) androidx.compose.ui.window.Dialog(onDismissRequest = { s.expanded(false); a.optionsChanged() }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.padding(16.dp).then(Modifier)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Draft", fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { s.expanded(false); a.optionsChanged() }) { Text("Collapse") }
                }
                Box(Modifier.weight(1f)) { editor(true) }
                Row {
                    if (s.running) TextButton(onClick = { a.cancel() }) { Text("Stop") }
                    Button(onClick = { s.expanded(false); a.optionsChanged(); send() }, enabled = !s.offline && s.input.isNotBlank()) { Text(if (s.running) "Send direction" else "Send") }
                }
            }
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
                RoundButton("Search chats", { searching = !searching; if (!searching) a.searchChats("") }) { SearchIcon(cs.onSurface) }
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
                                DropdownMenuItem(text = { Text("Usage") }, onClick = { menu = false; a.usage(c.file) })
                                DropdownMenuItem(text = { Text("Export") }, onClick = { menu = false; a.exportChat(c.file) })
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
private fun Item(it: AskItem, a: AskActions, ui: Ui) {
    val cs = MaterialTheme.colorScheme
    when (it) {
        is QueuedItem -> Column {
            Text(if (it.after) "Queued · after this answer" else "Queued · next turn", fontSize = 13.sp, color = cs.onSurfaceVariant)
            Text(it.text, fontSize = 16.sp, lineHeight = 24.sp)
            Row { TextButton(onClick = { it.edit.run() }) { Text("Edit") }; TextButton(onClick = { it.remove.run() }) { Text("Remove") } }
        }
        is DiscoveryItem -> Column {
            Text(it.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(it.detail, fontSize = 16.sp, lineHeight = 26.sp)
            Row {
                TextButton(onClick = { it.open.run() }) { Text("Open") }
                TextButton(onClick = { it.investigate.run() }) { Text("Investigate") }
                TextButton(onClick = { it.save.run() }) { Text("Save") }
                TextButton(onClick = { it.dismiss.run() }) { Text("Dismiss") }
            }
        }
        is Intro -> Column {
            Text(it.text, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            if (it.suggestions.isNotEmpty()) Text("Try", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            it.suggestions.forEach { q ->
                OutlinedButton(onClick = { a.suggest(q) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(q, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        is Work -> {
            var open by rememberSaveable { mutableStateOf(it.secs == Chat.RUNNING) } // an unfinished turn shows its steps
            Column {
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when { it.secs >= 0 -> "Worked for " + Chat.clock(it.secs); it.secs == Chat.RUNNING -> "Working…"; else -> "Worked" }, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Text(if (open) "  ⌃" else "  ⌄", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                if (open) Column(Modifier.padding(start = 4.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    it.steps.forEach { step -> Text("•  $step", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                }
            }
        }
        is Approval -> Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerHigh, border = androidx.compose.foundation.BorderStroke(1.dp, cs.primary)) {
            Column(Modifier.padding(16.dp)) {
                Text("Scout needs your OK", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                Text(it.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                if (it.detail.isNotEmpty()) Text(it.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerLow).padding(10.dp))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { a.approve(it.id, true) }, enabled = !it.busy, modifier = Modifier.weight(1f)) { Text("Allow") }
                    OutlinedButton(onClick = { a.approve(it.id, false) }, enabled = !it.busy, modifier = Modifier.weight(1f)) { Text("Decline") }
                }
            }
        }
        is Question -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(it.text, fontSize = 16.sp, lineHeight = 26.sp, color = cs.onPrimaryContainer,
                modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(cs.primaryContainer).padding(horizontal = 14.dp, vertical = 10.dp))
        }
        is Answer -> Column {
            Surface(color = cs.background) {
                Column {
                    Markdown(it.text, it.sources, ui)
                }
            }
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            var more by remember { mutableStateOf(false) }
            Row {
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it.raw)) }) { Text("Copy") }
                TextButton(onClick = { it.keep.run() }) { Text("Keep") }
                Box {
                    TextButton(onClick = { more = true }) { Text("More") }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text("Add to topic") }, onClick = { more = false; it.topic.run() })
                        DropdownMenuItem(text = { Text("Make cards from source note") }, onClick = { more = false; it.cards.run() })
                        DropdownMenuItem(text = { Text("Edit and retry") }, onClick = { more = false; it.retry.run() })
                    }
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
            if (it.partial.isNotEmpty()) Text(it.partial, fontSize = 16.sp, lineHeight = 26.sp)
            Text(it.message, style = MaterialTheme.typography.bodyMedium, color = cs.error)
            Button(onClick = { a.retry() }, modifier = Modifier.padding(top = 8.dp)) { Text("Retry") }
        }
        is Stopped -> Column {
            if (it.partial.isNotEmpty()) Text(it.partial, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("Incomplete answer. Earlier evidence remains inspectable.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { a.retry() }) { Text("Ask again") }
            }
        }
    }
}

@Composable
private fun Sources(label: String, refs: List<SourceRef>) {
    if (refs.isEmpty()) return
    var open by rememberSaveable(label, refs.size, refs.first().title) { mutableStateOf(false) }
    Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label · ${refs.size}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (open) "  ⌃" else "  ⌄", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) androidx.compose.ui.window.Dialog(onDismissRequest = { open = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sources · ${refs.size}", fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { open = false }) { Text("Close") }
                }
                var filter by remember { mutableStateOf("All") }
                Row {
                    listOf("All", "Notes", "Web", "Captures").forEach { tab -> TextButton(onClick = { filter = tab }) { Text(tab) } }
                }
                LazyColumn {
                    refs.filter { filter == "All" || filter == "Web" && it.meta.startsWith("Web") || filter == "Captures" && it.meta.contains("capture", true) || filter == "Notes" && !it.meta.startsWith("Web") && !it.meta.contains("capture", true) }.forEach { source ->
                        item { Column(Modifier.fillMaxWidth().clickable { source.onClick.run() }.padding(vertical = 16.dp)) {
                            Text("${source.num} ${source.title}", fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                            Text(source.meta, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                    }
                }
            }
        }
    }
}

/** The answer while it streams: a draft, not yet checked for citations. */
@Composable
private fun Running(s: AskState, a: AskActions) {
    val cs = MaterialTheme.colorScheme
    Column {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        val elapsed by produceState(0L, s.began) { while (true) { value = if (s.began > 0) (System.currentTimeMillis() - s.began) / 1000 else 0; kotlinx.coroutines.delay(1000) } }
        Text(s.status.ifEmpty { "Thinking…" } + (if (s.began > 0) "  ·  " + Chat.clock(elapsed) else ""), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        if (s.live.isNotEmpty()) Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).padding(14.dp)) {
            Text("Partial answer", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(s.live, fontSize = 16.sp, lineHeight = 26.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
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
