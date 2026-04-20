package yokai.presentation.webview

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Message
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.stack.mutableStateStackOf
import com.kevinnzou.web.AccompanistWebChromeClient
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.LoadingState
import com.kevinnzou.web.WebContent
import com.kevinnzou.web.WebView
import com.kevinnzou.web.WebViewNavigator
import com.kevinnzou.web.WebViewState
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.extensionIntentForText
import eu.kanade.tachiyomi.util.system.getHtml
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.i18n.MR
import yokai.presentation.component.AppBar
import yokai.presentation.component.AppBarActions
import yokai.presentation.component.AppBarTitle
import yokai.presentation.component.UpIcon
import yokai.presentation.component.WarningBanner

// ---------------------------------------------------------------------------
// Window model
// ---------------------------------------------------------------------------

/**
 * Represents a single WebView "tab" or popup window within the WebView screen.
 *
 * When [popupMessage] is non-null the window was opened via [onCreateWindow] and the
 * WebView transport must be initialised before the page can load.
 */
class WebViewWindow(webContent: WebContent, val navigator: WebViewNavigator) {
    var state by mutableStateOf(WebViewState(webContent))
    var popupMessage: Message? = null
        private set
    var webView: WebView? = null

    constructor(popupMessage: Message, navigator: WebViewNavigator) : this(WebContent.NavigatorOnly, navigator) {
        this.popupMessage = popupMessage
    }
}

// ---------------------------------------------------------------------------
// Screen composable
// ---------------------------------------------------------------------------

@Composable
fun WebViewScreenContent(
    onNavigateUp: () -> Unit,
    initialTitle: String?,
    url: String,
    onShare: (String) -> Unit,
    onOpenInApp: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onClearCookies: (String) -> Unit,
    headers: Map<String, String> = emptyMap(),
    onUrlChange: (String) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()

    // Stack of open windows; the last item is always the visible one.
    val windowStack = remember {
        mutableStateStackOf(
            WebViewWindow(
                WebContent.Url(url = url, additionalHttpHeaders = headers),
                WebViewNavigator(coroutineScope),
            ),
        )
    }

    val currentWindow = windowStack.lastItemOrNull!!
    val navigator = currentWindow.navigator

    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val network = remember { Injekt.get<NetworkHelper>() }
    val spoofedPackageName = remember { WebViewUtil.spoofedPackageName(context) }

    var currentUrl by remember { mutableStateOf(url) }
    var showCloudflareHelp by remember { mutableStateOf(false) }

    // Used to suppress JS dialogs after the screen has been disposed to avoid
    // WindowManager BadTokenException crashes.
    var isActive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        onDispose { isActive = false }
    }

    // -----------------------------------------------------------------------
    // WebViewClient
    // -----------------------------------------------------------------------

    val webClient = remember {
        createWebViewClient(
            headers = headers,
            network = network,
            context = context,
            spoofedPackageName = spoofedPackageName,
            onUrlChange = { newUrl ->
                currentUrl = newUrl
                onUrlChange(newUrl)
            },
            onPageFinished = { view ->
                scope.launch {
                    val html = view.getHtml()
                    showCloudflareHelp = isCloudflareChallenge(html)
                }
            },
        )
    }

    // -----------------------------------------------------------------------
    // WebChromeClient
    // -----------------------------------------------------------------------

    val webChromeClient = remember {
        object : AccompanistWebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                // Ignore windows not triggered by the user (same behaviour as desktop browsers).
                if (!isUserGesture) return false
                windowStack.push(WebViewWindow(resultMsg, WebViewNavigator(coroutineScope)))
                return true
            }

            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                if (!isActive) { result.confirm(); return true }
                return super.onJsAlert(view, url, message, result)
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                if (!isActive) { result.cancel(); return true }
                return super.onJsConfirm(view, url, message, result)
            }

            override fun onJsPrompt(
                view: WebView,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult,
            ): Boolean {
                if (!isActive) { result.cancel(); return true }
                return super.onJsPrompt(view, url, message, defaultValue, result)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Navigation helpers
    // -----------------------------------------------------------------------

    val popState = remember<() -> Unit> {
        {
            if (windowStack.size == 1) onNavigateUp() else windowStack.pop()
        }
    }

    BackHandler(enabled = windowStack.size > 1, onBack = popState)

    // -----------------------------------------------------------------------
    // Scaffold
    // -----------------------------------------------------------------------

    Scaffold(
        topBar = {
            WebViewTopBar(
                currentWindow = currentWindow,
                initialTitle = initialTitle,
                currentUrl = currentUrl,
                navigator = navigator,
                showCloudflareHelp = showCloudflareHelp,
                uriHandler = uriHandler,
                context = context,
                windowStackSize = windowStack.size,
                onNavigateUp = onNavigateUp,
                onShare = onShare,
                onOpenInApp = onOpenInApp,
                onOpenInBrowser = onOpenInBrowser,
                onClearCookies = onClearCookies,
                onCloseTab = popState,
            )
        },
    ) { contentPadding ->
        // key() ensures the WebView composable is fully recreated when the active window changes,
        // so the factory lambda runs again and the correct WebView instance is mounted.
        key(currentWindow) {
            WebView(
                state = currentWindow.state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .imePadding(),
                navigator = navigator,
                onCreated = { webView ->
                    configureWebView(webView, headers)
                },
                onDispose = { webView ->
                    val owningWindow = windowStack.items.find { it.webView == webView }
                    if (owningWindow == null) {
                        // Window was already closed – safe to destroy the underlying WebView.
                        webView.destroy()
                    } else {
                        // The composable is being unmounted while the window is still alive (e.g.
                        // another tab came to the foreground). Switch to NavigatorOnly so the state
                        // is preserved and the WebView can be re-attached without a reload.
                        owningWindow.state.content = WebContent.NavigatorOnly
                    }
                },
                client = webClient,
                chromeClient = webChromeClient,
                factory = { ctx ->
                    currentWindow.webView
                        ?: WebView(ctx).also { webView ->
                            currentWindow.webView = webView
                            currentWindow.popupMessage?.let { initializePopup(webView, it) }
                        }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

@Composable
private fun WebViewTopBar(
    currentWindow: WebViewWindow,
    initialTitle: String?,
    currentUrl: String,
    navigator: WebViewNavigator,
    showCloudflareHelp: Boolean,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    context: android.content.Context,
    windowStackSize: Int,
    onNavigateUp: () -> Unit,
    onShare: (String) -> Unit,
    onOpenInApp: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onClearCookies: (String) -> Unit,
    onCloseTab: () -> Unit,
) {
    Box {
        Column {
            TopAppBar(
                title = {
                    AppBarTitle(
                        title = currentWindow.state.pageTitle ?: initialTitle,
                        subtitle = currentUrl,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        UpIcon(navigationIcon = Icons.Outlined.Close)
                    }
                },
                actions = {
                    WebViewActions(
                        navigator = navigator,
                        currentUrl = currentUrl,
                        context = context,
                        windowStackSize = windowStackSize,
                        onShare = onShare,
                        onOpenInApp = onOpenInApp,
                        onOpenInBrowser = onOpenInBrowser,
                        onClearCookies = onClearCookies,
                        onCloseTab = onCloseTab,
                    )
                },
            )

            if (showCloudflareHelp) {
                CloudflareHelpBanner(uriHandler = uriHandler)
            }
        }

        LoadingIndicator(
            loadingState = currentWindow.state.loadingState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun WebViewActions(
    navigator: WebViewNavigator,
    currentUrl: String,
    context: android.content.Context,
    windowStackSize: Int,
    onShare: (String) -> Unit,
    onOpenInApp: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onClearCookies: (String) -> Unit,
    onCloseTab: () -> Unit,
) {
    val baseActions = persistentListOf(
        AppBar.Action(
            title = stringResource(MR.strings.action_webview_back),
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            onClick = { navigator.navigateBack() },
            enabled = navigator.canGoBack,
        ),
        AppBar.Action(
            title = stringResource(MR.strings.action_webview_forward),
            icon = Icons.AutoMirrored.Outlined.ArrowForward,
            onClick = { navigator.navigateForward() },
            enabled = navigator.canGoForward,
        ),
        AppBar.OverflowAction(
            title = stringResource(MR.strings.action_webview_refresh),
            onClick = { navigator.reload() },
        ),
        AppBar.OverflowAction(
            title = stringResource(MR.strings.share),
            onClick = { onShare(currentUrl) },
        ),
        AppBar.OverflowAction(
            title = stringResource(MR.strings.open_in_app),
            onClick = { onOpenInApp(currentUrl) },
            isVisible = navigator.canGoBack && context.extensionIntentForText(currentUrl) != null,
        ),
        AppBar.OverflowAction(
            title = stringResource(MR.strings.open_in_browser),
            onClick = { onOpenInBrowser(currentUrl) },
        ),
        AppBar.OverflowAction(
            title = stringResource(MR.strings.clear_cookies),
            onClick = { onClearCookies(currentUrl) },
        ),
    )

    // Dynamically prepend a "close tab" action when a popup window is open.
    val actions = baseActions.builder().apply {
        if (windowStackSize > 1) {
            add(
                0,
                AppBar.Action(
                    title = stringResource(MR.strings.action_webview_close_tab),
                    icon = ImageVector.vectorResource(R.drawable.ic_tab_close_24px),
                    onClick = onCloseTab,
                ),
            )
        }
    }.build()

    AppBarActions(actions)
}

@Composable
private fun CloudflareHelpBanner(uriHandler: androidx.compose.ui.platform.UriHandler) {
    Surface(modifier = Modifier.padding(8.dp)) {
        WarningBanner(
            textRes = MR.strings.information_cloudflare_help,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable { uriHandler.openUri(CLOUDFLARE_HELP_URL) },
        )
    }
}

@Composable
private fun LoadingIndicator(
    loadingState: LoadingState,
    modifier: Modifier = Modifier,
) {
    when (loadingState) {
        is LoadingState.Initializing -> LinearProgressIndicator(
            modifier = modifier.fillMaxWidth(),
        )
        is LoadingState.Loading -> LinearProgressIndicator(
            progress = { loadingState.progress },
            modifier = modifier.fillMaxWidth(),
        )
        else -> {}
    }
}

// ---------------------------------------------------------------------------
// WebViewClient factory
// ---------------------------------------------------------------------------

private fun createWebViewClient(
    headers: Map<String, String>,
    network: NetworkHelper,
    context: android.content.Context,
    spoofedPackageName: String,
    onUrlChange: (String) -> Unit,
    onPageFinished: (WebView) -> Unit,
) = object : AccompanistWebViewClient() {

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        url?.let(onUrlChange)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished(view)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        url?.let(onUrlChange)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val urlString = request?.url?.toString() ?: return false

        return when {
            // Let blob URLs pass through without interference.
            urlString.startsWith("blob:http") -> false
            // Block intent:// URLs from loading inside the WebView.
            urlString.startsWith("intent://") -> true
            // For regular http/https URLs load them directly with the custom headers.
            urlString.startsWith("http") -> {
                view?.loadUrl(urlString, headers)
                true
            }
            else -> super.shouldOverrideUrlLoading(view, request)
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        return try {
            request?.let { createInterceptedRequest(it, network, context, spoofedPackageName) }
                ?: super.shouldInterceptRequest(view, request)
        } catch (e: Throwable) {
            super.shouldInterceptRequest(view, request)
        }
    }
}

// ---------------------------------------------------------------------------
// Request interception (OkHttp-backed, from upstream repo)
// ---------------------------------------------------------------------------

private fun createInterceptedRequest(
    request: WebResourceRequest,
    network: NetworkHelper,
    context: android.content.Context,
    spoofedPackageName: String,
): WebResourceResponse {
    val filteredPackageNames = setOf(context.packageName, spoofedPackageName)

    val okhttpRequest = Request.Builder().apply {
        url(request.url.toString())
        request.requestHeaders.forEach { (key, value) ->
            // Strip the X-Requested-With header that leaks the real package name to servers.
            if (key == "X-Requested-With" && value in filteredPackageNames) return@forEach
            addHeader(key, value)
        }
        method(request.method, null)
    }.build()

    val response = network.nonCloudflareClient.newCall(okhttpRequest).execute()
    val contentType = response.body.contentType()

    return WebResourceResponse(
        contentType?.let { "${it.type}/${it.subtype}" } ?: "text/html",
        contentType?.charset()?.name() ?: "utf-8",
        response.code,
        response.message,
        response.headers.associate { it.first to it.second },
        response.body.byteStream(),
    )
}

// ---------------------------------------------------------------------------
// WebView configuration
// ---------------------------------------------------------------------------

private fun configureWebView(webView: WebView, headers: Map<String, String>) {
    webView.setDefaultSettings()

    // Enable Chrome remote debugging for debug builds.
    if (BuildConfig.DEBUG &&
        webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    ) {
        WebView.setWebContentsDebuggingEnabled(true)
    }

    headers["user-agent"]?.let { webView.settings.userAgentString = it }
}

// ---------------------------------------------------------------------------
// Popup initialisation
// ---------------------------------------------------------------------------

private fun initializePopup(webView: WebView, message: Message) {
    val transport = message.obj as WebView.WebViewTransport
    transport.webView = webView
    message.sendToTarget()
}

// ---------------------------------------------------------------------------
// Cloudflare helpers
// ---------------------------------------------------------------------------

private fun isCloudflareChallenge(html: String) =
    CLOUDFLARE_INDICATORS.any { it in html }

private val CLOUDFLARE_INDICATORS = listOf(
    "window._cf_chl_opt",
    "Ray ID is",
)

private const val CLOUDFLARE_HELP_URL =
    "https://mihon.app/docs/guides/troubleshooting/#cloudflare"
