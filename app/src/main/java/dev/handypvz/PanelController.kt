package dev.handypvz

import android.app.Activity
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton

/** Keeps the panel state shared between the Ozon and isolated WB activities. */
class PanelController(private val activity: Activity) {
    private val content = activity.findViewById<View>(R.id.panelContent)
    private val button = activity.findViewById<MaterialButton>(R.id.panelToggle)
    private val root = activity.findViewById<ViewGroup>(android.R.id.content)
    private var collapsed = false
    private var watchUpdateRequired = false

    init {
        sync()
        button.setOnClickListener {
            collapsed = !collapsed
            activity.contentResolver.call(UiStateProvider.uri(activity), UiStateProvider.SET_PANEL, null,
                Bundle().apply { putBoolean(UiStateProvider.PANEL, collapsed) })
            render(animate = true)
        }
    }

    fun sync() {
        collapsed = UiStateProvider.read(activity).getBoolean(UiStateProvider.PANEL, false)
        render(animate = false)
    }

    fun setWatchUpdateRequired(required: Boolean) {
        watchUpdateRequired = required
        render(animate = false)
    }

    private fun render(animate: Boolean) {
        if (animate) TransitionManager.beginDelayedTransition(root, AutoTransition().setDuration(250))
        content.visibility = if (collapsed) View.GONE else View.VISIBLE
        button.setText(if (collapsed && watchUpdateRequired) R.string.panel_expand_watch_update
            else if (collapsed) R.string.panel_expand else R.string.panel_collapse)
        button.setTextColor(activity.getColor(if (collapsed && watchUpdateRequired) R.color.status_warn else R.color.on_surface_variant))
        button.setIconResource(if (collapsed) R.drawable.ic_panel_arrow_up else R.drawable.ic_panel_arrow)
    }
}
