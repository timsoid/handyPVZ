package dev.handypvz

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.time.LocalDate
import java.time.ZoneId

object WearSync {

    private const val PATH = "/ozon_code"
    private const val NO_CODE = "__NO_CODE__"
    private const val TAG = "WearSync"
    private val MSK: ZoneId = ZoneId.of("Europe/Moscow")

    fun push(context: Context, code: String, epochDay: Long) {
        putData(context, code, epochDay)
        sendMessage(context, "$code|$epochDay")
    }

    fun pushNoCode(context: Context) {
        val today = LocalDate.now(MSK).toEpochDay()
        sendMessage(context, "$NO_CODE|$today")
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

    private fun sendMessage(context: Context, payload: String) {
        Thread {
            try {
                val nodes: List<Node> = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                Log.d(TAG, "connected nodes: ${nodes.map { it.id }}")
                for (node in nodes) {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, PATH, payload.toByteArray(Charsets.UTF_8))
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
