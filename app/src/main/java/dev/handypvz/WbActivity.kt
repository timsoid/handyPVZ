package dev.handypvz

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Separate activity/process so its WebView profile cannot clear the Ozon session. */
class WbActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var loginNote: TextView
    private lateinit var refresh: Button
    private var bridge: Messenger? = null
    private var bound = false
    private var fetchJob: Job? = null
    private var logoutPending = false
    private var mainFrameError = false
    private lateinit var panelController: PanelController
    private lateinit var watchVersionView: WatchVersionStatusView
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == WbBridgeService.STATUS) {
            if (message.arg1 == WbBridgeService.LOGOUT && logoutPending) {
                logoutPending = false
                clearWebViewSession()
            } else {
                showState(message.data.getString(WbBridgeService.STATE).orEmpty())
            }
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            bridge = Messenger(service)
            send(WbBridgeService.GET)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bridge = null
            if (logoutPending) {
                logoutPending = false
                refresh.isEnabled = true
                status.setText(R.string.wb_fetch_failed)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        status = findViewById(R.id.resultText)
        loginNote = findViewById(R.id.loginNote)
        refresh = findViewById(R.id.refreshButton)
        panelController = PanelController(this)
        watchVersionView = WatchVersionStatusView(this, findViewById(R.id.watchVersionStatus)) {
            panelController.setWatchUpdateRequired(it)
        }.also { it.start() }
        loginNote.setText(R.string.wb_login_note)
        findViewById<View>(R.id.notifDesc).visibility = View.GONE
        findViewById<View>(R.id.notifButton).visibility = View.GONE
        findViewById<View>(R.id.batteryDesc).visibility = View.GONE
        findViewById<View>(R.id.batteryButton).visibility = View.GONE

        val panel = findViewById<View>(R.id.bottomPanel)
        val padding = panel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            view.updatePadding(bottom = padding + insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
        val tabs = findViewById<MaterialButtonToggleGroup>(R.id.marketplaceTabs)
        val tabsTopPadding = tabs.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(tabs) { view, insets ->
            view.updatePadding(top = tabsTopPadding + insets.getInsets(WindowInsetsCompat.Type.systemBars()).top)
            insets
        }
        tabs.check(R.id.tabWb)
        tabs.addOnButtonCheckedListener { _, id, checked ->
            if (id == R.id.tabOzon && checked) finish()
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = WebUa.clean(webView)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                mainFrameError = false
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) mainFrameError = true
            }
        }
        webView.webChromeClient = WebChromeClient()
        if (savedInstanceState == null) webView.loadUrl(WbFetcher.DELIVERY_URL)
        else webView.restoreState(savedInstanceState)

        findViewById<Button>(R.id.ordersButton).setOnClickListener { webView.loadUrl(WbFetcher.DELIVERY_URL) }
        refresh.setOnClickListener { refreshCode() }
        findViewById<Button>(R.id.logoutButton).apply {
            setText(R.string.btn_logout_wb)
            setOnClickListener {
                MaterialAlertDialogBuilder(this@WbActivity, R.style.ThemeOverlay_HandyPVZ_Dialog)
                    .setTitle(R.string.logout_wb_title)
                    .setMessage(R.string.logout_wb_message)
                    .setNegativeButton(R.string.logout_dialog_cancel, null)
                    .setPositiveButton(R.string.logout_dialog_confirm) { _, _ -> clearSession() }
                    .show()
            }
        }
        findViewById<Button>(R.id.installWatchButton).setOnClickListener { WatchInstallSheet(this).show() }
        findViewById<Button>(R.id.donateButton).setOnClickListener { Links.open(this, "https://pay.cloudtips.ru/p/eabd1029") }
        findViewById<Button>(R.id.helpButton).setOnClickListener { showHelp() }
        showState("unknown")
        bound = bindService(Intent(this, WbBridgeService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun refreshCode() {
        if (bridge == null || fetchJob?.isActive == true) return
        refresh.isEnabled = false
        status.setTextColor(getColor(R.color.status_warn))
        status.setText(R.string.status_refreshing)
        fetchJob = lifecycleScope.launch {
            val qr = WbFetcher(webView).fetch()
            refresh.isEnabled = true
            if (qr == null || !send(WbBridgeService.SAVE, qr)) {
                // An absent image could be login, a slow page, no orders, or a network error.
                // Until those cases are verified in WebView, preserve the last saved code.
                status.setTextColor(getColor(R.color.status_err))
                status.setText(if (mainFrameError) R.string.wb_network_error else R.string.wb_fetch_failed)
            }
        }
    }

    private fun clearSession() {
        fetchJob?.cancel()
        refresh.isEnabled = false
        logoutPending = true
        if (!send(WbBridgeService.LOGOUT)) {
            logoutPending = false
            refresh.isEnabled = true
            status.setText(R.string.wb_fetch_failed)
        }
    }

    private fun clearWebViewSession() {
        webView.stopLoading()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearHistory()
            webView.clearCache(true)
            refresh.isEnabled = true
            status.setText(R.string.wb_session_cleared)
            webView.loadUrl(WbFetcher.DELIVERY_URL)
        }
    }

    private fun send(what: Int, qr: String? = null): Boolean {
        if (qr != null && qr.toByteArray(Charsets.UTF_8).size > WbBridgeService.MAX_QR_BYTES) return false
        val target = bridge ?: return false
        return try {
            target.send(Message.obtain(null, what).apply {
                replyTo = replies
                if (qr != null) data = Bundle().apply { putString(WbBridgeService.QR, qr) }
            })
            true
        } catch (_: RemoteException) {
            false
        }
    }

    private fun showState(state: String) {
        val ready = state == "ready"
        loginNote.visibility = if (ready) View.GONE else View.VISIBLE
        status.setTextColor(getColor(if (ready) R.color.status_ok else R.color.status_warn))
        status.setText(if (ready) R.string.wb_ready else R.string.wb_no_code)
    }

    private fun showHelp() {
        val dialog = BottomSheetDialog(this, R.style.ThemeOverlay_HandyPVZ_BottomSheet)
        val content = layoutInflater.inflate(R.layout.sheet_help, null)
        content.findViewById<View>(R.id.helpGithub).setOnClickListener { Links.open(this, "https://github.com/timsoid/handyPVZ") }
        content.findViewById<View>(R.id.helpTelegram).setOnClickListener { Links.open(this, "https://t.me/+jZOjtFSWPWpiODYy") }
        content.findViewById<TextView>(R.id.helpVersion).text =
            getString(R.string.help_version, BuildConfig.VERSION_NAME)
        dialog.setContentView(content)
        dialog.behavior.apply { state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true }
        dialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        panelController.sync()
        WearSync.requestWatchVersion(this)
    }

    override fun onDestroy() {
        fetchJob?.cancel()
        watchVersionView.stop()
        if (bound) unbindService(connection)
        webView.destroy()
        super.onDestroy()
    }
}
