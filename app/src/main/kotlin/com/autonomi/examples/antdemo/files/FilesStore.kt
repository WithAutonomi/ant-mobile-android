package com.autonomi.examples.antdemo.files

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.autonomi.examples.antdemo.deeplink.AntUri
import com.autonomi.examples.antdemo.wallet.EthCalldata
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
import uniffi.ant_ffi.PreparedUploadInfo
import uniffi.ant_ffi.ProgressListener
import uniffi.ant_ffi.ProgressUpdate
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
    val bytes: ByteArray,
    val visibility: String,          // "private" | "public"
    val info: PreparedUploadInfo?,   // null while (re)quoting
    val quoting: Boolean,
    val error: String?,
)

/// Backing store for the Files screens — the mobile analogue of the desktop
/// app's files store (`ant-ui/stores/files.ts`). Drives real network operations
/// through the bundled AntFfi AAR, with a quote → approve two-step upload and
/// live progress via the FFI's ProgressListener.
object FilesStore {
    /// Devnet manifest pushed to the device (see README wiring step).
    private const val MANIFEST_PATH = "/data/local/tmp/devnet-manifest.json"

    val uploads = mutableStateListOf<FileEntry>()
    val downloads = mutableStateListOf<FileEntry>()

    /// Non-null while an upload is being quoted / awaiting the user's Approve.
    var pendingUpload by mutableStateOf<PendingUpload?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong(1)

    // Clients are connected on first use and reused.
    private val clientLock = Mutex()
    @Volatile private var walletClient: Client? = null
    @Volatile private var esClient: Client? = null

    private suspend fun client(): Client = clientLock.withLock {
        walletClient ?: Client.connectFromDevnetManifest(MANIFEST_PATH).also { walletClient = it }
    }

    private suspend fun externalSignerClient(): Client = clientLock.withLock {
        esClient ?: Client.connectFromDevnetManifestExternalSigner(MANIFEST_PATH).also { esClient = it }
    }

    // ---- Uploads: quote → approve ----

    /// Step 1: stage a file and start quoting. Opens the confirm dialog
    /// (`pendingUpload`). With no wallet connected, falls back to the devnet
    /// single-shot put immediately (nothing to sign).
    fun stageUpload(name: String, bytes: ByteArray, context: Context) {
        val id = ids.getAndIncrement()
        uploads.add(0, FileEntry(id, FileKind.Upload, name, bytes.size.toLong(),
            FileStatus.Quoting, System.currentTimeMillis()))
        if (WalletConnectManager.state.value.address == null) {
            scope.launch {
                try { devnetUpload(id, bytes) }
                catch (e: Throwable) { setUpload(id) { it.copy(status = FileStatus.Failed, error = e.message) } }
            }
            return
        }
        pendingUpload = PendingUpload(id, name, bytes, "private", null, quoting = true, error = null)
        quote(id)
    }

    /// Flip visibility and re-quote (public pays for one extra chunk).
    fun setPendingVisibility(vis: String) {
        val p = pendingUpload ?: return
        if (p.visibility == vis) return
        pendingUpload = p.copy(visibility = vis, info = null, quoting = true, error = null)
        quote(p.id)
    }

    private fun quote(id: Long) {
        val pending = pendingUpload ?: return
        setUpload(id) { it.copy(status = FileStatus.Quoting) }
        scope.launch {
            try {
                val c = externalSignerClient()
                val info = withContext(Dispatchers.IO) { c.prepareDataUpload(pending.bytes, pending.visibility) }
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
        pendingUpload = null
        setUpload(id) { it.copy(status = FileStatus.Complete, stage = null, address = addr, cost = "already stored") }
    }

    /// Cancel the pending upload (dismiss the dialog, drop the row).
    fun cancelPending() {
        pendingUpload?.let { p -> uploads.removeAll { it.id == p.id } }
        pendingUpload = null
    }

    /// Step 2: the user approved. Sign the payment then finalize with progress.
    fun approvePending(context: Context) {
        val p = pendingUpload ?: return
        val info = p.info ?: return
        val id = p.id
        val visibility = p.visibility
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

                // Large uploads settle via a single `payForMerkleTree` call
                // instead of per-quote `payForQuotes`; the flow differs enough to
                // live in its own method.
                if (info.paymentType == "merkle") {
                    runMerklePayment(id, info, visibility, evm, c, context)
                    return@launch
                }

                setUpload(id) { it.copy(status = FileStatus.AwaitingApproval) }
                val approveTx = withContext(Dispatchers.Main) {
                    evm.chainId?.let { WalletConnectManager.switchChain(it) }
                    WalletConnectManager.sendTransaction(
                        to = evm.tokenAddress,
                        data = EthCalldata.approve(evm.vaultAddress, info.totalAmount),
                    )
                }
                waitForReceipt(evm.rpcUrl, approveTx)

                setUpload(id) { it.copy(status = FileStatus.Paying) }
                val quotePayments = info.payments.map {
                    EthCalldata.QuotePayment(rewardsAddress = it.rewardsAddress, amount = it.amount, quoteHash = it.quoteHash)
                }
                val payTx = withContext(Dispatchers.Main) {
                    WalletConnectManager.sendTransaction(
                        to = evm.vaultAddress,
                        data = EthCalldata.payForQuotes(quotePayments),
                    )
                }
                waitForReceipt(evm.rpcUrl, payTx)

                setUpload(id) {
                    it.copy(status = FileStatus.Uploading, stage = "storing", stageDone = 0,
                        stageTotal = info.payments.size.toLong())
                }
                val txHashes = info.payments.associate { it.quoteHash to payTx }
                val r = withContext(Dispatchers.IO) {
                    c.finalizeUploadWithProgress(info.uploadId, txHashes, progressListener(id, isUpload = true))
                }
                // Gas was paid by the external wallet (not ant-core), so read it
                // back from the approve + payForQuotes receipts.
                val gas = gasSpentEth(evm.rpcUrl, listOf(approveTx, payTx))
                val cost = "${r.chunksStored} chunk(s) · ${formatAtto(info.totalAmount)} ANT" +
                    (gas?.let { " · $it ETH gas" } ?: "")
                completeUpload(id, visibility, r.address ?: info.dataMapAddress, r.dataMap, cost, context)
            } catch (e: Throwable) {
                setUpload(id) { it.copy(status = FileStatus.Failed, error = e.message, stage = null) }
            }
        }
    }

    /// Merkle payment path: approve → `payForMerkleTree` → read the winning pool
    /// from the `MerklePaymentMade` event → finalize with that winner hash.
    /// Unlike the wave path there's a single vault call and the total cost isn't
    /// known until the contract picks a winner, so we approve a safe upper bound.
    private suspend fun runMerklePayment(
        id: Long, info: PreparedUploadInfo, visibility: String, evm: DevnetEvm, c: Client, context: Context,
    ) {
        val pools = info.poolCommitments.map { pc ->
            EthCalldata.PoolCommitment(
                poolHash = pc.poolHash,
                candidates = pc.candidates.map {
                    EthCalldata.MerkleCandidate(rewardsAddress = it.rewardsAddress, amount = it.amount)
                },
            )
        }

        // The contract charges `median16(winnerPool)·2^depth` via transferFrom;
        // median16 ≤ the largest candidate amount, so max·2^depth always suffices
        // to approve without reimplementing the on-chain winner/median logic.
        val approveAmount = merkleApproveUpperBound(info)

        setUpload(id) { it.copy(status = FileStatus.AwaitingApproval) }
        val approveTx = withContext(Dispatchers.Main) {
            evm.chainId?.let { WalletConnectManager.switchChain(it) }
            WalletConnectManager.sendTransaction(
                to = evm.tokenAddress,
                data = EthCalldata.approve(evm.vaultAddress, approveAmount),
            )
        }
        waitForReceipt(evm.rpcUrl, approveTx)

        setUpload(id) { it.copy(status = FileStatus.Paying) }
        val payData = EthCalldata.payForMerkleTree(
            depth = info.depth.toInt(),
            poolCommitments = pools,
            timestamp = info.merklePaymentTimestamp.toLong().toBigInteger(),
        )
        val payTx = withContext(Dispatchers.Main) {
            WalletConnectManager.sendTransaction(to = evm.vaultAddress, data = payData)
        }
        waitForReceipt(evm.rpcUrl, payTx)

        // The winning pool is selected on-chain — recover its hash from the
        // MerklePaymentMade log; finalizeUploadMerkle needs it.
        val winnerPoolHash = parseWinnerPoolHash(evm.rpcUrl, payTx)
            ?: throw RuntimeException("payForMerkleTree receipt had no MerklePaymentMade event")

        setUpload(id) {
            it.copy(status = FileStatus.Uploading, stage = "storing", stageDone = 0, stageTotal = 0)
        }
        val r = withContext(Dispatchers.IO) {
            c.finalizeUploadMerkleWithProgress(info.uploadId, winnerPoolHash, progressListener(id, isUpload = true))
        }
        // Gas was paid by the external wallet (not ant-core); read it back from
        // the approve + payForMerkleTree receipts. Exact ANT is what the vault
        // pulled, which we don't reparse — label it "merkle".
        val gas = gasSpentEth(evm.rpcUrl, listOf(approveTx, payTx))
        val cost = "${r.chunksStored} chunk(s) · merkle" + (gas?.let { " · $it ETH gas" } ?: "")
        completeUpload(id, visibility, r.address ?: info.dataMapAddress, r.dataMap, cost, context)
    }

    /// A valid 32-byte winner hash for the already-stored merkle case, where the
    /// FFI accepts any hash (no payment was made). Prefer a real pool hash.
    private fun anyWinnerHash(info: PreparedUploadInfo): String =
        info.poolCommitments.firstOrNull()?.poolHash ?: "0x" + "0".repeat(64)

    /// Pull the indexed `winnerPoolHash` (topics[1]) out of the MerklePaymentMade
    /// log in a payForMerkleTree receipt. It's already a 0x-prefixed 32-byte hash.
    private suspend fun parseWinnerPoolHash(rpcUrl: String, txHash: String): String? {
        val body = """{"jsonrpc":"2.0","id":1,"method":"eth_getTransactionReceipt","params":["$txHash"]}"""
        val resp = try { withContext(Dispatchers.IO) { httpPost(rpcUrl, body) } } catch (e: Exception) { return null }
        val logs = JSONObject(resp).optJSONObject("result")?.optJSONArray("logs") ?: return null
        val topic0 = EthCalldata.MERKLE_PAYMENT_MADE_TOPIC0.lowercase()
        for (i in 0 until logs.length()) {
            val topics = logs.getJSONObject(i).optJSONArray("topics") ?: continue
            if (topics.length() >= 2 && topics.getString(0).lowercase() == topic0) {
                return topics.getString(1)
            }
        }
        return null
    }

    private fun completeUpload(
        id: Long, visibility: String, address: String?, dataMapHex: String, cost: String, context: Context,
    ) {
        var dataMapFile: String? = null
        if (visibility == "private") {
            val dir = File(context.filesDir, "datamaps").apply { mkdirs() }
            val name = uploads.firstOrNull { it.id == id }?.name ?: "upload"
            val out = File(dir, "$name.datamap")
            out.writeText(dataMapHex)
            dataMapFile = out.absolutePath
        }
        setUpload(id) {
            it.copy(status = FileStatus.Complete, stage = null,
                address = if (visibility == "public") address else null,
                dataMapFile = dataMapFile, cost = cost)
        }
    }

    /// Devnet fallback: the manifest wallet pays inside ant-core (single-shot).
    private suspend fun devnetUpload(id: Long, bytes: ByteArray) {
        val c = client()
        setUpload(id) { it.copy(status = FileStatus.Uploading) }
        val result = withContext(Dispatchers.IO) { c.dataPutPublic(bytes, "auto") }
        setUpload(id) {
            it.copy(status = FileStatus.Complete, address = result.address,
                cost = "${result.chunksStored} chunk(s) · ${result.paymentModeUsed}")
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
        val rowName = suggestedName ?: "download-$shortAddr"
        val fileName = suggestedName ?: "download-${key.take(16)}.bin"
        downloads.add(0, FileEntry(id, FileKind.Download, rowName, 0, FileStatus.Downloading,
            System.currentTimeMillis(), address = addressHex, stage = "downloading"))
        scope.launch {
            try {
                val c = externalSignerClient()
                val dir = File(context.filesDir, "downloads").apply { mkdirs() }
                val out = File(dir, fileName)
                val written = withContext(Dispatchers.IO) {
                    if (addressHex != null) {
                        c.downloadPublicToFile(addressHex, out.absolutePath, progressListener(id, isUpload = false))
                    } else {
                        c.downloadPrivateToFile(dataMapHex!!, out.absolutePath, progressListener(id, isUpload = false))
                    }
                }
                setDownload(id) {
                    it.copy(status = FileStatus.Downloaded, stage = null,
                        sizeBytes = written.toLong(), savedTo = out.absolutePath)
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

    private data class DevnetEvm(
        val rpcUrl: String,
        val tokenAddress: String,
        val vaultAddress: String,
        val chainId: Long?,
    )

    private fun parseManifestEvm(): DevnetEvm {
        val json = JSONObject(File(MANIFEST_PATH).readText())
        val evm = json.getJSONObject("evm")
        val rpc = evm.getString("rpc_url")
        val chainId = when {
            rpc.contains("sepolia", ignoreCase = true) -> 421614L
            rpc.contains("arb1") || rpc.contains("arbitrum.io/rpc") -> 42161L
            else -> null
        }
        return DevnetEvm(rpc, evm.getString("payment_token_address"),
            evm.getString("payment_vault_address"), chainId)
    }

    /// Poll the EVM RPC until a tx is mined (nodes verify payment on-chain, and
    /// MetaMask estimates the next call against confirmed state).
    private suspend fun waitForReceipt(rpcUrl: String, txHash: String, timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val body = """{"jsonrpc":"2.0","id":1,"method":"eth_getTransactionReceipt","params":["$txHash"]}"""
            val resp = try { withContext(Dispatchers.IO) { httpPost(rpcUrl, body) } } catch (e: Exception) { null }
            if (resp != null) {
                val result = JSONObject(resp).optJSONObject("result")
                when (result?.optString("status")) {
                    "0x1" -> return
                    "0x0" -> throw RuntimeException("payment transaction reverted on-chain")
                }
            }
            delay(1500)
        }
        throw RuntimeException("timed out waiting for transaction to confirm")
    }

    /// Total gas spent (ETH) across the given tx hashes, from their receipts
    /// (`gasUsed × effectiveGasPrice`). Null if none could be read.
    private suspend fun gasSpentEth(rpcUrl: String, txHashes: List<String>): String? {
        var totalWei = java.math.BigInteger.ZERO
        for (h in txHashes) {
            val body = """{"jsonrpc":"2.0","id":1,"method":"eth_getTransactionReceipt","params":["$h"]}"""
            val resp = try { withContext(Dispatchers.IO) { httpPost(rpcUrl, body) } } catch (e: Exception) { null } ?: continue
            val result = JSONObject(resp).optJSONObject("result") ?: continue
            val used = result.optString("gasUsed").removePrefix("0x").toBigIntegerOrNull(16) ?: continue
            val price = result.optString("effectiveGasPrice").removePrefix("0x").toBigIntegerOrNull(16) ?: continue
            totalWei = totalWei.add(used.multiply(price))
        }
        if (totalWei.signum() == 0) return null
        return java.math.BigDecimal(totalWei).movePointLeft(18)
            .setScale(6, java.math.RoundingMode.HALF_UP).toPlainString()
    }

    private fun httpPost(urlStr: String, body: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    // ---- mutation helpers (replace-by-id keeps the list observable) ----

    private fun setUpload(id: Long, transform: (FileEntry) -> FileEntry) = replace(uploads, id, transform)
    private fun setDownload(id: Long, transform: (FileEntry) -> FileEntry) = replace(downloads, id, transform)

    private fun replace(list: MutableList<FileEntry>, id: Long, transform: (FileEntry) -> FileEntry) {
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = transform(list[idx])
    }
}
