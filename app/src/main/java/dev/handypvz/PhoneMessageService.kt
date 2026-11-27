package dev.handypvz

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

class PhoneMessageService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == VERSION_PATH) {
            if (messageEvent.data.size > 512) return
            try {
                val json = JSONObject(String(messageEvent.data, Charsets.UTF_8))
                val code = json.getInt("versionCode")
                val name = json.optString("versionName").take(50)
                if (code > 0) UiStateProvider.record(applicationContext, messageEvent.sourceNodeId, code, name)
            } catch (_: Exception) { }
            return
        }
        if (messageEvent.path != REQUEST_PATH) return
        Log.d(TAG, "request received from watch")
        val request = try {
            if (messageEvent.data.size > 256) null else {
                val json = JSONObject(String(messageEvent.data, Charsets.UTF_8))
                if (json.optInt("version") != 2) null
                else {
                    val code = json.optInt("watchVersionCode", 0)
                    UiStateProvider.record(applicationContext, messageEvent.sourceNodeId, code.coerceAtLeast(0),
                        json.optString("watchVersionName").take(50))
                    java.util.UUID.fromString(json.getString("nonce")).toString() to
                        json.optBoolean("refreshOzon", false)
                }
            }
        } catch (_: Exception) { null }
        if (request == null && messageEvent.data.isEmpty()) {
            UiStateProvider.record(applicationContext, messageEvent.sourceNodeId, 0)
        }
        WearSync.pushSnapshot(applicationContext, request?.first, messageEvent.sourceNodeId)
        // Legacy requests ask for an Ozon fetch. V2 may request only the saved snapshot.
        if (request != null && !request.second) return
        scope.launch {
            val today = LocalDate.now(MSK).toEpochDay()
            when (val result = OzonFetcher(applicationContext).fetch()) {
                is FetchResult.Success -> {
                    Log.d(TAG, "watch request: code found")
                    CodeStore.saveCode(applicationContext, result.code, today)
                    WearSync.push(applicationContext, result.code, today)
                }
                is FetchResult.NoOrder -> {
                    Log.d(TAG, "watch request: no order to pick up")
                    if (CodeStore.markNoOrder(applicationContext)) WearSync.pushNoCode(applicationContext)
                }
                is FetchResult.CodeUnavailable -> {
                    Log.d(TAG, "watch request: code unavailable")
                    if (CodeStore.markOzonUnavailable(applicationContext)) WearSync.pushSnapshot(applicationContext)
                }
                is FetchResult.SessionExpired -> {
                    Log.d(TAG, "watch request: session expired")
                    Notifier.sessionExpired(applicationContext)
                    WearSync.pushSnapshot(applicationContext)
                }
                is FetchResult.NetworkError -> {
                    Log.d(TAG, "watch request: network error")
                }
            }
        }
    }

    companion object {
        private const val TAG = "PhoneMessageService"
        private const val REQUEST_PATH = "/request_code"
        private const val VERSION_PATH = "/watch_version"
        private val MSK: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
