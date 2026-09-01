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
                    Text("Paste your cookie text below. Non-cookie lines will be automatically filtered out.")
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
                        // Extract only valid cookie lines containing '=' and strip headers/metadata
                        val cleaned = tempCookieText.lines()
                            .map { it.trim() }
                            .filter { it.contains("=") && !it.startsWith("***") && !it.startsWith("ETW") }
                            .joinToString("; {\\n}") { it.substringBefore(";") } // Clean formatting
                            
                        // Alternatively, standard semicolon-separated key-values:
                        val finalCookies = tempCookieText.lines()
                            .map { it.trim() }
                            .filter { it.contains("=") && !it.startsWith("***") }
                            .joinToString("; ") { line ->
                                if (line.endsWith(";")) line.dropLast(1) else line
                            }

                        ytCookie = finalCookies
                        preferences.edit().putString("yt_account_cookie", finalCookies).apply()
                        Innertube.cookie = finalCookies
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
                    text = if (ytCookie.isNotBlank()) "Cookie active (Tap to update)" else "Paste your raw cookie string manually",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
