package me.venkatesh.saveobsidian

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import java.util.function.Consumer

/** Dialog requests from the Java action layer, rendered by the Scout composition. */
class AskDialog(val title: String) {
    var message = ""; private set
    val fields = mutableListOf<AskField>()
    val choices = mutableListOf<Pair<String, Runnable>>()
    var positive = ""; private set
    var accept: Consumer<List<String>>? = null; private set
    var extra: Pair<String, Runnable>? = null; private set
    fun message(text: String) = apply { message = text }
    fun field(field: AskField) = apply { fields.add(field) }
    fun choice(label: String, action: Runnable) = apply { choices.add(label to action) }
    fun confirm(label: String, action: Consumer<List<String>>) = apply { positive = label; accept = action }
    fun secondary(label: String, action: Runnable) = apply { extra = label to action }
}

class AskField @JvmOverloads constructor(val label: String, val initial: String, val toggle: Boolean = false, val enabled: Boolean = true, val options: List<String> = emptyList())

@Composable
internal fun ScoutDialog(request: AskDialog, dismiss: () -> Unit) = key(request) {
    var values by rememberSaveable { mutableStateOf(request.fields.map { it.initial }) }
    var query by rememberSaveable { mutableStateOf("") }
    fun run(action: Runnable) { dismiss(); action.run() }
    AlertDialog(onDismissRequest = dismiss, title = { Text(request.title) }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (request.message.isNotEmpty()) item { Text(request.message) }
            request.fields.forEachIndexed { index, field -> item {
                val change: (String) -> Unit = { value -> values = values.toMutableList().also { it[index] = value } }
                if (field.toggle) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(field.label, Modifier.weight(1f))
                    Switch(checked = values[index].toBoolean(), onCheckedChange = { change(it.toString()) }, enabled = field.enabled, modifier = Modifier.semantics { contentDescription = field.label })
                } else if (field.options.isNotEmpty()) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { open = true }) { Text(field.label + ": " + field.options.getOrElse(values[index].toIntOrNull() ?: 0) { "None" }) }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            field.options.forEachIndexed { at, option -> DropdownMenuItem(text = { Text(option) }, onClick = { change(at.toString()); open = false }) }
                        }
                    }
                } else OutlinedTextField(value = values[index], onValueChange = change, label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(), enabled = field.enabled)
            } }
            if (request.choices.size > 8) item { OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            items(request.choices.filter { it.first.contains(query, ignoreCase = true) }) { (label, action) ->
                TextButton(onClick = { run(action) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label, Modifier.fillMaxWidth()) }
            }
        }
    }, confirmButton = {
        if (request.accept != null) TextButton(onClick = { dismiss(); request.accept?.accept(values) }) { Text(request.positive) }
    }, dismissButton = {
        Row {
            request.extra?.let { (label, action) -> TextButton(onClick = { run(action) }) { Text(label) } }
            TextButton(onClick = dismiss) { Text("Cancel") }
        }
    })
}
