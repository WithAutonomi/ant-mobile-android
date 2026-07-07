package com.autonomi.examples.antdemo.files

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/// Lifecycle states mirroring the desktop app's file manager
/// (ant-ui `pages/files.vue` statusLabel). Uploads progress
/// quoting → (awaiting approval / paying) → uploading → complete;
/// downloads downloading → downloaded. Either can end in failed.
enum class FileStatus(val label: String) {
    Quoting("Quoting"),
    AwaitingApproval("Awaiting approval"),
    Paying("Paying"),
    Uploading("Uploading"),
    Complete("Complete"),
    Downloading("Downloading"),
    Downloaded("Downloaded"),
    Failed("Failed"),
}

val FileStatus.inProgress: Boolean
    get() = this == FileStatus.Quoting || this == FileStatus.AwaitingApproval ||
        this == FileStatus.Paying || this == FileStatus.Uploading || this == FileStatus.Downloading

enum class FileKind { Upload, Download }

/// One row in the Uploads or Downloads list — the mobile analogue of a
/// desktop table row (name / status / size / cost|saved-to / date).
data class FileEntry(
    val id: Long,
    val kind: FileKind,
    val name: String,
    val sizeBytes: Long,
    val status: FileStatus,
    val createdAt: Long,
    /// Content address (hex) once uploaded, or the address used to download.
    val address: String? = null,
    /// Storage cost summary (e.g. chunk count / atto). Blank until known.
    val cost: String? = null,
    /// Where a downloaded file was written on device.
    val savedTo: String? = null,
    /// For private uploads: path to the saved data-map file (used to re-download).
    val dataMapFile: String? = null,
    // ── Live transfer progress (from the FFI ProgressListener) ──
    /// "encrypting", "quoting", "storing", "resolving", "downloading" — or null.
    val stage: String? = null,
    val stageDone: Long = 0,
    val stageTotal: Long = 0,
    val error: String? = null,
) {
    /// 0.0–1.0 of the current stage, or null when the total is unknown (render
    /// an indeterminate bar). Mirrors the desktop's per-stage percent.
    val progress: Float?
        get() = if (stageTotal > 0) (stageDone.toFloat() / stageTotal).coerceAtMost(1f) else null

    /// Sub-stage detail line under the badge, mirroring desktop files.vue.
    val stageDetail: String?
        get() = when (stage) {
            "encrypting" -> "Encrypting…"
            "quoting" -> pctLabel("Quoting")
            "storing" -> pctLabel("Storing")
            "resolving" -> pctLabel("Resolving datamap")
            "downloading" -> pctLabel("Downloading")
            else -> null
        }

    private fun pctLabel(base: String): String {
        val p = progress
        return if (p != null) "$base · ${(p * 100).roundToInt()}%" else "$base…"
    }
}

/// Human-readable status text, matching the desktop's statusLabel.
fun FileEntry.statusText(): String = when (status) {
    FileStatus.Failed -> error?.let { "Failed: $it" } ?: "Failed"
    else -> status.label
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024; i++
    }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}

/// Format an atto-token amount (1e18 = 1 ANT) as a short ANT string.
fun formatAtto(atto: String): String {
    val value = atto.toBigDecimalOrNull() ?: return atto
    val ant = value.movePointLeft(18)
    return when {
        ant.signum() == 0 -> "0"
        ant < java.math.BigDecimal("0.0001") -> ant.setScale(8, java.math.RoundingMode.HALF_UP).toPlainString()
        else -> ant.setScale(6, java.math.RoundingMode.HALF_UP).toPlainString()
    }
}

private val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
fun formatDate(epochMillis: Long): String = dateFmt.format(Date(epochMillis))
