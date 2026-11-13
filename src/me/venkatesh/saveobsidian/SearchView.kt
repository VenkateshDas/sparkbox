package me.venkatesh.saveobsidian

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class Hit(val path: String, val kind: String, val lane: String, val age: String, val title: String, val meta: String, val snippet: String)

interface SearchActions { fun query(q: String); fun chip(name: String); fun open(path: String); fun ask() }

class SearchState {
    var query by mutableStateOf(""); private set
    var chip by mutableStateOf("All"); private set
    var total by mutableStateOf(0); private set
    var hits by mutableStateOf(emptyList<Hit>()); private set
    fun typed(q: String) { query = q }
    fun publish(chip: String, total: Int, hits: List<Hit>) { this.chip = chip; this.total = total; this.hits = hits }
}

object SearchView {
    val CHIPS = listOf("All", "Sources", "Topics", "People")
    @JvmStatic fun create(c: Context, ui: Ui, s: SearchState, a: SearchActions): View = ComposeView(c).apply {
        setContent { SparkTheme(ui) { SearchUi(s, a) } }
    }
}

@Composable
private fun SearchUi(s: SearchState, a: SearchActions) {
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            TopBar("Search", null)
            OutlinedTextField(value = s.query, onValueChange = { s.typed(it); a.query(it.trim()) }, singleLine = true, shape = RoundedCornerShape(24.dp),
                placeholder = { Text("Search notes, topics, people") }, textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchView.CHIPS, key = { it }) { name ->
                    FilterChip(selected = name == s.chip, onClick = { a.chip(name) }, label = { Text(if (name == "All" && s.query.isNotBlank()) "All ${s.total}" else name) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
                }
            }
            when {
                s.query.isBlank() -> Text("Type to search your notes, topics and people.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                s.hits.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 32.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No results", style = MaterialTheme.typography.titleLarge)
                    Text("Nothing matches. Try another word, or ask your vault.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    Button(onClick = { keyboard?.hide(); a.ask() }, modifier = Modifier.padding(top = 16.dp)) { Text("Ask Scout") }
                }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(s.hits, key = { it.path }) { h -> HitCard(h, s.query, a) }
                }
            }
        }
    }
}

@Composable
private fun HitCard(h: Hit, query: String, a: SearchActions) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = { a.open(h.path) }, shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerLow, border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToneChip(Tag(h.kind, 0))
                if (h.lane.isNotEmpty()) ToneChip(Tag(h.lane, 0))
                Spacer(Modifier.weight(1f))
                if (h.age.isNotEmpty()) Text(h.age, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Text(h.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            if (h.meta.isNotEmpty()) Text(h.meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            if (h.snippet.isNotEmpty()) Text(emphasis(h.snippet, query), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
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
