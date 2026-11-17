@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package me.venkatesh.saveobsidian

import androidx.compose.material3.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest

class SourceRef(val num: String, val title: String, val meta: String, val onClick: Runnable)

class QueuedItem(val text: String, val after: Boolean, val edit: Runnable, val remove: Runnable) : AskItem
class DiscoveryItem(val title: String, val detail: String, val open: Runnable, val investigate: Runnable, val save: Runnable, val dismiss: Runnable) : AskItem
sealed interface AskItem
data class Question(val text: String) : AskItem
class Answer(val text: String, val singleSource: Boolean, val sources: List<SourceRef>, val raw: String, val keep: Runnable, val topic: Runnable, val cards: Runnable, val retry: Runnable) : AskItem
class NotFound(val near: List<SourceRef>) : AskItem
class Failure @JvmOverloads constructor(val message: String, val partial: String = "") : AskItem
/** A stopped answer: what had arrived is kept, never sent again by itself. */
class Stopped(val partial: String) : AskItem
class Intro(val text: String, val suggestions: List<String>) : AskItem
/** A turn's tool steps, folded under "Worked for m:ss". secs: Chat.UNKNOWN or Chat.RUNNING when there is no time. */
data class Work(val secs: Long, val steps: List<String>) : AskItem
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
    fun suggest(text: String)
    fun attachNote()
    fun attachQuote()
    fun approvalMode()
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

data class AskUiState(
    val afterAnswer: Boolean = false,
    val caret: Int = 0,
    val expanded: Boolean = false,
    val research: Boolean = false,
    val web: Boolean = true,
    val fullAccess: Boolean = false,
    val keys: List<String> = emptyList(),
    val usage: String = "",
    val sessionFile: String = "",
    val items: List<AskItem> = emptyList(),
    val running: Boolean = false,
    val status: String = "",
    val live: String = "",
    val input: String = "",
    val subtitle: String = "",
    val offline: Boolean = false,
    val title: String = "",
    val context: String = "",
    val began: Long = 0L,
    val drawer: Boolean = false,
    val chats: List<ChatRow> = emptyList(),
    val query: String = "",
    val toolNotice: String = "",
    val dialog: AskDialog? = null
) {
    /** Streaming text belongs to its leaf, not the history/composer layout. */
    fun chrome() = if (live.isEmpty()) this else copy(live = "")
}

/** Per-chat position and user intent survive layout changes and navigation. */
class ChatScrollPosition(var index: Int = 0, var offset: Int = 0) {
    private val follow = MutableStateFlow(true)
    val following = follow.asStateFlow()
    val followLatest: Boolean get() = follow.value
    private var userScrolling = false
    fun scrollChanged(scrolling: Boolean, dragging: Boolean, atBottom: Boolean) {
        if (dragging) { userScrolling = true; follow.value = false }
        else if (userScrolling && !scrolling) { follow.value = atBottom; userScrolling = false }
    }
    fun jumpToLatest() { follow.value = true }
    fun restoreOffset(itemIndex: Int, itemSize: Int): Int? = if (itemIndex == index && itemSize > offset) offset else null
    fun shouldFollow(hasContent: Boolean, scrolling: Boolean, atBottom: Boolean) = followLatest && hasContent && !scrolling && !atBottom
}

/** Java actions publish immutable snapshots; Compose only reads the flow. */
class AskState {
    private val mutable = MutableStateFlow(AskUiState())
    val state = mutable.asStateFlow()
    val scrollPositions = mutableMapOf<String, ChatScrollPosition>()
    val afterAnswer: Boolean get() = mutable.value.afterAnswer
    val caret: Int get() = mutable.value.caret
    val expanded: Boolean get() = mutable.value.expanded
    val research: Boolean get() = mutable.value.research
    val fullAccess: Boolean get() = mutable.value.fullAccess
    val web: Boolean get() = mutable.value.web
    val keys: List<String> get() = mutable.value.keys
    val usage: String get() = mutable.value.usage
    val sessionFile: String get() = mutable.value.sessionFile
    val items: List<AskItem> get() = mutable.value.items
    val running: Boolean get() = mutable.value.running
    val status: String get() = mutable.value.status
    val live: String get() = mutable.value.live
    val input: String get() = mutable.value.input
    val subtitle: String get() = mutable.value.subtitle
    val offline: Boolean get() = mutable.value.offline
    val title: String get() = mutable.value.title
    val context: String get() = mutable.value.context
    val began: Long get() = mutable.value.began
    val drawer: Boolean get() = mutable.value.drawer
    val chats: List<ChatRow> get() = mutable.value.chats
    val query: String get() = mutable.value.query
    fun afterAnswer(value: Boolean) { mutable.update { it.copy(afterAnswer = value) } }
    fun expanded(value: Boolean) { mutable.update { it.copy(expanded = value) } }
    fun research(value: Boolean) { mutable.update { it.copy(research = value) } }
    fun fullAccess(value: Boolean) { mutable.update { it.copy(fullAccess = value) } }
    fun web(value: Boolean) { mutable.update { it.copy(web = value) } }
    fun status(value: String) { mutable.update { it.copy(status = value) } }
    fun live(value: String) { mutable.update { it.copy(live = value) } }
    fun title(value: String) { mutable.update { it.copy(title = value) } }
    fun context(value: String) { mutable.update { it.copy(context = value) } }
    fun began(value: Long) { mutable.update { it.copy(began = value) } }
    fun chats(value: List<ChatRow>) { mutable.update { it.copy(chats = value.toList()) } }
    fun query(value: String) { mutable.update { it.copy(query = value) } }
    fun toolNotice(text: String) { mutable.update { it.copy(toolNotice = text) } }
    fun dialog(request: AskDialog?) { mutable.update { it.copy(dialog = request) } }
    fun caret(at: Int) { mutable.update { it.copy(caret = at.coerceIn(0, it.input.length)) } }
    fun input(text: String) { mutable.update { it.copy(input = text, caret = it.caret.coerceAtMost(text.length)) } }
    fun session(file: String) { mutable.update { it.copy(sessionFile = file) } }
    fun usage(file: String, text: String) { mutable.update { it.copy(sessionFile = file, usage = text) } }
    fun publish(items: List<AskItem>, keys: List<String>) { mutable.update { it.copy(items = items.toList(), keys = keys.toList()) } }
    fun running(on: Boolean) { mutable.update { it.copy(running = on, status = if (on) it.status else "", live = if (on) it.live else "") } }
    fun drawer(on: Boolean) { mutable.update { it.copy(drawer = on, query = if (on) it.query else "") } }
    fun header(subtitle: String, offline: Boolean) { mutable.update { it.copy(subtitle = subtitle, offline = offline) } }
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

@Composable
private fun AskUi(store: AskState, a: AskActions, ui: Ui) {
    val chrome = remember(store) { store.state.map { it.chrome() }.distinctUntilChanged() }
    val initial = remember(store) { store.state.value.chrome() }
    val s by chrome.collectAsState(initial = initial)
    s.dialog?.let { ScoutDialog(it) { store.dialog(null) } }
    val drawer = rememberDrawerState(if (s.drawer) DrawerValue.Open else DrawerValue.Closed)
    LaunchedEffect(s.drawer) { if (s.drawer) drawer.open() else drawer.close() }
    LaunchedEffect(drawer) { snapshotFlow { drawer.currentValue }.collect { a.drawer(it == DrawerValue.Open) } }
    val savedScroll = remember(store, s.sessionFile) { store.scrollPositions.getOrPut(s.sessionFile) { ChatScrollPosition() } }
    val list = androidx.compose.runtime.key(s.sessionFile) { rememberLazyListState(savedScroll.index, savedScroll.offset) }
    androidx.compose.runtime.DisposableEffect(s.sessionFile, list) {
        onDispose { savedScroll.index = list.firstVisibleItemIndex; savedScroll.offset = list.firstVisibleItemScrollOffset }
    }
    val scope = rememberCoroutineScope()
    val atBottom by remember(list) { derivedStateOf { !list.canScrollForward } }
    val following by savedScroll.following.collectAsState()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(list, list.isScrollInProgress, dragging, atBottom) { savedScroll.scrollChanged(list.isScrollInProgress, dragging, atBottom) }
    LaunchedEffect(list, savedScroll) {
        var restoring = !savedScroll.followLatest && (savedScroll.index > 0 || savedScroll.offset > 0)
        // Observe actual measurement, including late Markdown and keyboard/viewport changes.
        snapshotFlow { list.layoutInfo to Triple(list.isScrollInProgress, following, dragging) }.collectLatest { (layout, _) ->
            if (dragging) restoring = false
            if (layout.totalItemsCount == 0 || list.isScrollInProgress) return@collectLatest
            if (restoring) {
                val target = savedScroll.index.coerceAtMost(layout.totalItemsCount - 1)
                val measured = layout.visibleItemsInfo.firstOrNull { it.index == target }
                if (target != savedScroll.index) { restoring = false; list.scrollToItem(target) }
                else if (measured == null) list.scrollToItem(target)
                else savedScroll.restoreOffset(measured.index, measured.size)?.let { offset ->
                    restoring = false
                    list.scrollToItem(target, offset)
                }
            } else if (savedScroll.shouldFollow(true, false, !list.canScrollForward))
                list.scrollToItem(layout.totalItemsCount - 1)
        }
    }
    val cs = MaterialTheme.colorScheme
    val intro = s.items.singleOrNull() as? Intro
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(drawerState = drawer, windowInsets = WindowInsets(0, 0, 0, 0)) { ChatsDrawer(s, a) }
    }) {
    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Box(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton("Open chats", { a.drawer(true) }) { Icon(painterResource(R.drawable.ic_menu), null, tint = cs.onSurface) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.title.ifEmpty { "Scout" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.subtitle.isNotEmpty()) Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    RoundButton("New chat", { a.newChat() }) { Icon(painterResource(R.drawable.ic_note_plus), null, tint = cs.onSurface) }
                }
                if (s.offline) Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) { BlockUi(Banner("You are offline. Earlier answers stay readable. Scout needs a network connection.", false)) }
                if (s.toolNotice.isNotEmpty()) Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { BlockUi(Banner(s.toolNotice, true)) }
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
                } else Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (s.items.isNotEmpty() || s.running) LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        itemsIndexed(s.items, key = { index, _ -> s.keys.getOrElse(index) { "item:$index" } }, contentType = { _, item -> item.javaClass }) { _, it -> Item(it, a, ui) }
                        if (s.running) item(key = "scout-running", contentType = "running") { Running(s.status, s.began, store) }
                        // Target a trailing item so tall answers scroll to their end, not their beginning.
                        item(key = "scout-latest") { Spacer(Modifier.height(1.dp)) }
                    }
                    if (!atBottom) FilledTonalIconButton(
                        onClick = { scope.launch { list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)); savedScroll.jumpToLatest() } },
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(48.dp)
                    ) { Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = "Jump to latest") }
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
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.research) TextButton(onClick = { store.research(false) }) { Text("Research ×") }
                }
                Composer(s, store, a)

            }
        }
    }
    }
}

@Composable
private fun Composer(s: AskUiState, store: AskState, a: AskActions) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val send = { if (s.input.isNotBlank() && !s.offline) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); a.ask(s.input.trim()) } }
    val editor: @Composable (Boolean) -> Unit = { expanded ->
        var field by rememberSaveable(s.sessionFile, expanded, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(s.input, TextRange(s.caret))) }
        LaunchedEffect(s.input) { if (field.text != s.input) field = TextFieldValue(s.input, TextRange(s.caret)) }
        OutlinedTextField(value = field, onValueChange = {
            if (it.text.length <= 32000) { field = it; store.input(it.text); store.caret(it.selection.end); a.draft(it.text) }
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
                DropdownMenuItem(text = { Text(if (s.research) "Research: on" else "Research") }, onClick = { menu = false; store.research(!s.research) })
                DropdownMenuItem(text = { Text(if (s.web) "Sources: vault + web" else "Sources: vault only") }, enabled = !s.running, onClick = { menu = false; store.web(!s.web) })
                DropdownMenuItem(text = { Text("Approvals: " + if (s.fullAccess) "Full access" else "Ask for approval") }, enabled = !s.running, onClick = { menu = false; a.approvalMode() })
                if (s.running) DropdownMenuItem(text = { Text(if (s.afterAnswer) "Delivery: after answer" else "Delivery: next turn") }, onClick = { menu = false; store.afterAnswer(!s.afterAnswer) })
                DropdownMenuItem(text = { Text("Discover") }, onClick = { menu = false; a.discover() })
                DropdownMenuItem(text = { Text("Expand draft") }, onClick = { menu = false; store.expanded(true) })
            }
        }
        Box(Modifier.weight(1f)) { editor(false) }
        if (s.running) TextButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); a.cancel() }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Stop Scout" }) { Text("Stop") }
        if (!s.running || s.input.isNotBlank()) FilledIconButton(onClick = send, enabled = !s.offline && s.input.isNotBlank(), modifier = Modifier.size(48.dp).semantics { contentDescription = if (s.running) "Send direction" else "Send message" }) { Text("↑", fontSize = 22.sp) }
    }
    if (s.expanded) ModalBottomSheet(onDismissRequest = { store.expanded(false) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Draft", fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { store.expanded(false) }) { Text("Collapse") }
                }
                Box(Modifier.weight(1f)) { editor(true) }
                Row {
                    if (s.running) TextButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); a.cancel() }) { Text("Stop") }
                    Button(onClick = { store.expanded(false); send() }, enabled = !s.offline && s.input.isNotBlank()) { Text(if (s.running) "Send direction" else "Send") }
                }
            }
        }
    }
}

@Composable
private fun ChatsDrawer(s: AskUiState, a: AskActions) {
    val cs = MaterialTheme.colorScheme
    var searching by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxHeight().width(320.dp), color = cs.surfaceContainerLow, shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chats", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                RoundButton("Search chats", { searching = !searching; if (!searching) a.searchChats("") }) { Icon(painterResource(R.drawable.ic_search), null, tint = cs.onSurface) }
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
                            TextButton(onClick = { menu = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Chat options for ${c.title}" }) { Text("⋯", style = MaterialTheme.typography.titleLarge, color = cs.onSurfaceVariant) }
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
    val haptic = LocalHapticFeedback.current
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
                Row(Modifier.clip(RoundedCornerShape(8.dp)).sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); a.approve(it.id, true) }, enabled = !it.busy, modifier = Modifier.weight(1f)) { Text("Allow") }
                    OutlinedButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); a.approve(it.id, false) }, enabled = !it.busy, modifier = Modifier.weight(1f)) { Text("Decline") }
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
                TextButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); it.keep.run() }) { Text("Keep") }
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
    Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label · ${refs.size}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (open) "  ⌃" else "  ⌄", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
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
private fun Running(status: String, began: Long, store: AskState) {
    val cs = MaterialTheme.colorScheme
    Column {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        RunningStatus(status, began)
        LiveAnswer(store)
    }
}

@Composable
private fun LiveAnswer(store: AskState) {
    val cs = MaterialTheme.colorScheme
    val updates = remember(store) { store.state.map { it.live }.distinctUntilChanged() }
    val live by updates.collectAsState(initial = store.live)
    if (live.isNotEmpty()) Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).padding(14.dp)) {
            Text("Partial answer", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(live, fontSize = 16.sp, lineHeight = 26.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
}

@Composable
private fun RunningStatus(status: String, began: Long) {
    Row(Modifier.padding(top = 8.dp)) {
        Text(status.ifEmpty { "Thinking…" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        RunningClock(began)
    }
}

@Composable
private fun RunningClock(began: Long) {
    val elapsed by produceState(0L, began) { while (true) { value = if (began > 0) (System.currentTimeMillis() - began) / 1000 else 0; kotlinx.coroutines.delay(1000) } }
    if (began > 0) Text("  ·  " + Chat.clock(elapsed), style = MaterialTheme.typography.bodySmall)
}
