package ceo.villa.matty

import ceo.villa.matty.plugins.villanet.VnPushState
import ceo.villa.matty.push.FailReason
import ceo.villa.matty.push.PushFacts
import ceo.villa.matty.push.PushLine
import ceo.villa.matty.push.PushLine.Action
import ceo.villa.matty.push.PushPlan
import ceo.villa.matty.push.PushRegistration
import ceo.villa.matty.push.PushSide
import ceo.villa.matty.push.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one line Settings > Notifications says about push for the server in view. */
class PushLineTest {
    private val ntfy = "io.heckel.ntfy"
    private val vapid = "B" + "A".repeat(86)
    private val endpoint = "https://ntfy.example.com/upabc?up=1"
    private val asking = PushRegistration(
        serverId = "s1", distributor = ntfy, token = "t0", vapid = vapid, description = "Chat",
        p256dh = "pub", sealedKey = "k", sealedAuth = "a",
    )
    private val delivering = asking.on(PushRegistration.Event.NewEndpoint(endpoint)) { "t1" }.registration!!
    private val confirmed = delivering.on(PushRegistration.Event.ServerConfirmed(endpoint)) { "t1" }.registration!!
    private val on = PushSide(offer = PushSide.OFFER_ON)

    private fun facts(
        on: Boolean = true, installed: List<String> = listOf(ntfy), chosen: String? = ntfy, permitted: Boolean = true,
        stop: Stop? = null, side: PushSide = this.on, registration: PushRegistration? = confirmed, working: Int = 1, servers: Int = 1,
        chosenInstalled: Boolean = chosen != null && chosen in installed,
    ) = PushFacts(on, installed, chosen, if (chosen == null) "" else "ntfy", permitted, stop, side, registration, working, servers, chosenInstalled)

    private fun line(f: PushFacts) = PushLine.of(f)!!

    @Test fun offSaysNothingUnlessThereIsNoAppToUse() {
        assertNull(PushLine.of(facts(on = false)))
        val none = line(facts(on = false, installed = emptyList(), chosen = null))
        assertEquals("No UnifiedPush app is installed on this phone", none.text)
        assertEquals(Action.WHAT_IS, none.action)
        assertEquals(Action.WHAT_IS, line(facts(installed = emptyList(), chosen = null)).action)
    }

    @Test fun aRemovedAppIsSaidAndOffersToSetUpAgainWithAnother() {
        for (f in listOf(facts(installed = listOf("org.example.other")), facts(stop = Stop.REMOVED, registration = null, chosenInstalled = false))) {
            val l = line(f)
            assertEquals("Your UnifiedPush app was removed", l.text)
            assertEquals(Action.SET_UP_AGAIN, l.action)
        }
        // With none left there is nothing to set up again with: what UnifiedPush is, instead.
        val none = line(facts(installed = emptyList()))
        assertEquals("Your UnifiedPush app was removed", none.text)
        assertEquals(Action.WHAT_IS, none.action)
    }

    @Test fun anAppStillInstalledThatTakesNoRegistrationNeedsSettingUp() {
        val text = "ntfy isn't set up for UnifiedPush right now: open it, then Set up again"
        // Its receiver switched off, the only one or beside another.
        for (f in listOf(facts(installed = emptyList(), chosenInstalled = true), facts(installed = listOf("org.example.other"), chosenInstalled = true))) {
            val l = line(f)
            assertEquals(text, l.text)
            assertEquals(Action.SET_UP_AGAIN, l.action)
            assertTrue(l.warning)
        }
        // Registrations kept meanwhile: the same line, not a removed app.
        assertEquals(text, line(facts(installed = emptyList(), chosenInstalled = true, registration = delivering)).text)
        // With push off: what to do first, not that none is installed.
        val off = line(facts(on = false, installed = emptyList(), chosenInstalled = true))
        assertEquals("ntfy isn't set up for UnifiedPush right now: open it first", off.text)
        assertEquals(Action.NONE, off.action)
        // Push off with another app to choose from: nothing to say, as ever.
        assertNull(PushLine.of(facts(on = false, installed = listOf("org.example.other"), chosenInstalled = true)))
    }

    @Test fun anAppTakingRegistrationsAgainAfterItsRegistrationWentIsSetUpAgain() {
        val l = line(facts(stop = Stop.REMOVED, registration = null))
        assertEquals("ntfy no longer has Matty's registration: Set up again", l.text)
        assertEquals(Action.SET_UP_AGAIN, l.action)
        assertTrue(l.warning)
        // Signed by somebody else now: removed, as it is.
        assertEquals("Your UnifiedPush app was removed", line(facts(stop = Stop.REMOVED, registration = null, chosenInstalled = false)).text)
    }

    @Test fun notificationsSwitchedOffForMattyComeFirst() {
        assertEquals("Notifications are off for Matty", line(facts(permitted = false)).text)
    }

    @Test fun aServerThatDoesNotOfferItSaysWhy() {
        assertEquals("This server doesn't offer push notifications: it needs Matty Plugin 4.1.0", line(facts(side = PushSide(offer = PushSide.OFFER_OLD))).text)
        assertEquals("This server doesn't offer push notifications: it needs Matty Plugin 4.1.0", line(facts(side = PushSide(offer = PushSide.OFFER_NONE))).text)
        // Switched off there: the server's own sentence.
        val off = PushSide(offer = PushSide.OFFER_OFF, reason = "Push notifications are switched off on this server.")
        assertEquals("Push notifications are switched off on this server.", line(facts(side = off)).text)
    }

    @Test fun aDistributorThatStoppedOffersToSetUpAgain() {
        val l = line(facts(stop = Stop.STOPPED, registration = null))
        assertEquals("ntfy stopped delivering for Matty", l.text)
        assertEquals(Action.SET_UP_AGAIN, l.action)
    }

    @Test fun whileTheDistributorIsAsked() {
        assertEquals("Asking ntfy…", line(facts(registration = null)).text)
        // Nothing was asked yet: the server could not be reached.
        val away = line(facts(registration = null, side = PushSide(unreachable = true)))
        assertEquals("This server can't be reached right now: Matty asks again when you come back here or the network changes", away.text)
        assertTrue(away.warning)
        assertEquals("Asking ntfy…", line(facts(registration = asking)).text)
        assertEquals("ntfy is temporarily unavailable", line(facts(registration = delivering.on(PushRegistration.Event.TempUnavailable) { "x" }.registration)).text)
    }

    @Test fun aFailedRegistrationSaysWhatToDo() {
        fun failed(r: FailReason) = asking.copy(failure = r)
        assertEquals("Open ntfy and finish setting it up", line(facts(registration = failed(FailReason.ACTION_REQUIRED))).text)
        assertTrue(line(facts(registration = failed(FailReason.NETWORK))).text.contains("when the network is back"))
        assertEquals("ntfy did not register Matty", line(facts(registration = failed(FailReason.INTERNAL_ERROR))).text)
    }

    @Test fun waitingForTheFirstPushAndForTheCheckMessage() {
        val waiting = line(facts(registration = delivering, side = on.copy(state = "pending")))
        assertEquals("Waiting for the first push", waiting.text)
        assertEquals(Action.NONE, waiting.action)
        val unheard = line(facts(registration = delivering, side = on.copy(state = "pending", unheard = true)))
        assertEquals("ntfy has not delivered the check message: open it and make sure it is connected", unheard.text)
        assertTrue(unheard.warning)
        assertEquals("the address goes once more", Action.TRY_AGAIN, unheard.action)
    }

    @Test fun tryAgainOnAMissingCheckMessageWaitsForTheFirstPushAgain() {
        val now = 1_800_000_000_000L
        // Three PUTs, and the server still waits for the hello.
        val tired = on.copy(state = "pending", pendingAt = now - 200_000, puts = PushPlan.MAX_PUTS, unheard = true, sent = endpoint)
        assertEquals(Action.TRY_AGAIN, line(facts(registration = delivering, side = tired)).action)
        // What PushCenter.tryAgain does: beforeTryAgain, then a PUT made for a start.
        val pending = VnPushState(state = "pending", host = "ntfy.example.com")
        val again = PushPlan.afterPut(pending, PushPlan.beforeTryAgain(tired), endpoint, PushPlan.Why.START, now)
        assertFalse(again.unheard)
        assertEquals(1, again.puts)
        assertEquals(now, again.pendingAt)
        assertEquals("Waiting for the first push", line(facts(registration = delivering, side = again)).text)
        // Its hello is waited for as the first was, and the address may go twice more by itself.
        assertFalse(PushPlan.helloDue(again, now + PushPlan.HELLO_WAIT_MS - 1))
        assertEquals(PushPlan.Next.PUT, PushPlan.helloMissing(again, pending, false, now + PushPlan.HELLO_WAIT_MS).first)
        // Answered confirmed meanwhile (the hello got through late): nothing stale either.
        val done = PushPlan.afterPut(VnPushState(state = "confirmed", host = "ntfy.example.com"), PushPlan.beforeTryAgain(tired), endpoint, PushPlan.Why.START, now)
        assertFalse(done.unheard)
        assertEquals(0, done.puts)
    }

    @Test fun workingNamesTheAppAndThePushServerAndOffersATest() {
        val l = line(facts(side = on.copy(state = "confirmed", host = "ntfy.example.com")))
        assertEquals("Working through ntfy (ntfy.example.com)", l.text)
        assertEquals(Action.SEND_TEST, l.action)
        assertTrue(l.working)
        assertFalse(l.warning)
        // With several saved servers, how many it works for.
        assertEquals(
            "Working through ntfy (ntfy.example.com), for 2 of your 3 servers",
            line(facts(side = on.copy(state = "confirmed", host = "ntfy.example.com"), working = 2, servers = 3)).text,
        )
    }

    @Test fun workingNeedsTheServersOwnWord() {
        // Confirmed here, but the server's last word is a hello under way (that may yet be refused).
        assertEquals("Waiting for the first push", line(facts(side = on.copy(state = "pending", host = "ntfy.example.com"))).text)
        assertFalse(line(facts(side = on.copy(state = "pending"))).working)
        assertFalse("nothing heard yet", line(facts(side = on)).working)
    }

    @Test fun anAddressTheServerWouldNotTakeShowsTheServersSentenceAndTryAgain() {
        val sentence = "This server sends pushes only to the push servers its admin listed."
        val l = line(facts(registration = delivering, side = on.copy(state = "none", refusal = sentence, rejected = endpoint)))
        assertEquals(sentence, l.text)
        assertTrue(l.warning)
        assertEquals("its admin may have listed it since", Action.TRY_AGAIN, l.action)
        // The app check's sentence: nothing the person can retry.
        val check = line(facts(registration = delivering, side = on.copy(state = "none", refusal = "Only the official Matty.", owesPut = true)))
        assertEquals("Only the official Matty.", check.text)
        assertEquals(Action.NONE, check.action)
    }

    @Test fun aPutThatHasNotReachedTheServerIsNeverWaitingForTheFirstPush() {
        val text = "Couldn't register with this server yet: trying again"
        for (side in listOf(on.copy(state = "pending", owesPut = true), on.copy(owesPut = true), on.copy(state = "confirmed", owesPut = true))) {
            val l = line(facts(registration = delivering, side = side))
            assertEquals(text, l.text)
            assertEquals(Action.TRY_AGAIN, l.action)
            assertTrue(l.warning)
        }
        assertEquals(text, line(facts(side = on.copy(state = "confirmed", host = "ntfy.example.com", owesPut = true))).text)
    }

    @Test fun aRefusingPushServerIsNamedWithNtfysUsualCause() {
        val l = line(facts(side = on.copy(state = "refused", host = "ntfy.example.com", status = 403)))
        assertTrue(l.text.startsWith("Your push server (ntfy.example.com) refused this server's messages"))
        assertEquals("the person may have fixed it", Action.TRY_AGAIN, l.action)
        assertTrue(l.text.contains("anonymous publishing to up* topics"))
        assertFalse(l.working)
        val other = line(facts(chosen = "org.example.distributor", installed = listOf("org.example.distributor"), side = on.copy(state = "refused", host = "push.example.com", status = 401)))
        assertFalse(other.text.contains("up*"))
    }

    @Test fun aPushServerThisServerCannotReach() {
        val l = line(facts(side = on.copy(state = "failing", host = "push.example.com", kind = "certificate")))
        assertEquals("This server can't reach your push server (push.example.com): its certificate did not verify", l.text)
        assertTrue(l.warning)
        assertEquals(Action.TRY_AGAIN, l.action)
        assertEquals("This server can't reach your push server (push.example.com)", line(facts(side = on.copy(state = "failing", host = "push.example.com", kind = "unreachable"))).text)
        assertEquals(
            "This server can't reach your push server (push.example.com): the server's address rules don't allow it",
            line(facts(side = on.copy(state = "failing", host = "push.example.com", kind = "address"))).text,
        )
    }

    @Test fun aPushServerThatAnswersButNotWithAYes() {
        val l = line(facts(side = on.copy(state = "failing", host = "push.example.com", kind = "answer")))
        assertEquals("Your push server (push.example.com) doesn't take this server's messages: it answers, but not with a yes", l.text)
        assertTrue(l.warning)
        assertEquals(Action.TRY_AGAIN, l.action)
    }

    @Test fun sentShowsUntilTheTestArrivesThenTenSecondsOnSaysItHasNot() {
        val sent = 1_000_000L
        val missing = "The test notification has not arrived: open ntfy and make sure it is connected"
        // Not asked for (or the server did not take it).
        assertNull(PushLine.testSaid(0, 0, 5, "ntfy"))
        assertFalse(PushLine.testMissing(0, 0, PushLine.TEST_SHOWN_MS + 5))
        // Asked, and waiting.
        val waiting = PushLine.testSaid(sent, 0, sent + 1, "ntfy")!!
        assertEquals(PushLine.TEST_SENT, waiting.text)
        assertFalse(waiting.warning)
        assertEquals("an earlier test's arrival", PushLine.TEST_SENT, PushLine.testSaid(sent, sent - 5, sent + 1, "ntfy")?.text)
        assertEquals(PushLine.TEST_SENT, PushLine.testSaid(sent, 0, sent + PushLine.TEST_SHOWN_MS - 1, "ntfy")?.text)
        assertFalse(PushLine.testMissing(sent, 0, sent + PushLine.TEST_SHOWN_MS - 1))
        // Arrived: nothing more (the notification says the rest).
        assertNull("this one arrived", PushLine.testSaid(sent, sent + 65, sent + 100, "ntfy"))
        assertNull("even before the answer did", PushLine.testSaid(sent, sent, sent + 100, "ntfy"))
        // Ten seconds on without it: a warning, which stays.
        for (at in listOf(sent + PushLine.TEST_SHOWN_MS, sent + 120_000)) {
            val late = PushLine.testSaid(sent, sent - 5, at, "ntfy")!!
            assertEquals(missing, late.text)
            assertTrue(late.warning)
            assertTrue(PushLine.testMissing(sent, sent - 5, at))
        }
        assertEquals(
            "The test notification has not arrived: open your UnifiedPush app and make sure it is connected",
            PushLine.testSaid(sent, 0, sent + PushLine.TEST_SHOWN_MS, "")?.text,
        )
        // Arrived late (the UnifiedPush app opened two minutes on): it goes.
        assertNull(PushLine.testSaid(sent, sent + 120_000, sent + 121_000, "ntfy"))
        assertFalse(PushLine.testMissing(sent, sent + 120_000, sent + 121_000))
    }
}
