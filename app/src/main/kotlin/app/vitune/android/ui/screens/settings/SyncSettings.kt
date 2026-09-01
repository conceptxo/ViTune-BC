package app.vitune.android.ui.screens.settings

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.vitune.android.R
import app.vitune.android.ui.screens.AuthScreen
import app.vitune.android.ui.screens.Route

@Route
@Composable
fun SyncSettings() {
    val context = LocalContext.current as Application
    var showWebViewLogin by remember { mutableStateOf(false) }

    if (showWebViewLogin) {
        AuthScreen(
            onBack = { showWebViewLogin = false },
            application = context
        )
        return
    }

    SettingsCategoryScreen(title = stringResource(R.string.sync)) {
        SettingsGroup(title = "YouTube Account Sync") {
            SettingsEntry(
                title = "Log in via Google WebView",
                text = "Securely log into your YouTube account to sync playlists automatically",
                onClick = { showWebViewLogin = true }
            )
        }
    }
}

