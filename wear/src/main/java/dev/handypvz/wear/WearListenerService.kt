package dev.handypvz.wear

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
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
                Log.d(TAG, "onDataChanged: received Ozon state")
                save(code, date, false)
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == VERSION_REQUEST_PATH) {
            scope.launch {
                WearCodeStore.recordPhoneContact(applicationContext)
                val data = JSONObject().put("versionCode", BuildConfig.VERSION_CODE)
                    .put("versionName", BuildConfig.VERSION_NAME).toString().toByteArray(Charsets.UTF_8)
                Wearable.getMessageClient(applicationContext)
                    .sendMessage(messageEvent.sourceNodeId, VERSION_PATH, data)
            }
            return
        }
        if (messageEvent.path == SNAPSHOT_PATH) {
            if (messageEvent.data.size > MAX_SNAPSHOT_BYTES) return
            val snapshot = parseSnapshot(String(messageEvent.data, Charsets.UTF_8)) ?: return
            scope.launch { WearCodeStore.saveSnapshot(applicationContext, snapshot) }
            return
        }
        if (messageEvent.path == PATH) {
            val payload = String(messageEvent.data, Charsets.UTF_8)
            val parts = payload.split('|')
            val code = parts.getOrNull(0)
            val date = parts.getOrNull(1)?.toLongOrNull() ?: LocalDate.now(MSK).toEpochDay()
            if (code.isNullOrBlank()) return
            Log.d(TAG, "onMessageReceived: received Ozon state")
            save(code, date, true)
        }
    }

    private fun parseSnapshot(payload: String): IncomingSnapshot? = try {
        val json = JSONObject(payload)
        require(json.getInt("version") == 2)
        val source = java.util.UUID.fromString(json.getString("sourceId")).toString()
        val revision = json.getLong("revision")
        require(revision >= 0)
        val nonce = if (json.has("requestNonce"))
            java.util.UUID.fromString(json.getString("requestNonce")).toString() else null
        val ozonJson = json.getJSONObject("ozon")
        val wbJson = json.getJSONObject("wb")
        val ozonState = ozonJson.getString("state")
        val wbState = wbJson.getString("state")
        val states = setOf("unknown", "ready", "no_order", "login_required")
        require(ozonState in states && wbState in states)
        val ozon = if (ozonState == "ready") {
            val barcode = ozonJson.getString("barcode")
            require(OzonBarcode.isValid(barcode))
            WearStoredCode(barcode, ozonJson.getLong("epochDay"))
        } else null
        val wb = if (wbState == "ready") {
            val qr = wbJson.getString("qrData")
            require(qr.isNotEmpty())
            val expiry = if (wbJson.isNull("expiresAt")) null else wbJson.getLong("expiresAt")
            WearStoredWbCode(qr, expiry)
        } else null
        val requiredWear = if (json.has("requiredWearVersionCode"))
            json.getInt("requiredWearVersionCode").also { require(it > 0) } else null
        IncomingSnapshot(source, revision, nonce, ozon, ozonState, wb, wbState, requiredWear)
    } catch (_: Exception) {
        null
    }

    private fun save(code: String, date: Long, fromMessage: Boolean) {
        scope.launch {
            WearCodeStore.save(applicationContext, code, date, fromMessage)
        }
    }

    companion object {
        private const val TAG = "WearListener"
        private const val PATH = "/ozon_code"
        private const val SNAPSHOT_PATH = "/marketplace_snapshot"
        private const val VERSION_REQUEST_PATH = "/watch_version_request"
        private const val VERSION_PATH = "/watch_version"
        private const val MAX_SNAPSHOT_BYTES = 80_000
        private val MSK: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
