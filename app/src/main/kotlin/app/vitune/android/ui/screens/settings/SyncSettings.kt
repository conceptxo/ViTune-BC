package app.vitune.android.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.vitune.android.R
import app.vitune.android.ui.components.*
import app.vitune.android.ui.screens.settings.dialogs.LoginDialog
import app.vitune.android.ui.theme.colorPalette
import app.vitune.android.ui.theme.typography
import app.vitune.android.utils.*
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.piped.Piped
import app.vitune.providers.piped.models.Instance
import app.vitune.providers.piped.models.PipedSession
import app.vitune.database.Database
import app.vitune.database.transaction
import app.vitune.compose.persist.persistList
import app.vitune.compose.persist.rememberPersistList
import io.ktor.http.Url
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.fastForEachIndexed


@Composable
fun SyncSettings() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()

    val preferences = remember { context.getSharedPreferences("preferences", Context.MODE_PRIVATE) }
    var ytCookie by rememberSaveable { mutableStateOf(preferences.getString("yt_account_cookie", "") ?: "") }
    var showWebLoginDialog by remember { mutableStateOf(false) }
    var showYtCookieDialog by remember { mutableStateOf(false) }
    var tempCookieText by rememberSaveable { mutableStateOf("") }
    
    var linkingPiped by remember { mutableStateOf(false) }
    var deletingPipedSession: Int? by remember { mutableStateOf(null) }

    val pipedSessions by rememberPersistList<PipedSession>(tag = "database/piped_sessions")

    // 1. WebView Login Dialog
    if (showWebLoginDialog) {
        LoginDialog(
            onDismiss = { showWebLoginDialog = false },
            onLoginSuccess = { cookies ->
                preferences.edit().putString("yt_account_cookie", cookies).apply()
                Innertube.cookie = cookies
                ytCookie = cookies
            }
        )
    }

    // 2. Manual Cookie Paste Dialog
    if (showYtCookieDialog) {
        DefaultDialog(
            onDismiss = { showYtCookieDialog = false },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                BasicText(
                    text = "YouTube Music Cookie Token",
                    style = typography.m.semiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                BasicText(
                    text = "Paste your cookie token below to sync your playlists.",
                    style = typography.xxs.secondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = tempCookieText,
                    onValueChange = { tempCookieText = it },
                    hintText = "Paste cookie here...",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                DialogTextButton(
                    text = "Save",
                    primary = true,
                    onClick = {
                        val cleaned = tempCookieText.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("***") }
                            .joinToString("; ")
                        ytCookie = cleaned
                        preferences.edit().putString("yt_account_cookie", cleaned).apply()
                        Innertube.cookie = cleaned
                        showYtCookieDialog = false
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }

    // Piped Link Dialog
    if (linkingPiped) {
        DefaultDialog(
            onDismiss = { linkingPiped = false },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            var isLoading by rememberSaveable { mutableStateOf(false) }
            var hasError by rememberSaveable { mutableStateOf(false) }
            var successful by remember { mutableStateOf(false) }

            when {
                successful -> BasicText(
                    text = stringResource(R.string.piped_session_created_successfully),
                    style = typography.xs.semiBold.center,
                    modifier = Modifier.padding(all = 24.dp)
                )

                hasError -> ConfirmationDialogBody(
                    text = stringResource(R.string.error_piped_link),
                    onDismiss = { },
                    onCancel = { linkingPiped = false },
                    onConfirm = { hasError = false }
                )

                isLoading -> CircularProgressIndicator(modifier = Modifier.padding(all = 8.dp))

                else -> Box(modifier = Modifier.fillMaxWidth()) {
                    var backgroundLoading by rememberSaveable { mutableStateOf(false) }
                    if (backgroundLoading) CircularProgressIndicator(modifier = Modifier.align(Alignment.TopEnd))

                    Column(modifier = Modifier.fillMaxWidth()) {
                        var instances by persistList<Instance>(tag = "settings/sync/piped/instances")
                        var loadingInstances by rememberSaveable { mutableStateOf(true) }
                        var selectedInstance: Int? by rememberSaveable { mutableStateOf(null) }
                        var username by rememberSaveable { mutableStateOf("") }
                        var password by rememberSaveable { mutableStateOf("") }
                        var canSelect by rememberSaveable { mutableStateOf(false) }
                        var instancesUnavailable by rememberSaveable { mutableStateOf(false) }
                        var customInstance: String? by rememberSaveable { mutableStateOf(null) }

                        LaunchedEffect(Unit) {
                            Piped.getInstances()?.getOrNull()?.let {
                                selectedInstance = null
                                instances = it.toImmutableList()
                                canSelect = true
                            } ?: run { instancesUnavailable = true }
                            loadingInstances = false

                            backgroundLoading = true
                            runCatching {
                                val credential = credentialManager.get(context)
                                if (credential != null) {
                                    username = credential.id
                                    password = credential.password
                                }
                            }.getOrNull()
                            backgroundLoading = false
                        }

                        BasicText(
                            text = stringResource(R.string.piped),
                            style = typography.m.semiBold
                        )

                        if (customInstance == null) ValueSelectorSettingsEntry(
                            title = stringResource(R.string.instance),
                            selectedValue = selectedInstance,
                            values = instances.indices.toImmutableList(),
                            onValueSelect = { selectedInstance = it },
                            valueText = { idx ->
                                idx?.let { instances.getOrNull(it)?.name }
                                    ?: if (instancesUnavailable) stringResource(R.string.error_piped_instances_unavailable)
                                    else stringResource(R.string.click_to_select)
                            },
                            isEnabled = !instancesUnavailable && canSelect,
                            usePadding = false,
                            trailingContent = if (loadingInstances) {
                                { CircularProgressIndicator() }
                            } else null
                        )
                        SwitchSettingsEntry(
                            title = stringResource(R.string.custom_instance),
                            text = null,
                            isChecked = customInstance != null,
                            onCheckedChange = {
                                customInstance = if (customInstance == null) "" else null
                            },
                            usePadding = false
                        )
                        customInstance?.let { instance ->
                            Spacer(modifier = Modifier.height(8.dp))
                            TextField(
                                value = instance,
                                onValueChange = { customInstance = it },
                                hintText = stringResource(R.string.base_api_url),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        TextField(
                            value = username,
                            onValueChange = { username = it },
                            hintText = stringResource(R.string.username),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        TextField(
                            value = password,
                            onValueChange = { password = it },
                            hintText = stringResource(R.string.password),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Password
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        DialogTextButton(
                            text = stringResource(R.string.login),
                            primary = true,
                            enabled = (customInstance?.isNotBlank() == true || selectedInstance != null) &&
                                username.isNotBlank() && password.isNotBlank(),
                            onClick = {
                                (customInstance?.let {
                                    runCatching { Url(it) }.getOrNull() ?: runCatching { Url("https://$it") }.getOrNull()
                                } ?: selectedInstance?.let { instances[it].apiBaseUrl })?.let { url ->
                                    coroutineScope.launch {
                                        isLoading = true
                                        val session = Piped.login(
                                            apiBaseUrl = url,
                                            username = username,
                                            password = password
                                        )?.getOrNull()
                                        isLoading = false
                                        if (session == null) {
                                            hasError = true
                                            return@launch
                                        }

                                        transaction {
                                            Database.insert(
                                                PipedSession(
                                                    apiBaseUrl = session.apiBaseUrl,
                                                    username = username,
                                                    token = session.token
                                                )
                                            )
                                        }

                                        successful = true
                                        linkingPiped = false
                                    }
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }

    // Delete Session Confirmation Dialog
    deletingPipedSession?.let { index ->
        ConfirmationDialog(
            text = "Are you sure you want to delete this Piped session?",
            onDismiss = { deletingPipedSession = null },
            onConfirm = {
                transaction { Database.delete(pipedSessions[index]) }
                deletingPipedSession = null
            }
        )
    }

    SettingsCategoryScreen(title = stringResource(R.string.sync)) {
        SettingsDescription(text = stringResource(R.string.sync_description))

        // YouTube Music Group
        SettingsGroup(title = "YouTube Music Account") {
            SettingsEntry(
                title = "Google Web Login",
                text = if (ytCookie.isNotBlank()) "Logged in via Web" else "Sign in with your Google account seamlessly",
                onClick = { showWebLoginDialog = true }
            )
            SettingsEntry(
                title = "Paste Cookie Token",
                text = if (ytCookie.isNotBlank()) "Cookie token active (Tap to update)" else "Paste your raw cookie string manually",
                onClick = {
                    tempCookieText = ytCookie
                    showYtCookieDialog = true
                }
            )
        }

        SettingsGroup(title = stringResource(R.string.piped)) {
            SettingsEntry(
                title = stringResource(R.string.add_account),
                text = stringResource(R.string.add_account_description),
                onClick = { linkingPiped = true }
            )
            SettingsEntry(
                title = stringResource(R.string.learn_more),
                text = stringResource(R.string.learn_more_description),
                onClick = { uriHandler.openUri("https://github.com/TeamPiped/Piped/blob/master/README.md") }
            )
        }

        SettingsGroup(title = stringResource(R.string.piped_sessions)) {
            if (pipedSessions.isEmpty()) {
                SettingsGroupSpacer()
                BasicText(
                    text = stringResource(R.string.no_items_found),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    style = typography.s.semiBold.center
                )
            } else {
                pipedSessions.fastForEachIndexed { i, session ->
                    SettingsEntry(
                        title = session.username,
                        text = session.apiBaseUrl.toString(),
                        onClick = { },
                        trailingContent = {
                            IconButton(
                                onClick = { deletingPipedSession = i },
                                icon = R.drawable.delete,
                                color = colorPalette.text
                            )
                        }
                    )
                }
            }
        }
    }
}

