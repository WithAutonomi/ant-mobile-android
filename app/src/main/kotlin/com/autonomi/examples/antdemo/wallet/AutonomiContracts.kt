package com.autonomi.examples.antdemo.wallet

/// On-chain coordinates for Autonomi payments — Kotlin port of the iOS spike's
/// `AutonomiContracts.swift`, mirroring the desktop app's `wallet-config.ts`.
/// Uploads are paid by approving the payment-vault contract to spend the
/// network token, then calling `payForQuotes` / `payForMerkleTree`.
///
/// Spike scope: only Arbitrum One (mainnet) addresses are known here (from the
/// desktop config). For Arbitrum Sepolia the token/vault addresses differ per
/// devnet; fill them from your devnet manifest before testing on testnet.
enum class AutonomiChain(
    val chainId: Int,
    val tokenAddress: String,
    val paymentVaultAddress: String,
) {
    ARBITRUM_ONE(
        chainId = 42161,
        tokenAddress = "0xa78d8321B20c4Ef90eCd72f2588AA985A4BDb684",
        paymentVaultAddress = "0x9A3EcAc693b699Fc0B2B6A50B5549e50c2320A26",
    ),
    ARBITRUM_SEPOLIA(
        chainId = 421614,
        // Deployed ArbitrumSepoliaTest contracts (evmlib::Network::ArbitrumSepoliaTest),
        // matching the devnet manifest's evm.payment_token / payment_vault.
        tokenAddress = "0x4bc1aCE0E66170375462cB4E6Af42Ad4D5EC689C",
        paymentVaultAddress = "0xd742e8cfef27a9a884f3effa239ee2f39c276522",
    );

    /// CAIP-2 id used to build a WalletConnect `eip155:<id>` blockchain.
    val caip2: String get() = "eip155:$chainId"

    /// Public JSON-RPC endpoint for read-only balance queries on this chain.
    val rpcUrl: String
        get() = when (this) {
            ARBITRUM_ONE -> "https://arb1.arbitrum.io/rpc"
            ARBITRUM_SEPOLIA -> "https://sepolia-rollup.arbitrum.io/rpc"
        }

    /// Whether the ERC-20 token address is a real (non-zero) deployment — the
    /// Sepolia address is a per-devnet placeholder, so ANT balance is unknown there.
    val hasKnownToken: Boolean
        get() = tokenAddress != "0x0000000000000000000000000000000000000000"

    companion object {
        /// Map a connected wallet's chain id back to a known Autonomi chain.
        fun fromChainId(chainId: Int): AutonomiChain? = entries.firstOrNull { it.chainId == chainId }
    }
}
