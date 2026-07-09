package com.autonomi.examples.antdemo.files

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.autonomi.examples.antdemo.deeplink.AntUri
import com.autonomi.examples.antdemo.wallet.WalletConnectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.ant_ffi.Client
import uniffi.ant_ffi.CostEstimate
import uniffi.ant_ffi.PreparedUploadInfo
import uniffi.ant_ffi.ProgressListener
import uniffi.ant_ffi.ProgressUpdate
import uniffi.ant_ffi.TxReceipt
import uniffi.ant_ffi.merkleWinnerPoolHash
import uniffi.ant_ffi.waitForReceipt
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/// An upload that has been (or is being) quoted and is waiting for the user to
/// review the cost and Approve — drives the confirm dialog. Mirrors the desktop
/// UploadConfirmDialog's bound state.
data class PendingUpload(
    val id: Long,
    val name: String,
    /// Path to the picked file copied into the app cache. The upload streams
    /// from disk (file-path FFI) instead of holding the file in memory.
    val path: String,
    val sizeBytes: Long,
    val visibility: String,          // "private" | "public"
    val info: PreparedUploadInfo?,   // null while (re)quoting
    /// Fast sampled cost estimate shown while the full quote is still running.
    val estimate: CostEstimate?,
    val quoting: Boolean,
    val error: String?,
)

/// Autonomi network connection status — mirrors the desktop `connectionStore`
/// (`ant-ui/stores/connection.ts`): idle | connecting | connected | failed.
sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Failed(val reason: String) : ConnectionStatus
}

/// Backing store for the Files screens — the mobile analogue of the desktop
/// app's files store (`ant-ui/stores/files.ts`). Drives real network operations
/// through the bundled AntFfi AAR, with a quote → approve two-step upload and
/// live progress via the FFI's ProgressListener.
object FilesStore {
    /// Devnet host serving the manifest API (Developer settings), e.g.
    /// `192.168.0.62:8088`. Empty → use the adb-pushed file (back-compat).
    private fun devnetHost(): String =
        appContext?.getSharedPreferences("antdemo", Context.MODE_PRIVATE)
            ?.getString("devnetHost", "")?.trim().orEmpty()

    /// Path the FFI/parse read. With a devnet host set, the manifest is fetched
    /// into app storage (writable); otherwise the adb-pushed file in
    /// /data/local/tmp (read-only — the legacy delivery, no host needed).
    private fun manifestPath(): String {
        val ctx = appContext
        return if (devnetHost().isNotEmpty() && ctx != null)
            File(ctx.filesDir, "devnet-manifest.json").absolutePath
        else
            "/data/local/tmp/devnet-manifest.json"
    }

    /// Fetch the manifest from the devnet host's HTTP API into app storage, so
    /// the device needs no `adb push` and re-fetches on reconnect. No-op when
    /// no host is set. Blocking — call from an IO coroutine.
    private fun ensureManifest() {
        val host = devnetHost()
        if (host.isEmpty()) return
        val ctx = appContext ?: return
        try {
            val conn = (URL("http://$host/api/devnet-manifest.json")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (conn.responseCode == 200) {
                val data = conn.inputStream.use { it.readBytes() }
                File(ctx.filesDir, "devnet-manifest.json").writeBytes(data)
            }
            conn.disconnect()
        } catch (_: Throwable) {
            // Keep any previously-fetched manifest on a transient failure.
        }
    }

    /// Recompute manifest-derived flags (e.g. whether it has a funded key).
    private fun refreshManifestFlags() {
        manifestHasKey = try {
            val evm = JSONObject(File(manifestPath()).readText()).optJSONObject("evm")
            !(evm?.optString("wallet_private_key").isNullOrBlank())
        } catch (_: Throwable) {
            false
        }
    }

    val uploads = mutableStateListOf<FileEntry>()
    val downloads = mutableStateListOf<FileEntry>()

    /// Non-null while an upload is being quoted / awaiting the user's Approve.
    var pendingUpload by mutableStateOf<PendingUpload?>(null)
        private set

    /// Autonomi network connection status, driving the header indicator.
    var connection by mutableStateOf<ConnectionStatus>(ConnectionStatus.Idle)
        private set

    /// Whether the current manifest carries a funded wallet key (local-Anvil
    /// devnet). False for a Sepolia devnet (empty key → external wallet needed).
    /// Drives whether uploads are possible without a connected wallet.
    var manifestHasKey by mutableStateOf(false)
        private set

    /// Join the Autonomi network (build + start the P2P client from the devnet
    /// manifest) and track status for the indicator. Idempotent — a no-op while
    /// already connecting or connected. Called at launch and by the Retry action.
    fun connectNetwork() {
        if (connection is ConnectionStatus.Connecting || connection is ConnectionStatus.Connected) return
        connection = ConnectionStatus.Connecting
        scope.launch {
            ensureManifest()
            withContext(Dispatchers.Main) { refreshManifestFlags() }
            val next = try {
                externalSignerClient()
                ConnectionStatus.Connected
            } catch (e: Throwable) {
                ConnectionStatus.Failed(e.message ?: "connection failed")
            }
            withContext(Dispatchers.Main) { connection = next }
        }
    }

    /// Background liveness: while a devnet host is set, poll its HTTP API. If it
    /// goes unreachable the badge flips to Failed (the devnet died), and when it
    /// returns we auto-reconnect — so the indicator reflects reality instead of
    /// staying green after the devnet stops. Started once from attach(). (No-op
    /// on the adb-push path, which has no API to probe.)
    @Volatile private var livenessStarted = false
    private fun startLivenessPoll() {
        if (livenessStarted) return
        livenessStarted = true
        scope.launch {
            while (true) {
                delay(6000)
                val host = devnetHost()
                if (host.isEmpty()) continue
                val alive = apiAlive(host)
                if (!alive && connection is ConnectionStatus.Connected) {
                    esClient = null
                    walletClient = null
                    withContext(Dispatchers.Main) {
                        connection = ConnectionStatus.Failed("devnet unreachable")
                    }
                } else if (alive && connection is ConnectionStatus.Failed) {
                    withContext(Dispatchers.Main) { connectNetwork() }
                }
            }
        }
    }

    /// Cheap reachability check against the devnet's manifest HTTP API.
    private fun apiAlive(host: String): Boolean = try {
        val conn = (URL("http://$host/api/info").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
        }
        val ok = conn.responseCode == 200
        conn.disconnect()
        ok
    } catch (_: Throwable) {
        false
    }

    /// Force a fresh connection attempt (drops the cached client so a previously
    /// failed build is retried, not reused).
    fun retryConnection() {
        esClient = null
        connection = ConnectionStatus.Idle
        connectNetwork()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong(1)

    // Clients are connected on first use and reused.
    private val clientLock = Mutex()
    @Volatile private var walletClient: Client? = null
    @Volatile private var esClient: Client? = null

    private suspend fun client(): Client = clientLock.withLock {
        walletClient ?: Client.connectFromDevnetManifest(manifestPath()).also { walletClient = it }
    }

    private suspend fun externalSignerClient(): Client = clientLock.withLock {
        esClient ?: Client.connectFromDevnetManifestExternalSigner(manifestPath()).also { esClient = it }
    }

    // ---- Uploads: quote → approve ----

    /// Step 1: stage a file and start quoting. Opens the confirm dialog
    /// (`pendingUpload`). With no wallet connected, falls back to the devnet
    /// single-shot put immediately (nothing to sign).
    fun stageUpload(name: String, path: String, context: Context) {
        val id = ids.getAndIncrement()
        val sizeBytes = File(path).length()
        uploads.add(0, FileEntry(id, FileKind.Upload, name, sizeBytes,
            FileStatus.Quoting, System.currentTimeMillis()))
        syncTransferService()
        if (WalletConnectManager.state.value.address == null) {
            scope.launch {
                try { devnetUpload(id, path) }
                catch (e: Throwable) { setUpload(id) { it.copy(status = FileStatus.Failed, error = e.message) } }
                finally { cleanupTemp(path) }
            }
            return
        }
        pendingUpload = PendingUpload(id, name, path, sizeBytes, "private", null, null, quoting = true, error = null)
        quote(id)
    }

    /// Flip visibility and re-quote (public pays for one extra chunk). The
    /// sampled estimate is visibility-independent, so it's kept.
    fun setPendingVisibility(vis: String) {
        val p = pendingUpload ?: return
        if (p.visibility == vis) return
        pendingUpload = p.copy(visibility = vis, info = null, quoting = true, error = null)
        quote(p.id)
    }

    /// Best-effort removal of a staged upload's cache copy once it's no longer
    /// needed (completed, cancelled, or failed).
    private fun cleanupTemp(path: String?) {
        if (path != null) runCatching { File(path).delete() }
    }

    private fun quote(id: Long) {
        val pending = pendingUpload ?: return
        val path = pending.path
        setUpload(id) { it.copy(status = FileStatus.Quoting) }
        scope.launch {
            try {
                val c = externalSignerClient()
                // Fast sampled estimate first, so the dialog shows a ballpark cost
                // immediately instead of a bare spinner. Fetch once — it doesn't
                // depend on visibility.
                if (pendingUpload?.id == id && pendingUpload?.estimate == null) {
                    val est = runCatching {
                        withContext(Dispatchers.IO) { c.estimateFileCost(path, "auto") }
                    }.getOrNull()
                    if (est != null) {
                        pendingUpload?.let { if (it.id == id) pendingUpload = it.copy(estimate = est) }
                    }
                }
                val info = withContext(Dispatchers.IO) { c.prepareFileUpload(path, pending.visibility) }
                val p = pendingUpload ?: return@launch
                if (p.id != id) return@launch
                pendingUpload = p.copy(info = info, quoting = false)
                setUpload(id) {
                    it.copy(status = FileStatus.AwaitingApproval,
                        cost = "${info.payments.size} quote(s) · ${formatAtto(info.totalAmount)} ANT")
                }
            } catch (e: Throwable) {
                pendingUpload?.let { if (it.id == id) pendingUpload = it.copy(quoting = false, error = e.message) }
                setUpload(id) { it.copy(status = FileStatus.Failed, error = e.message) }
            }
        }
    }

    /// Public + already-stored: the data-map address is already known from the
    /// quote, so no finalize or transaction is needed — complete the row now.
    fun completeAlreadyStored() {
        val p = pendingUpload ?: return
        val addr = p.info?.dataMapAddress ?: return
        val id = p.id
        cleanupTemp(p.path)
        pendingUpload = null
        setUpload(id) { it.copy(status = FileStatus.Complete, stage = null, address = addr, cost = "already stored") }
    }

    /// Cancel the pending upload (dismiss the dialog, drop the row).
    fun cancelPending() {
        pendingUpload?.let { p -> uploads.removeAll { it.id == p.id }; cleanupTemp(p.path) }
        pendingUpload = null
    }

    /// Step 2: the user approved. Sign the payment then finalize with progress.
    fun approvePending(context: Context) {
        val p = pendingUpload ?: return
        val info = p.info ?: return
        val id = p.id
        val visibility = p.visibility
        val tmpPath = p.path
        pendingUpload = null
        scope.launch {
            try {
                val c = externalSignerClient()
                if (info.alreadyStored) {
                    // Finalize must be routed by payment shape even when nothing
                    // is owed — the FFI rejects a mis-routed finalize. Merkle
                    // accepts any valid 32-byte winner hash here.
                    val r = withContext(Dispatchers.IO) {
                        if (info.paymentType == "merkle") {
                            c.finalizeUploadMerkle(info.uploadId, anyWinnerHash(info))
                        } else {
                            c.finalizeUpload(info.uploadId, emptyMap())
                        }
                    }
                    completeUpload(id, visibility, r.address ?: info.dataMapAddress, r.dataMap, "already stored", context)
                    return@launch
                }
                val evm = parseManifestEvm()

                // The SDK builds the exact ordered transactions to sign (approve
                // + the payment call), including wave batching and the merkle
                // approve upper-bound. Each pay tx carries the quote hashes it
                // settles. All ABI encoding, receipt polling, and the merkle
                // winner lookup now live in the SDK.
                setUpload(id) { it.copy(status = FileStatus.AwaitingApproval) }
                val txs = c.paymentTransactions(info.uploadId)

                // Point the wallet at the payment chain once, up front.
                withContext(Dispatchers.Main) { WalletConnectManager.switchChain(evm.chainId) }

                val txHashes = HashMap<String, String>()   // quoteHash -> txHash (wave finalize)
                var merklePayTx: String? = null
                var merkleVault: String? = null
                var gasWei = java.math.BigInteger.ZERO
                for (tx in txs) {
                    if (tx.kind == "pay") setUpload(id) { it.copy(status = FileStatus.Paying) }
                    val hash = withContext(Dispatchers.Main) {
                        WalletConnectManager.sendTransaction(to = tx.to, data = tx.data)
                    }
                    val receipt = waitForReceipt(evm.rpcUrl, hash, 60UL)
                    if (!receipt.success) throw RuntimeException("payment transaction reverted on-chain")
                    gasWei = gasWei.add(weiOf(receipt))
                    if (tx.kind == "pay") {
                        for (qh in tx.quoteHashes) txHashes[qh] = hash
                        if (info.paymentType == "merkle") { merklePayTx = hash; merkleVault = tx.to }
                    }
                }

                val storeTotal = if (info.paymentType == "merkle") 0L else info.payments.size.toLong()
                setUpload(id) {
                    it.copy(status = FileStatus.Uploading, stage = "storing", stageDone = 0, stageTotal = storeTotal)
                }
                val r = if (info.paymentType == "merkle") {
                    val payTx = merklePayTx ?: throw RuntimeException("merkle payment produced no signed transaction")
                    // The winning pool is chosen on-chain; the SDK reads it from
                    // the payForMerkleTree receipt's MerklePaymentMade event.
                    val winner = merkleWinnerPoolHash(evm.rpcUrl, merkleVault!!, payTx)
                    withContext(Dispatchers.IO) {
                        c.finalizeUploadMerkleWithProgress(info.uploadId, winner, progressListener(id, isUpload = true))
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        c.finalizeUploadWithProgress(info.uploadId, txHashes, progressListener(id, isUpload = true))
                    }
                }
                val costLabel = if (info.paymentType == "merkle") "${r.chunksStored} chunk(s) · merkle"
                    else "${r.chunksStored} chunk(s) · ${formatAtto(info.totalAmount)} ANT"
                val gas = gasEthOrNull(gasWei)
                val cost = costLabel + (gas?.let { " · $it ETH gas" } ?: "")
                completeUpload(id, visibility, r.address ?: info.dataMapAddress, r.dataMap, cost, context)
            } catch (e: Throwable) {
                setUpload(id) { it.copy(status = FileStatus.Failed, error = e.message, stage = null) }
            } finally {
                cleanupTemp(tmpPath)
            }
        }
    }

    /// A valid 32-byte winner hash for the already-stored merkle case, where the
    /// FFI accepts any hash (no payment was made). Prefer a real pool hash.
    private fun anyWinnerHash(info: PreparedUploadInfo): String =
        info.poolCommitments.firstOrNull()?.poolHash ?: "0x" + "0".repeat(64)

    private fun completeUpload(
        id: Long, visibility: String, address: String?, dataMapHex: String, cost: String, context: Context,
    ) {
        var dataMapFile: String? = null
        var savedTo: String? = null
        if (visibility == "private") {
            val name = uploads.firstOrNull { it.id == id }?.name ?: "upload"
            val filename = "$name.datamap"
            dataMapFile = saveDatamapToDownloads(context, filename, dataMapHex)
            if (dataMapFile != null) savedTo = "Download/$filename"
        }
        setUpload(id) {
            it.copy(status = FileStatus.Complete, stage = null,
                address = if (visibility == "public") address else null,
                dataMapFile = dataMapFile, savedTo = savedTo, cost = cost)
        }
    }

    /// Save bytes to the public **Downloads** folder (MediaStore on API 29+, app
    /// storage otherwise), so downloaded files and private-upload datamaps land
    /// somewhere the user can actually find them. Returns a `content://` Uri /
    /// file path for the "Open file" action, or null on failure. `write` receives
    /// the destination stream.
    private fun saveToDownloads(
        context: Context,
        filename: String,
        mimeType: String,
        write: (java.io.OutputStream) -> Unit,
    ): String? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) null else {
                resolver.openOutputStream(uri)?.use { write(it) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri.toString()
            }
        } else {
            // Pre-scoped-storage: keep it in app storage (public Downloads would
            // need WRITE_EXTERNAL_STORAGE).
            val dir = File(context.filesDir, "downloads").apply { mkdirs() }
            File(dir, filename).apply { outputStream().use { write(it) } }.absolutePath
        }
    } catch (_: Throwable) {
        null
    }

    private fun saveDatamapToDownloads(context: Context, filename: String, contents: String): String? =
        saveToDownloads(context, filename, "application/octet-stream") { it.write(contents.toByteArray()) }

    /// Best-effort MIME from a filename extension so a saved download indexes in
    /// the gallery and opens in the right default app. Falls back to octet-stream.
    private fun mimeOf(name: String): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /// Devnet fallback: the manifest wallet pays inside ant-core (single-shot).
    private suspend fun devnetUpload(id: Long, path: String) {
        val c = client()
        setUpload(id) { it.copy(status = FileStatus.Uploading) }
        val result = withContext(Dispatchers.IO) { c.fileUploadPublic(path, "auto") }
        setUpload(id) {
            it.copy(status = FileStatus.Complete, address = result.address, cost = "uploaded")
        }
    }

    // ---- Downloads ----

    /// Download by a pasted address or `autonomi://<addr>?name=&filetype=` URI.
    fun download(input: String, context: Context) {
        val trimmed = input.trim()
        if (trimmed.startsWith("autonomi://", ignoreCase = true)) {
            val parsed = AntUri.parse(trimmed)
            startDownload(parsed.address, null, AntUri.resolveFilename(parsed), context)
        } else {
            startDownload(trimmed, null, null, context)
        }
    }

    /// Download a private upload from a datamap (hex read from a picked file).
    fun downloadFromDatamap(dataMapHex: String, name: String?, context: Context) {
        startDownload(null, dataMapHex.trim(), name, context)
    }

    private fun startDownload(addressHex: String?, dataMapHex: String?, suggestedName: String?, context: Context) {
        val key = addressHex ?: "datamap"
        val id = ids.getAndIncrement()
        val shortAddr = if (key.length > 10) "${key.take(10)}…" else key
        // A datamap file is named "<original>.datamap"; strip that suffix so the
        // downloaded file restores the original name + extension.
        val cleanName = suggestedName?.let {
            if (dataMapHex != null && it.endsWith(".datamap", ignoreCase = true))
                it.dropLast(".datamap".length) else it
        }
        val rowName = cleanName ?: "download-$shortAddr"
        val fileName = cleanName ?: "download-${key.take(16)}.bin"
        downloads.add(0, FileEntry(id, FileKind.Download, rowName, 0, FileStatus.Downloading,
            System.currentTimeMillis(), address = addressHex, stage = "downloading"))
        syncTransferService()
        scope.launch {
            try {
                val c = externalSignerClient()
                // The FFI writes to a real file path (MediaStore has none), so
                // download to a temp file, then copy it into public Downloads.
                val tmp = File(context.cacheDir, fileName)
                val written = withContext(Dispatchers.IO) {
                    if (addressHex != null) {
                        c.downloadPublicToFile(addressHex, tmp.absolutePath, progressListener(id, isUpload = false))
                    } else {
                        c.downloadPrivateToFile(dataMapHex!!, tmp.absolutePath, progressListener(id, isUpload = false))
                    }
                }
                val saved = withContext(Dispatchers.IO) {
                    saveToDownloads(context, fileName, mimeOf(fileName)) { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    }.also { tmp.delete() }
                }
                setDownload(id) {
                    it.copy(status = FileStatus.Downloaded, stage = null,
                        sizeBytes = written.toLong(),
                        savedTo = if (saved != null) "Download/$fileName" else tmp.absolutePath,
                        dataMapFile = saved)
                }
            } catch (e: Throwable) {
                setDownload(id) { it.copy(status = FileStatus.Failed, error = e.message, stage = null) }
            }
        }
    }

    // ---- Progress ----

    private fun progressListener(id: Long, isUpload: Boolean) = object : ProgressListener {
        override fun onProgress(update: ProgressUpdate) {
            scope.launch(Dispatchers.Main) {
                val t: (FileEntry) -> FileEntry = {
                    it.copy(stage = update.phase, stageDone = update.done.toLong(), stageTotal = update.total.toLong())
                }
                if (isUpload) setUpload(id, t) else setDownload(id, t)
            }
        }
    }

    // ---- Manifest / receipts ----

    private data class DevnetEvm(val rpcUrl: String, val chainId: Long)

    /// Read the devnet RPC + chain id from the manifest. Token/vault addresses no
    /// longer come from here — the SDK's `paymentTransactions` supplies each tx's
    /// `to`. The chain id is taken from the manifest when present, else defaults
    /// to Arbitrum Sepolia (the external-signer devnet), replacing the old
    /// RPC-string guess.
    private fun parseManifestEvm(): DevnetEvm {
        val json = JSONObject(File(manifestPath()).readText())
        val evm = json.getJSONObject("evm")
        val chainId = evm.optLong("chain_id", 0L).takeIf { it != 0L } ?: 421614L
        return DevnetEvm(evm.getString("rpc_url"), chainId)
    }

    /// Gas spent (wei) for a receipt: `gasUsed × effectiveGasPrice` (base-10
    /// decimal strings from the SDK's `TxReceipt`).
    private fun weiOf(r: TxReceipt): java.math.BigInteger {
        val used = r.gasUsed.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
        val price = r.effectiveGasPrice.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
        return used.multiply(price)
    }

    /// Format accumulated wei as an ETH string, or null when nothing was spent.
    private fun gasEthOrNull(wei: java.math.BigInteger): String? {
        if (wei.signum() == 0) return null
        return java.math.BigDecimal(wei).movePointLeft(18)
            .setScale(6, java.math.RoundingMode.HALF_UP).toPlainString()
    }

    // ---- mutation helpers (replace-by-id keeps the list observable) ----

    private fun setUpload(id: Long, transform: (FileEntry) -> FileEntry) {
        replace(uploads, id, transform); syncTransferService()
    }
    private fun setDownload(id: Long, transform: (FileEntry) -> FileEntry) {
        replace(downloads, id, transform); syncTransferService()
    }

    private fun replace(list: MutableList<FileEntry>, id: Long, transform: (FileEntry) -> FileEntry) {
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = transform(list[idx])
    }

    // ── Background-transfer foreground service (V2-563) ──

    /// App context for starting the transfer service. Set once at launch.
    @Volatile private var appContext: Context? = null

    /// Wire the application context so transfers can keep a foreground service
    /// alive while backgrounded. Call once from the Activity.
    fun attach(context: Context) {
        appContext = context.applicationContext
        startLivenessPoll()
    }

    /// Number of uploads + downloads currently in progress. Read by the transfer
    /// service to decide whether to keep running (it reconciles against this on
    /// every start command, so a race between start/stop can't strand it).
    fun activeTransferCount(): Int =
        uploads.count { it.status.inProgress } + downloads.count { it.status.inProgress }

    /// Start/stop the transfer foreground service to match the active-transfer
    /// count. Called after every transfer state change (and on each new row).
    private fun syncTransferService() {
        val ctx = appContext ?: return
        com.autonomi.examples.antdemo.transfer.TransferService.sync(ctx, activeTransferCount())
    }
}
