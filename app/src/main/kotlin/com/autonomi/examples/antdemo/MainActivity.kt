package com.autonomi.examples.antdemo

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import com.autonomi.examples.antdemo.deeplink.AntUri
import com.autonomi.examples.antdemo.deeplink.DeepLinkNav
import com.autonomi.examples.antdemo.files.FilesStore
import com.autonomi.examples.antdemo.ui.AntTheme
import com.autonomi.examples.antdemo.ui.AppShell
import com.autonomi.examples.antdemo.ui.ThemeController
import com.autonomi.examples.antdemo.wallet.WalletConnectManager

class MainActivity : ComponentActivity() {
    // Result launcher for the POST_NOTIFICATIONS runtime prompt (API 33+). The
    // transfer foreground service runs regardless, but its ongoing notification
    // is only shown if the permission is granted.
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AntFfiBootstrap.plantOnce(applicationContext)
        ThemeController.load(applicationContext)
        // Let the files store keep a foreground service alive during transfers.
        FilesStore.attach(applicationContext)
        maybeRequestNotificationPermission()
        // WalletConnect: dedicated projectId from https://dashboard.reown.com.
        WalletConnectManager.configure(application, REOWN_PROJECT_ID)
        setContent {
            AntTheme {
                Surface { AppShell() }
            }
        }
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /// Handle an `autonomi://<address>[?name=&filetype=&filename=]` VIEW intent:
    /// start the download (ant-webex-style filename fallbacks) and jump to the
    /// Downloads tab.
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (!data.startsWith("autonomi://")) return
        FilesStore.download(data, applicationContext)
        DeepLinkNav.goto("downloads")
    }

    override fun onResume() {
        super.onResume()
        // The wallet may have approved the session while we were backgrounded.
        WalletConnectManager.refreshAccount()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        // Dedicated Reown (WalletConnect Cloud) projectId for the mobile SDK.
        // projectIds are public client identifiers (shipped in apps), not secrets.
        private const val REOWN_PROJECT_ID = "2cd5b44944e27d5234557a9183dc1cdd"
    }
}
