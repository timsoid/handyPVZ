package dev.handypvz.wear

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.CapabilityClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BarcodeActivity : Activity() {

    private lateinit var labelText: TextView
    private lateinit var cardImage: ImageView
    private lateinit var codeText: TextView
    private lateinit var statusText: TextView
    private lateinit var messageText: TextView
    private lateinit var contactText: TextView
    private lateinit var backgroundImage: ImageView
    private var currentSnapshot: WearSnapshot? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var lastRequestMs = 0L
    private var lastOzonRequestMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val reconnectRequest = Runnable { requestRefresh() }
    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { capability ->
        if (capability.nodes.isNotEmpty()) {
            handler.removeCallbacks(reconnectRequest)
            val wait = (REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestMs)).coerceAtLeast(0)
            handler.postDelayed(reconnectRequest, wait)
        }
    }
    private val releaseAwake = Runnable {
        Log.d(TAG, "keep-awake window over, screen may lock now")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_barcode)
        labelText = findViewById(R.id.labelText)
        cardImage = findViewById(R.id.cardImage)
        codeText = findViewById(R.id.codeText)
        statusText = findViewById(R.id.statusText)
        messageText = findViewById(R.id.messageText)
        contactText = findViewById(R.id.contactText)
        backgroundImage = findViewById(R.id.backgroundImage)

        // Позиции заданы долями высоты экрана, чтобы не зависеть от конкретного разрешения.
        val height = resources.displayMetrics.heightPixels
        place(labelText, LABEL_TOP, height)
        place(cardImage, CARD_TOP, height)
        place(codeText, CODE_TOP, height)
        place(statusText, STATUS_TOP, height)
        place(messageText, 0.36f, height)
        place(contactText, 0.60f, height)
    }

    /** Максимальная яркость и «не гасить экран» ровно на KEEP_AWAKE_MS, потом часы блокируются как обычно. */
    private fun keepAwakeFor60Seconds() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        handler.removeCallbacks(releaseAwake)
        handler.postDelayed(releaseAwake, KEEP_AWAKE_MS)
    }

    private fun place(view: View, topFraction: Float, screenHeight: Int) {
        (view.layoutParams as FrameLayout.LayoutParams).topMargin =
            (screenHeight * topFraction).toInt()
    }

    override fun onResume() {
        super.onResume()
        keepAwakeFor60Seconds()
        Wearable.getCapabilityClient(applicationContext).addListener(capabilityListener, PHONE_CAPABILITY)
        requestRefresh()
        job?.cancel()
        job = scope.launch {
            WearCodeStore.snapshotFlow(applicationContext).collect { snapshot -> renderSnapshot(snapshot) }
        }
    }

    private fun renderSnapshot(snapshot: WearSnapshot) {
        currentSnapshot = snapshot
        val ozon = snapshot.ozon?.takeIf { snapshot.ozonState == "ready" }
        labelText.text = "Ozon"
        val updateRequired = (snapshot.requiredWearVersionCode ?: 0) > BuildConfig.VERSION_CODE
        if (updateRequired) labelText.append(" · ${getString(R.string.wear_update_needed)}")
        labelText.setTextColor(getColor(if (updateRequired) R.color.status_warn else R.color.text_muted))
        labelText.contentDescription = labelText.text
        backgroundImage.setImageResource(R.drawable.bg_barcode)
        if (ozon != null) {
            render(ozon)
        } else {
            showMessage(getString(if (snapshot.ozonState == "no_order") R.string.wear_no_orders else R.string.wear_no_code))
            contactText.text = snapshot.lastPhoneContact?.let {
                val date = java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd.MM.yy 'в' HH:mm"))
                getString(R.string.wear_phone_last_contact, date)
            } ?: getString(R.string.wear_phone_no_contact)
            contactText.visibility = View.VISIBLE
            if (snapshot.ozonState == "unknown") requestRefresh(true)
        }
    }

    private fun showWb(wb: WearStoredWbCode) {
        val metrics = resources.displayMetrics
        val size = (minOf(metrics.widthPixels, metrics.heightPixels) * 0.60f).toInt()
        val bitmap = QrRenderer.render(wb.qrData, size)
        if (bitmap == null) {
            showMessage(getString(R.string.wear_qr_error))
            return
        }
        place(cardImage, 0.21f, metrics.heightPixels)
        place(statusText, 0.84f, metrics.heightPixels)
        cardImage.setImageBitmap(bitmap)
        cardImage.visibility = View.VISIBLE
        labelText.visibility = View.VISIBLE
        codeText.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        messageText.visibility = View.GONE
        contactText.visibility = View.GONE
        val expired = wb.expiresAt != null && wb.expiresAt <= System.currentTimeMillis()
        statusText.text = when {
            expired -> getString(R.string.wear_wb_expired)
            wb.expiresAt == null -> getString(R.string.wear_wb_unknown_expiry)
            else -> getString(R.string.wear_wb_expiry,
                java.time.Instant.ofEpochMilli(wb.expiresAt).atZone(MSK)
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")))
        }
        statusText.setTextColor(getColor(if (expired || wb.expiresAt == null) R.color.status_warn else R.color.status_ok))
    }

    private fun render(stored: WearStoredCode?) {
        val today = LocalDate.now(MSK).toEpochDay()
        when {
            stored == null -> {
                Log.d(TAG, "render: no stored code")
                showMessage(getString(R.string.wear_no_code))
                requestRefresh(true)
            }
            stored.code == NO_CODE -> {
                Log.d(TAG, "render: no-code marker")
                showMessage(getString(R.string.wear_no_orders))
                if (stored.epochDay != today) requestRefresh(true)
            }
            stored.epochDay != today -> {
                Log.d(TAG, "render: stale Ozon code")
                showCode(stored, stale = true)
                requestRefresh(true)
            }
            else -> {
                Log.d(TAG, "render: fresh Ozon code")
                showCode(stored, stale = false)
            }
        }
    }

    private fun showMessage(text: String) {
        labelText.visibility = View.VISIBLE
        cardImage.visibility = View.GONE
        codeText.visibility = View.GONE
        statusText.visibility = View.GONE
        messageText.visibility = View.VISIBLE
        contactText.visibility = View.GONE
        messageText.text = text
    }

    private fun showCode(stored: WearStoredCode, stale: Boolean) {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        place(cardImage, CARD_TOP, screenHeight)
        place(statusText, STATUS_TOP, screenHeight)

        val cardWidth = (screenWidth * CARD_WIDTH).toInt()
        val cardHeight = (screenHeight * CARD_HEIGHT).toInt()
        val bitmap = BarcodeRenderer.renderCard(
            text = stored.code,
            width = cardWidth,
            height = cardHeight,
            cornerRadius = screenWidth * CARD_RADIUS,
            barHeight = (cardHeight * BAR_HEIGHT).toInt()
        )
        cardImage.setImageBitmap(bitmap)

        val date = LocalDate.ofEpochDay(stored.epochDay)
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        codeText.text = OzonBarcode.format(stored.code)
        statusText.text = if (stale) "$date · ${getString(R.string.wear_stale)}" else date
        statusText.setTextColor(getColor(if (stale) R.color.status_warn else R.color.status_ok))

        labelText.visibility = View.VISIBLE
        cardImage.visibility = View.VISIBLE
        codeText.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        messageText.visibility = View.GONE
        contactText.visibility = View.GONE
    }

    private fun requestRefresh(refreshOzon: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = if (refreshOzon) lastOzonRequestMs else lastRequestMs
        if (now - last < REQUEST_INTERVAL_MS) return
        if (refreshOzon) lastOzonRequestMs = now else lastRequestMs = now
        Log.d(TAG, "requestRefresh: sending to phone")
        scope.launch {
            val nonce = java.util.UUID.randomUUID().toString()
            WearCodeStore.registerRequest(applicationContext, nonce)
            val payload = JSONObject().put("version", 2).put("nonce", nonce)
                .put("refreshOzon", refreshOzon)
                .put("watchVersionCode", BuildConfig.VERSION_CODE)
                .put("watchVersionName", BuildConfig.VERSION_NAME)
                .toString().toByteArray(Charsets.UTF_8)
            Thread {
                try {
                    val nodes = Tasks.await(Wearable.getNodeClient(applicationContext).connectedNodes)
                    if (nodes.isEmpty()) {
                        Log.d(TAG, "requestRefresh: no phone connected")
                        return@Thread
                    }
                    for (node in nodes) {
                        Wearable.getMessageClient(applicationContext)
                            .sendMessage(node.id, REQUEST_PATH, payload)
                            .addOnSuccessListener { Log.d(TAG, "request sent to ${node.id}") }
                            .addOnFailureListener { Log.d(TAG, "request failed: $it") }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "requestRefresh failed: $e")
                }
            }.start()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(releaseAwake)
        handler.removeCallbacks(reconnectRequest)
        Wearable.getCapabilityClient(applicationContext).removeListener(capabilityListener, PHONE_CAPABILITY)
        job?.cancel()
        job = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(releaseAwake)
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val TAG = "BarcodeActivity"
        private const val REQUEST_PATH = "/request_code"
        private const val PHONE_CAPABILITY = "handypvz_phone"
        private const val NO_CODE = "__NO_CODE__"
        private const val REQUEST_INTERVAL_MS = 10_000L
        private const val KEEP_AWAKE_MS = 60_000L
        private val MSK: ZoneId = ZoneId.of("Europe/Moscow")
        // Геометрия в долях экрана (эталон 480x480): карточка 441x136, радиус ~20.
        // Центр карточки (0.5H) совпадает с тёмной полосой между яркими линиями фона (0.341H и 0.658H).
        private const val CARD_WIDTH = 0.92f
        private const val CARD_HEIGHT = 0.283f
        private const val CARD_RADIUS = 0.042f
        private const val BAR_HEIGHT = 0.72f
        private const val LABEL_TOP = 0.14f
        private const val CARD_TOP = 0.3585f
        private const val CODE_TOP = 0.700f
        private const val STATUS_TOP = 0.800f
    }
}
