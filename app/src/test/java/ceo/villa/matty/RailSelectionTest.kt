package ceo.villa.matty

import androidx.compose.runtime.saveable.SaverScope
import ceo.villa.matty.data.api.Channel
import ceo.villa.matty.ui.HomeTab
import ceo.villa.matty.ui.RailDirect
import ceo.villa.matty.ui.RailDiscover
import ceo.villa.matty.ui.RailPage
import ceo.villa.matty.ui.RailPages
import ceo.villa.matty.ui.RailPlugins
import ceo.villa.matty.ui.channelInMain
import ceo.villa.matty.ui.home.HomeBack
import ceo.villa.matty.ui.home.PanelValue
import ceo.villa.matty.ui.home.chatOnScreen
import ceo.villa.matty.ui.home.homeBack
import ceo.villa.matty.ui.home.membersBesideChat
import ceo.villa.matty.ui.railSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Discover and Plugins on the rail: their page takes the main area, the side bar stays as it was. */
class RailSelectionTest {
    @Test fun thePageInTheMainAreaIsTheOneLit() {
        assertEquals(RailDirect, railSelection(null, null))
        assertEquals("team1", railSelection("team1", null))
        assertEquals(RailDiscover, railSelection("team1", RailPage.Discover))
        assertEquals(RailPlugins, railSelection(null, RailPage.Plugins))
    }

    @Test fun backGoesToWhatWasSelectedBefore() {
        val pages = RailPages()
        pages.open(RailPage.Discover)
        pages.open(RailPage.Plugins)
        assertEquals(RailPage.Plugins, pages.current)
        assertTrue(pages.back())
        assertEquals(RailPage.Discover, pages.current)
        assertTrue(pages.back())
        // The team underneath, which stayed selected all along.
        assertNull(pages.current)
        assertEquals("team1", railSelection("team1", pages.current))
        assertFalse(pages.back(), "nothing left: Back is the screen's own again")
    }

    @Test fun pressingTheOpenPageAgainOrSwitchingBackStacksNothing() {
        val pages = RailPages()
        pages.open(RailPage.Discover)
        pages.open(RailPage.Discover)
        pages.open(RailPage.Plugins)
        pages.open(RailPage.Discover)
        assertEquals(RailPage.Discover, pages.current)
        assertTrue(pages.back())
        assertEquals(RailPage.Plugins, pages.current)
        assertTrue(pages.back())
        assertNull(pages.current)
    }

    @Test fun pickingATeamLeavesThePages() {
        val pages = RailPages()
        pages.open(RailPage.Plugins)
        pages.clear()
        assertNull(pages.current)
        assertFalse(pages.back())
    }

    @Test fun underAPageNoChannelIsOpenInTheListAndTheChatIsNotBeingRead() {
        assertEquals("ch1", channelInMain("ch1", null))
        assertNull(channelInMain("ch1", RailPage.Discover))
        assertNull(channelInMain("ch1", RailPage.Plugins))
        // Side by side the chat column shows the page; on a phone the chat's panel does.
        assertTrue(chatOnScreen(HomeTab.Home, wide = true, PanelValue.Center, null))
        assertFalse(chatOnScreen(HomeTab.Home, wide = true, PanelValue.Center, RailPage.Discover))
        assertTrue(chatOnScreen(HomeTab.Home, wide = false, PanelValue.Center, null))
        assertFalse(chatOnScreen(HomeTab.Home, wide = false, PanelValue.Center, RailPage.Plugins))
        assertFalse(chatOnScreen(HomeTab.Home, wide = false, PanelValue.Start, null))
    }

    @Test fun thePageTakesTheMembersPlaceToo() {
        val channel = Channel(id = "ch1", type = "O")
        val direct = Channel(id = "dm1", type = "D")
        assertTrue(membersBesideChat(channel, null))
        assertFalse(membersBesideChat(channel, RailPage.Discover), "no member column beside the page, and no slide to one on a phone")
        assertFalse(membersBesideChat(direct, null))
        assertFalse(membersBesideChat(null, null))
    }

    @Test fun backFromAPageOnAPhoneIsBackFromAChat() {
        // In the chat's panel: back to the side bar, the page's button still lit.
        assertEquals(HomeBack.ToStart, homeBack(wide = false, PanelValue.Center, RailPage.Discover))
        assertEquals(HomeBack.ToStart, homeBack(wide = false, PanelValue.Center, null))
        // On the side bar: the page closes and the team is lit again; with none, Back leaves the app.
        assertEquals(HomeBack.ClosePage, homeBack(wide = false, PanelValue.Start, RailPage.Discover))
        assertNull(homeBack(wide = false, PanelValue.Start, null))
        assertEquals(HomeBack.ToCenter, homeBack(wide = false, PanelValue.End, null))
    }

    @Test fun backFromAPageSideBySideClosesIt() {
        assertEquals(HomeBack.ClosePage, homeBack(wide = true, PanelValue.Center, RailPage.Plugins))
        assertNull(homeBack(wide = true, PanelValue.Center, null))
    }

    @Test fun thePagesComeBackWithTheProcess() {
        val pages = RailPages()
        pages.open(RailPage.Discover)
        pages.open(RailPage.Plugins)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(RailPages.Saver) { scope.save(pages) }!!
        val restored = RailPages.Saver.restore(saved)!!
        assertEquals(RailPage.Plugins, restored.current)
        assertTrue(restored.back())
        assertEquals(RailPage.Discover, restored.current)
    }
}
