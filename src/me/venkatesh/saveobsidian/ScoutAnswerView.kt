package me.venkatesh.saveobsidian

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** Scout-specific reading scale; the note viewer keeps its existing renderer. */
@Composable
internal fun ScoutAnswer(text: String, ui: Ui?, sources: List<SourceRef>) {
    val lineHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 26.sp.roundToPx() }
    val blocks = remember(text) { ScoutMarkdown.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        blocks.forEach { block -> key(block.offset, block.kind) {
            when (block.kind) {
                "code" -> CodeBlock(block)
                "table" -> Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    block.rows.forEachIndexed { index, row ->
                        Row {
                            row.forEach { cell -> SelectionContainer { Text(cell, fontSize = 14.sp, lineHeight = 22.sp,
                                fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.width(180.dp).padding(horizontal = 10.dp, vertical = 8.dp)) } }
                        }
                        HorizontalDivider()
                    }
                }
                "heading" -> SelectionContainer { Text(block.text, fontSize = if (block.level <= 2) 18.sp else 16.sp,
                    lineHeight = if (block.level <= 2) 28.sp else 24.sp, fontWeight = FontWeight.SemiBold) }
                else -> if (ui == null) SelectionContainer { Text(block.text, fontSize = 16.sp, lineHeight = 26.sp) }
                else AndroidView(modifier = Modifier.fillMaxWidth(), factory = { android.widget.TextView(it) }, update = { view ->
                    view.setTextColor(ui.ink); view.textSize = 16f
                    androidx.core.widget.TextViewCompat.setLineHeight(view, lineHeightPx)
                    view.text = Md.scoutInline(ui, block.text) { number -> sources.firstOrNull { it.num == "[$number]" }?.onClick?.run() }
                    view.setTextIsSelectable(true)
                    view.movementMethod = android.text.method.LinkMovementMethod.getInstance()
                })
            }
        } }
    }
}

@Composable
private fun CodeBlock(block: ScoutMarkdown.Block) {
    var wrap by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row {
                Text(block.language.ifEmpty { "Code" } + if (block.complete) "" else " · partial", fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { wrap = !wrap }) { Text(if (wrap) "Unwrap" else "Wrap") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(block.text)) }) { Text("Copy") }
            }
            if (block.language.equals("mermaid", ignoreCase = true)) Text("Mermaid text · open saved note in Obsidian to render", fontSize = 13.sp)
            SelectionContainer {
                Text(block.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp,
                    softWrap = wrap, modifier = if (wrap) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
            }
        }
    }
}
