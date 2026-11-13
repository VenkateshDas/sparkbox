package me.venkatesh.saveobsidian

import android.content.Context
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One line of Settings. toggle is null for a plain row, or the switch state. note 1 shows the title as a warning, 2 as quiet small print. */
class SettingRow @JvmOverloads constructor(
    val title: String, val value: String = "", val onClick: Runnable? = null,
    val toggle: Boolean? = null, val note: Int = 0,
)
class SettingGroup(val title: String, val rows: List<SettingRow>)

/** Settings state the Java screen fills; Compose redraws when groups changes. */
class SettingsState {
    var groups by mutableStateOf(emptyList<SettingGroup>())
        private set
    fun publish(g: List<SettingGroup>) { groups = g }
}

object SettingsView {
    @JvmStatic fun create(c: Context, ui: Ui, state: SettingsState, back: Runnable): View = ComposeView(c).apply {
        // Default strategy: the composition ends when the view leaves the window (tab switch, back, theme rebuild) and starts again on return.
        // State lives in SettingsState, so nothing is lost; a strategy tied to the activity would keep every rebuilt view alive.
        setContent { SparkTheme(ui) { Settings(state, back) } }
    }
}

@Composable
private fun Settings(state: SettingsState, back: Runnable) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back.run() }) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.groups, key = { it.title }) { Group(it) }
            }
        }
    }
}

@Composable
private fun Group(g: SettingGroup) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(g.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column {
                g.rows.forEachIndexed { i, r ->
                    if (i > 0 && r.note == 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Line(r)
                }
            }
        }
    }
}

@Composable
private fun Line(r: SettingRow) {
    if (r.note != 0) {
        Text(r.title, style = MaterialTheme.typography.bodySmall,
            color = if (r.note == 1) LocalStatus.current.warn else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        return
    }
    val click = r.onClick
    val base = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    val m = when {
        click == null -> base
        r.toggle != null -> base.clickable(role = Role.Switch) { click.run() }
        else -> base.clickable(role = Role.Button) { click.run() }
    }
    Row(m.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val stacked = r.value.length > 18 // a path or model id reads better under the label
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.bodyLarge)
            if (stacked) Text(r.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            r.toggle != null -> Switch(checked = r.toggle, onCheckedChange = { click?.run() })
            !stacked && r.value.isNotEmpty() -> Text(r.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
        }
        if (click != null && r.toggle == null) Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}
