package com.autonomi.examples.antdemo.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/// Quote → approve dialog, mirroring the desktop `UploadConfirmDialog`: shows the
/// network's cost estimate + a Private/Public selector, and only spends money
/// when the user taps Approve. Reads live pending-upload state from the store,
/// so the cost fills in as the quote arrives.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadConfirmDialog() {
    val pending = FilesStore.pendingUpload ?: return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val info = pending.info

    Dialog(onDismissRequest = { FilesStore.cancelPending() }) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.background,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Confirm Upload", style = MaterialTheme.typography.titleLarge)

                // Info banner
                Text(
                    "Review the cost below. Nothing is spent until you Approve — that's when your wallet signs the payment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )

                // File row
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(pending.name, style = MaterialTheme.typography.bodyMedium)
                    Text(formatSize(pending.bytes.size.toLong()),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Cost breakdown
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when {
                        pending.error != null ->
                            Text(pending.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        pending.quoting || info == null ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
                                Text("Obtaining quote from network…", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        info.alreadyStored -> {
                            Text("Already stored on the network — free",
                                style = MaterialTheme.typography.bodyMedium, color = Color(0xFF22C55E), fontWeight = FontWeight.Medium)
                            Text("This exact file is already on the network — you'll get the same address. No ANT or gas will be spent.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (pending.visibility == "public" && info.dataMapAddress != null) {
                                Text("autonomi://${info.dataMapAddress}",
                                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary, maxLines = 1)
                            }
                        }
                        info.paymentType == "merkle" -> {
                            costRow("Estimated max cost", "${formatAtto(merkleApproveUpperBound(info))} ANT", accent = true)
                            costRow("Merkle tree depth", "${info.depth}")
                            costRow("Gas", "paid at signing")
                            Text("Large upload — paid in a single merkle-tree transaction. The exact cost is settled on-chain (a winning pool is selected at payment); you approve up to the estimate above.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> {
                            costRow("Network storage cost", "${formatAtto(info.totalAmount)} ANT", accent = true)
                            costRow("Chunks to pay for", "${info.payments.size}")
                            costRow("Gas", "paid at signing")
                        }
                    }
                }

                // Visibility
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("VISIBILITY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = pending.visibility == "private",
                            onClick = { FilesStore.setPendingVisibility("private") },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Private") }
                        SegmentedButton(
                            selected = pending.visibility == "public",
                            onClick = { FilesStore.setPendingVisibility("public") },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("Public") }
                    }
                    Text(
                        if (pending.visibility == "private")
                            "Encrypted; only retrievable with your data map."
                        else "Data map is published — share one address to let anyone retrieve it.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Actions
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = { FilesStore.cancelPending() }) { Text("Cancel") }
                    val alreadyPublic = info?.alreadyStored == true && pending.visibility == "public"
                    val label = when {
                        info?.alreadyStored != true -> "Approve & Pay"
                        pending.visibility == "public" -> "Copy link"
                        else -> "Get datamap"
                    }
                    Button(
                        enabled = info != null && !pending.quoting,
                        onClick = {
                            // Public + already-stored shortcut: copy the link and
                            // finish with no finalize/tx.
                            val addr = info?.dataMapAddress
                            if (alreadyPublic && addr != null) {
                                clipboard.setText(AnnotatedString("autonomi://$addr"))
                                FilesStore.completeAlreadyStored()
                            } else {
                                FilesStore.approvePending(context)
                            }
                        },
                    ) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun costRow(key: String, value: String, accent: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (accent) FontWeight.Medium else FontWeight.Normal,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
