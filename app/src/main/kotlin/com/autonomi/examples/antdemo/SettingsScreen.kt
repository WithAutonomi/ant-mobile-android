package com.autonomi.examples.antdemo

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autonomi.examples.antdemo.ui.ThemeController

/// Settings — mirrors ant-ui/pages/settings.vue: a centered column of bordered
/// cards. The Light Mode toggle is functional (drives ThemeController); the
/// rest are faithful mock controls for the demo.
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val colWidth = Modifier.fillMaxWidth().widthIn(max = 560.dp)

        SettingCard(colWidth, "Alert Sound", "Bell on critical errors.") {
            ToggleRow(checked = false, onChange = {})
        }

        // The one live control.
        SettingCard(colWidth, "Screen mode", "Switch between dark and light themes.") {
            ScreenModeSelector(
                dark = ThemeController.dark,
                onSelect = { isDark -> ThemeController.setDark(context, isDark) },
            )
        }

        SettingCard(colWidth, "Upload history", "3 settled uploads.") {
            OutlinedButton(onClick = {}) { Text("Clear history") }
        }

        SettingCard(colWidth, "About", null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyVal("App", "AntFfi Demo 0.1")
                Text(
                    "autonomi.com  ·  github.com/WithAutonomi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SettingCard(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/// Segmented Dark | Light selector — left = Dark, right = Light.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenModeSelector(dark: Boolean, onSelect: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = dark,
            onClick = { onSelect(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("Dark") }
        SegmentedButton(
            selected = !dark,
            onClick = { onSelect(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("Light") }
    }
}

@Composable
private fun ToggleRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun KeyVal(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
