// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import de.wuerl.easyconnect.R
import de.wuerl.easyconnect.ha.DiscoveredHa
import de.wuerl.easyconnect.ui.components.StatusLine

class HaPickerActions(
    val onInput: (String) -> Unit = {},
    val onPick: (DiscoveredHa) -> Unit = {},
    val onContinue: () -> Unit = {},
    val onClose: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HaPickerSheet(picker: HaPickerUi, actions: HaPickerActions) {
    ModalBottomSheet(onDismissRequest = actions.onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        HaPickerContent(picker, actions)
    }
}

@Composable
fun HaPickerContent(picker: HaPickerUi, actions: HaPickerActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.ha_picker_title), style = MaterialTheme.typography.headlineSmall)
        when {
            picker.discovered.isNotEmpty() -> {
                Text(stringResource(R.string.ha_picker_found), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                picker.discovered.forEach { ha -> DiscoveredRow(ha) { actions.onPick(ha) } }
            }
            picker.searching -> StatusLine(stringResource(R.string.ha_picker_searching), working = true)
            else -> Text(stringResource(R.string.ha_picker_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = picker.input,
            onValueChange = actions.onInput,
            label = { Text(stringResource(R.string.ha_picker_address_label)) },
            placeholder = { Text(stringResource(R.string.ha_picker_address_placeholder)) },
            singleLine = true,
            isError = picker.error != null,
            supportingText = picker.error?.let { error -> { Text(error.resolve()) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { actions.onContinue() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.ha_picker_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.onClose) { Text(stringResource(R.string.action_cancel)) }
            Spacer(Modifier.size(8.dp))
            Button(onClick = actions.onContinue, enabled = !picker.checking && picker.input.isNotBlank()) {
                if (picker.checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.size(8.dp))
                }
                Text(stringResource(R.string.action_continue))
            }
        }
    }
}

@Composable
private fun DiscoveredRow(ha: DiscoveredHa, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ha.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(ha.url, ha.version).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Home Assistant's own login page (any auth provider, MFA included) in a WebView. The OAuth
 * redirect is intercepted here, like the official Home Assistant Android app does, so no
 * hosted redirect page or custom URL scheme is needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HaLoginScreen(
    login: HaLoginUi,
    onNavigation: (String) -> Boolean,
    onPageError: (de.wuerl.easyconnect.ui.UiText) -> Unit,
    onClose: () -> Unit,
) {
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val navigate by rememberUpdatedState(onNavigation)
    val reportError by rememberUpdatedState(onPageError)
    val baseHost = remember(login.baseUrl) { login.baseUrl.toUri().host }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack() && login.error == null) view.goBack() else onClose()
    }
    DisposableEffect(Unit) {
        onDispose {
            // Nothing from the login page should outlive the sign-in.
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            webView?.destroy()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close)) }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.titleMedium)
                        Text(login.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
    ) { padding ->
        // edge-to-edge: keep the login form above the keyboard
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.setSupportMultipleWindows(false)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url
                                if (navigate(url.toString())) return true
                                if (url.host == baseHost) return false
                                // Help links and the like open in the browser, not in the login view.
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                if (navigate(url)) view.stopLoading()
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame && !navigate(request.url.toString())) {
                                    reportError(UiText.res(R.string.login_error_page, error.description?.toString().orEmpty()))
                                }
                            }

                            @SuppressLint("WebViewClientOnReceivedSslError")
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                handler.cancel() // never bypass certificate errors
                                reportError(UiText.res(R.string.login_error_ssl))
                            }
                        }
                        loadUrl(login.authorizeUrl)
                        webView = this
                    }
                },
            )
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            login.error?.let { error ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.size(16.dp))
                        Text(error.resolve(), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.size(24.dp))
                        Button(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }
        }
    }
}
