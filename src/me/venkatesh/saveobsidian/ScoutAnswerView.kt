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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.text.style.*

/** Scout-specific reading scale; the note viewer keeps its existing renderer. */
@Composable
internal fun ScoutAnswer(text: String, ui: Ui?, sources: List<SourceRef>) {
    val blocks by produceState<List<ScoutMarkdown.Block>>(emptyList(), text) {
        value = withContext(Dispatchers.Default) { ScoutMarkdown.parse(text) }
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            blocks.forEach { block -> key(block.offset, block.kind) {
                when (block.kind) {
                    "code" -> CodeBlock(block)
                    "table" -> AnswerTable(block, ui, sources)
                    "heading" -> Text(inline(block.text, ui, sources), fontSize = if (block.level <= 2) 18.sp else 16.sp,
                        lineHeight = if (block.level <= 2) 28.sp else 24.sp, fontWeight = FontWeight.SemiBold)
                    else -> Text(inline(block.text, ui, sources), fontSize = 16.sp, lineHeight = 26.sp)
                }
            } }
        }
    }
}

/** Reuse the note reader's formatting and safe link policy, rendered entirely in Compose. */
@Composable
private fun inline(text: String, ui: Ui?, sources: List<SourceRef>): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    val raised = MaterialTheme.colorScheme.surfaceContainerHigh
    return remember(text, ui, sources, accent, raised) {
        if (ui == null) AnnotatedString(text) else {
            val spans = Md.scoutInline(ui, text) { number -> sources.firstOrNull { it.num == "[$number]" }?.onClick?.run() }
            buildAnnotatedString {
                append(spans.toString())
                spans.getSpans(0, spans.length, Any::class.java).forEach { span ->
                    val start = spans.getSpanStart(span)
                    val end = spans.getSpanEnd(span)
                    when (span) {
                        is ClickableSpan -> addLink(LinkAnnotation.Clickable("link:$start", TextLinkStyles(SpanStyle(color = accent))) {
                            span.onClick(android.view.View(ui.c))
                        }, start, end)
                        is TypefaceSpan -> addStyle(if (span.family == "monospace") SpanStyle(fontFamily = FontFamily.Monospace) else SpanStyle(fontWeight = FontWeight.SemiBold), start, end)
                        is StyleSpan -> addStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), start, end)
                        is BackgroundColorSpan -> addStyle(SpanStyle(background = raised), start, end)
                        is ForegroundColorSpan -> addStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(span.foregroundColor)), start, end)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnswerTable(block: ScoutMarkdown.Block, ui: Ui?, sources: List<SourceRef>) {
    val columns = block.rows.maxOfOrNull { it.size } ?: return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Narrow tables remain tables; wide ones become labeled rows without sideways scrolling.
        val stacked = maxWidth / columns < 112.dp
        Column {
            block.rows.forEachIndexed { index, row ->
                if (stacked) {
                    if (index > 0) Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { col, cell ->
                            Text(inline(block.rows.first().getOrElse(col) { "" }, ui, sources), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(inline(cell, ui, sources), fontSize = 14.sp, lineHeight = 22.sp)
                        }
                    }
                } else Row(Modifier.fillMaxWidth()) {
                    repeat(columns) { col -> Text(inline(row.getOrElse(col) { "" }, ui, sources), fontSize = 14.sp, lineHeight = 22.sp,
                        fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp)) }
                }
                if (!stacked || index > 0) HorizontalDivider()
            }
        }
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
            run {
                Text(block.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp,
                    softWrap = wrap, modifier = if (wrap) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
            }
        }
    }
}
