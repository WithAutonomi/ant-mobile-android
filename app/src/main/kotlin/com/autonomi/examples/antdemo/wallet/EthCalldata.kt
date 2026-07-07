package com.autonomi.examples.antdemo.wallet

import java.math.BigInteger

/// Minimal, dependency-free EVM ABI calldata encoding — Kotlin port of the iOS
/// spike's `EthCalldata.swift` (which mirrors `ant-ui/utils/payment.ts`).
/// Kotlin's BigInteger makes the uint256 encoding cleaner than the Swift side.
///
/// Function selectors are hard-coded (precomputed keccak256 of the signature):
///   approve(address,uint256)                    -> 0x095ea7b3
///   payForQuotes((address,uint256,bytes32)[])   -> 0xb6c2141b
///   payForMerkleTree(uint8,(bytes32,(address,uint256)[16])[],uint64) -> 0x5460f240
/// (Verified with `cast sig`; 0x77a23fd7 was wrong — no such function on the
/// deployed PaymentVault, so calls reverted with empty data.)
object EthCalldata {
    private const val APPROVE_SELECTOR = "095ea7b3"
    private const val PAY_FOR_QUOTES_SELECTOR = "b6c2141b"
    private const val PAY_FOR_MERKLE_TREE_SELECTOR = "5460f240"

    /// keccak256("MerklePaymentMade(bytes32,uint8,uint256,uint64)") — topic0 of
    /// the event the PaymentVault emits from `payForMerkleTree`. `winnerPoolHash`
    /// is `indexed`, so it lands in `topics[1]` of the matching receipt log; that
    /// hash is what `finalizeUploadMerkle` needs.
    const val MERKLE_PAYMENT_MADE_TOPIC0 =
        "0x89f0ad3859fec321e325bcc553fe234bcad374789a86f7ba932067f3f05affec"

    /// ERC-20 `approve(spender, amount)`. `amount` is a base-10 string
    /// (atto-token amounts exceed Long).
    fun approve(spender: String, amount: String): String =
        "0x" + APPROVE_SELECTOR + wordAddress(spender) + wordUint(amount)

    /// A single PaymentVault quote payment.
    data class QuotePayment(
        val rewardsAddress: String,  // 0x… address
        val amount: String,          // base-10 atto-token amount
        val quoteHash: String,       // 0x… 32-byte hash
    )

    /// PaymentVault `payForQuotes((address,uint256,bytes32)[])`. The tuple is
    /// static (3 words), so the dynamic array encodes as offset → length →
    /// each tuple's 3 words laid out consecutively.
    fun payForQuotes(payments: List<QuotePayment>): String {
        val sb = StringBuilder()
        sb.append(wordUint(BigInteger.valueOf(0x20)))            // offset to array data
        sb.append(wordUint(BigInteger.valueOf(payments.size.toLong())))
        for (p in payments) {
            sb.append(wordAddress(p.rewardsAddress))
            sb.append(wordUint(p.amount))
            sb.append(wordBytes32(p.quoteHash))
        }
        return "0x" + PAY_FOR_QUOTES_SELECTOR + sb
    }

    /// One candidate node inside a pool commitment (mirrors the FFI
    /// `CandidateNodeEntry`).
    data class MerkleCandidate(
        val rewardsAddress: String,  // 0x… address
        val amount: String,          // base-10 atto-token amount (node price)
    )

    /// One pool commitment (mirrors the FFI `PoolCommitmentEntry`). `candidates`
    /// must contain exactly 16 entries.
    data class PoolCommitment(
        val poolHash: String,                // 0x… 32-byte hash
        val candidates: List<MerkleCandidate>,  // exactly 16
    )

    /// PaymentVault `payForMerkleTree(uint8 depth, PoolCommitment[], uint64 ts)`
    /// where `PoolCommitment = (bytes32 poolHash, (address,uint256)[16] candidates)`.
    ///
    /// `PoolCommitment` is a *fully static* tuple — bytes32 (1 word) + a fixed
    /// `[16]` of static `(address,uint256)` (32 words) = 33 words, no dynamic
    /// parts. So the dynamic `PoolCommitment[]` needs no per-element offsets:
    /// just `length` then each element's 33 words consecutively. The top-level
    /// arg head is 3 words (depth · offset 0x60 · ts).
    fun payForMerkleTree(depth: Int, poolCommitments: List<PoolCommitment>, timestamp: BigInteger): String {
        val sb = StringBuilder()
        sb.append(wordUint(BigInteger.valueOf(depth.toLong())))  // head 0: depth (uint8)
        sb.append(wordUint(BigInteger.valueOf(0x60)))            // head 1: offset to array (3 head words)
        sb.append(wordUint(timestamp))                           // head 2: merklePaymentTimestamp
        sb.append(wordUint(BigInteger.valueOf(poolCommitments.size.toLong())))  // array length
        for (pc in poolCommitments) {
            sb.append(wordBytes32(pc.poolHash))
            require(pc.candidates.size == 16) {
                "each pool commitment must have exactly 16 candidates, got ${pc.candidates.size}"
            }
            for (c in pc.candidates) {
                sb.append(wordAddress(c.rewardsAddress))
                sb.append(wordUint(c.amount))
            }
        }
        return "0x" + PAY_FOR_MERKLE_TREE_SELECTOR + sb
    }

    // MARK: word encoders — each returns a 64-hex-char / 32-byte word

    private fun wordAddress(address: String): String {
        val clean = strip0x(address).lowercase()
        require(clean.length == 40) { "address must be 20 bytes: $address" }
        return "0".repeat(24) + clean
    }

    private fun wordBytes32(value: String): String {
        val clean = strip0x(value)
        require(clean.length == 64) { "bytes32 must be 32 bytes: $value" }
        return clean
    }

    private fun wordUint(decimal: String): String = wordUint(BigInteger(decimal))

    private fun wordUint(value: BigInteger): String {
        require(value.signum() >= 0) { "uint256 must be non-negative: $value" }
        val hex = value.toString(16)
        require(hex.length <= 64) { "value overflows uint256: $value" }
        return "0".repeat(64 - hex.length) + hex
    }

    private fun strip0x(s: String): String =
        if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2) else s
}
