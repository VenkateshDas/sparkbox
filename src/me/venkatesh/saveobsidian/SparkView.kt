package me.venkatesh.saveobsidian

import android.content.Context
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
class SparkCard(
    val id: String, val lane: String, val from: String, val question: String, val answer: String, val take: String,
    val done: Int, val total: Int, val revealed: Boolean, val canReview: Boolean, val hasSource: Boolean, val grades: List<Grade>,
) : SparkPage

interface SparkActions { fun reveal(); fun park(); fun flag(); fun source(); fun edit(); fun take() }

class SparkState {
    var page by mutableStateOf<SparkPage?>(null); private set
    var subtitle by mutableStateOf(""); private set
    var canWrite by mutableStateOf(true); private set
    fun publish(p: SparkPage, subtitle: String, canWrite: Boolean) { page = p; this.subtitle = subtitle; this.canWrite = canWrite }
}

object SparkView {
    @JvmStatic fun create(c: Context, ui: Ui, s: SparkState, a: SparkActions): View = ComposeView(c).apply {
        setContent { SparkTheme(ui) { SparkUi(s, a) } }
    }
}

@Composable
private fun SparkUi(s: SparkState, a: SparkActions) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            TopBar("Spark", null)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.subtitle.isNotEmpty()) Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!s.canWrite) BlockUi(Banner("Allow write access to your vault folder in Settings to review cards.", false))
                when (val p = s.page) {
                    is SparkMessage -> Message(p)
                    is SparkCard -> Card(p, a)
                    null -> {}
                }
            }
        }
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

@Composable
private fun Card(p: SparkCard, a: SparkActions) {
    val cs = MaterialTheme.colorScheme
    LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
    Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerLow, border = BorderStroke(1.dp, cs.outlineVariant)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.lane.isNotEmpty()) { ToneChip(Tag(p.lane, 0)); Text(" ", style = MaterialTheme.typography.bodySmall) }
                Text(p.from, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(p.question, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
            if (!p.revealed) Button(onClick = { a.reveal() }, enabled = p.canReview, modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) { Text("Show answer") }
            AnimatedVisibility(p.revealed, enter = fadeIn() + expandVertically()) {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BlockUi(Panel("Answer", p.answer, Origin.NONE))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).clickable { a.take() }.padding(14.dp)) {
                        Text("Your note", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Text(p.take.ifEmpty { "Add your own words" }, style = MaterialTheme.typography.bodyLarge,
                            color = if (p.take.isEmpty()) cs.onSurfaceVariant else cs.onSurface, fontStyle = if (p.take.isEmpty()) null else FontStyle.Italic, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
    if (p.revealed) {
        Text("How well did you recall it?", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            p.grades.forEach { g ->
                OutlinedButton(onClick = { g.onClick.run() }, shape = RoundedCornerShape(14.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(g.name, style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
                        Text(g.next, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { a.park() }) { Text("Park 24h") }
        TextButton(onClick = { a.flag() }) { Text("Flag") }
        if (p.hasSource) TextButton(onClick = { a.source() }) { Text("Source") }
        TextButton(onClick = { a.edit() }) { Text("Edit") }
    }
}
