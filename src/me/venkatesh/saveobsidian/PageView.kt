package me.venkatesh.saveobsidian

import android.content.Context
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Where a piece of text came from, shown as a small tag so the owner can tell them apart. */
object Origin { const val NONE = 0; const val SOURCE = 1; const val AI = 2; const val YOU = 3 }

/** Chip tones: 0 neutral, 1 green, 2 warning, 3 error. */
class Tag(val text: String, val tone: Int)
class Nav @JvmOverloads constructor(val title: String, val sub: String = "", val meta: String = "", val isNew: Boolean = false, val onClick: Runnable? = null)
fun interface Saver { fun save(text: String, done: java.util.function.Consumer<String>) }

/** The building blocks of a detail page. Java screens build a list of these; PageView draws them. */
sealed interface Block
class Tags(val tags: List<Tag>, val trailing: String = "") : Block
class Title(val text: String) : Block
class Body @JvmOverloads constructor(val text: String, val muted: Boolean = false, val italic: Boolean = false) : Block
class Link(val text: String, val onClick: Runnable) : Block
class Banner(val text: String, val bad: Boolean) : Block
class Panel(val label: String, val text: String, val origin: Int) : Block
class Heading @JvmOverloads constructor(val text: String, val origin: Int = Origin.NONE, val onClick: Runnable? = null) : Block
class Bullet(val text: String, val low: Boolean) : Block
class Group(val rows: List<Nav>) : Block
class Primary(val text: String, val onClick: Runnable) : Block
class Act(val label: String, val onClick: Runnable)
/** A card in a list: tags, a bold first line, body, a quiet note, and text actions under it. */
class Entry(val tags: List<Tag>, val title: String, val body: String, val note: String, val acts: List<Act>) : Block
/** A text field. Typing reports through onChange; publishing a new value (a chip tap) replaces the text. chips add a prefix when tapped. */
class Field @JvmOverloads constructor(val label: String, val value: String, val minLines: Int, val chips: List<Act>, val onChange: java.util.function.Consumer<String>, val hint: String = "") : Block
class Meter(val done: Int, val total: Int) : Block
/** A View drawn inside the page (a link preview). */
class Picture(val height: Int, val make: java.util.function.Supplier<View>) : Block
class Buttons(val acts: List<Act>) : Block
/** Chips that open something (a note's topics). */
class Chips(val acts: List<Act>) : Block
class Tabs(val labels: List<String>, val selected: Int, val onSelect: java.util.function.IntConsumer) : Block
/** A View that sizes itself (rendered Markdown). Made again on every publish. */
class Rich(val make: java.util.function.Supplier<View>) : Block
/** A big avatar letter with a title and subtitle, for profile-style headers. */
class Hero(val letter: String, val title: String, val sub: String) : Block
class Danger(val text: String, val onClick: Runnable) : Block
/** An editable section of the note. draft survives redraws through onChange; Save shows only when the text differs from saved. */
class Editor(val label: String, val saved: String, val draft: String, val hint: String, val onChange: java.util.function.Consumer<String>, val saver: Saver) : Block

class PageState {
    var blocks by mutableStateOf(emptyList<Block>()); private set
    var title by mutableStateOf(""); private set
    fun publish(b: List<Block>) { blocks = b }
    fun publish(title: String, b: List<Block>) { this.title = title; blocks = b }
}

object PageView {
    @JvmStatic fun create(c: Context, ui: Ui, title: String, state: PageState, back: Runnable): View = ComposeView(c).apply {
        setContent { SparkTheme(ui) { Page(title, state, back) } }
    }
}

@Composable
fun TopBar(title: String, back: Runnable?, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) IconButton(onClick = { back.run() }) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
        else Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        actions()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Page(title: String, state: PageState, back: Runnable) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            TopBar(state.title.ifEmpty { title }, back)
            LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(state.blocks, contentType = { _, b -> b::class }) { _, b -> BlockUi(b) }
            }
        }
    }
}

@Composable
fun ToneChip(t: Tag) {
    val cs = MaterialTheme.colorScheme
    val (fg, bg) = when (t.tone) {
        1 -> cs.onPrimaryContainer to cs.primaryContainer
        2 -> LocalStatus.current.warn to LocalStatus.current.warnBg
        3 -> cs.error to cs.errorContainer
        else -> cs.onSurfaceVariant to cs.surfaceContainerHigh
    }
    Text(t.text, style = MaterialTheme.typography.labelMedium, color = fg, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun OriginTag(origin: Int) {
    val label = when (origin) { Origin.SOURCE -> "from the source"; Origin.AI -> "AI"; Origin.YOU -> "yours"; else -> return }
    Text(label, style = MaterialTheme.typography.labelMedium,
        color = if (origin == Origin.YOU) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp))
            .background(if (origin == Origin.YOU) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 1.dp))
}

@Composable
fun NavCard(rows: List<Nav>) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            rows.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val m = Modifier.fillMaxWidth().heightIn(min = 52.dp).let { base -> r.onClick?.let { c -> base.clickable { c.run() } } ?: base }
                Row(m.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        if (r.sub.isNotEmpty()) Text(r.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (r.isNew) ToneChip(Tag("New", 1))
                    if (r.meta.isNotEmpty()) Text(r.meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                    if (r.onClick != null) Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
fun BlockUi(b: Block) {
    val cs = MaterialTheme.colorScheme
    when (b) {
        is Tags -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            b.tags.forEach { ToneChip(it) }
            Spacer(Modifier.weight(1f))
            if (b.trailing.isNotEmpty()) Text(b.trailing, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        is Title -> Text(b.text, style = MaterialTheme.typography.headlineSmall)
        is Body -> Text(b.text, style = MaterialTheme.typography.bodyLarge, color = if (b.muted) cs.onSurfaceVariant else cs.onSurface, fontStyle = if (b.italic) FontStyle.Italic else null)
        is Link -> TextButton(onClick = { b.onClick.run() }, contentPadding = PaddingValues(0.dp)) { Text(b.text, style = MaterialTheme.typography.bodyMedium) }
        is Banner -> Text(b.text, style = MaterialTheme.typography.bodyMedium, color = if (b.bad) cs.error else LocalStatus.current.warn,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (b.bad) cs.errorContainer else LocalStatus.current.warnBg).padding(12.dp))
        is Panel -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(b.label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant); OriginTag(b.origin) }
            Text(b.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        }
        is Heading -> Row(Modifier.padding(top = 8.dp).let { m -> b.onClick?.let { c -> m.clickable { c.run() } } ?: m }.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(b.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OriginTag(b.origin)
            if (b.onClick != null) { Spacer(Modifier.weight(1f)); Text("›", style = MaterialTheme.typography.titleLarge, color = cs.onSurfaceVariant) }
        }
        is Bullet -> Row {
            Text("•", style = MaterialTheme.typography.bodyLarge, color = cs.primary, modifier = Modifier.width(16.dp))
            Column {
                Text(b.text, style = MaterialTheme.typography.bodyLarge)
                if (b.low) Row(Modifier.padding(top = 4.dp)) { ToneChip(Tag("low confidence", 2)) }
            }
        }
        is Group -> NavCard(b.rows)
        is Primary -> Button(onClick = { b.onClick.run() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text(b.text) }
        is Danger -> TextButton(onClick = { b.onClick.run() }, modifier = Modifier.fillMaxWidth()) { Text(b.text, color = cs.error) }
        is Editor -> EditorUi(b)
        is Entry -> Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerLow, border = BorderStroke(1.dp, cs.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
                if (b.tags.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) { b.tags.forEach { ToneChip(it) } }
                Text(b.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
                if (b.body.isNotEmpty()) Text(b.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp, end = 8.dp))
                if (b.note.isNotEmpty()) Text(b.note, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, end = 8.dp))
                if (b.acts.isNotEmpty()) Row(Modifier.padding(top = 2.dp)) { b.acts.forEach { a -> TextButton(onClick = { a.onClick.run() }) { Text(a.label) } } }
            }
        }
        is Field -> Column {
            if (b.label.isNotEmpty()) Text(b.label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            if (b.chips.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                b.chips.forEach { c -> androidx.compose.material3.AssistChip(onClick = { c.onClick.run() }, label = { Text(c.label) }) }
            }
            var text by remember(b.value) { mutableStateOf(b.value) }
            OutlinedTextField(value = text, onValueChange = { text = it; b.onChange.accept(it) }, minLines = b.minLines, singleLine = b.minLines == 1,
                placeholder = if (b.hint.isEmpty()) null else ({ Text(b.hint) }), shape = RoundedCornerShape(if (b.minLines == 1) 24.dp else 8.dp), textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        is Meter -> androidx.compose.material3.LinearProgressIndicator(progress = { if (b.total == 0) 0f else b.done.toFloat() / b.total }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
        is Picture -> androidx.compose.runtime.key(b) { androidx.compose.ui.viewinterop.AndroidView(factory = { b.make.get() }, modifier = Modifier.fillMaxWidth().heightIn(min = b.height.dp, max = b.height.dp).clip(RoundedCornerShape(12.dp))) }
        is Rich -> androidx.compose.runtime.key(b) { androidx.compose.ui.viewinterop.AndroidView(factory = { b.make.get() }, modifier = Modifier.fillMaxWidth()) }
        is Chips -> androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(b.acts.size) { i -> androidx.compose.material3.SuggestionChip(onClick = { b.acts[i].onClick.run() }, label = { Text(b.acts[i].label) }) }
        }
        is Tabs -> androidx.compose.material3.PrimaryTabRow(selectedTabIndex = b.selected, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
            b.labels.forEachIndexed { i, l -> androidx.compose.material3.Tab(selected = i == b.selected, onClick = { b.onSelect.accept(i) }, text = { Text(l) }) }
        }
        is Buttons -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            b.acts.forEachIndexed { i, a ->
                if (i == 0) Button(onClick = { a.onClick.run() }, modifier = Modifier.weight(1f)) { Text(a.label) }
                else androidx.compose.material3.OutlinedButton(onClick = { a.onClick.run() }, modifier = Modifier.weight(1f)) { Text(a.label) }
            }
        }
        is Hero -> Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(Modifier.padding(end = 16.dp).clip(RoundedCornerShape(32.dp)).background(cs.primary).padding(horizontal = 22.dp, vertical = 16.dp)) {
                Text(b.letter, style = MaterialTheme.typography.headlineSmall, color = cs.onPrimary)
            }
            Column { Text(b.title, style = MaterialTheme.typography.headlineSmall); Text(b.sub, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
        }
    }
}

@Composable
private fun EditorUi(b: Editor) {
    var text by remember(b.saved) { mutableStateOf(b.draft) }
    var busy by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(b.label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant); OriginTag(Origin.YOU) }
        OutlinedTextField(value = text, onValueChange = { text = it; b.onChange.accept(it) }, placeholder = { Text(b.hint) },
            textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 2)
        if (text.trim() != b.saved.trim()) Button(enabled = !busy, onClick = {
            busy = true
            b.saver.save(text) { problem -> busy = false; if (problem.isNotEmpty()) android.util.Log.i("Sparkbox", "save failed: $problem") }
        }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(if (busy) "Saving…" else "Save") }
    }
}

