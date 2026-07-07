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
        // TODO(spike): set from your devnet manifest before testing on Sepolia.
        tokenAddress = "0x0000000000000000000000000000000000000000",
        paymentVaultAddress = "0x0000000000000000000000000000000000000000",
    );

    /// CAIP-2 id used to build a WalletConnect `eip155:<id>` blockchain.
    val caip2: String get() = "eip155:$chainId"
}
