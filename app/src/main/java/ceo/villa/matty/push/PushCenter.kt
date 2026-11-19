package ceo.villa.matty.push

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.app.NotificationManagerCompat
import ceo.villa.matty.AppContainer
import ceo.villa.matty.data.runCatchingCancellable
import ceo.villa.matty.data.session.SavedServer
import ceo.villa.matty.plugins.villanet.VillaNetApi
import ceo.villa.matty.plugins.villanet.VillaNetException
import ceo.villa.matty.plugins.villanet.VnPushRegistration
import ceo.villa.matty.plugins.villanet.pushOffered
import ceo.villa.matty.service.Notifier
import ceo.villa.matty.service.OneOff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Push notifications as the app carries them out: registering every saved server with the
 * chosen distributor and giving each server its address (`PUT /me/push`), answering the server's
 * `hello`, showing what arrives, and taking it all back when push is switched off or a server is
 * dropped. The rules are [PushPlan]'s and [PushRegistration]'s; this does what they say.
 *
 * Everything with a server goes through a one-off client for that saved server ([OneOff]),
 * never the live session, and nothing here starts the app. A push's work runs inside the
 * receiver's few seconds ([OneOff.RECEIVER_SECONDS] a call); what the app does at its start runs
 * with [OneOff.ACTION_SECONDS]. One saved server's registration is made, changed and taken back
 * under that server's lock only. The token and the endpoint are never logged.
 */
class PushCenter(private val c: AppContainer) : PushSink {
    private val app = c.app
    private val store: PushStore by lazy { PushStore.of(app) }
    /** One server's talk with its server at a time: a push, the app's start and Settings may all be at it. */
    private val locks = ConcurrentHashMap<String, Mutex>()
    /** A migration moves every registration in one go: two UNREGISTEREDs never move one server twice. */
    private val migrating = Mutex()
    private val helloTimers = ConcurrentHashMap<String, Job>()
    @Volatile private var listening = false

    private fun lock(serverId: String) = locks.getOrPut(serverId) { Mutex() }

    val enabled: Boolean get() = store.enabled

    /** Moves on with every change Settings would show. */
    val changes: StateFlow<Long> get() = store.changes

    private val _testArrived = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** When the latest test push of each saved server arrived (ms): one server's never hides another's. */
    val testArrived: StateFlow<Map<String, Long>> = _testArrived

    // ---- the process -------------------------------------------------------------------------

    /**
     * From the process's start (no network): whenever a saved server goes, by whichever path
     * (sign-out, a session ended, the Keystore key gone, a server without HTTPS, signing in again
     * to the same account), its registrations go at the same moment: UNREGISTER, keys deleted.
     */
    fun watch() {
        c.appScope.launch {
            c.sessionStore.servers.collect { saved ->
                val ids = saved.mapTo(HashSet()) { it.id }
                val gone = (store.all().map { it.serverId } + store.owedDeletes()).distinct().filter { it !in ids }
                // After whatever is under way for it, never in the middle of it.
                for (id in gone) launch { lock(id).withLock { forget(id) } }
            }
        }
    }

    /**
     * The app starts (its live side's first start in a process): a `DELETE /me/push` still owed
     * is sent (push on or off); then every registration is REGISTERed again, and each saved server
     * asked where it stands. A remembered distributor that is gone (not installed, or now signed by
     * somebody else) is treated as one that stopped delivering, for every registration. One still
     * installed that takes no REGISTER right now (it needs setting up) keeps them all, and they are
     * REGISTERed again once it does ([distributorEffects]); nothing is deleted at the servers.
     */
    fun onStart() {
        c.appScope.launch(Dispatchers.IO) {
            val start = PushPlan.atProcessStart(store.enabled, store.owedDeletes())
            for (id in start.deletes) launch { payDelete(id, OneOff.ACTION_SECONDS) }
            if (start.listen) withContext(Dispatchers.Main) { listenForNetwork() }
            if (!start.register) return@launch
            val chosen = store.distributor ?: return@launch
            if (standing(chosen) == UnifiedPush.Standing.GONE) {
                distributorRemoved()
                return@launch
            }
            // Registrations at another distributor are left over from a change: their tokens go.
            store.all().filter { it.distributor != chosen }.forEach { stale ->
                UnifiedPush.unregister(app, stale.distributor, stale.token)
                store.remove(stale)
            }
            for (r in store.all()) store.step(r.token, PushRegistration.Event.Started)?.let { (_, step) -> distributorEffects(step) }
            for (server in c.sessionStore.servers.value) {
                fronted.mark(server.id)
                launch { runCatchingCancellable { sync(server, OneOff.ACTION_SECONDS, askAgain = false) } }
            }
        }
    }

    /** A server was just signed in to: it is registered too while push is on. */
    fun serverAdded(serverId: String) {
        if (!store.enabled || !c.sessions.started.value) return
        val server = c.sessionStore.servers.value.firstOrNull { it.id == serverId } ?: return
        c.appScope.launch(Dispatchers.IO) { runCatchingCancellable { sync(server, OneOff.ACTION_SECONDS) } }
    }

    /**
     * [serverId] has just passed the app check: what push owes it (a DELETE, a PUT or a confirm
     * the check refused, a look that could not be made) is sent now. Decided under the server's
     * lock, so that a refusal still being written when the check passed is paid too.
     */
    fun payOwed(serverId: String) {
        val server = c.sessionStore.servers.value.firstOrNull { it.id == serverId } ?: return
        c.appScope.launch(Dispatchers.IO) {
            runCatchingCancellable {
                payDelete(serverId, OneOff.ACTION_SECONDS)
                sync(server, OneOff.ACTION_SECONDS, askAgain = false)
            }
        }
    }

    // ---- the person's choices ----------------------------------------------------------------

    /**
     * Push switched on with [distributor] (or set up again, or moved to another): the old tokens
     * go from the old distributor, and every saved server that offers push is registered afresh.
     * The distributor is remembered with its signing certificates.
     */
    suspend fun switchOn(distributor: String) {
        val before = store.distributor
        store.rememberDistributor(distributor, UnifiedPush.signersOf(app, distributor))
        store.setEnabled(true)
        store.clearStops()
        store.all().filter { it.distributor != distributor || before != distributor }.forEach { old ->
            UnifiedPush.unregister(app, old.distributor, old.token)
            store.remove(old)
        }
        store.forgetSides()
        withContext(Dispatchers.IO) {
            for (server in c.sessionStore.servers.value) launch { runCatchingCancellable { sync(server, OneOff.ACTION_SECONDS) } }
        }
        listenForNetwork()
    }

    /**
     * Push switched off: for every saved server, UNREGISTER and its keys deleted, under that
     * server's lock (a registration being made goes too), and `DELETE /me/push`, owed until it
     * reaches the server.
     */
    suspend fun switchOff() {
        store.setEnabled(false)
        store.forgetSides()
        val ids = (store.all().map { it.serverId } + c.sessionStore.servers.value.map { it.id }).distinct()
        withContext(Dispatchers.IO) {
            for (id in ids) launch {
                runCatchingCancellable {
                    lock(id).withLock {
                        helloTimers.remove(id)?.cancel()
                        val mine = store.all().filter { it.serverId == id }
                        if (mine.isEmpty()) return@withLock
                        store.oweDelete(id)
                        for (r in mine) store.step(r.token, PushRegistration.Event.SwitchedOff)?.let { (_, step) -> distributorEffects(step) }
                        payDeleteLocked(id, OneOff.ACTION_SECONDS)
                    }
                }
            }
        }
        if (store.owedDeletes().isNotEmpty()) listenForNetwork()
    }

    /**
     * Settings is looking at [serverId] (it came on screen, or Matty came back to it): read its
     * offer and its registration's state now, and REGISTER again a registration that has no
     * endpoint yet (the person may have just set their UnifiedPush app up). The work runs in the
     * app's scope: leaving the screen stops only the waiting for it, never a PUT under way.
     * [reading]: the look only reads ([PushPlan.readOnly]), as after a test push that did not
     * arrive, so that a registration the server found refused or failing shows its line at once.
     */
    suspend fun look(serverId: String, reading: Boolean = false) {
        val server = c.sessionStore.servers.value.firstOrNull { it.id == serverId } ?: return
        c.appScope.launch(Dispatchers.IO) { runCatchingCancellable { sync(server, OneOff.ACTION_SECONDS, reading = reading) } }.join()
    }

    /**
     * "Try again" on a refused or failing line, an address the server would not take, or a PUT
     * still owed: the address goes to [serverId] once more ([PushPlan.beforeTryAgain]).
     */
    suspend fun tryAgain(serverId: String) {
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                lock(serverId).withLock {
                    store.updateSide(serverId, PushPlan::beforeTryAgain)
                    putLocked(serverId, PushPlan.Why.START, OneOff.ACTION_SECONDS)
                }
            }
        }
    }

    /** "Send a test" for [serverId]: null when the server took it, else what to say. */
    suspend fun test(serverId: String): String? = withContext(Dispatchers.IO) {
        val one = c.sessions.oneOff(serverId, OneOff.ACTION_SECONDS) ?: return@withContext "Matty can't use its sign-in for this server right now."
        try {
            one.plugin.pushTest()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: VillaNetException) {
            if (one.sessionOver(e)) { ended(serverId); null } else VillaNetApi.lockCopy(e.serverMessage, ceo.villa.matty.plugins.villanet.PRODUCT_NAME)
        } catch (e: Exception) {
            if (one.sessionOver(e)) { ended(serverId); null } else VillaNetApi.notAnswering(ceo.villa.matty.plugins.villanet.PRODUCT_NAME)
        }
    }

    /** What Settings shows for [serverId]. */
    fun facts(serverId: String): PushFacts {
        val installed = runCatching { UnifiedPush.distributors(app) }.getOrDefault(emptyList())
        val chosen = store.distributor
        val working = c.sessionStore.servers.value.count { s -> chosen != null && store.registration(s.id, chosen)?.let(::works) == true }
        return PushFacts(
            on = store.enabled, installed = installed, chosen = chosen, appName = chosen?.let(::label).orEmpty(),
            permitted = permitted(), stop = store.stop(serverId), side = store.side(serverId),
            registration = chosen?.let { store.registration(serverId, it) },
            working = working, servers = c.sessionStore.servers.value.size,
            chosenInstalled = chosen != null && isInstalled(chosen) && signedAsChosen(chosen, adopt = false),
        )
    }

    /** Whether push works for [serverId] now: then the live connection is not needed for its notifications. */
    fun worksFor(serverId: String): Boolean {
        if (!store.enabled) return false
        val chosen = store.distributor ?: return false
        return store.registration(serverId, chosen)?.let(::works) == true
    }

    /** Confirmed here, and the server's last word was "confirmed" too ([PushPlan.works]). */
    private fun works(r: PushRegistration): Boolean = PushPlan.works(r, store.side(r.serverId))

    /** What a distributor is called on this phone. */
    fun label(pkg: String): String = runCatching {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
    }.getOrDefault(pkg)

    private fun isInstalled(pkg: String): Boolean = runCatching {
        app.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)); true
    }.getOrDefault(false)

    /**
     * Whether [pkg] is still signed as it was when it was chosen. A choice remembered without its
     * certificates has them remembered now when [adopt] (before anything is sent to it).
     */
    private fun signedAsChosen(pkg: String, adopt: Boolean): Boolean {
        val remembered = store.distributorSigners
        if (remembered == null) {
            if (adopt) UnifiedPush.signersOf(app, pkg)?.let { store.rememberDistributor(pkg, it) }
            return true
        }
        return UnifiedPush.signedAs(app, pkg, remembered)
    }

    /** Where [chosen] stands now: taking REGISTER, needing setting up, or gone ([UnifiedPush.standing]). */
    private fun standing(chosen: String): UnifiedPush.Standing = UnifiedPush.standing(
        takesRegister = chosen in UnifiedPush.distributors(app), installed = isInstalled(chosen), signed = signedAsChosen(chosen, adopt = true),
    )

    /** The chosen distributor takes REGISTER and is the app that was chosen. */
    private fun usable(chosen: String): Boolean = standing(chosen) == UnifiedPush.Standing.USABLE

    private fun permitted(): Boolean {
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) return false
        val channel = app.getSystemService(NotificationManager::class.java)?.getNotificationChannel(Notifier.CH_MESSAGES)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    // ---- a saved server goes -----------------------------------------------------------------

    /**
     * [serverId]'s registrations go from their distributor and from the phone, with its keys and
     * what was owed to it. No network. Called under its lock, or from a path that holds it.
     */
    fun forget(serverId: String) {
        store.all().filter { it.serverId == serverId }.forEach { UnifiedPush.unregister(app, it.distributor, it.token) }
        store.forget(serverId)
        helloTimers.remove(serverId)?.cancel()
    }

    /**
     * Just before a dropped session is ended on its server ([client] still holds its token):
     * its registration is taken away there too, as well as that can be done. Never waited for.
     */
    suspend fun forgetAtServer(client: ceo.villa.matty.data.api.MattermostClient) {
        if (!store.enabled) return
        runCatchingCancellable { VillaNetApi(client).pushForget() }
    }

    /**
     * The session on [serverId] is over (a 401 the server confirmed): its notifications go, one
     * says so, and its registrations go ([forget], which `endedAway` calls).
     */
    private fun ended(serverId: String) {
        forget(serverId)
        c.sessions.endedAway(serverId)
    }

    /**
     * An `ended` push: the server could not read [server]'s session, or found it over. It is
     * authentic (it opened with this registration's keys), so the notifications of that server go
     * at once; whether the person is signed out is Mattermost's own answer to `/users/me`, given
     * twice a second apart ([PushPlan.sessionAsked]). A session found alive only has its
     * registration given back; one that could not be settled is asked about again. Owed from the
     * start, so that a look cut short leaves the server to be asked again too.
     */
    private suspend fun endedPush(server: SavedServer) {
        c.notifier.clearMessages(server.id)
        store.updateSide(server.id) { it.copy(unreachable = true) }
        val one = withContext(Dispatchers.IO) { c.sessions.oneOff(server.id, OneOff.RECEIVER_SECONDS) } ?: return
        when (PushPlan.sessionAsked(server.userId) { one.client.me() }) {
            PushPlan.SessionCheck.OVER -> lock(server.id).withLock { ended(server.id) }
            PushPlan.SessionCheck.ALIVE -> {
                // Owed first: what the receiver's few seconds do not finish, the next start, network or return to the app does.
                store.updateSide(server.id) { it.copy(owesPut = true) }
                sync(server, OneOff.RECEIVER_SECONDS, askAgain = false)
            }
            // Owed: the server is asked again when the app comes to the front or the network is back.
            PushPlan.SessionCheck.UNKNOWN -> store.updateSide(server.id) { it.copy(unreachable = true) }
        }
    }

    // ---- what the receiver hands over (PushSink) ---------------------------------------------

    override suspend fun effect(context: Context, before: PushRegistration, effect: PushRegistration.Effect) {
        when (effect) {
            is PushRegistration.Effect.SendEndpoint -> put(before.serverId, PushPlan.Why.ENDPOINT, OneOff.RECEIVER_SECONDS)
            PushRegistration.Effect.DeleteAtServer -> {
                store.oweDelete(before.serverId)
                payDelete(before.serverId, OneOff.RECEIVER_SECONDS)
            }
            is PushRegistration.Effect.MoveTo -> moveTo(before, effect.distributor)
            // Kept by the store with the step; Settings reads it from there.
            is PushRegistration.Effect.Stopped -> Unit
            // The receiver's REGISTER (a new token after a failure), checked as every other is.
            is PushRegistration.Effect.Register -> distributorEffects(PushRegistration.Step(store.byToken(effect.token), listOf(effect)))
            is PushRegistration.Effect.Unregister -> Unit
        }
    }

    override suspend fun arrived(context: Context, registration: PushRegistration, arrival: Arrival) {
        val serverId = registration.serverId
        val server = c.sessionStore.servers.value.firstOrNull { it.id == serverId } ?: return
        when (arrival) {
            is Arrival.Confirm -> confirm(serverId, arrival.nonce, OneOff.RECEIVER_SECONDS)
            Arrival.Tested -> {
                c.notifier.showTested(server)
                _testArrived.update { it + (serverId to System.currentTimeMillis()) }
            }
            // The notifier decides first whether it shows at all, and only then opens the client.
            is Arrival.Show -> c.notifier.showPush(server, arrival.message, arrival.silent) {
                withContext(Dispatchers.IO) { c.sessions.oneOff(serverId, OneOff.RECEIVER_SECONDS) }
            }
            is Arrival.Cleared -> c.notifier.cleared(serverId, arrival.clear)
            Arrival.Ended -> endedPush(server)
            Arrival.Drop -> Unit
        }
    }

    // ---- with the server ---------------------------------------------------------------------

    /**
     * Where [server] stands: its offer (`GET /access`), its registration made or brought to the
     * server's current key, a registration with no endpoint yet REGISTERed again ([askAgain]; the
     * app's start does that itself), and what `GET /me/push` asks for ([PushPlan.atStart]; for a
     * look that only reads, [PushPlan.readOnly]). A server that cannot be reached is asked again
     * when the app comes back or the network does.
     */
    private suspend fun sync(server: SavedServer, seconds: Long, askAgain: Boolean = true, reading: Boolean = false) = lock(server.id).withLock {
        if (!store.enabled) return@withLock
        val one = c.sessions.oneOff(server.id, seconds) ?: return@withLock
        val access = try {
            one.plugin.access()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (one.sessionOver(e)) { ended(server.id); return@withLock }
            // No Matty Plugin answers here at all; anything else is a server not reached now.
            if ((e as? VillaNetException)?.status == 404) store.updateSide(server.id) { it.copy(offer = PushSide.OFFER_NONE, reason = "", unreachable = false) }
            else store.updateSide(server.id) { it.copy(unreachable = true) }
            return@withLock
        }
        val word = access.push
        val offer = when {
            !pushOffered(access.version) -> PushSide.OFFER_OLD
            word?.on != true -> PushSide.OFFER_OFF
            else -> PushSide.OFFER_ON
        }
        store.updateSide(server.id) { it.copy(offer = offer, reason = if (offer == PushSide.OFFER_OFF) word?.reason.orEmpty() else "", unreachable = false) }
        if (offer != PushSide.OFFER_ON) return@withLock
        val key = word!!.key
        if (!UnifiedPush.isVapid(key)) return@withLock
        val chosen = store.distributor ?: return@withLock
        if (store.stop(server.id) != null) return@withLock
        val registration = store.registration(server.id, chosen)
        if (registration == null) {
            // Now that the lock is held: push still on, the server still saved, the app still the one chosen.
            if (!store.enabled || c.sessionStore.servers.value.none { it.id == server.id } || !usable(chosen)) return@withLock
            // A DELETE still owed goes first: it would take away the address given next. Not paid:
            // the server is asked again when the app comes back or the network does.
            if (!payDeleteLocked(server.id, seconds)) {
                if (server.id in store.owedDeletes()) store.updateSide(server.id) { it.copy(unreachable = true) }
                return@withLock
            }
            // Its NEW_ENDPOINT gives the server the address.
            val made = runCatching { store.create(server.id, chosen, key, description(server)) }.getOrNull() ?: return@withLock
            register(made)
            return@withLock
        }
        if (registration.vapid != key) {
            // The server's key changed: a fresh token with the new key, sent once it has an address.
            store.step(registration.token, PushRegistration.Event.VapidChanged(key))?.let { (_, step) -> distributorEffects(step) }
            return@withLock
        }
        if (askAgain && (registration.endpoint == null || registration.owesRegister)) {
            // Never answered, refused (its token is already a new one), or held back while the
            // distributor took no REGISTER: asked again.
            store.step(registration.token, PushRegistration.Event.Started)?.let { (_, step) -> distributorEffects(step) }
        }
        val state = try {
            one.plugin.pushState()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (one.sessionOver(e)) ended(server.id)
            else if (e !is VillaNetException) store.updateSide(server.id) { it.copy(unreachable = true) }
            return@withLock
        }
        val side = store.side(server.id)
        val current = store.registration(server.id, chosen) ?: return@withLock
        val decided = PushPlan.atStart(state, current, side, System.currentTimeMillis())
        val (next, kept) = if (reading) PushPlan.readOnly(decided, state, side) else decided
        store.updateSide(server.id) { kept }
        when (next) {
            PushPlan.Next.PUT -> putLocked(server.id, PushPlan.Why.START, seconds)
            PushPlan.Next.CONFIRM -> confirmLocked(server.id, side.owedNonce, seconds)
            PushPlan.Next.NOTHING -> if (kept.state == "pending" && !kept.unheard) awaitHello(server.id, kept)
        }
    }

    private suspend fun put(serverId: String, why: PushPlan.Why, seconds: Long) = lock(serverId).withLock { putLocked(serverId, why, seconds) }

    /** `PUT /me/push` with [serverId]'s current address, keys and key; never an address the server refused for good. */
    private suspend fun putLocked(serverId: String, why: PushPlan.Why, seconds: Long) {
        if (!store.enabled) return
        val chosen = store.distributor ?: return
        val r = store.registration(serverId, chosen) ?: return
        val endpoint = r.endpoint ?: return
        if (store.side(serverId).rejected == endpoint) return
        // The UnifiedPush app gave the same address again (at a start): that try was made already.
        if (why == PushPlan.Why.ENDPOINT && PushPlan.sameTry(store.side(serverId), endpoint)) return
        val keys = store.keys(r) ?: return
        val one = withContext(Dispatchers.IO) { c.sessions.oneOff(serverId, seconds) } ?: return
        val answer = try {
            one.plugin.pushRegister(VnPushRegistration(endpoint, r.p256dh, keys.authText, r.vapid))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            refused(serverId, one, e, owe = { it.copy(owesPut = true) }, endpoint = endpoint)
            return
        }
        val side = store.updateSide(serverId) { PushPlan.afterPut(answer, it, endpoint, why, System.currentTimeMillis()) }
        if (answer.state == "confirmed") store.step(r.token, PushRegistration.Event.ServerConfirmed(endpoint))
        if (answer.state == "pending") awaitHello(serverId, side)
    }

    private suspend fun confirm(serverId: String, nonce: String, seconds: Long) = lock(serverId).withLock { confirmLocked(serverId, nonce, seconds) }

    /** The hello's answer. A superseded hello (409) changes nothing. */
    private suspend fun confirmLocked(serverId: String, nonce: String, seconds: Long) {
        if (nonce.isEmpty()) return
        val one = withContext(Dispatchers.IO) { c.sessions.oneOff(serverId, seconds) } ?: return
        val answer = try {
            one.plugin.pushConfirm(nonce)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? VillaNetException)?.code == VillaNetApi.PUSH_CONFLICT) {
                store.updateSide(serverId) { if (it.owedNonce == nonce) it.copy(owedNonce = "") else it }
                return
            }
            refused(serverId, one, e, owe = { it.copy(owedNonce = nonce) })
            return
        }
        helloTimers.remove(serverId)?.cancel()
        val side = store.updateSide(serverId) { PushPlan.afterConfirm(answer, it) }
        val chosen = store.distributor ?: return
        val r = store.registration(serverId, chosen) ?: return
        val endpoint = side.sent.ifEmpty { r.endpoint.orEmpty() }
        if (answer.state == "confirmed" && endpoint.isNotEmpty()) store.step(r.token, PushRegistration.Event.ServerConfirmed(endpoint))
    }

    /**
     * A push route refused [e]: a session the server confirms is over ends here; a key that is no
     * longer the server's is replaced; anything else is as [PushPlan.afterRefusal] says. A PUT or
     * confirm owed for anything but the app check (which is paid once the check passes) is also
     * sent again by itself soon ([payLater]); the next start and the network coming back pay it too.
     */
    private suspend fun refused(serverId: String, one: OneOff, e: Exception, owe: (PushSide) -> PushSide, endpoint: String? = null) {
        if (one.sessionOver(e)) { ended(serverId); return }
        val v = e as? VillaNetException
        if (v?.code == VillaNetApi.PUSH_KEY) {
            // Registered with a key the server no longer has: the current one, a fresh token.
            val key = runCatchingCancellable { one.plugin.access().push?.key }.getOrNull()
            val chosen = store.distributor
            val r = chosen?.let { store.registration(serverId, it) }
            if (r != null && UnifiedPush.isVapid(key) && key != r.vapid) {
                store.step(r.token, PushRegistration.Event.VapidChanged(key!!))?.let { (_, step) -> distributorEffects(step) }
                return
            }
        }
        val side = store.updateSide(serverId) { PushPlan.afterRefusal(it, e, owe, endpoint) }
        if (PushPlan.owesSend(side) && v?.code != VillaNetApi.APP_UNVERIFIED) payLater(serverId, v?.retryAfterMs ?: 0)
    }

    /** The owed PUT or confirm of a server being sent again by itself, and the wait its server named last. */
    private val owedTimers = HashMap<String, Job>()
    private val owedWaits = HashMap<String, Long>()

    /**
     * What is owed to [serverId] (a PUT, a confirm) is sent again by itself: after [retryAfterMs]
     * when the server named it, else after [PushPlan.OWED_FIRST_WAIT_MS], then twice more at
     * most, as [PushPlan.owedWait] says, while it is still owed. Only while the process runs; a
     * refusal while the tries are under way only names the next wait.
     */
    private fun payLater(serverId: String, retryAfterMs: Long): Unit = synchronized(owedTimers) {
        owedWaits[serverId] = retryAfterMs
        if (owedTimers.containsKey(serverId)) return
        val job = c.appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                var tried = 0
                while (true) {
                    // Decided where a refusal is noted, so that one written meanwhile starts its own tries.
                    val wait = synchronized(owedTimers) {
                        val owed = store.enabled && PushPlan.owesSend(store.side(serverId))
                        PushPlan.owedWait(tried++, owedWaits[serverId] ?: 0)?.takeIf { owed }
                            ?: run { owedTimers.remove(serverId, self); null }
                    } ?: return@launch
                    delay(wait)
                    // Paid meanwhile (a start, the network back, Try again): nothing to send.
                    if (!PushPlan.owesSend(store.side(serverId))) continue
                    val server = c.sessionStore.servers.value.firstOrNull { it.id == serverId } ?: return@launch
                    runCatchingCancellable { sync(server, OneOff.ACTION_SECONDS, askAgain = false) }
                }
            } finally {
                synchronized(owedTimers) { owedTimers.remove(serverId, self) }
            }
        }
        owedTimers[serverId] = job
        job.start()
    }

    /** A `DELETE /me/push` owed to [serverId], under its lock. */
    private suspend fun payDelete(serverId: String, seconds: Long) = lock(serverId).withLock { payDeleteLocked(serverId, seconds) }

    /**
     * Sends the `DELETE /me/push` owed to [serverId], if one is: true once nothing is owed (it was
     * answered, there was nothing to delete, or the server is no longer saved); false while it is
     * still owed, or when the server confirmed the session is over (nothing more is done for it).
     */
    private suspend fun payDeleteLocked(serverId: String, seconds: Long): Boolean {
        if (serverId !in store.owedDeletes()) return true
        if (c.sessionStore.servers.value.none { it.id == serverId }) { store.paidDelete(serverId); return true }
        val one = withContext(Dispatchers.IO) { c.sessions.oneOff(serverId, seconds) } ?: return false
        val error = try {
            one.plugin.pushForget(); null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        if (PushPlan.settleDelete(store, serverId, error)) return true
        // A session the server confirms is over has no registration left there either.
        if (one.sessionOver(error!!)) ended(serverId)
        return false
    }

    /**
     * No hello [PushPlan.HELLO_WAIT_MS] after "pending": the server is asked how the registration
     * stands ([PushPlan.helloMissing]) and only while it still waits is the same address sent
     * again, at most twice more. Only while the app runs; a process that ends first leaves it to
     * the next start.
     */
    private fun awaitHello(serverId: String, side: PushSide) {
        if (!c.sessions.started.value || side.pendingAt == 0L) return
        helloTimers.remove(serverId)?.cancel()
        helloTimers[serverId] = c.appScope.launch(Dispatchers.IO) {
            delay((side.pendingAt + PushPlan.HELLO_WAIT_MS - System.currentTimeMillis()).coerceAtLeast(0) + 500)
            lock(serverId).withLock {
                if (!store.enabled || !PushPlan.helloDue(store.side(serverId), System.currentTimeMillis())) return@withLock
                val one = c.sessions.oneOff(serverId, OneOff.ACTION_SECONDS) ?: return@withLock
                val answer = try {
                    one.plugin.pushState()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    refused(serverId, one, e, owe = { it.copy(owesPut = true) })
                    return@withLock
                }
                val here = store.distributor?.let { store.registration(serverId, it) }?.isConfirmed == true
                val (next, kept) = PushPlan.helloMissing(store.side(serverId), answer, here, System.currentTimeMillis())
                store.updateSide(serverId) { kept }
                if (next == PushPlan.Next.PUT) putLocked(serverId, PushPlan.Why.REPEAT, OneOff.ACTION_SECONDS)
            }
        }
    }

    // ---- with the distributor ----------------------------------------------------------------

    /** The distributor's half of [step] (REGISTER through [register], UNREGISTER), sent from here. */
    private fun distributorEffects(step: PushRegistration.Step) {
        for (e in step.effects) when (e) {
            is PushRegistration.Effect.Register -> step.registration?.takeIf { it.token == e.token }?.let { if (!register(it)) return }
            is PushRegistration.Effect.Unregister -> UnifiedPush.unregister(app, e.distributor, e.token)
            // The server's half is done by whoever made the step.
            else -> Unit
        }
    }

    /** When each token was last REGISTERed: not again within [UnifiedPush.REGISTER_SPACING_MS]. */
    private val registered = Paced(UnifiedPush.REGISTER_SPACING_MS)

    /**
     * REGISTER [r]'s token, the only way one is sent: only at the chosen distributor, and only
     * while it is signed as when it was chosen ([standing]). One that is gone is removed as at the
     * app's start (false: nothing more is sent for it); one that takes no REGISTER now has the
     * REGISTER held back, to be sent once it does. A token REGISTERed in the last
     * [UnifiedPush.REGISTER_SPACING_MS] is not sent again (its answer is on its way).
     */
    private fun register(r: PushRegistration): Boolean {
        if (r.distributor != store.distributor) return true
        when (standing(r.distributor)) {
            UnifiedPush.Standing.GONE -> { distributorRemoved(); return false }
            UnifiedPush.Standing.NOT_SET_UP -> store.step(r.token, PushRegistration.Event.HeldBack)
            UnifiedPush.Standing.USABLE -> if (registered.due(r.token)) UnifiedPush.register(app, r.distributor, r.token, r.vapid, r.description)
        }
        return true
    }

    /** The chosen distributor is gone (not installed, or signed by somebody else): every registration goes, as if it had stopped delivering. */
    private fun distributorRemoved() {
        val all = store.all()
        val ids = all.map { it.serverId }.distinct()
        for (r in all) store.step(r.token, PushRegistration.Event.DistributorRemoved)
        ids.forEach(store::oweDelete)
        c.appScope.launch(Dispatchers.IO) {
            for (id in ids) launch { runCatchingCancellable { payDelete(id, OneOff.ACTION_SECONDS) } }
        }
    }

    /**
     * The distributor moved Matty to [target] (an UNREGISTERED naming an installed distributor):
     * it becomes the chosen one (with its signing certificates), and every saved server's
     * registration moves there, each with a new token and keys and the key and description it had.
     * All of it in one go: a second UNREGISTERED finds its server already moved. Each server's
     * part is done under its lock, checking again there that push is still on, the server still
     * saved, and no registration made at [target] meanwhile.
     */
    private suspend fun moveTo(before: PushRegistration, target: String) = migrating.withLock {
        if (!store.enabled) return@withLock
        val old = before.distributor
        val moving = listOf(before) + store.all().filter { it.distributor == old }
        store.rememberDistributor(target, UnifiedPush.signersOf(app, target))
        for (r in moving.distinctBy { it.serverId }) lock(r.serverId).withLock {
            store.registration(r.serverId, old)?.let { UnifiedPush.unregister(app, old, it.token); store.remove(it) }
            if (!store.enabled || c.sessionStore.servers.value.none { it.id == r.serverId } || store.registration(r.serverId, target) != null) return@withLock
            val made = runCatching { store.create(r.serverId, target, r.vapid, r.description) }.getOrNull() ?: return@withLock
            register(made)
        }
    }

    /** What the distributor may show for a registration: the server's name, within the protocol's 100 bytes. */
    private fun description(server: SavedServer): String {
        var text = server.name.ifBlank { server.url.substringAfter("://") }.trim()
        while (!UnifiedPush.isDescription(text)) text = text.dropLast(1)
        return text
    }

    /**
     * A registration that failed for want of a network is asked for again when one comes back,
     * and what was owed to a server (a DELETE, a PUT, a confirm, a look) is sent. Only while the
     * app runs.
     */
    private fun listenForNetwork() {
        if (listening) return
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).build()
        runCatching {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    c.appScope.launch { networkBack() }
                }
            })
            listening = true
        }
    }

    private fun networkBack() {
        c.appScope.launch(Dispatchers.IO) {
            for (id in store.owedDeletes()) launch { runCatchingCancellable { payDelete(id, OneOff.ACTION_SECONDS) } }
            if (!store.enabled) return@launch
            val chosen = store.distributor ?: return@launch
            for (r in store.all()) {
                if (r.distributor == chosen && r.failure == FailReason.NETWORK) store.step(r.token, PushRegistration.Event.Started)?.let { (_, step) -> distributorEffects(step) }
            }
            for (server in c.sessionStore.servers.value.filter { store.side(it.id).owes }) {
                launch { runCatchingCancellable { sync(server, OneOff.ACTION_SECONDS, askAgain = false) } }
            }
        }
    }

    // ---- the app comes to the front ----------------------------------------------------------

    /** When each saved server was last asked at the start or because the app came to the front. */
    private val fronted = Paced(FRONT_EVERY_MS)

    /**
     * Matty came to the front (every time, not only the first in a process; nothing before the
     * app has started, whose own start asks every server): while the chosen distributor takes
     * REGISTER, every registration it has not answered, or that was held back from it, is
     * REGISTERed again ([PushRegistration.asksOnReturn]; the person may have just set it up). Then
     * what is owed to a saved server (a PUT, a confirm, a look that could not be made) is sent as
     * when the network is back, and a saved server that offers push (or has not said yet) with no
     * registration yet is registered while that app takes REGISTER ([PushPlan.askedAtFront]), at
     * most once in [FRONT_EVERY_MS] a server.
     */
    fun cameToFront() {
        if (!c.sessions.started.value) return
        c.appScope.launch(Dispatchers.IO) {
            if (!store.enabled) return@launch
            val chosen = store.distributor
            val canRegister = chosen != null && usable(chosen)
            if (canRegister) for (r in store.all()) {
                if (r.distributor == chosen && r.asksOnReturn) store.step(r.token, PushRegistration.Event.Started)?.let { (_, step) -> distributorEffects(step) }
            }
            for (server in c.sessionStore.servers.value) {
                val asked = PushPlan.askedAtFront(
                    owes = store.side(server.id).owes, usable = canRegister,
                    registered = chosen?.let { store.registration(server.id, it) } != null, stopped = store.stop(server.id) != null,
                    offer = store.side(server.id).offer,
                )
                if (!asked || !fronted.due(server.id)) continue
                launch { runCatchingCancellable { sync(server, OneOff.ACTION_SECONDS, askAgain = false) } }
            }
        }
    }

    private companion object {
        /** A server is asked at most this often because the app came to the front. */
        const val FRONT_EVERY_MS = 30_000L
    }
}

/**
 * At most once in [everyMs] for each key, by [clock] (ms, a clock that counts the phone's sleep).
 * Any thread.
 */
internal class Paced(private val everyMs: Long, private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private val last = ConcurrentHashMap<String, Long>()

    /** Whether [key] is due now; when it is, it is marked as done now. */
    fun due(key: String): Boolean {
        val now = clock()
        var taken = false
        last.compute(key) { _, at -> if (at == null || now - at >= everyMs) now.also { taken = true } else at }
        return taken
    }

    /** [key] was done now. */
    fun mark(key: String) {
        last[key] = clock()
    }
}
