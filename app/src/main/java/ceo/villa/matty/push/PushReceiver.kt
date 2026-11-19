package ceo.villa.matty.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import ceo.villa.matty.NeverCrash
import ceo.villa.matty.container
import ceo.villa.matty.data.api.RequestDeadline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a registration's events and pushes lead to beyond the registration itself: the server
 * (`PUT` and `DELETE /me/push`, the confirm), another registration, a REGISTER (sent only to a
 * distributor still signed as when it was chosen), and notifications. [PushReceiver] sends
 * UNREGISTER and MESSAGE_ACK itself.
 */
interface PushSink {
    /** [effect] of an event on [before]'s registration. */
    suspend fun effect(context: Context, before: PushRegistration, effect: PushRegistration.Effect)

    /** A push for [registration] that opened and parsed. */
    suspend fun arrived(context: Context, registration: PushRegistration, arrival: Arrival)

    /** Acts on nothing. */
    object None : PushSink {
        override suspend fun effect(context: Context, before: PushRegistration, effect: PushRegistration.Effect) = Unit
        override suspend fun arrived(context: Context, registration: PushRegistration, arrival: Arrival) = Unit
    }
}

/**
 * Hears the distributor (NEW_ENDPOINT, REGISTRATION_FAILED, MESSAGE, UNREGISTERED,
 * TEMP_UNAVAILABLE). Exported, as the protocol needs: [PushGate] turns away everything that is not
 * for a token this install made at the chosen distributor before any work starts. Only then does
 * it take a 10-second wake lock and finish off the main thread within 9 seconds, every request its
 * work makes over by 8.5 seconds after the broadcast arrived ([RequestDeadline]). It never connects
 * to a server itself and never starts the app's live session.
 */
class PushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Counted from here: the time Android gives the broadcast runs from its arrival.
        val deadline = RequestDeadline.after(REQUESTS_MS)
        val store = PushStore.of(context)
        val inbound = Inbound.of(intent) { store.distributorOf(it) != null } ?: return
        val admitted = PushGate.admit(inbound, store::distributorOf, store.distributor) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        val wake = app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "matty:push")
            ?.apply { setReferenceCounted(false); acquire(WAKE_MS) }
        scope.launch(deadline) {
            try {
                withTimeoutOrNull(WORK_MS) { handle(app, store, admitted) }
            } finally {
                runCatching { wake?.release() }
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, store: PushStore, admitted: PushGate.Admitted) {
        admitted.id?.let { UnifiedPush.ack(context, admitted.distributor, admitted.token, it) }
        val registration = store.byToken(admitted.token) ?: return
        when (admitted) {
            is PushGate.Admitted.Message -> {
                val userId = context.container.sessionStore.servers.value.firstOrNull { it.id == registration.serverId }?.userId ?: return
                val arrival = PushGate.open(store, registration, admitted.body, userId, System.currentTimeMillis())
                if (arrival == Arrival.Drop) return
                // It came through, so the distributor is no longer temporarily unavailable.
                if (registration.unavailable) store.step(admitted.token, PushRegistration.Event.Delivered)
                sink.arrived(context, registration, arrival)
            }
            is PushGate.Admitted.Changed -> {
                val event = (admitted.event as? PushRegistration.Event.Unregistered)?.let {
                    PushRegistration.Event.Unregistered(UnifiedPush.migrationTarget(it.moveTo, registration.distributor, UnifiedPush.distributors(context)))
                } ?: admitted.event
                // Read and written in one step: the app may be changing the same registration.
                val (before, step) = store.step(admitted.token, event) ?: return
                for (effect in step.effects) when (effect) {
                    is PushRegistration.Effect.Unregister -> UnifiedPush.unregister(context, effect.distributor, effect.token)
                    else -> sink.effect(context, before, effect)
                }
            }
        }
    }

    companion object {
        /** The wake lock's limit: a receiver gets about 10 seconds. */
        const val WAKE_MS = 10_000L
        /** Everything is done (or given up) by then. */
        const val WORK_MS = 9_000L
        /** Every request the work makes is over by then, counted from the broadcast's arrival. */
        const val REQUESTS_MS = 8_500L

        /** Where the server and notification side of push is carried out. */
        @Volatile var sink: PushSink = PushSink.None

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + NeverCrash)
    }
}
