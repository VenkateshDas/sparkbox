package me.venkatesh.saveobsidian

import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

interface QuickActions { fun write(); fun speak(); fun save(text: String); fun discard(); fun stop(); fun dismiss(); fun draft(text: String) }

/** The capture sheet's state. QuickActivity keeps recording and saving; this only draws. */
class QuickState(draft: String, val inbox: String, val hint: String) {
    val shown = MutableTransitionState(false)
    var speaking by mutableStateOf(false)
    var text by mutableStateOf(draft)
    var clock by mutableStateOf("0:00")
    val levels = FloatArray(160)
    var frame by mutableIntStateOf(0)
    private var smooth = 0f
    fun clear() { levels.fill(0f); smooth = 0f; frame++ }
    /** Loudness bars: smoothed, newest on the right. */
    fun push(raw: Float) {
        val target = Math.pow(minOf(1f, raw * 3f).toDouble(), 0.7).toFloat()
        smooth += (target - smooth) * 0.6f
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = smooth
        frame++
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSheet(s: QuickState, a: QuickActions) {
    LaunchedEffect(Unit) { s.shown.targetState = true }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(s.shown, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0xA6000000)).clickable(remember { MutableInteractionSource() }, null) { a.dismiss() })
        }
        AnimatedVisibility(s.shown, modifier = Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.navigationBarsPadding().imePadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        SingleChoiceSegmentedButtonRow {
                            SegmentedButton(selected = !s.speaking, onClick = { a.write() }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Write") }
                            SegmentedButton(selected = s.speaking, onClick = { a.speak() }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Speak") }
                        }
                        Text(s.inbox, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    }
                    if (s.speaking) Speak(s, a) else Write(s, a)
                }
            }
        }
    }
}

@Composable
private fun Write(s: QuickState, a: QuickActions) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    TextField(value = s.text, onValueChange = { s.text = it; a.draft(it) }, placeholder = { Text("Write a thought") },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp), minLines = 4, maxLines = 9,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus))
    Button(onClick = { a.save(s.text) }, enabled = s.text.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save") }
}

@Composable
private fun Speak(s: QuickState, a: QuickActions) {
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(s.clock, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 44.sp, lineHeight = 52.sp))
        val accent = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(64.dp).padding(top = 12.dp)) {
            @Suppress("UNUSED_EXPRESSION") s.frame // redraw on every new level
            val bar = 3.dp.toPx(); val gap = 4.dp.toPx(); val min = 4.dp.toPx(); val mid = size.height / 2
            val count = minOf(s.levels.size, (size.width / (bar + gap)).toInt())
            for (i in 0 until count) {
                val v = s.levels[s.levels.size - 1 - i]
                val half = maxOf(min, v * size.height) / 2
                val right = size.width - i * (bar + gap)
                drawRoundRect(accent.copy(alpha = maxOf(0.12f, 1f - i / count.toFloat())), Offset(right - bar, mid - half), Size(bar, half * 2), CornerRadius(bar / 2))
            }
        }
        Text(s.hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { a.discard() }) { Text("Discard") }
            Button(onClick = { a.stop() }, modifier = Modifier.weight(1f)) { Text("Stop and save") }
        }
        Box(Modifier.width(1.dp))
    }
}

/** Puts the sheet into the activity (setContent lives in Kotlin). */
object QuickViewHost {
    @JvmStatic fun set(activity: androidx.activity.ComponentActivity, ui: Ui, s: QuickState, a: QuickActions) {
        activity.setContent { SparkTheme(ui) { QuickSheet(s, a) } }
    }
}
