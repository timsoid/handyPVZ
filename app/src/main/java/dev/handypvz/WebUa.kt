package dev.handypvz

import android.webkit.WebView

object WebUa {
    fun clean(webView: WebView): String =
        webView.settings.userAgentString
            .replace("; wv", "")
            .replace(" Version/4.0", "")
            .replace(Regex(""" Build/[^;)]+"""), "")
}
