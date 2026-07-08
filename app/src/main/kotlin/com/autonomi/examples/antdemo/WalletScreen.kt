package com.autonomi.examples.antdemo

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.autonomi.examples.antdemo.wallet.AutonomiChain
import com.autonomi.examples.antdemo.wallet.WalletConnectManager
import com.reown.appkit.ui.components.button.Web3Button
import com.reown.appkit.ui.components.button.rememberAppKitState
import kotlinx.coroutines.launch

/// Wallet — external-signer connect + a payment test, plus mock balances
/// (mirrors ant-ui/pages/wallet.vue). Connect opens the Reown AppKit modal;
/// "Send test approve tx" has the wallet sign a real payment-vault approve.
@Composable
fun WalletScreen(navController: NavController) {
    val wallet by WalletConnectManager.state.collectAsState()
    val appKitState = rememberAppKitState(navController = navController)
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Text("Connection", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Web3Button(state = appKitState)
            wallet.address?.let {
                Text("Chain: ${wallet.chainId ?: "?"}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(wallet.status, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Live balances for the connected wallet on its current chain (desktop
        // wallet page shows token holdings). Read-only; "—" when not connected
        // or the chain/token is unknown.
        Card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Balances", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (wallet.address != null) {
                    Text("Refresh", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { WalletConnectManager.refreshBalances() })
                }
            }
            BalanceRow("ANT", wallet.antBalance ?: "—")
            BalanceRow("ETH", wallet.ethBalance ?: "—")
            if (wallet.address == null) {
                Text("Connect a wallet to see balances.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun BalanceRow(symbol: String, amount: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(symbol, style = MaterialTheme.typography.bodyMedium)
        Text(amount, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
