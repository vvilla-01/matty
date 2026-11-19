package ceo.villa.matty.ui.lock

import android.app.Activity
import android.view.WindowManager
import android.view.inspector.WindowInspector
import java.lang.ref.WeakReference

/**
 * The one decision whether Matty's windows are kept out of screenshots, screen recordings, casts
 * and the app switcher (FLAG_SECURE): while the server on screen requires it or its lock shows,
 * and while a screen holds it for itself (the sign-in screen, for passwords).
 */
object SecureWindow {
    private var held = 0
    private var serverWants = false
    private var hideFromRecents = false
    private var applied: Boolean? = null
    private var appliedTo = WeakReference<Activity>(null)

    /** A screen that keeps itself out of captures whatever the server says, until [release]. */
    fun hold(activity: Activity) { held++; apply(activity) }

    fun release(activity: Activity) { held = (held - 1).coerceAtLeast(0); apply(activity) }

    /**
     * What the server on screen asks for ([secure]); [noRecentsPicture] keeps its picture out
     * of the app switcher even when captures are allowed (a server that asks for an unlock).
     */
    fun server(activity: Activity, secure: Boolean, noRecentsPicture: Boolean) {
        serverWants = secure
        hideFromRecents = noRecentsPicture
        apply(activity)
    }

    /** Again, as Mattermost's own app does on every resume. */
    fun reapply(activity: Activity) { applied = null; apply(activity) }

    private fun apply(activity: Activity) {
        val secure = serverWants || held > 0
        activity.setRecentsScreenshotEnabled(!secure && !hideFromRecents)
        if (secure == applied && appliedTo.get() === activity) return
        applied = secure
        appliedTo = WeakReference(activity)
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        if (secure) activity.window.addFlags(flag) else activity.window.clearFlags(flag)
        // Dialogs, sheets, menus and popups take the activity's flag when they are made: one
        // already open gets it here. Its view's layout params are its window's own attributes,
        // so the window keeps the flag when it updates itself later.
        val decor = activity.window.peekDecorView()
        runCatching { WindowInspector.getGlobalWindowViews() }.getOrDefault(emptyList()).forEach { view ->
            if (view === decor) return@forEach
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            if ((lp.flags and flag != 0) == secure) return@forEach
            lp.flags = if (secure) lp.flags or flag else lp.flags and flag.inv()
            runCatching { activity.windowManager.updateViewLayout(view, lp) }
        }
    }
}
