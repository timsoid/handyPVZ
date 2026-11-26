package dev.handypvz

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** The main process owns DataStore; the isolated WB process communicates only through IPC. */
class WbBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<Message>(Channel.UNLIMITED)
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        queue.trySend(Message.obtain(message))
        true
    })

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (message in queue) {
                when (message.what) {
                    SAVE -> {
                        val qr = message.data.getString(QR)
                        if (!qr.isNullOrEmpty() && qr.toByteArray(Charsets.UTF_8).size <= MAX_QR_BYTES) {
                            CodeStore.saveWb(applicationContext, qr)
                            WearSync.pushSnapshot(applicationContext)
                        }
                    }
                    LOGOUT -> {
                        CodeStore.clearWb(applicationContext, "login_required")
                        WearSync.pushSnapshot(applicationContext)
                    }
                    GET -> Unit
                    else -> continue
                }
                val snapshot = CodeStore.snapshot(applicationContext)
                try {
                    message.replyTo?.send(Message.obtain(null, STATUS).apply {
                        arg1 = message.what
                        data = Bundle().apply { putString(STATE, snapshot.wbState) }
                    })
                } catch (_: RemoteException) {
                    // The activity may have gone away while the request was in progress.
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        queue.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val GET = 1
        const val SAVE = 2
        const val LOGOUT = 3
        const val STATUS = 4
        const val QR = "qr"
        const val STATE = "state"
        const val MAX_QR_BYTES = 8_000
    }
}
