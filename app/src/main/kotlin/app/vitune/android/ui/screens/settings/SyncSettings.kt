package app.vitune.android.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.vitune.providers.innertube.Innertube

@Composable
fun SyncSettings() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("preferences", Context.MODE_PRIVATE) }
    
    var ytCookie by rememberSaveable { 
        mutableStateOf(preferences.getString("yt_account_cookie", "") ?: "") 
    }
    var showYtCookieDialog by remember { mutableStateOf(false) }
    var tempCookieText by rememberSaveable { mutableStateOf("") }

    if (showYtCookieDialog) {
        AlertDialog(
            onDismissRequest = { showYtCookieDialog = false },
            title = { Text("YouTube Music Cookie Token") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Paste your cookie token below to sync your playlists.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempCookieText,
                        onValueChange = { tempCookieText = it },
                        label = { Text("Paste cookie here...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleaned = tempCookieText.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("***") }
                            .joinToString("; ")
                        ytCookie = cleaned
                        preferences.edit().putString("yt_account_cookie", cleaned).apply()
                        Innertube.cookie = cleaned
                        showYtCookieDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showYtCookieDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "YouTube Music Sync",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                tempCookieText = ytCookie
                showYtCookieDialog = true
            }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Paste Cookie Token",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (ytCookie.isNotBlank()) "Cookie token active (Tap to update)" else "Paste your raw cookie string manually",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
