package dev.handypvz

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusIcon: ImageView
    private lateinit var loginNote: View
    private var refreshing = false
    private lateinit var resultText: TextView
    private lateinit var batteryDesc: TextView
    private lateinit var batteryButton: Button
    private lateinit var notifDesc: TextView
    private lateinit var notifButton: Button

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        resultText = findViewById(R.id.resultText)
        statusIcon = findViewById(R.id.statusIcon)
        loginNote = findViewById(R.id.loginNote)
        batteryDesc = findViewById(R.id.batteryDesc)
        batteryButton = findViewById(R.id.batteryButton)
        notifDesc = findViewById(R.id.notifDesc)
        notifButton = findViewById(R.id.notifButton)
        val ordersButton = findViewById<Button>(R.id.ordersButton)
        val refreshButton = findViewById<Button>(R.id.refreshButton)
        val logoutButton = findViewById<Button>(R.id.logoutButton)
        val donateButton = findViewById<Button>(R.id.donateButton)
        val helpButton = findViewById<Button>(R.id.helpButton)
        val installWatchButton = findViewById<Button>(R.id.installWatchButton)

        // Keep the panel content clear of the gesture/navigation bar
        val bottomPanel = findViewById<View>(R.id.bottomPanel)
        val panelBottomPadding = bottomPanel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(bottomPanel) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = panelBottomPadding + bars.bottom)
            insets
        }

        requestNotificationPermissionIfNeeded()

        batteryButton.setOnClickListener { requestBatteryExemption() }
        notifButton.setOnClickListener { openNotificationAccessSettings() }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = WebUa.clean(webView)
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                Log.d(TAG, "webview title: $title")
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                Log.d(TAG, "onPageStarted: $url")
            }

            override fun onPageFinished(view: WebView, url: String) {
                // The user may have just logged in (or out): re-evaluate the status badge
                if (!refreshing) renderStoredStatus()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                Log.d(TAG, "http error ${errorResponse.statusCode} for ${request.url}")
            }
        }

        if (savedInstanceState == null) {
            webView.loadUrl(ORDER_LIST_URL)
        }

        ordersButton.setOnClickListener {
            webView.loadUrl(ORDER_LIST_URL)
        }

        logoutButton.setOnClickListener {
            MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HandyPVZ_Dialog)
                .setTitle(R.string.logout_dialog_title)
                .setMessage(R.string.logout_dialog_message)
                .setNegativeButton(R.string.logout_dialog_cancel, null)
                .setPositiveButton(R.string.logout_dialog_confirm) { _, _ ->
                    clearSession()
                    webView.loadUrl(ORDER_LIST_URL)
                }
                .show()
        }

        donateButton.setOnClickListener { Links.open(this, DONATE_URL) }
        helpButton.setOnClickListener { showHelp() }
        installWatchButton.setOnClickListener { WatchInstallSheet(this).show() }

        refreshButton.setOnClickListener {
            refreshing = true
            setLoginRequired(false)
            resultText.setTextColor(getColor(R.color.status_warn))
            resultText.text = getString(R.string.status_refreshing)
            lifecycleScope.launch {
                val today = LocalDate.now(MSK).toEpochDay()
                val result = OzonFetcher(this@MainActivity).fetch()
                refreshing = false
                when (result) {
                    is FetchResult.Success -> {
                        CodeStore.saveCode(this@MainActivity, result.code, today)
                        WearSync.push(this@MainActivity, result.code, today)
                        showState(StoredCode(result.code, today), hasOrder = true, loggedIn = true)
                    }
                    is FetchResult.NotFound -> {
                        CodeStore.clearOrder(this@MainActivity)
                        resultText.setTextColor(getColor(R.color.status_idle))
                        resultText.text = getString(R.string.status_no_orders)
                    }
                    is FetchResult.SessionExpired -> {
                        showLoginRequired()
                        Notifier.sessionExpired(this@MainActivity)
                    }
                    else -> {
                        resultText.setTextColor(getColor(R.color.status_err))
                        resultText.text = getString(R.string.result_missing)
                        Notifier.fetchFailed(this@MainActivity)
                    }
                }
            }
        }

    }

    override fun onResume() {
        super.onResume()
        refreshStoredStatus()
        updateBatteryUi()
        updateNotificationUi()
    }

    private fun showHelp() {
        val dialog = BottomSheetDialog(this, R.style.ThemeOverlay_HandyPVZ_BottomSheet)
        val content = layoutInflater.inflate(R.layout.sheet_help, null)
        content.findViewById<View>(R.id.helpGithub).setOnClickListener { Links.open(this, GITHUB_URL) }
        content.findViewById<View>(R.id.helpTelegram).setOnClickListener { Links.open(this, TELEGRAM_GROUP_URL) }
        dialog.setContentView(content)
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        dialog.show()
    }

    private fun clearSession() {
        CookieManager.getInstance().removeAllCookies { }
        CookieManager.getInstance().flush()
        webView.clearHistory()
        webView.clearCache(true)
        WebStorage.getInstance().deleteAllData()
        resultText.setTextColor(getColor(R.color.status_warn))
        resultText.text = getString(R.string.status_session_cleared)
    }

    private fun isBatteryExempt(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateBatteryUi() {
        val exempt = isBatteryExempt()
        batteryDesc.visibility = if (exempt) View.GONE else View.VISIBLE
        batteryButton.visibility = if (exempt) View.GONE else View.VISIBLE
    }

    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun refreshStoredStatus() {
        lifecycleScope.launch {
            val stored = CodeStore.get(this@MainActivity)
            val hasOrder = CodeStore.getHasOrder(this@MainActivity)
            showState(stored, hasOrder)
            if (stored != null && hasOrder) {
                WearSync.push(this@MainActivity, stored.code, stored.epochDay)
            }
        }
    }

    private fun renderStoredStatus() {
        lifecycleScope.launch {
            showState(CodeStore.get(this@MainActivity), CodeStore.getHasOrder(this@MainActivity))
        }
    }

    private fun setLoginRequired(required: Boolean) {
        loginNote.visibility = if (required) View.VISIBLE else View.GONE
        statusIcon.setImageResource(if (required) R.drawable.ic_account else R.drawable.ic_box)
    }

    private fun showLoginRequired() {
        setLoginRequired(true)
        resultText.setTextColor(getColor(R.color.status_warn))
        resultText.text = getString(R.string.status_login_required)
    }

    private fun showState(
        stored: StoredCode?,
        hasOrder: Boolean,
        loggedIn: Boolean = OzonAuth.isLoggedIn()
    ) {
        if (!loggedIn) {
            showLoginRequired()
            return
        }
        setLoginRequired(false)
        if (stored == null) {
            resultText.setTextColor(getColor(R.color.status_warn))
            resultText.text = getString(R.string.status_no_code)
            return
        }
        if (!hasOrder) {
            resultText.setTextColor(getColor(R.color.status_idle))
            resultText.text = getString(R.string.status_no_orders)
            return
        }
        val date = LocalDate.ofEpochDay(stored.epochDay)
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        val stale = stored.epochDay != LocalDate.now(MSK).toEpochDay()
        resultText.text = getString(R.string.status_code, stored.code, date) +
            if (stale) getString(R.string.status_stale) else ""
        resultText.setTextColor(
            getColor(if (stale) R.color.status_warn else R.color.status_ok)
        )
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val component = ComponentName(this, OzonNotificationService::class.java).flattenToString()
        return flat.split(':').any { it == component }
    }

    private fun updateNotificationUi() {
        val granted = isNotificationAccessGranted()
        notifDesc.visibility = if (granted) View.GONE else View.VISIBLE
        notifButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val ORDER_LIST_URL = "https://www.ozon.ru/my/orderlist"
        private const val GITHUB_URL = "https://github.com/timsoid/handyPVZ"
        private const val TELEGRAM_GROUP_URL = "https://t.me/+jZOjtFSWPWpiODYy"
        private const val DONATE_URL = "https://pay.cloudtips.ru/p/eabd1029"
        private val MSK: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
