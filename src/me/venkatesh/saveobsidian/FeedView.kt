package me.venkatesh.saveobsidian

import android.content.Context
import android.view.View
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** One row of the Feed. Rows are immutable data: when a capture changes step only its row differs, so only that row recomposes. */
sealed interface FeedRow { val key: String }
data class HeadingRow(override val key: String, val text: String) : FeedRow
data class AttentionRow(override val key: String, val label: String, val error: String, val ago: String) : FeedRow
/** stage is the step index (−1 when not processing), queued when waiting its turn, seconds on the current step. */
data class ItemRow(
    override val key: String, val item: Feed.Item, val stage: Int, val queued: Boolean, val seconds: Int,
    val problem: String, val kept: Boolean, val canRetry: Boolean, val canEdit: Boolean,
) : FeedRow
data class EmptyRow(val vaultReady: Boolean) : FeedRow { override val key get() = "empty" }
/** One search result. */
data class Hit(val path: String, val kind: String, val lane: String, val age: String, val title: String, val meta: String, val snippet: String)
data class HitRow(val hit: Hit) : FeedRow { override val key get() = "hit:" + hit.path }
data class NoHitsRow(val query: String) : FeedRow { override val key get() = "nohits" }

/** What FeedScreen (Java) does when the owner acts. */
interface FeedActions {
    fun open(item: Feed.Item)
    fun link(url: String)
    fun ask(item: Feed.Item)
    fun retry(item: Feed.Item)
    fun edit(item: Feed.Item)
    fun cards(item: Feed.Item)
    fun keep(item: Feed.Item)
    fun share(item: Feed.Item)
    fun note(path: String)
    fun query(q: String)
    fun askScout(q: String)
    fun chip(name: String)
    fun menu(which: Int)
    fun capture()
    fun refresh()
    fun retryCapture(key: String)
    fun destinationCapture(key: String)
    fun dismissCapture(key: String)
    fun trash(item: Feed.Item)
    fun lane(item: Feed.Item, lane: String)
    fun emptyAction(vaultReady: Boolean)
    fun thumb(item: Feed.Item): View
}

class FeedState {
    var rows by mutableStateOf(emptyList<FeedRow>()); private set
    var chip by mutableStateOf("All"); private set
    var query by mutableStateOf(""); private set
    fun typed(q: String) { query = q }
    var subtitle by mutableStateOf(""); private set
    var offline by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    var steps: List<String> = emptyList()
    var top by mutableStateOf(0); private set
    fun publish(rows: List<FeedRow>, chip: String, subtitle: String, offline: Boolean) { this.rows = rows; this.chip = chip; this.subtitle = subtitle; this.offline = offline }
    fun refreshing(on: Boolean) { refreshing = on }
    /** Ask the list to jump to the top (a new filter). */
    fun toTop() { top++ }
}

object FeedView {
    val CHIPS = listOf("All", "Kept", "Craft", "Life", "Sources", "Topics", "People")
    @JvmStatic fun create(c: Context, ui: Ui, state: FeedState, a: FeedActions): View = ComposeView(c).apply {
        setContent { SparkTheme(ui) { FeedScreenUi(state, a, ui.dark) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedScreenUi(state: FeedState, a: FeedActions, dark: Boolean) {
    var sheet by remember { mutableStateOf<ItemRow?>(null) }
    val list = rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(state.top) { if (state.top > 0) list.scrollToItem(0) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box {
            Column {
                Header(state.subtitle, a)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (state.offline) Text("Offline. Captures are kept and processed when you are back online.", style = MaterialTheme.typography.bodyMedium, color = LocalStatus.current.warn,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LocalStatus.current.warnBg).padding(12.dp))
                SearchField(state, a)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FeedView.CHIPS, key = { it }) { name ->
                        FilterChip(selected = name == state.chip, onClick = { a.chip(name) }, label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
                    }
                }
                PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { a.refresh() }, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                        items(state.rows, key = { it.key }, contentType = { it::class }) { row ->
                            Box(Modifier.animateItem()) {
                                when (row) {
                                    is HeadingRow -> Text(row.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                                    is AttentionRow -> AttentionCard(row, a)
                                    is ItemRow -> ItemCard(row, a, state.steps, dark) { sheet = row }
                                    is EmptyRow -> Empty(row, state.chip, a)
                                    is HitRow -> HitCard(row.hit, state.query, dark, a)
                                    is NoHitsRow -> NoHits(row, a)
                                }
                            }
                        }
                    }
                }
            }
            val inlineCapture = state.chip == "All" && state.rows.any { it is EmptyRow && it.vaultReady }
            if (!inlineCapture) ExtendedFloatingActionButton(onClick = { a.capture() }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("Capture") }
        }
    }
    sheet?.let { row -> ItemSheet(row, a) { sheet = null } }
}

@Composable
private fun SearchField(state: FeedState, a: FeedActions) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value = state.query, onValueChange = { state.typed(it); a.query(it.trim()) }, singleLine = true, shape = RoundedCornerShape(24.dp),
        placeholder = { Text("Search notes, topics, people") }, textStyle = MaterialTheme.typography.bodyLarge,
        trailingIcon = { if (state.query.isNotEmpty()) TextButton(onClick = { state.typed(""); a.query("") }) { Text("\u2715") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp))
}

@Composable
private fun NoHits(r: NoHitsRow, a: FeedActions) {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No results", style = MaterialTheme.typography.titleLarge)
        Text("Nothing matches. Try another word or filter, or ask your vault.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = { a.askScout(r.query) }, modifier = Modifier.padding(top = 16.dp)) { Text("Ask Scout") }
    }
}

/** A search result, in the same card as the feed. */
@Composable
private fun HitCard(h: Hit, query: String, dark: Boolean, a: FeedActions) {
    val cs = MaterialTheme.colorScheme
    CardBox(tint = calmTint(h.lane, dark), onClick = { a.note(h.path) }) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToneChip(Tag(h.kind, 0))
                if (h.lane.isNotEmpty()) ToneChip(Tag(h.lane, 0))
                Spacer(Modifier.weight(1f))
                if (h.age.isNotEmpty()) Text(h.age, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Text(h.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            if (h.meta.isNotEmpty()) Text(h.meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            if (h.snippet.isNotEmpty()) Text(emphasis(h.snippet, query), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            val note = Feed.Item().also { it.path = h.path; it.title = h.title; it.kept = CardStore.kept().contains(h.path) }
            Row {
                TextButton(onClick = { a.edit(note) }) { Text("Edit") }
                TextButton(onClick = { a.keep(note) }) { Text(if (note.kept) "Unkeep" else "Keep") }
                TextButton(onClick = { a.cards(note) }) { Text("Cards") }
            }
        }
    }
}

/** The matching words in semi-bold. */
private fun emphasis(snippet: String, query: String) = buildAnnotatedString {
    append(snippet)
    val low = snippet.lowercase()
    for (w in query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
        var at = low.indexOf(w)
        while (at >= 0) { addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), at, at + w.length); at = low.indexOf(w, at + w.length) }
    }
}

/** A calm card tint: the same hue idea as the Spark topic tiles, but nearly grey. Craft leans green, Life leans warm. */
private fun calmTint(lane: String, dark: Boolean): Color {
    val hue = when (lane) { "Craft" -> 165f; "Life" -> 32f; else -> 215f } // no lane: neutral slate, never a random colour
    return Color.hsv(hue, if (dark) 0.14f else 0.08f, if (dark) 0.17f else 0.975f)
}

@Composable
private fun Header(subtitle: String, a: FeedActions) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Library", style = MaterialTheme.typography.headlineSmall)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { a.menu(2) }) { Text("Browse") }
        Box {
            IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_menu), contentDescription = "Library options") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                listOf(1 to "Settings", 3 to "Dashboard").forEach { (i, s) -> DropdownMenuItem(text = { Text(s) }, onClick = { open = false; a.menu(i) }) }
            }
        }
    }
}

@Composable
private fun CardBox(tint: Color? = null, onClick: (() -> Unit)? = null, onLong: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val m = if (onClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLong) else Modifier
    Surface(shape = RoundedCornerShape(20.dp), color = tint ?: MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Box(m) { content() }
    }
}

@Composable
private fun AttentionCard(r: AttentionRow, a: FeedActions) = CardBox {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("Pending capture", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
            Spacer(Modifier.weight(1f))
            Text(r.ago, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(r.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(r.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { a.retryCapture(r.key) }, modifier = Modifier.weight(1f)) { Text("Retry") }
            OutlinedButton(onClick = { a.dismissCapture(r.key) }, modifier = Modifier.weight(1f)) { Text("Dismiss") }
        }
        TextButton(onClick = { a.destinationCapture(r.key) }) { Text("Change destination / title")
        }
    }
}

@Composable
private fun Pill(text: String, fg: Color, bg: Color) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = fg, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCard(r: ItemRow, a: FeedActions, steps: List<String>, dark: Boolean, onLong: () -> Unit) {
    val it = r.item
    val source = it.kind == Feed.SOURCE
    val topic = it.kind == Feed.TOPIC
    CardBox(tint = calmTint(it.lane, dark), onClick = { a.open(it) }, onLong = if (r.canEdit || r.canRetry) onLong else null) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(it.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (r.canEdit || r.canRetry) IconButton(onClick = onLong) {
                    Text("⋮", modifier = Modifier.semantics { contentDescription = "Note actions" }, style = MaterialTheme.typography.headlineSmall)
                }
            }
            val who = if (source) it.sourceName.ifEmpty { "Note" } else if (topic) "Topic" else "Person"
            val whenText = if (topic && it.count > 0) "${it.count} new sources" else if (topic) "Personal note" else it.age
            Text(listOf(who, whenText, it.lane).filter { part -> part.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (source && it.status.isNotEmpty()) Text(it.status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (it.why.isNotEmpty()) {
                Column(Modifier.padding(top = 12.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp)) {
                    Text("Why I saved it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it.why, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (it.kind == Feed.PERSON) Small("Mentioned in ${it.count} ${if (it.count == 1) "note" else "notes"}")
            if (it.tldr.isNotEmpty()) Text(it.tldr, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            if (source && it.url.isNotEmpty()) AndroidView(factory = { _ -> a.thumb(it) }, modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(160.dp).clip(RoundedCornerShape(14.dp)))
            if (it.curiosity.isNotEmpty()) Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp)) {
                Text("Curiosity prompt", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it.curiosity, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            }
            if (it.waiting.isNotEmpty()) Small(it.waiting)
            if (it.made.isNotEmpty()) Small(it.made)
            if (it.cards > 0) Small("${it.cards} ${if (it.cards == 1) "card" else "cards"}")
            if (r.stage >= 0 || r.queued) Progress(r, steps)
            if (r.problem.isNotEmpty()) Text(r.problem, style = MaterialTheme.typography.bodyMedium, color = LocalStatus.current.warn,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LocalStatus.current.warnBg).padding(12.dp))
            if (it.related.isNotEmpty()) Column(Modifier.padding(top = 10.dp)) {
                Text("Related", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                it.related.forEach { rel ->
                    TextButton(onClick = { a.note(rel[1]) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 36.dp)) {
                        Text(rel[0], maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 2 else 4
            ) {
                if (it.entry == null) Action("Ask", R.drawable.ic_nav_ask) { a.ask(it) }
                else if (r.canRetry) Action("Retry", R.drawable.ic_retry) { a.retry(it) }
                if (it.entry == null && it.path.isNotEmpty()) Action(if (r.kept) "Kept" else "Keep", R.drawable.ic_keep) { a.keep(it) }
                if (it.url.isNotEmpty()) Action("Open", R.drawable.ic_open) { a.link(it.url) }
                Action("Share", R.drawable.ic_share) { a.share(it) }
            }
        }
    }
}

@Composable
private fun Small(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.Action(label: String, icon: Int, onClick: () -> Unit) {
    // Small side padding: with four actions on a phone, the default 24dp left no room and "Open" and "Share" wrapped letter by letter.
    TextButton(onClick = onClick, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 2.dp), colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
    }
}

/** The live checklist, inline on the card being processed. */
@Composable
private fun Progress(r: ItemRow, steps: List<String>) {
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp)) {
        var expanded by remember { mutableStateOf(false) }
        Text(if (r.queued) "Waiting to process" else steps.getOrNull(r.stage) ?: "Processing", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Show details") }
        if (expanded) steps.forEachIndexed { i, s ->
            val done = (!r.queued && i < r.stage) || i == 0
            val active = !r.queued && i == r.stage
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (done) "✓" else if (active) "●" else "○", color = if (done || active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(22.dp))
                Text(s + if (active) "…  ${r.seconds}s" else "", style = MaterialTheme.typography.bodyMedium,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (active) FontWeight.Medium else null)
            }
        }
    }
}

@Composable
private fun Empty(r: EmptyRow, filter: String, a: FeedActions) {
    val filtered = r.vaultReady && filter != "All"
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.Start) {
        Text(when {
            !r.vaultReady -> "Connect your notes"
            filtered -> "No notes in this filter"
            else -> "Capture your first idea"
        }, style = MaterialTheme.typography.headlineSmall)
        Text(when {
            !r.vaultReady -> "Choose your Obsidian folder. Allow folder access so Sparkbox can read your notes and save new ones there."
            filtered -> "Choose All to see the rest of your library."
            else -> "Write a thought here or share a link to Sparkbox from another app."
        }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = {
            if (filtered) a.chip("All") else a.emptyAction(r.vaultReady)
        }, modifier = Modifier.padding(top = 16.dp)) { Text(if (filtered) "Show all notes" else if (r.vaultReady) "Capture" else "Choose folder") }
    }
}

/** Long-press: what can be done to this post. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemSheet(r: ItemRow, a: FeedActions, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(r.item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (r.canRetry) SheetItem("Process again", "Read the page and file it again") { close(); a.retry(r.item) }
            if (r.canEdit) {
                SheetItem("Edit note", "Full Markdown, including frontmatter") { close(); a.edit(r.item) }
                SheetItem("Cards", "Add, edit or delete recall cards") { close(); a.cards(r.item) }
                SheetItem(if (r.kept) "Unkeep" else "Keep", "Bookmark this note") { close(); a.keep(r.item) }
            }
            if (r.canEdit && r.item.kind == Feed.SOURCE) {
                val other = if (r.item.lane == Lane.LIFE) Lane.CRAFT else Lane.LIFE
                SheetItem("Move to $other", "Changes the lane in the note") { close(); a.lane(r.item, other) }
                SheetItem("Move to trash", "To the vault's .trash folder; Obsidian can restore it", danger = true) { close(); a.trash(r.item) }
            }
        }
    }
}

@Composable
private fun SheetItem(title: String, sub: String, danger: Boolean = false, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
        supportingContent = { Text(sub) }, modifier = Modifier.combinedClickable(onClick = onClick))
}
