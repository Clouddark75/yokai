package yokai.presentation.webview

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.LoadingState
import com.kevinnzou.web.WebView
import com.kevinnzou.web.rememberWebViewNavigator
import com.kevinnzou.web.rememberWebViewState
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.BuildConfig
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
    val state = rememberWebViewState(url = url, additionalHttpHeaders = headers)
    val navigator = rememberWebViewNavigator()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val network = remember { Injekt.get<NetworkHelper>() }
    val spoofedPackageName = remember { WebViewUtil.spoofedPackageName(context) }

    var currentUrl by remember { mutableStateOf(url) }
    var showCloudflareHelp by remember { mutableStateOf(false) }

    val webClient = remember {
        createWebViewClient(
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
            headers = headers,
            network = network,
            context = context,
            spoofedPackageName = spoofedPackageName,
        )
    }

    Scaffold(
        topBar = {
            WebViewTopBar(
                state = state,
                initialTitle = initialTitle,
                currentUrl = currentUrl,
                navigator = navigator,
                showCloudflareHelp = showCloudflareHelp,
                uriHandler = uriHandler,
                context = context,
                onNavigateUp = onNavigateUp,
                onShare = onShare,
                onOpenInApp = onOpenInApp,
                onOpenInBrowser = onOpenInBrowser,
                onClearCookies = onClearCookies,
            )
        },
    ) { contentPadding ->
        WebView(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding(),
            navigator = navigator,
            onCreated = { webView ->
                configureWebView(webView, headers)
            },
            client = webClient,
        )
    }
}

@Composable
private fun WebViewTopBar(
    state: com.kevinnzou.web.WebViewState,
    initialTitle: String?,
    currentUrl: String,
    navigator: com.kevinnzou.web.WebViewNavigator,
    showCloudflareHelp: Boolean,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    context: android.content.Context,
    onNavigateUp: () -> Unit,
    onShare: (String) -> Unit,
    onOpenInApp: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onClearCookies: (String) -> Unit,
) {
    Box {
        Column {
            TopAppBar(
                title = {
                    AppBarTitle(
                        title = state.pageTitle ?: initialTitle,
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
                        onShare = onShare,
                        onOpenInApp = onOpenInApp,
                        onOpenInBrowser = onOpenInBrowser,
                        onClearCookies = onClearCookies,
                    )
                },
            )

            if (showCloudflareHelp) {
                CloudflareHelpBanner(uriHandler = uriHandler)
            }
        }

        LoadingIndicator(
            loadingState = state.loadingState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun WebViewActions(
    navigator: com.kevinnzou.web.WebViewNavigator,
    currentUrl: String,
    context: android.content.Context,
    onShare: (String) -> Unit,
    onOpenInApp: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onClearCookies: (String) -> Unit,
) {
    AppBarActions(
        persistentListOf(
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
                isVisible = navigator.canGoBack &&
                    context.extensionIntentForText(currentUrl) != null,
            ),
            AppBar.OverflowAction(
                title = stringResource(MR.strings.open_in_browser),
                onClick = { onOpenInBrowser(currentUrl) },
            ),
            AppBar.OverflowAction(
                title = stringResource(MR.strings.clear_cookies),
                onClick = { onClearCookies(currentUrl) },
            ),
        ),
    )
}

@Composable
private fun CloudflareHelpBanner(uriHandler: androidx.compose.ui.platform.UriHandler) {
    Surface(
        modifier = Modifier.padding(8.dp),
    ) {
        WarningBanner(
            textRes = MR.strings.information_cloudflare_help,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable {
                    uriHandler.openUri(CLOUDFLARE_HELP_URL)
                },
        )
    }
}

@Composable
private fun LoadingIndicator(
    loadingState: LoadingState,
    modifier: Modifier = Modifier,
) {
    when (loadingState) {
        is LoadingState.Initializing -> {
            LinearProgressIndicator(
                modifier = modifier.fillMaxWidth(),
            )
        }
        is LoadingState.Loading -> {
            LinearProgressIndicator(
                progress = { loadingState.progress },
                modifier = modifier.fillMaxWidth(),
            )
        }
        else -> { /* No loading indicator */ }
    }
}

private fun createWebViewClient(
    onUrlChange: (String) -> Unit,
    onPageFinished: (WebView) -> Unit,
    headers: Map<String, String>,
    network: NetworkHelper,
    context: android.content.Context,
    spoofedPackageName: String,
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
        request?.let { req ->
            val urlString = req.url.toString()
            
            return when {
                urlString.startsWith("blob:http") -> false
                urlString.startsWith("intent://") -> true
                else -> {
                    view?.loadUrl(urlString, headers)
                    super.shouldOverrideUrlLoading(view, request)
                }
            }
        }
        return super.shouldOverrideUrlLoading(view, request)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        return try {
            request?.let { req ->
                createInterceptedRequest(req, network, context, spoofedPackageName)
            } ?: super.shouldInterceptRequest(view, request)
        } catch (e: Throwable) {
            super.shouldInterceptRequest(view, request)
        }
    }
}

private fun createInterceptedRequest(
    request: WebResourceRequest,
    network: NetworkHelper,
    context: android.content.Context,
    spoofedPackageName: String,
): WebResourceResponse {
    val internalRequest = Request.Builder().apply {
        url(request.url.toString())
        request.requestHeaders.forEach { (key, value) ->
            if (key == "X-Requested-With" && value in FILTERED_PACKAGE_NAMES(context, spoofedPackageName)) {
                return@forEach
            }
            addHeader(key, value)
        }
        method(request.method, null)
    }.build()

    val response = network.nonCloudflareClient.newCall(internalRequest).execute()
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

private fun configureWebView(webView: WebView, headers: Map<String, String>) {
    webView.setDefaultSettings()

    // Debug mode (chrome://inspect/#devices)
    if (BuildConfig.DEBUG && webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        WebView.setWebContentsDebuggingEnabled(true)
    }

    headers["user-agent"]?.let { userAgent ->
        webView.settings.userAgentString = userAgent
    }
}

private fun isCloudflareChallenge(html: String): Boolean {
    return CLOUDFLARE_INDICATORS.any { it in html }
}

private fun FILTERED_PACKAGE_NAMES(context: android.content.Context, spoofedPackageName: String) = setOf(
    context.packageName,
    spoofedPackageName,
)

private const val CLOUDFLARE_HELP_URL = "https://mihon.app/docs/guides/troubleshooting/#cloudflare"

private val CLOUDFLARE_INDICATORS = listOf(
    "window._cf_chl_opt",
    "Ray ID is",
)
