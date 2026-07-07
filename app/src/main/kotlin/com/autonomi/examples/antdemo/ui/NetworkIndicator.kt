package com.autonomi.examples.antdemo.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonomi.examples.antdemo.files.ConnectionStatus
import com.autonomi.examples.antdemo.files.FilesStore

/// Compact Autonomi-network connection pill, the counterpart of the desktop
/// `AppHeader` indicator (ant-ui `components/AppHeader.vue` + `stores/connection.ts`):
/// a spinner "Connecting", a green "● Network" when connected, and a tappable
/// red "● Offline · Retry" on failure.
@Composable
fun NetworkIndicator() {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    val error = MaterialTheme.colorScheme.error
    val success = Color(0xFF22C55E)

    when (FilesStore.connection) {
        is ConnectionStatus.Idle, is ConnectionStatus.Connecting -> Pill(border = outline) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = muted,
            )
            Text("Connecting", fontSize = 11.sp, color = muted)
        }
        is ConnectionStatus.Connected -> Pill(border = outline) {
            Text("●", fontSize = 11.sp, color = success)
            Text("Network", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        is ConnectionStatus.Failed -> Pill(
            border = error.copy(alpha = 0.35f),
            onClick = { FilesStore.retryConnection() },
        ) {
            Text("●", fontSize = 11.sp, color = error)
            Text("Offline · Retry", fontSize = 11.sp, color = error, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Pill(border: Color, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    var m = Modifier.border(1.dp, border, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(
        modifier = m.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
