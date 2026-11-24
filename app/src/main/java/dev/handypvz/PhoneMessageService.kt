package dev.handypvz

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class PhoneMessageService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != REQUEST_PATH) return
        Log.d(TAG, "request received from watch")
        scope.launch {
            val today = LocalDate.now(MSK).toEpochDay()
            when (val result = OzonFetcher(applicationContext).fetch()) {
                is FetchResult.Success -> {
                    Log.d(TAG, "watch request: code found")
                    CodeStore.saveCode(applicationContext, result.code, today)
                    WearSync.push(applicationContext, result.code, today)
                }
                is FetchResult.NotFound -> {
                    Log.d(TAG, "watch request: no orders")
                    CodeStore.clearOrder(applicationContext)
                    WearSync.pushNoCode(applicationContext)
                }
                is FetchResult.SessionExpired -> {
                    Log.d(TAG, "watch request: session expired")
                    Notifier.sessionExpired(applicationContext)
                    WearSync.pushNoCode(applicationContext)
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
        private val MSK: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
