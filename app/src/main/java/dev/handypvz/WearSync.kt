package dev.handypvz

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

object WearSync {

    private const val PATH = "/ozon_code"
    private const val SNAPSHOT_PATH = "/marketplace_snapshot"
    private const val VERSION_REQUEST_PATH = "/watch_version_request"
    private const val NO_CODE = "__NO_CODE__"
    private const val TAG = "WearSync"
    private val MSK: ZoneId = ZoneId.of("Europe/Moscow")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun push(context: Context, code: String, epochDay: Long) {
        putData(context, code, epochDay)
        sendMessage(context, "$code|$epochDay")
        pushSnapshot(context)
    }

    fun pushNoCode(context: Context) {
        val today = LocalDate.now(MSK).toEpochDay()
        sendMessage(context, "$NO_CODE|$today")
        pushSnapshot(context)
    }

    /** Snapshot and revision are read together; old watches continue to receive /ozon_code. */
    fun pushSnapshot(context: Context, requestNonce: String? = null, targetNode: String? = null) {
        val app = context.applicationContext
        scope.launch {
            val snapshot = CodeStore.snapshot(app)
            val ozon = JSONObject().put("state", snapshot.ozonState)
            if (snapshot.ozonState == "ready" && snapshot.ozon != null) {
                ozon.put("barcode", snapshot.ozon.code)
                ozon.put("epochDay", snapshot.ozon.epochDay)
            }
            val wb = JSONObject().put("state", snapshot.wbState)
            if (snapshot.wbState == "ready" && snapshot.wb != null) {
                wb.put("qrData", snapshot.wb.qrData)
                wb.put("expiresAt", snapshot.wb.expiresAt ?: JSONObject.NULL)
            }
            val payload = JSONObject()
                .put("version", 2)
                .put("sourceId", snapshot.sourceId)
                .put("revision", snapshot.revision)
                .put("requiredWearVersionCode", BuildConfig.REQUIRED_WEAR_VERSION_CODE)
                .put("requiredWearVersionName", BuildConfig.REQUIRED_WEAR_VERSION_NAME)
                .put("ozon", ozon)
                .put("wb", wb)
            if (requestNonce != null) payload.put("requestNonce", requestNonce)
            sendMessage(app, payload.toString(), SNAPSHOT_PATH, targetNode)
        }
    }

    fun requestWatchVersion(context: Context) {
        sendMessage(context.applicationContext, "{}", VERSION_REQUEST_PATH)
    }

    private fun putData(context: Context, code: String, epochDay: Long) {
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putString("code", code)
            dataMap.putLong("date", epochDay)
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener { Log.d(TAG, "putDataItem ok") }
            .addOnFailureListener { Log.d(TAG, "putDataItem failed: $it") }
    }

    private fun sendMessage(context: Context, payload: String, path: String = PATH, targetNode: String? = null) {
        Thread {
            try {
                val nodes: List<Node> = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                Log.d(TAG, "connected nodes: ${nodes.map { it.id }}")
                for (node in nodes) {
                    if (targetNode != null && node.id != targetNode) continue
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, path, payload.toByteArray(Charsets.UTF_8))
                        .addOnSuccessListener { Log.d(TAG, "message sent to ${node.id}") }
                        .addOnFailureListener { Log.d(TAG, "message failed to ${node.id}: $it") }
                }
                if (nodes.isEmpty()) Log.d(TAG, "NO connected nodes — watch not reachable via Wearable")
            } catch (e: Exception) {
                Log.d(TAG, "node query failed: $e")
            }
        }.start()
    }
}
