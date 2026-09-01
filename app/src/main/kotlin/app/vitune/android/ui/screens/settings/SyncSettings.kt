package app.vitune.android.ui.screens.settings

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "YouTube Account Sync",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Log in using an embedded Google WebView to securely capture session cookies and sync your private playlists.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { showWebViewLogin = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Google Login Window")
            }
        }
    }
}
