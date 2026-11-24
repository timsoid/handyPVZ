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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BarcodeActivity : Activity() {

    private lateinit var labelText: TextView
    private lateinit var cardImage: ImageView
    private lateinit var codeText: TextView
    private lateinit var statusText: TextView
    private lateinit var messageText: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var lastRequestMs = 0L
    private val handler = Handler(Looper.getMainLooper())
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

        // Позиции заданы долями высоты экрана, чтобы не зависеть от конкретного разрешения.
        val height = resources.displayMetrics.heightPixels
        place(labelText, LABEL_TOP, height)
        place(cardImage, CARD_TOP, height)
        place(codeText, CODE_TOP, height)
        place(statusText, STATUS_TOP, height)
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
        job?.cancel()
        job = scope.launch {
            WearCodeStore.getFlow(applicationContext).collect { stored -> render(stored) }
        }
    }

    private fun render(stored: WearStoredCode?) {
        val today = LocalDate.now(MSK).toEpochDay()
        when {
            stored == null -> {
                Log.d(TAG, "render: no stored code")
                showMessage(getString(R.string.wear_no_code))
                requestRefresh()
            }
            stored.code == NO_CODE -> {
                Log.d(TAG, "render: no-code marker")
                showMessage(getString(R.string.wear_no_orders))
                if (stored.epochDay != today) requestRefresh()
            }
            stored.epochDay != today -> {
                Log.d(TAG, "render: stale code=${stored.code}")
                showCode(stored, stale = true)
                requestRefresh()
            }
            else -> {
                Log.d(TAG, "render: fresh code=${stored.code}")
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
        messageText.text = text
    }

    private fun showCode(stored: WearStoredCode, stale: Boolean) {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

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
        codeText.text = formatCode(stored.code)
        statusText.text = if (stale) "$date · ${getString(R.string.wear_stale)}" else date
        statusText.setTextColor(getColor(if (stale) R.color.status_warn else R.color.status_ok))

        labelText.visibility = View.VISIBLE
        cardImage.visibility = View.VISIBLE
        codeText.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        messageText.visibility = View.GONE
    }

    private fun formatCode(code: String): String =
        CODE_FORMAT.matchEntire(code)?.destructured?.let { (a, b, c) -> "$a $b * $c" } ?: code

    private fun requestRefresh() {
        if (System.currentTimeMillis() - lastRequestMs < REQUEST_INTERVAL_MS) return
        lastRequestMs = System.currentTimeMillis()
        Log.d(TAG, "requestRefresh: sending to phone")
        Thread {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(applicationContext).connectedNodes)
                if (nodes.isEmpty()) {
                    Log.d(TAG, "requestRefresh: no phone connected")
                    return@Thread
                }
                for (node in nodes) {
                    Wearable.getMessageClient(applicationContext)
                        .sendMessage(node.id, REQUEST_PATH, ByteArray(0))
                        .addOnSuccessListener { Log.d(TAG, "request sent to ${node.id}") }
                        .addOnFailureListener { Log.d(TAG, "request failed: $it") }
                }
            } catch (e: Exception) {
                Log.d(TAG, "requestRefresh failed: $e")
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(releaseAwake)
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
        private const val NO_CODE = "__NO_CODE__"
        private const val REQUEST_INTERVAL_MS = 10_000L
        private const val KEEP_AWAKE_MS = 60_000L
        private val MSK: ZoneId = ZoneId.of("Europe/Moscow")
        private val CODE_FORMAT = Regex("""(\d{4})(\d{4})\*(\d{4})""")

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
