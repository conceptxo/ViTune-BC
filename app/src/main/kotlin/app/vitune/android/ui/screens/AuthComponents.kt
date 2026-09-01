package app.vitune.android.ui.screens

import android.app.Application
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.vitune.providers.innertube.Innertube
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState = _uiState.asStateFlow()

    private val _eventsChannel = MutableSharedFlow<ScreenEvent.Out>()
    val eventFlow = _eventsChannel.asSharedFlow()
    
    private val preferences = application.getSharedPreferences("preferences", android.content.Context.MODE_PRIVATE)

    fun onPageFinished(url: String?) {
        viewModelScope.launch {
            if (url?.contains("youtube.com") == true && !_uiState.value.isLoggedIn) {
                val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
                if (cookies.contains("SID") || cookies.contains("HSID")) {
                    saveCookies(cookies)
                    _uiState.update { it.copy(isLoggedIn = true) }
                    _eventsChannel.emit(ScreenEvent.Out.LoginCompleted)
                }
            }
        }
    }

    private fun saveCookies(cookies: String) {
        viewModelScope.launch {
            preferences.edit().putString("yt_account_cookie", cookies).apply()
            Innertube.cookie = cookies
        }
    }

    data class AuthUiState(val isLoggedIn: Boolean = false)

    sealed interface ScreenEvent {
        sealed class Out {
            data object LoginCompleted : Out()
        }
    }

    companion object {
        fun Factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AuthViewModel(application)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onBack: () -> Unit,
    application: Application
) {
    val context = LocalContext.current
    val authViewModel = remember { AuthViewModel(application) }

    LaunchedEffect(Unit) {
        authViewModel.eventFlow.collectLatest { event ->
            when (event) {
                AuthViewModel.ScreenEvent.Out.LoginCompleted -> {
                    Toast.makeText(context, "Login Successful! Cookies saved.", Toast.LENGTH_LONG).show()
                    onBack()
                }
            }
        }
    }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    authViewModel.onPageFinished(url)
                }
            }
            loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log in to YouTube") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(top = paddingValues.calculateTopPadding())) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webView },
                onRelease = { view ->
                    view.stopLoading()
                    view.destroy()
                }
            )
        }
    }
}

