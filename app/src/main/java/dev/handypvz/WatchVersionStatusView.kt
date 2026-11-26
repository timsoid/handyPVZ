package dev.handypvz

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reads status in the main process through a provider, including from the WB process.
 * When [onlyWhenUpdateRequired] is set, the view is hidden unless the watch needs an update
 * (used on the main screen); the install sheet keeps showing every state.
 */
class WatchVersionStatusView(
    private val context: Context,
    private val view: TextView,
    private val onlyWhenUpdateRequired: Boolean = false,
    private val onUpdateRequired: (Boolean) -> Unit = {}
) {
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = render()
    }

    fun start() {
        context.contentResolver.registerContentObserver(UiStateProvider.uri(context), false, observer)
        render()
    }

    fun stop() = context.contentResolver.unregisterContentObserver(observer)

    private fun render() {
        val state = UiStateProvider.read(context)
        val code = state.getInt(UiStateProvider.CODE, -1)
        val time = state.getLong(UiStateProvider.TIME, 0)
        val name = state.getString(UiStateProvider.NAME).orEmpty().ifBlank { code.toString() }
        val versionState = watchVersionState(if (time > 0) code else null, BuildConfig.REQUIRED_WEAR_VERSION_CODE)
        if (versionState == WatchVersionState.UNKNOWN) {
            onUpdateRequired(false)
            if (onlyWhenUpdateRequired) view.visibility = View.GONE
            else {
                view.visibility = View.VISIBLE
                view.setText(R.string.watch_version_unknown)
                view.setTextColor(context.getColor(R.color.on_surface_muted))
            }
            return
        }
        val date = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(FORMAT)
        val outdated = versionState == WatchVersionState.UPDATE_REQUIRED
        onUpdateRequired(outdated)
        if (onlyWhenUpdateRequired && !outdated) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = when {
            code == 0 -> context.getString(R.string.watch_version_legacy, date)
            outdated -> context.getString(R.string.watch_version_update, name, BuildConfig.REQUIRED_WEAR_VERSION_NAME, date)
            else -> context.getString(R.string.watch_version_current, name, date)
        }
        view.setTextColor(context.getColor(if (outdated) R.color.status_warn else R.color.status_ok))
    }

    companion object {
        private val FORMAT = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")
    }
}
