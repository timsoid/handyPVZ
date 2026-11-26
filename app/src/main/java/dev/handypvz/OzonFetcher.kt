package dev.handypvz

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean

sealed class FetchResult {
    data class Success(val code: String) : FetchResult()
    object SessionExpired : FetchResult()
    object CodeUnavailable : FetchResult()
    object NetworkError : FetchResult()
}

class OzonFetcher(private val context: Context) {

    suspend fun fetch(): FetchResult {
        val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchInternal() } ?: FetchResult.NetworkError
        if (result is FetchResult.CodeUnavailable && !OzonAuth.isLoggedIn()) return FetchResult.SessionExpired
        return result
    }

    private suspend fun fetchInternal(): FetchResult = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val webView = WebView(context.applicationContext)
            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = WebUa.clean(webView)
            settings.cacheMode = WebSettings.LOAD_DEFAULT

            val mainHandler = Handler(Looper.getMainLooper())
            val finished = AtomicBoolean(false)

            fun destroyOnMain() {
                if (!finished.compareAndSet(false, true)) return
                mainHandler.post {
                    webView.stopLoading()
                    webView.destroy()
                }
            }

            fun finish(result: FetchResult) {
                if (!finished.compareAndSet(false, true)) return
                Log.d(TAG, "fetch completed: ${result.javaClass.simpleName}")
                webView.stopLoading()
                webView.destroy()
                if (cont.isActive) cont.resume(result)
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    Log.d(
                        TAG,
                        "onReceivedError code=${error.errorCode} desc=${error.description} " +
                            "url=${request.url} main=${request.isForMainFrame}"
                    )
                    if (request.isForMainFrame) finish(FetchResult.NetworkError)
                }
            }

            CookieManager.getInstance().setAcceptCookie(true)
            webView.loadUrl(ORDER_LIST_URL)

            var attempt = 0
            val tick = object : Runnable {
                override fun run() {
                    if (finished.get()) return
                    attempt++
                    val isLast = attempt >= MAX_ATTEMPTS
                    webView.evaluateJavascript(OzonCodeExtractor.JS) { raw ->
                        if (finished.get()) return@evaluateJavascript
                        when (val extract = OzonCodeExtractor.parse(raw)) {
                            is OzonCodeExtractor.Extract.Code ->
                                finish(FetchResult.Success(extract.value))
                            OzonCodeExtractor.Extract.Login -> Unit
                            OzonCodeExtractor.Extract.None -> Unit
                        }
                    }
                    if (isLast) {
                        mainHandler.postDelayed({
                            if (!finished.get()) {
                                finish(FetchResult.CodeUnavailable)
                            }
                        }, GRACE_MS)
                    } else {
                        mainHandler.postDelayed(this, POLL_MS)
                    }
                }
            }
            mainHandler.postDelayed(tick, START_DELAY_MS)

            cont.invokeOnCancellation { destroyOnMain() }
        }
    }

    companion object {
        private const val TAG = "OzonFetcher"
        private const val ORDER_LIST_URL = "https://www.ozon.ru/my/orderlist"
        private const val START_DELAY_MS = 2000L
        private const val POLL_MS = 1000L
        private const val MAX_ATTEMPTS = 4
        private const val GRACE_MS = 500L
        private const val FETCH_TIMEOUT_MS = 12_000L
    }
}
