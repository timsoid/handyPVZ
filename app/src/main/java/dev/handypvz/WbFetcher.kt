package dev.handypvz

import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Runs against the visible WB WebView in its isolated process. */
class WbFetcher(private val webView: WebView) {
    suspend fun fetch(): String? = withContext(Dispatchers.Main.immediate) {
        webView.loadUrl(DELIVERY_URL)
        withTimeoutOrNull(15_000L) {
            repeat(10) {
                delay(1_000L)
                // evaluateJavascript callbacks may never arrive after a redirect.
                val raw = withTimeoutOrNull(800L) {
                    suspendCancellableCoroutine { continuation ->
                        webView.evaluateJavascript(WbCodeExtractor.JS) { value ->
                            if (continuation.isActive) continuation.resume(value)
                        }
                    }
                }
                val image = raw?.let(WbCodeExtractor::parseImage) ?: return@repeat
                val decoded = withContext(Dispatchers.Default) { WbCodeExtractor.decode(image) }
                if (decoded != null) return@withTimeoutOrNull decoded
            }
            null
        }
    }

    companion object {
        const val DELIVERY_URL = "https://www.wildberries.ru/lk/myorders/delivery"
    }
}
