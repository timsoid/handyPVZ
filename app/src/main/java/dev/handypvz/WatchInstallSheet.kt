package dev.handypvz

import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.handypvz.adb.AdbDiscovery
import dev.handypvz.adb.WatchInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Bottom sheet that walks the user through installing the :wear APK over ADB-over-Wi-Fi. */
class WatchInstallSheet(private val activity: AppCompatActivity) {

    private val dialog = BottomSheetDialog(activity, R.style.ThemeOverlay_HandyPVZ_BottomSheet)
    private val view: View =
        LayoutInflater.from(dialog.context).inflate(R.layout.sheet_watch_install, null)

    private val hostLayout = view.findViewById<TextInputLayout>(R.id.hostLayout)
    private val hostInput = view.findViewById<TextInputEditText>(R.id.hostInput)
    private val codeLayout = view.findViewById<TextInputLayout>(R.id.codeLayout)
    private val codeInput = view.findViewById<TextInputEditText>(R.id.codeInput)
    private val portLayout = view.findViewById<TextInputLayout>(R.id.portLayout)
    private val portInput = view.findViewById<TextInputEditText>(R.id.portInput)
    private val status = view.findViewById<TextView>(R.id.watchStatus)
    private val progress = view.findViewById<LinearProgressIndicator>(R.id.watchProgress)
    private val button = view.findViewById<MaterialButton>(R.id.watchInstallButton)

    private val installer = WatchInstaller(activity)
    private val discovery = AdbDiscovery(activity)

    private var job: Job? = null
    private var finished = false
    private var hostEditedByUser = false
    private var settingHostProgrammatically = false

    // Pairing codes are single-use: remember what was already paired in this session
    private var pairedHost: String? = null
    private var pairedCode: String? = null

    fun show() {
        dialog.setContentView(view)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        view.findViewById<TextView>(R.id.watchSteps).text =
            HtmlCompat.fromHtml(activity.getString(R.string.watch_steps), HtmlCompat.FROM_HTML_MODE_LEGACY)
        view.findViewById<TextView>(R.id.watchFooter).apply {
            text = Links.routeLinks(
                activity,
                HtmlCompat.fromHtml(activity.getString(R.string.watch_footer), HtmlCompat.FROM_HTML_MODE_LEGACY)
            )
            movementMethod = LinkMovementMethod.getInstance()
        }

        hostInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!settingHostProgrammatically) hostEditedByUser = true
                hostLayout.error = null
            }
        })
        codeInput.addTextChangedListener(clearErrorWatcher(codeLayout))
        portInput.addTextChangedListener(clearErrorWatcher(portLayout))

        button.setOnClickListener {
            if (finished) dialog.dismiss() else startInstall()
        }

        // Live discovery of the pairing service advertised by the watch ("Pair new device" screen)
        val handle = discovery.watch(AdbDiscovery.PAIRING) { host, port ->
            activity.runOnUiThread {
                if (!hostEditedByUser && job?.isActive != true) {
                    settingHostProgrammatically = true
                    hostInput.setText("$host:$port")
                    settingHostProgrammatically = false
                    hostLayout.helperText = activity.getString(R.string.watch_found)
                }
            }
        }
        dialog.setOnDismissListener {
            handle.stop()
            job?.cancel()
        }
        dialog.show()
        codeInput.requestFocus()
    }

    private fun clearErrorWatcher(layout: TextInputLayout) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            layout.error = null
        }
    }

    private fun startInstall() {
        val hostText = hostInput.text?.toString().orEmpty().trim()
        val code = codeInput.text?.toString().orEmpty().trim()
        val portText = portInput.text?.toString().orEmpty().trim()

        val parts = hostText.split(":")
        val host = parts.firstOrNull().orEmpty()
        val pairPort = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size > 2 || !IPV4.matches(host)) {
            hostLayout.error = activity.getString(R.string.watch_error_host)
            return
        }
        if (code.isNotEmpty() && !CODE.matches(code)) {
            codeLayout.error = activity.getString(R.string.watch_error_code)
            return
        }
        if (code.isNotEmpty() && (pairPort == null || pairPort !in 1..65535)) {
            hostLayout.error = activity.getString(R.string.watch_error_host)
            return
        }
        val connectPort = portText.takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (portText.isNotEmpty() && (connectPort == null || connectPort !in 1..65535)) {
            portLayout.error = activity.getString(R.string.watch_error_port)
            return
        }

        val alreadyPaired = code.isNotEmpty() && host == pairedHost && code == pairedCode
        val doPair = code.isNotEmpty() && !alreadyPaired

        hideKeyboard()
        setBusy(true)
        showProgress(if (doPair) R.string.watch_step_pairing else R.string.watch_step_connecting)
        job = activity.lifecycleScope.launch {
            try {
                installer.install(
                    host = host,
                    pairingPort = if (doPair) pairPort else null,
                    code = if (doPair) code else null,
                    connectPort = connectPort
                ) { step -> activity.runOnUiThread { onStep(step, host, code) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WatchInstaller.InstallException) {
                showError(e.message.orEmpty())
            } catch (e: Exception) {
                showError(activity.getString(R.string.watch_error_unexpected, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun onStep(step: WatchInstaller.Step, host: String, code: String) {
        when (step) {
            WatchInstaller.Step.Pairing -> showProgress(R.string.watch_step_pairing)
            WatchInstaller.Step.Connecting -> {
                if (code.isNotEmpty()) {
                    pairedHost = host
                    pairedCode = code
                }
                showProgress(R.string.watch_step_connecting)
            }
            is WatchInstaller.Step.Installing -> {
                status.text = activity.getString(R.string.watch_step_installing, step.percent)
                progress.isIndeterminate = step.percent <= 0
                if (step.percent > 0) progress.setProgressCompat(step.percent, true)
            }
            WatchInstaller.Step.Done -> {
                finished = true
                progress.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.setTextColor(activity.getColor(R.color.status_ok))
                status.setText(R.string.watch_step_done)
                setBusy(false)
                button.setText(R.string.help_close)
                button.setIconResource(0)
            }
        }
    }

    private fun showProgress(textRes: Int) {
        status.visibility = View.VISIBLE
        status.setTextColor(activity.getColor(R.color.on_surface_variant))
        status.setText(textRes)
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.setTextColor(activity.getColor(R.color.logout_text))
        status.text = message
        setBusy(false)
        button.setText(R.string.watch_retry)
    }

    private fun setBusy(busy: Boolean) {
        button.isEnabled = !busy
        hostLayout.isEnabled = !busy
        codeLayout.isEnabled = !busy
        portLayout.isEnabled = !busy
    }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private companion object {
        val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
        val CODE = Regex("""^\d{6}$""")
    }
}
