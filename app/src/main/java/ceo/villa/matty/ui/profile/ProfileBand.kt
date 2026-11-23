package ceo.villa.matty.ui.profile

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How high the band across the top of a profile is for a [width]: three wide by one high on every
 * screen, the shape a banner's part is chosen in, so the part somebody chose is exactly the part
 * that shows. The same on the profile sheet and on the You page.
 */
internal fun bannerBandHeight(width: Dp): Dp = width / 3

/** How much of the picture's foot the band's foot overlaps. */
internal val PICTURE_OVERLAP = 48.dp

/** How far down a band [bandHeight] high the picture over it starts. */
internal fun pictureTop(bandHeight: Dp): Dp = bandHeight - PICTURE_OVERLAP

/**
 * Whether the You page of [myId] keeps what plugins hold while a profile is open (VillaNET: the
 * crop screen for the banner). Not while the profile sheet over it shows that same person
 * ([sheetUserId], null with no sheet open): the sheet keeps it then, so there is one crop screen
 * and never two.
 */
internal fun youPageKeepsProfileOver(myId: String, sheetUserId: String?): Boolean = sheetUserId != myId

/**
 * Whether the You page can be seen: its tab is the one shown ([youTab]), no full-screen view
 * (Settings, a thread, ...) is open over it ([routesAbove]), and the app is in front ([inFront]).
 * Only then does a banner on it count as looked at; an animated one moves as [bannerPlays] says.
 */
internal fun youPageInView(youTab: Boolean, routesAbove: Int, inFront: Boolean): Boolean =
    youTab && routesAbove == 0 && inFront

/** How long an animated banner goes on moving after its page stopped being seen: longer than a full-screen view takes to come in over it. */
internal const val BANNER_LINGER_MS = 1_000L

/**
 * Whether a profile's banner is drawn: while the app is in front ([inFront]), still or animated,
 * whenever its page is composed, so nothing visible changes while a full-screen view comes in over
 * the page or leaves it. Minimised, nothing is drawn.
 */
internal fun bannerDrawn(inFront: Boolean): Boolean = inFront

/**
 * Whether an animated banner moves: while its page can be seen ([inView]) or was seen less than
 * [BANNER_LINGER_MS] ago ([recentlyInView]). Covered for longer, it holds still on its frame.
 */
internal fun bannerPlays(inView: Boolean, recentlyInView: Boolean): Boolean = inView || recentlyInView
