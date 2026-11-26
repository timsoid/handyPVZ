package dev.handypvz

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Live phone→watch connectivity line: checks reachable nodes on refresh and shows the last
 * contact timestamp recorded in [UiStateProvider]. No background polling — refresh is called
 * from onResume, and the content observer re-renders when a watch message arrives.
 */
class WatchConnectionView(
    private val context: Context,
    private val view: TextView
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connected: Boolean? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = render()
    }

    fun start() {
        context.contentResolver.registerContentObserver(UiStateProvider.uri(context), false, observer)
        refresh()
    }

    fun stop() {
        context.contentResolver.unregisterContentObserver(observer)
    }

    fun refresh() {
        scope.launch {
            connected = WatchConnection.isConnected(context)
            render()
        }
    }

    private fun render() {
        val state = UiStateProvider.read(context)
        val time = state.getLong(UiStateProvider.TIME, 0)
        when {
            connected == true -> {
                view.text = context.getString(R.string.watch_connected)
                view.setTextColor(context.getColor(R.color.status_ok))
            }
            time > 0 -> {
                val date = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(FORMAT)
                view.text = context.getString(R.string.watch_disconnected_last, date)
                view.setTextColor(context.getColor(R.color.status_warn))
            }
            else -> {
                view.text = context.getString(R.string.watch_never_connected)
                view.setTextColor(context.getColor(R.color.on_surface_muted))
            }
        }
    }

    companion object {
        private val FORMAT = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")
    }
}
