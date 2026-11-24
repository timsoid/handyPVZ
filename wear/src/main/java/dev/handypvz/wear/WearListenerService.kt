package dev.handypvz.wear

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class WearListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == PATH) {
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                val code = map.getString("code") ?: continue
                val date = map.getLong("date")
                Log.d(TAG, "onDataChanged: code=$code date=$date")
                save(code, date)
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == PATH) {
            val payload = String(messageEvent.data, Charsets.UTF_8)
            val parts = payload.split('|')
            val code = parts.getOrNull(0)
            val date = parts.getOrNull(1)?.toLongOrNull() ?: LocalDate.now(MSK).toEpochDay()
            if (code.isNullOrBlank()) return
            Log.d(TAG, "onMessageReceived: code=$code date=$date")
            save(code, date)
        }
    }

    private fun save(code: String, date: Long) {
        scope.launch {
            WearCodeStore.save(applicationContext, code, date)
        }
    }

    companion object {
        private const val TAG = "WearListener"
        private const val PATH = "/ozon_code"
        private val MSK: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
