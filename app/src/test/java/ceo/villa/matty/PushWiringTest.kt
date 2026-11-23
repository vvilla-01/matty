package ceo.villa.matty

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where push is joined to the rest of the app, held by the source itself (as StartPathsTest holds
 * the start paths): the receiver's work is carried out, every app start registers again, and every
 * path that drops a saved server or ends its session takes its push registration with it.
 */
class PushWiringTest {
    private val main = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/ceo/villa/matty") }.first { it.isDirectory }

    private val app = File(main, "MattyApp.kt").readText()
    private val center = File(main, "push/PushCenter.kt").readText()
    private val receiver = File(main, "push/PushReceiver.kt").readText()
    private val plugin = File(main, "plugins/villanet/VillaNetPlugin.kt").readText()
    private val settings = File(main, "ui/settings/PushSettings.kt").readText()

    private fun inOrder(text: String, vararg parts: String) {
        var at = -1
        for (p in parts) {
            val next = text.indexOf(p, at + 1)
            assertTrue("'$p' after what comes before it", next > at)
            at = next
        }
    }

    /** [text] before [anchor], which must be there (a missing one would leave the whole text). */
    private fun before(text: String, anchor: String): String {
        assertTrue("'$anchor' is there", anchor in text)
        return text.substringBefore(anchor)
    }

    /** [text] after [anchor], which must be there. */
    private fun after(text: String, anchor: String): String {
        assertTrue("'$anchor' is there", anchor in text)
        return text.substringAfter(anchor)
    }

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature).also { assertTrue("$signature is there", it >= 0) }
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) { '{' -> depth++; '}' -> if (--depth == 0) return text.substring(open, i + 1) }
        }
        error("unbalanced $signature")
    }

    @Test fun theReceiversWorkIsCarriedOutFromTheProcessesStart() {
        val onCreate = body(app, "override fun onCreate()")
        assertTrue("the receiver hands its work to the push center", "PushReceiver.sink = container.push" in onCreate)
        assertTrue("a server that goes takes its registrations with it", "container.push.watch()" in onCreate)
    }

    @Test fun everyAppStartRegistersAgainOnce() {
        val ensure = body(app, "fun ensureActive(")
        assertTrue("if (first) c.push.onStart()" in ensure)
    }

    @Test fun everySavedServerThatGoesIsUnregistered() {
        // Every path that removes a saved server changes the saved list, which the watch follows.
        val watch = body(center, "fun watch()")
        assertTrue("c.sessionStore.servers.collect" in watch)
        assertTrue("forget(id)" in watch)
        val forget = body(center, "fun forget(serverId: String)")
        assertTrue("UnifiedPush.unregister" in forget)
        assertTrue("store.forget(serverId)" in forget)
        // A session that ended away from the app goes at once, before the next start's wipe.
        assertTrue("c.push.forget(serverId)" in body(app, "fun endedAway("))
    }

    @Test fun signingOutTakesTheRegistrationOffTheServerBeforeTheSessionEnds() {
        val revoke = body(app, "private fun revokeLater(")
        val at = revoke.indexOf("c.push.forgetAtServer(client)")
        assertTrue("the registration is deleted there", at >= 0)
        assertTrue("while the token still works", at < revoke.indexOf("client.logout()"))
    }

    @Test fun aNewSignInIsRegisteredWhilePushIsOn() {
        assertTrue("c.push.serverAdded(server.id)" in body(app, "fun signedIn("))
    }

    @Test fun anEndedPushClearsAtOnceAndSignsOutOnlyOnTheServersWord() {
        val arrived = body(center, "override suspend fun arrived(")
        assertTrue("Arrival.Ended -> endedPush(server)" in arrived)
        assertFalse("never acted on without the question", "Arrival.Ended -> ended(" in arrived)
        val ended = body(center, "private suspend fun endedPush(")
        inOrder(ended, "c.notifier.clearMessages(server.id)", "c.sessions.oneOff(server.id, OneOff.RECEIVER_SECONDS)", "PushPlan.sessionAsked(server.userId) { one.client.me() }")
        // Owed before anything is asked: a look cut short (no client, the receiver's time up) leaves the server to be asked again.
        val first = before(ended, "c.sessions.oneOff(server.id, OneOff.RECEIVER_SECONDS)")
        inOrder(first, "c.notifier.clearMessages(server.id)", "store.updateSide(server.id) { it.copy(unreachable = true) }")
        inOrder(ended, "PushPlan.SessionCheck.OVER -> lock(server.id).withLock { ended(server.id) }")
        inOrder(ended, "PushPlan.SessionCheck.ALIVE ->", "owesPut = true", "sync(server, OneOff.RECEIVER_SECONDS")
        // Not settled: owed, so the server is asked again when the app comes back or the network does.
        inOrder(ended, "PushPlan.SessionCheck.UNKNOWN ->", "store.updateSide(server.id) { it.copy(unreachable = true) }")
        // Asked before anything is marked: the one-off client answers nothing for a marked server.
        assertFalse("endedAway" in before(ended, "one.client.me()"))
        assertFalse("forget(" in before(ended, "one.client.me()"))
    }

    @Test fun aSessionTheServerEndedGoesAtOnceOnceTheAppHasStarted() {
        assertTrue("if (_started.value) dropEnded(setOf(serverId))" in body(app, "fun endedAway("))
        assertTrue("if (server.id in endedIds()) return false" in body(app, "fun activate("))
        assertTrue("serverId in endedIds()" in body(app, "fun switchTo("))
        assertTrue("endedIds())" in body(app, "fun ensureActive("))
        // A mark written while it runs is kept.
        val drop = body(app, "private fun dropEnded(")
        assertTrue("synchronized(this) { endedPrefs.edit().putStringSet(KEY_ENDED, endedIds() - ids).commit() }" in drop)
        assertFalse("remove(KEY_ENDED)" in drop)
        // The live session of an ended server is torn down before the server goes, and another takes its place.
        inOrder(drop, "_current.value?.takeIf { it.server.id in ids }", "teardown(live)", "_current.value = null", "c.sessionStore.remove(it.id)")
        inOrder(drop, "putStringSet(KEY_ENDED", "if (live != null) activateOrStop(c.sessionStore.active)")
    }

    @Test fun signingInAgainToTheSameAccountClearsTheOldSessionsNotifications() {
        inOrder(body(app, "fun signedIn("), "c.sessionStore.upsert(server)", "c.notifier.clearDropped(")
    }

    @Test fun aRegistrationWithNoEndpointIsAskedForAgainWhenSettingsLooks() {
        inOrder(body(center, "private suspend fun sync("), "askAgain && (registration.endpoint == null || registration.owesRegister)", "PushRegistration.Event.Started", "one.plugin.pushState()")
        assertTrue("the app's start asks by itself", "sync(server, OneOff.ACTION_SECONDS, askAgain = false)" in body(center, "fun onStart()"))
        val look = before(after(settings, "LifecycleResumeEffect(serverId, facts.on)"), "onPauseOrDispose")
        assertTrue("every time Settings comes back", "push.look(serverId)" in look)
    }

    @Test fun whatSettingsLookStartsIsNotCutOffByLeavingTheScreen() {
        val look = body(center, "suspend fun look(serverId: String, reading: Boolean = false)")
        // In the app's scope, waited for: leaving the screen cancels only the waiting.
        assertTrue("c.appScope.launch(Dispatchers.IO) { runCatchingCancellable { sync(server, OneOff.ACTION_SECONDS, reading = reading) } }.join()" in look)
        assertFalse("not in the caller's scope", "withContext" in look)
    }

    @Test fun theLookAfterATestThatDidNotArriveOnlyReads() {
        // Settings coming back looks as a start does; the look after a missing test sends nothing by itself.
        assertTrue("scope.launch { push.look(serverId) }" in settings)
        assertTrue("if (PushLine.testMissing(sentAt, latest, now)) push.look(serverId, reading = true)" in settings)
        val sync = body(center, "private suspend fun sync(")
        inOrder(
            sync, "val decided = PushPlan.atStart(state, current, side, System.currentTimeMillis())",
            "val (next, kept) = if (reading) PushPlan.readOnly(decided, state, side) else decided",
            "store.updateSide(server.id) { kept }",
        )
    }

    @Test fun theSameAddressAfterARefusalIsNotSentAgain() {
        val put = body(center, "private suspend fun putLocked(")
        inOrder(
            put, "val endpoint = r.endpoint ?: return",
            "if (why == PushPlan.Why.ENDPOINT && PushPlan.sameTry(store.side(serverId), endpoint)) return",
            "one.plugin.pushRegister(",
        )
    }

    @Test fun nothingIsRegisteredOncePushIsOffOrTheServerGone() {
        val sync = body(center, "private suspend fun sync(")
        val made = before(after(sync, "if (registration == null) {"), "store.create(")
        assertTrue("!store.enabled" in made)
        assertTrue("c.sessionStore.servers.value.none { it.id == server.id }" in made)
        assertTrue("an owed DELETE goes first", "payDeleteLocked(server.id, seconds)" in made)
        inOrder(body(center, "suspend fun switchOff()"), "store.setEnabled(false)", "lock(id).withLock", "PushRegistration.Event.SwitchedOff")
        assertTrue("lock(id).withLock { forget(id) }" in body(center, "fun watch()"))
        assertTrue("migrating.withLock" in after(center, "private suspend fun moveTo("))
    }

    @Test fun aMigrationMovesEachServerUnderItsLockCheckingAgainThere() {
        val move = before(after(center, "private suspend fun moveTo("), "private fun description(")
        inOrder(
            move, "migrating.withLock", "store.rememberDistributor(target, UnifiedPush.signersOf(app, target))",
            "lock(r.serverId).withLock {", "store.remove(it)",
            "!store.enabled || c.sessionStore.servers.value.none { it.id == r.serverId } || store.registration(r.serverId, target) != null) return@withLock",
            "store.create(r.serverId, target", "register(made)",
        )
        assertFalse("nothing made before the server's lock", "store.create(" in before(move, "lock(r.serverId).withLock {"))
    }

    @Test fun nothingIsRegisteredWithAnAppNotSignedAsChosen() {
        // The one place a REGISTER is sent, and what it checks first.
        assertTrue("one REGISTER in the push center", center.split("UnifiedPush.register(").size == 2)
        assertFalse("the receiver sends none itself", "UnifiedPush.register(" in receiver)
        assertTrue("the receiver's REGISTER is checked too",
            "is PushRegistration.Effect.Register -> distributorEffects(PushRegistration.Step(store.byToken(effect.token), listOf(effect)))" in body(center, "override suspend fun effect("))
        val register = body(center, "private fun register(r: PushRegistration)")
        inOrder(
            register, "if (r.distributor != store.distributor) return true", "when (standing(r.distributor))",
            "UnifiedPush.Standing.GONE -> { distributorRemoved(); return false }",
            "UnifiedPush.Standing.NOT_SET_UP -> store.step(r.token, PushRegistration.Event.HeldBack)",
            "UnifiedPush.Standing.USABLE -> if (registered.due(r.token)) UnifiedPush.register(app, r.distributor, r.token, r.vapid, r.description)",
        )
        assertTrue("private val registered = Paced(UnifiedPush.REGISTER_SPACING_MS)" in center)
        inOrder(body(center, "private fun distributorEffects("), "is PushRegistration.Effect.Register ->", "if (!register(it)) return")
        assertTrue("register(made)" in body(center, "private suspend fun sync("))
        // Where the app stands: installed, signed as remembered, taking REGISTER.
        val standing = before(after(center, "private fun standing(chosen: String)"), "private fun usable(")
        inOrder(standing, "chosen in UnifiedPush.distributors(app)", "isInstalled(chosen)", "signedAsChosen(chosen, adopt = true)")
        assertTrue("private fun usable(chosen: String): Boolean = standing(chosen) == UnifiedPush.Standing.USABLE" in center)
        assertTrue("UnifiedPush.signedAs(app, pkg, remembered)" in body(center, "private fun signedAsChosen("))
        assertTrue("store.rememberDistributor(distributor, UnifiedPush.signersOf(app, distributor))" in body(center, "suspend fun switchOn("))
    }

    @Test fun anAppThatNeedsSettingUpKeepsItsRegistrations() {
        val start = body(center, "fun onStart()")
        inOrder(start, "if (standing(chosen) == UnifiedPush.Standing.GONE) {", "distributorRemoved()", "return@launch", "PushRegistration.Event.Started", "distributorEffects(step)")
        assertFalse("only a gone app is removed", "usable(" in start)
        // Settings opens it when there is nothing else to choose.
        inOrder(settings, "val notSetUp = facts.chosen?.takeIf { facts.chosenInstalled }", "UnifiedPush.choose(UnifiedPush.linkDefault(ctx), UnifiedPush.distributors(ctx), notSetUp)",
            "is UnifiedPush.Choice.Open -> ctx.packageManager.getLaunchIntentForPackage(choice.distributor)")
    }

    @Test fun comingToTheFrontRegistersAgainWhatTheDistributorHasNotAnswered() {
        val front = body(center, "fun cameToFront()")
        inOrder(
            front, "if (!store.enabled) return@launch", "val canRegister = chosen != null && usable(chosen)", "if (canRegister)",
            "r.distributor == chosen && r.asksOnReturn", "store.step(r.token, PushRegistration.Event.Started)", "distributorEffects(step)",
            "for (server in c.sessionStore.servers.value)",
        )
    }

    @Test fun aDeleteThatDidNotArriveIsOwedAndPaid() {
        val start = body(center, "fun onStart()")
        // The start's first step decides (PushPlan.atProcessStart), and nothing returns before it.
        inOrder(start, "PushPlan.atProcessStart(store.enabled, store.owedDeletes())", "for (id in start.deletes) launch { payDelete(", "if (!start.register) return@launch")
        assertFalse("return" in start.substring(0, start.indexOf("PushPlan.atProcessStart(")))
        inOrder(body(center, "suspend fun switchOff()"), "store.oweDelete(id)", "payDeleteLocked(id")
        inOrder(body(center, "private fun networkBack()"), "store.owedDeletes()", "payDelete(", "if (!store.enabled) return@launch")
        assertTrue("store.oweDelete(before.serverId)" in body(center, "override suspend fun effect("))
    }

    @Test fun whatTheAppCheckRefusedIsPaidOnceItPasses() {
        val prove = body(plugin, "private fun proveIfDue(")
        inOrder(prove, "appRefusal.proved(answer.state)", "if (answer.state == APP_VERIFIED) c.push.payOwed(r.server.id)")
        // Decided under the server's lock by the sync itself: a refusal still being written is paid too.
        val pay = body(center, "fun payOwed(serverId: String)")
        inOrder(pay, "payDelete(serverId, OneOff.ACTION_SECONDS)", "sync(server, OneOff.ACTION_SECONDS, askAgain = false)")
        assertFalse("nothing looked at before the lock", "owes" in pay || "owedDeletes" in pay || "store." in pay)
    }

    @Test fun aDeleteThatCannotBePaidBeforeARegistrationIsMadeLeavesTheServerToBeAskedAgain() {
        val sync = body(center, "private suspend fun sync(")
        inOrder(sync, "if (registration == null) {", "if (!payDeleteLocked(server.id, seconds)) {", "store.updateSide(server.id) { it.copy(unreachable = true) }", "return@withLock", "store.create(")
        // Within the create branch itself, between the owed DELETE and the registration made.
        val made = before(after(sync, "if (!payDeleteLocked(server.id, seconds)) {"), "store.create(")
        assertTrue("if (server.id in store.owedDeletes()) store.updateSide(server.id) { it.copy(unreachable = true) }" in made)
        val pay = body(center, "private suspend fun payDeleteLocked(")
        inOrder(pay, "one.plugin.pushForget()", "if (PushPlan.settleDelete(store, serverId, error)) return true", "one.sessionOver(error!!)")
        assertTrue("paid otherwise only for a server no longer saved", pay.split("store.paidDelete(").size == 2 && "none { it.id == serverId }) { store.paidDelete(serverId)" in pay)
    }

    @Test fun aRefusalIsDecidedByThePlanAndWhatIsOwedIsSentAgainByItself() {
        val refused = body(center, "private suspend fun refused(")
        inOrder(refused, "if (one.sessionOver(e)) { ended(serverId); return }", "store.updateSide(serverId) { PushPlan.afterRefusal(it, e, owe, endpoint) }", "payLater(serverId, v?.retryAfterMs ?: 0)")
        assertFalse("a 404 is owed like the rest", "404" in refused)
        val later = body(center, "private fun payLater(")
        inOrder(later, "PushPlan.owesSend(store.side(serverId))", "PushPlan.owedWait(tried++, owedWaits[serverId] ?: 0)", "delay(wait)", "sync(server, OneOff.ACTION_SECONDS, askAgain = false)")
        assertTrue("private fun works(r: PushRegistration): Boolean = PushPlan.works(r, store.side(r.serverId))" in center)
        // One series a server: a refusal while one runs only names the next wait.
        inOrder(later, "owedWaits[serverId] = retryAfterMs", "if (owedTimers.containsKey(serverId)) return", "c.appScope.launch(")
        assertTrue("one series a server", "owedTimers.containsKey(serverId)" in before(later, "c.appScope.launch("))
        // It ends, sending nothing, once push is off or nothing is owed.
        val decide = before(after(later, "val wait = synchronized(owedTimers) {"), "delay(wait)")
        assertTrue("store.enabled && PushPlan.owesSend(store.side(serverId))" in decide)
        assertTrue("?.takeIf { owed }" in decide)
        assertTrue("?: return@launch" in decide)
        // The app check's refusal starts none: it is paid once the check passes. Nor does an app
        // older than the server takes: it is paid as soon as the server opens to it again.
        assertTrue("if (PushPlan.owesSend(side) && v?.code != VillaNetApi.APP_UNVERIFIED && v?.code != VillaNetApi.APP_OUTDATED) payLater(serverId, v?.retryAfterMs ?: 0)" in refused)
    }

    @Test fun tryAgainSendsARefusedAddressOnceMore() {
        inOrder(body(center, "suspend fun tryAgain("), "lock(serverId).withLock", "store.updateSide(serverId, PushPlan::beforeTryAgain)", "putLocked(serverId, PushPlan.Why.START")
    }

    @Test fun theHelloWaitAsksTheServerBeforeAnyPut() {
        val wait = body(center, "private fun awaitHello(")
        inOrder(wait, "PushPlan.helloDue(", "one.plugin.pushState()", "PushPlan.helloMissing(store.side(serverId), answer", "putLocked(serverId, PushPlan.Why.REPEAT")
        assertTrue("no PUT before the question", wait.indexOf("putLocked") > wait.indexOf("one.plugin.pushState()"))
    }

    @Test fun aPushThatComesThroughEndsTemporarilyUnavailable() {
        assertTrue("PushRegistration.Event.Delivered" in body(receiver, "is PushGate.Admitted.Message ->"))
        assertTrue("Inbound.of(intent) { store.distributorOf(it) != null }" in body(receiver, "override fun onReceive("))
    }
}
