package com.autonomi.examples.antdemo.wallet

import android.app.Application
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.android.relay.ConnectionType
import com.reown.appkit.client.AppKit
import com.reown.appkit.client.Modal
import com.reown.appkit.client.models.request.Request
import com.reown.appkit.client.models.request.SentRequestResult
import com.reown.appkit.presets.AppKitChainsPresets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// WalletConnect spike (Android): connect an external self-custody wallet via
/// Reown AppKit and have it sign an Autonomi payment transaction. The mobile
/// equivalent of the desktop app's external-signer flow — the app never holds
/// a key. Kotlin counterpart of the iOS `WalletConnectManager.swift`.
///
/// API verified against the resolved `com.reown:appkit:1.4.1` (javap) and the
/// upstream `sample/modal` app: `CoreClient.initialize` + `AppKit.initialize` +
/// `AppKit.setChains`, the full 14-method `ModalDelegate`, `AppKit.getAccount()`
/// (`.address` / `.chain`), and `AppKit.request(Request, onSuccess, onError)`
/// with the response delivered on `onSessionRequestResponse`.
data class WalletState(
    val address: String? = null,
    val chainId: String? = null,
    val lastTxHash: String? = null,
    val status: String = "Not connected",
    /// Live ANT / ETH balances on the connected chain (read-only, cosmetic).
    /// Null when not connected, chain/token unknown, or the read failed.
    val antBalance: String? = null,
    val ethBalance: String? = null,
)

object WalletConnectManager {
    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> get() = _state

    // Pending eth_sendTransaction → resolved by the delegate's response callback.
    private var pending: CompletableDeferred<String>? = null
    private var configured = false

    // Background scope for read-only balance queries.
    private val balanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /// Call once from Application/Activity onCreate. Get a projectId from
    /// https://dashboard.reown.com.
    fun configure(app: Application, projectId: String) {
        if (configured) return
        configured = true

        val metadata = Core.Model.AppMetaData(
            name = "AntAndroidDemo",
            description = "Autonomi mobile bindings demo",
            url = "https://autonomi.com",
            icons = listOf("https://avatars.githubusercontent.com/u/179229932"),
            // Deep-link the wallet uses to return to us; must match an
            // intent-filter in AndroidManifest.xml.
            redirect = "antdemo-wc://request",
            linkMode = false,
            appLink = "",
        )

        CoreClient.initialize(
            projectId = projectId,
            connectionType = ConnectionType.AUTOMATIC,
            application = app,
            metaData = metadata,
        ) { error -> _state.value = _state.value.copy(status = "Core init error: ${error.throwable.message}") }

        AppKit.initialize(Modal.Params.Init(core = CoreClient)) { error ->
            _state.value = _state.value.copy(status = "AppKit init error: ${error.throwable.message}")
        }

        // Advertise ONLY the devnet's chain (Arbitrum Sepolia) to the wallet.
        // Offering mainnet too let MetaMask negotiate a mainnet-only session and
        // silently sign the payment on Arbitrum One, while the app polls the
        // Sepolia RPC for the receipt → "timed out waiting for transaction to
        // confirm". Proposing only Sepolia forces the wallet onto the manifest's
        // chain (it prompts to add the network if missing), so payments and the
        // nodes' on-chain verification happen on the same chain.
        val devnetChain = AutonomiChain.ARBITRUM_SEPOLIA
        val chains = AppKitChainsPresets.ethChains.values
            .filter { it.id == devnetChain.caip2 }
            .ifEmpty { AppKitChainsPresets.ethChains.values.toList() }
        AppKit.setChains(chains)

        AppKit.setDelegate(Delegate)
        refreshAccount()
    }

    /// Read the connected account into our state (call after connect / resume).
    fun refreshAccount() {
        val account = AppKit.getAccount()
        _state.value = if (account == null) {
            WalletState(status = "Not connected")
        } else {
            _state.value.copy(address = account.address, chainId = account.chain.id, status = "Connected")
        }
        if (account != null) refreshBalances() else _state.value = _state.value.copy(antBalance = null, ethBalance = null)
    }

    /// Read the connected wallet's ANT + ETH balances from its chain's public RPC
    /// (`eth_getBalance` for the gas token, ERC-20 `balanceOf` for ANT). Cosmetic
    /// and read-only — unknown chain/token leaves a field null (UI shows "—").
    fun refreshBalances() {
        val s = _state.value
        val address = s.address ?: return
        val chainId = s.chainId?.substringAfterLast(':')?.toIntOrNull()
        val chain = chainId?.let { AutonomiChain.fromChainId(it) }
        if (chain == null) {
            _state.value = s.copy(antBalance = null, ethBalance = null)
            return
        }
        balanceScope.launch {
            val eth = rpcResult(chain.rpcUrl, "eth_getBalance", """["$address","latest"]""")?.let { formatUnits(it, 6) }
            val ant = if (chain.hasKnownToken) {
                val data = "0x70a08231" + "0".repeat(24) + address.removePrefix("0x").lowercase()
                rpcResult(chain.rpcUrl, "eth_call", """[{"to":"${chain.tokenAddress}","data":"$data"},"latest"]""")
                    ?.let { formatUnits(it, 4) }
            } else null
            _state.value = _state.value.copy(antBalance = ant, ethBalance = eth)
        }
    }

    /// One read-only JSON-RPC call; returns the `result` hex string or null.
    private suspend fun rpcResult(rpcUrl: String, method: String, params: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
                val conn = (java.net.URL(rpcUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                org.json.JSONObject(resp).optString("result").takeIf { it.startsWith("0x") }
            }.getOrNull()
        }

    /// Format a 0x hex quantity as a token amount (÷1e18). Accumulates in Double —
    /// fine for a display value (we only show a few decimals).
    private fun formatUnits(hex: String, places: Int): String {
        var v = 0.0
        for (c in hex.lowercase()) {
            val d = Character.digit(c, 16)
            if (d >= 0) v = v * 16 + d
        }
        return String.format(java.util.Locale.US, "%.${places}f", v / 1e18)
    }

    /// Core: fire one JSON-RPC request to the connected wallet and await its
    /// result (delivered via onSessionRequestResponse). One in flight at a time.
    /// NOTE: appkit 1.4.1's Request has no chainId field — requests target the
    /// session's *selected* chain, so use [switchChain] to move the wallet first.
    private suspend fun rpc(method: String, params: String): String {
        val deferred = CompletableDeferred<String>()
        pending = deferred
        AppKit.request(
            request = Request(method = method, params = params),
            onSuccess = { _: SentRequestResult -> /* delivered; result via delegate */ },
            onError = { err: Throwable -> deferred.completeExceptionally(err) },
        )
        return deferred.await()
    }

    /// Send a raw `eth_sendTransaction` (to, calldata). Returns the tx hash.
    ///
    /// Retries on the relay's "Invalid Id": the first request after (re)connect
    /// can be rejected before it reaches the wallet while the session is still
    /// warming up (nothing reaches the wallet, so a retry is safe).
    suspend fun sendTransaction(to: String, data: String, value: String = "0x0"): String {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                return sendOnce(to, data, value)
            } catch (e: Throwable) {
                lastError = e
                if (attempt < 2 && e.message?.contains("invalid id", ignoreCase = true) == true) {
                    _state.value = _state.value.copy(status = "Connecting to wallet…")
                    delay(1500)
                    return@repeat
                }
                throw e
            }
        }
        throw lastError ?: IllegalStateException("send failed")
    }

    private suspend fun sendOnce(to: String, data: String, value: String): String {
        val from = AppKit.getAccount()?.address ?: error("No wallet connected")
        // Explicit gas: MetaMask underprices / defaults the limit to 21000 on the
        // WalletConnect path otherwise. gas=3M ceiling, maxFee 0.5 gwei, prio 0.01
        // gwei — only a ceiling, so generous costs nothing on Arbitrum.
        val txJson = """[{"from":"$from","to":"$to","data":"$data","value":"$value",""" +
            """"gas":"0x2dc6c0","maxFeePerGas":"0x1dcd6500","maxPriorityFeePerGas":"0x989680"}]"""
        _state.value = _state.value.copy(status = "Awaiting wallet signature…")
        val hash = rpc("eth_sendTransaction", txJson)
        _state.value = _state.value.copy(lastTxHash = hash, status = "Signed. tx: $hash")
        return hash
    }

    /// Prompt the wallet to switch to `chainId` (EIP-3326) so subsequent
    /// requests execute on the right chain. Best-effort; ignores the null result
    /// and a failure (e.g. wallet already on that chain / chain not added).
    suspend fun switchChain(chainId: Long) {
        val hex = "0x" + chainId.toString(16)
        _state.value = _state.value.copy(status = "Switching wallet to chain $chainId…")
        runCatching { rpc("wallet_switchEthereumChain", """[{"chainId":"$hex"}]""") }
    }

    /// Convenience: ERC-20 `approve(vault, amount)` on the token contract.
    suspend fun sendApprove(chain: AutonomiChain, amount: String = "0"): String =
        sendTransaction(to = chain.tokenAddress, data = EthCalldata.approve(chain.paymentVaultAddress, amount))

    private object Delegate : AppKit.ModalDelegate {
        override fun onSessionApproved(approvedSession: Modal.Model.ApprovedSession) { refreshAccount() }
        override fun onSessionRejected(rejectedSession: Modal.Model.RejectedSession) {}
        override fun onSessionUpdate(updatedSession: Modal.Model.UpdatedSession) {}
        override fun onSessionEvent(sessionEvent: Modal.Model.SessionEvent) {}
        override fun onSessionEvent(sessionEvent: Modal.Model.Event) {}
        override fun onSessionExtend(session: Modal.Model.Session) {}
        override fun onSessionDelete(deletedSession: Modal.Model.DeletedSession) { refreshAccount() }

        override fun onSessionRequestResponse(response: Modal.Model.SessionRequestResponse) {
            val d = pending ?: return
            pending = null
            when (val res = response.result) {
                is Modal.Model.JsonRpcResponse.JsonRpcResult -> d.complete(res.result)
                is Modal.Model.JsonRpcResponse.JsonRpcError -> d.completeExceptionally(IllegalStateException(res.message))
            }
        }

        override fun onSessionAuthenticateResponse(sessionAuthenticateResponse: Modal.Model.SessionAuthenticateResponse) {}
        override fun onSIWEAuthenticationResponse(response: Modal.Model.SIWEAuthenticateResponse) {}
        override fun onProposalExpired(proposal: Modal.Model.ExpiredProposal) {}
        override fun onRequestExpired(request: Modal.Model.ExpiredRequest) {}
        override fun onConnectionStateChange(state: Modal.Model.ConnectionState) {}
        override fun onError(error: Modal.Model.Error) {
            _state.value = _state.value.copy(status = "Wallet error: ${error.throwable.message}")
            pending?.completeExceptionally(error.throwable)
            pending = null
        }
    }
}
