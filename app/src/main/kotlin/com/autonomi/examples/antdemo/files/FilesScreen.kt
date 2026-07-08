package com.autonomi.examples.antdemo.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/// Uploads tab — pick a file to stage it; the confirm dialog (quote → Approve)
/// opens automatically when a wallet is connected.
@Composable
fun UploadsScreen() {
    val context = LocalContext.current
    // Uploads must be payable: either a wallet is connected (external signer) or
    // the manifest carries a funded key (local-Anvil devnet). Otherwise disable.
    val walletConnected = com.autonomi.examples.antdemo.wallet.WalletConnectManager
        .state.collectAsState().value.address != null
    val canUpload = walletConnected || FilesStore.manifestHasKey
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val name = queryDisplayName(context, uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes != null) FilesStore.stageUpload(name, bytes, context)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { Button(enabled = canUpload, onClick = { picker.launch("*/*") }) { Text("Upload a file") } }
        if (!canUpload) {
            item {
                InfoBar("This devnet is paid via a connected wallet. Connect one in the Wallet tab to upload.")
            }
        }
        if (FilesStore.uploads.isEmpty()) {
            item { EmptyState("No uploads yet", "Tap “Upload a file” to store data on the network") }
        } else {
            items(FilesStore.uploads, key = { it.id }) { FileRow(it) }
        }
    }
}

/// Downloads tab — one input + one Download button, two ways in: paste an
/// address / autonomi:// link, or attach (📎) a datamap file.
@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var datamapHex by remember { mutableStateOf<String?>(null) }
    var datamapName by remember { mutableStateOf<String?>(null) }

    val datamapPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val hex = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }?.trim()
        if (!hex.isNullOrEmpty()) {
            datamapHex = hex
            datamapName = queryDisplayName(context, uri)
        }
    }

    val canDownload = datamapHex != null || input.isNotBlank()

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Address or autonomi:// link") },
                    singleLine = true,
                    enabled = datamapHex == null,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { datamapPicker.launch("*/*") }) { Text("📎", fontSize = 18.sp) }
            }
        }
        datamapName?.let { name ->
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📄 $name", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text("✕",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable { datamapHex = null; datamapName = null })
                }
            }
        }
        item {
            Button(
                enabled = canDownload,
                onClick = {
                    val hex = datamapHex
                    if (hex != null) {
                        FilesStore.downloadFromDatamap(hex, datamapName, context)
                        datamapHex = null; datamapName = null
                    } else {
                        FilesStore.download(input, context); input = ""
                    }
                },
            ) { Text("Download") }
        }
        if (FilesStore.downloads.isEmpty()) {
            item { EmptyState("No downloads yet", "Paste an address or autonomi:// link, or 📎 attach a datamap file") }
        } else {
            items(FilesStore.downloads, key = { it.id }) { FileRow(it) }
        }
    }
}

// ---- shared row rendering ----

/// Inline info bar (e.g. why uploads are disabled).
@Composable
private fun InfoBar(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ⓘ", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(entry: FileEntry) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Shareable link for a public upload (autonomi://<data-map address>).
    val autonomiLink = if (entry.kind == FileKind.Upload) entry.address?.let { "autonomi://$it" } else null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            // Long-press → copy the shareable reference.
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    (autonomiLink ?: entry.dataMapFile)?.let { clipboard.setText(AnnotatedString(it)) }
                },
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusBadge(entry)
            if (entry.sizeBytes > 0) {
                Text(formatSize(entry.sizeBytes), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(formatDate(entry.createdAt), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Full error on its own wrapping line (the badge stays a short pill so the
        // row layout doesn't blow out when the message is long).
        if (entry.status == FileStatus.Failed) {
            entry.error?.let { err ->
                Text(err, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
            }
        }
        if (entry.status.inProgress) {
            val p = entry.progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            }
            entry.stageDetail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        entry.cost?.let {
            Text("Cost: $it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        autonomiLink?.let { link ->
            SelectionContainer {
                Text(link, style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }
        entry.dataMapFile?.let { path ->
            TextButton(onClick = { openFile(context, path) }, contentPadding = PaddingValues(0.dp)) {
                Text("Open file")
            }
        }
        entry.savedTo?.let {
            Text("Saved to: $it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/// Share a saved datamap file with another app via FileProvider.
private fun openFile(context: Context, path: String) {
    // Datamaps saved to public Downloads are a content:// Uri (MediaStore);
    // older/app-internal paths still go through FileProvider.
    val uri = if (path.startsWith("content://")) Uri.parse(path)
        else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
    // Resolve the real MIME so it opens in the default app for that type (gallery
    // for images, PDF reader, …): from the stored type for content Uris, else the
    // file extension.
    val mime = (if (path.startsWith("content://")) context.contentResolver.getType(uri)
        else android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(File(path).extension.lowercase())) ?: "*/*"
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // Prefer the default handler; fall back to an "Open with" chooser if none.
    runCatching { context.startActivity(view) }.onFailure {
        runCatching {
            context.startActivity(
                Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun StatusBadge(entry: FileEntry) {
    val (bg, fg) = when (entry.status) {
        FileStatus.Complete, FileStatus.Downloaded -> Color(0xFF1B5E20) to Color.White
        FileStatus.Failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    }
    Text(
        entry.status.label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/// Resolve a human-readable file name from a content Uri.
private fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) c.getString(idx)?.let { return it }
        }
    }
    return uri.lastPathSegment ?: "file"
}
