package dev.handypvz

import android.webkit.CookieManager

/** Detects whether the shared WebView cookie jar holds a logged-in Ozon session. */
object OzonAuth {

    private const val URL = "https://www.ozon.ru"
    private const val USER_ID = "__Secure-user-id"
    private const val ACCESS_TOKEN = "__Secure-access-token"

    /**
     * Ozon hands every visitor an anonymous session, so the mere presence of a token means
     * nothing: anonymous sessions carry `__Secure-user-id=0`. When the user-id cookie is not
     * visible at all, a present access token is treated as "logged in" (be lenient, never lock
     * out a working session).
     */
    fun isLoggedIn(): Boolean {
        val cookies = CookieManager.getInstance().getCookie(URL).orEmpty()
            .split(';')
            .mapNotNull {
                val i = it.indexOf('=')
                if (i < 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
            }
            .toMap()
        val userId = cookies[USER_ID]
        if (userId != null) return userId.isNotEmpty() && userId != "0"
        return cookies.containsKey(ACCESS_TOKEN)
    }
}
