package dev.handypvz

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.util.Log
import android.view.View

object Links {
    private const val TAG = "Links"

    private val TELEGRAM_PACKAGES = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "org.thunderdog.challegram"
    )

    /** Opens [url] externally; t.me links go straight to the Telegram app when it is installed. */
    fun open(context: Context, url: String) {
        val uri = Uri.parse(url)
        if (uri.host == "t.me") {
            for (pkg in TELEGRAM_PACKAGES) {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addNewTaskIfNeeded(context)
                    )
                    return
                } catch (e: ActivityNotFoundException) {
                    Log.d(TAG, "$pkg not installed")
                }
            }
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addNewTaskIfNeeded(context))
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "no app to open $url")
        }
    }

    /** Replaces every [URLSpan] with a span that routes the tap through [open]. */
    fun routeLinks(context: Context, text: Spanned): CharSequence {
        val body = SpannableStringBuilder(text)
        for (span in body.getSpans(0, body.length, URLSpan::class.java)) {
            val start = body.getSpanStart(span)
            val end = body.getSpanEnd(span)
            val flags = body.getSpanFlags(span)
            val url = span.url
            body.removeSpan(span)
            body.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = open(context, url)
            }, start, end, flags)
        }
        return body
    }

    private fun Intent.addNewTaskIfNeeded(context: Context): Intent =
        if (context is android.app.Activity) this else addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
