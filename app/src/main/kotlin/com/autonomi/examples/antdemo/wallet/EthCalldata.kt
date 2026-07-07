package com.autonomi.examples.antdemo.wallet

import java.math.BigInteger

/// Minimal, dependency-free EVM ABI calldata encoding — Kotlin port of the iOS
/// spike's `EthCalldata.swift` (which mirrors `ant-ui/utils/payment.ts`).
/// Kotlin's BigInteger makes the uint256 encoding cleaner than the Swift side.
///
/// Function selectors are hard-coded (precomputed keccak256 of the signature):
///   approve(address,uint256)                    -> 0x095ea7b3
///   payForQuotes((address,uint256,bytes32)[])   -> 0xb6c2141b
/// (Verified with `cast sig`; 0x77a23fd7 was wrong — no such function on the
/// deployed PaymentVault, so calls reverted with empty data.)
object EthCalldata {
    private const val APPROVE_SELECTOR = "095ea7b3"
    private const val PAY_FOR_QUOTES_SELECTOR = "b6c2141b"

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
