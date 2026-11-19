package ceo.villa.matty.push

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * What a distributor binds to while it hands Matty a push (`RAISE_TO_FOREGROUND`), so Matty has
 * foreground importance for those seconds. Binding is all it is for: it does nothing and offers
 * nothing.
 */
class RaiseToForegroundService : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder = binder
}
