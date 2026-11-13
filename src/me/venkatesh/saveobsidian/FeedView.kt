package me.venkatesh.saveobsidian

import android.content.Context
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** One row of the Feed. Rows are immutable data: when a capture changes step only its row differs, so only that row recomposes. */
sealed interface FeedRow { val key: String }
data class SparkRow(val cards: Int, val minutes: Int) : FeedRow { override val key get() = "spark" }
data class HeadingRow(override val key: String, val text: String) : FeedRow
data class AttentionRow(override val key: String, val label: String, val error: String, val ago: String) : FeedRow
/** stage is the step index (−1 when not processing), queued when waiting its turn, seconds on the current step. */
data class ItemRow(
    override val key: String, val item: Feed.Item, val stage: Int, val queued: Boolean, val seconds: Int,
    val problem: String, val kept: Boolean, val canRetry: Boolean, val canEdit: Boolean,
) : FeedRow
data class EmptyRow(val vaultReady: Boolean) : FeedRow { override val key get() = "empty" }

/** What FeedScreen (Java) does when the owner acts. */
interface FeedActions {
    fun open(item: Feed.Item)
    fun link(url: String)
    fun ask(item: Feed.Item)
    fun retry(item: Feed.Item)
    fun keep(item: Feed.Item)
    fun share(item: Feed.Item)
    fun note(path: String)
    fun spark()
    fun chip(name: String)
    fun menu(which: Int)
    fun capture()
    fun refresh()
    fun retryCapture(key: String)
    fun dismissCapture(key: String)
    fun trash(item: Feed.Item)
    fun lane(item: Feed.Item, lane: String)
    fun emptyAction(vaultReady: Boolean)
    fun thumb(item: Feed.Item): View
}

class FeedState {
    var rows by mutableStateOf(emptyList<FeedRow>()); private set
    var chip by mutableStateOf("All"); private set
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
    val CHIPS = listOf("All", "Craft", "Life", "Sources", "Topics", "People")
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
                                    is SparkRow -> SparkCard(row, a)
                                    is HeadingRow -> Text(row.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                                    is AttentionRow -> AttentionCard(row, a)
                                    is ItemRow -> ItemCard(row, a, state.steps, dark) { sheet = row }
                                    is EmptyRow -> Empty(row, a)
                                }
                            }
                        }
                    }
                }
            }
            FloatingActionButton(onClick = { a.capture() }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("+", style = MaterialTheme.typography.headlineSmall) }
        }
    }
    sheet?.let { row -> ItemSheet(row, a) { sheet = null } }
}

@Composable
private fun Header(subtitle: String, a: FeedActions) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Feed", style = MaterialTheme.typography.headlineSmall)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { open = true }) { Text("☰", style = MaterialTheme.typography.titleLarge) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                listOf("Profile", "Settings", "Browse vault").forEachIndexed { i, s -> DropdownMenuItem(text = { Text(s) }, onClick = { open = false; a.menu(i) }) }
            }
        }
    }
}

@Composable
private fun CardBox(onClick: (() -> Unit)? = null, onLong: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val m = if (onClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLong) else Modifier
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Box(m) { content() }
    }
}

@Composable
private fun SparkCard(r: SparkRow, a: FeedActions) = CardBox(onClick = { a.spark() }) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(sparkTitle(), style = MaterialTheme.typography.titleMedium)
            Text("${r.cards} ${if (r.cards == 1) "card" else "cards"} due, about ${r.minutes} min", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = { a.spark() }) { Text("Start") }
    }
}

@Composable
private fun AttentionCard(r: AttentionRow, a: FeedActions) = CardBox {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("Capture failed", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
            Spacer(Modifier.weight(1f))
            Text(r.ago, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(r.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(r.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { a.retryCapture(r.key) }, modifier = Modifier.weight(1f)) { Text("Retry") }
            OutlinedButton(onClick = { a.dismissCapture(r.key) }, modifier = Modifier.weight(1f)) { Text("Dismiss") }
        }
    }
}

@Composable
private fun Pill(text: String, fg: Color, bg: Color) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = fg, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp))

@Composable
private fun Avatar(seed: String, dark: Boolean) {
    val hsv = floatArrayOf((Math.abs(seed.hashCode()) % 360).toFloat(), if (dark) 0.35f else 0.25f, if (dark) 0.38f else 0.92f)
    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(android.graphics.Color.HSVToColor(hsv))), contentAlignment = Alignment.Center) {
        Text(seed.take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = if (dark) Color(0xFFDDDDDD) else Color(0xFF444444))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCard(r: ItemRow, a: FeedActions, steps: List<String>, dark: Boolean, onLong: () -> Unit) {
    val it = r.item
    val source = it.kind == Feed.SOURCE
    val topic = it.kind == Feed.TOPIC
    CardBox(onClick = { a.open(it) }, onLong = if (r.canEdit || r.canRetry) onLong else null) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val who = if (source) it.sourceName.ifEmpty { "Note" } else if (topic) "Topic" else "Person"
                Avatar(who, dark)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(who, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val whenText = if (topic) "${it.count} new sources" else it.age
                    val line = listOf(whenText, it.lane).filter { s -> s.isNotEmpty() }.joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (source && it.status.isNotEmpty()) {
                    if (it.status == Feed.PROCESSED) Pill(it.status, MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer)
                    else Pill(it.status, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
                }
            }
            Text(it.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            if (it.kind == Feed.PERSON) Small("Mentioned in ${it.count} ${if (it.count == 1) "note" else "notes"}")
            if (it.tldr.isNotEmpty()) Text(it.tldr, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            if (source && it.url.isNotEmpty()) AndroidView(factory = { _ -> a.thumb(it) }, modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(160.dp).clip(RoundedCornerShape(10.dp)))
            if (it.curiosity.isNotEmpty()) Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp)) {
                Text("Curiosity prompt", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it.curiosity, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            }
            if (it.why.isNotEmpty()) Small("Why saved: ${it.why}")
            if (it.waiting.isNotEmpty()) Small(it.waiting)
            if (it.made.isNotEmpty()) Small(it.made)
            if (it.cards > 0) Small("${it.cards} ${if (it.cards == 1) "card" else "cards"}${if (r.kept) ", up first in Spark" else ""}")
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
            Row(Modifier.fillMaxWidth()) {
                if (it.entry == null) Action("Ask", R.drawable.ic_nav_ask) { a.ask(it) }
                else if (r.canRetry) Action("Retry", R.drawable.ic_retry) { a.retry(it) }
                if (it.cards > 0) Action(if (r.kept) "Kept" else "Keep", R.drawable.ic_keep) { a.keep(it) }
                if (it.url.isNotEmpty()) Action("Open", R.drawable.ic_open) { a.link(it.url) }
                Action("Share", R.drawable.ic_share) { a.share(it) }
            }
        }
    }
}

@Composable
private fun Small(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun androidx.compose.foundation.layout.RowScope.Action(label: String, icon: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.weight(1f).heightIn(min = 44.dp), colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** The live checklist, inline on the card being processed. */
@Composable
private fun Progress(r: ItemRow, steps: List<String>) {
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp)) {
        Text(if (r.queued) "Waiting for its turn" else "Processing now", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        steps.forEachIndexed { i, s ->
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
private fun Empty(r: EmptyRow, a: FeedActions) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (r.vaultReady) "Nothing here yet" else "Choose your vault folder", style = MaterialTheme.typography.titleLarge)
        Text(if (r.vaultReady) "Share a link or text to Sparkbox from any app. It shows up here." else "Sparkbox reads Knowledge from your Obsidian vault.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = { a.emptyAction(r.vaultReady) }, modifier = Modifier.padding(top = 16.dp)) { Text(if (r.vaultReady) "Check vault folder now" else "Choose folder") }
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

/** The Spark card's title follows the time of day, so it never says "Morning" at night. */
private fun sparkTitle(): String {
    val hour = java.time.LocalTime.now().hour
    return when { hour in 5..11 -> "Morning Spark"; hour in 12..16 -> "Afternoon Spark"; hour in 17..20 -> "Evening Spark"; else -> "Night Spark" }
}
