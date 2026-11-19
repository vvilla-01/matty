package ceo.villa.matty

import androidx.compose.ui.graphics.Color
import ceo.villa.matty.data.api.User
import ceo.villa.matty.data.repo.NameColors
import ceo.villa.matty.data.repo.NamePalette
import ceo.villa.matty.data.repo.decodeNameColors
import ceo.villa.matty.data.repo.encodeNameColors
import ceo.villa.matty.data.repo.fallbackNameColor
import ceo.villa.matty.data.repo.handOutNameColors
import ceo.villa.matty.ui.components.nameColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

class NameColorsTest {
    private fun person(n: Int, createAt: Long = 1_000L + n) = User(id = "user%02d".format(n), username = "u$n", createAt = createAt)
    private val everyone = (0 until 30).map { person(it) }

    @Test
    fun theFirstTwentyAreAllDifferent() {
        val given = handOutNameColors(emptyMap(), everyone.take(20).shuffled(Random(1)))
        assertEquals(20, given.values.toSet().size)
    }

    @Test
    fun theOldestAccountsGoFirst() {
        val given = handOutNameColors(emptyMap(), listOf(person(1, createAt = 50), person(2, createAt = 10), person(3, createAt = 10)))
        assertEquals(mapOf("user02" to 0, "user03" to 1, "user01" to 2), given)
    }

    @Test
    fun aColourOnceGivenNeverChanges() {
        val first = handOutNameColors(emptyMap(), everyone.take(9))
        repeat(20) { seed ->
            var now = first
            // The rest arrive in any order, a few at a time, older accounts among them.
            val rest = (everyone.drop(9) + person(99, createAt = 1)).shuffled(Random(seed))
            rest.chunked(3).forEach { now = handOutNameColors(now, it + everyone.take(9).shuffled(Random(seed))) }
            first.forEach { (id, i) -> assertEquals("$id kept its colour", i, now[id]) }
            assertEquals(31, now.size)
        }
    }

    @Test
    fun afterTwentyTheLeastUsedIsTaken() {
        val twenty = handOutNameColors(emptyMap(), everyone.take(20))
        assertEquals("all used once: the lowest", 0, handOutNameColors(twenty, listOf(person(20)))["user20"])
        // Everything used twice but colour 7, once.
        val worn = (0 until 20).flatMap { i -> List(if (i == 7) 1 else 2) { k -> "x$i-$k" to i } }.toMap()
        assertEquals(7, handOutNameColors(worn, listOf(person(40)))["user40"])
    }

    @Test
    fun theOrderTheyArePassedInDoesNotMatter() {
        val once = handOutNameColors(emptyMap(), everyone)
        repeat(10) { assertEquals(once, handOutNameColors(emptyMap(), everyone.shuffled(Random(it)))) }
    }

    @Test
    fun somebodyKnownOnlyByIdWaitsAndNobodyNewChangesNothing() {
        val given = handOutNameColors(emptyMap(), everyone.take(3))
        assertSame(given, handOutNameColors(given, everyone.take(3) + User("placeholder")))
    }

    @Test
    fun whatIsKeptReadsBackTheSame() {
        val given = handOutNameColors(emptyMap(), everyone)
        assertEquals(given, decodeNameColors(encodeNameColors(given)))
        assertEquals(mapOf("a" to 3), decodeNameColors("a=3,b=99,=2,c=x,d"))
        assertEquals(emptyMap<String, Int>(), decodeNameColors(null))
    }

    @Test
    fun namesTakeTheirHandedOutColourAndAHashUntilThen() {
        NameColors.assigned = mapOf("user01" to 13)
        try {
            assertEquals(Color(NamePalette.dark[13]), nameColor("user01", dark = true))
            assertEquals(Color(NamePalette.light[13]), nameColor("user01", dark = false))
            assertEquals(Color(NamePalette.dark[fallbackNameColor("hook")]), nameColor("hook", dark = true))
        } finally {
            NameColors.assigned = emptyMap()
        }
    }

    // ---- the palette ----------------------------------------------------------------------

    private fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    private fun rgb(argb: Long) = Triple(lin(((argb shr 16) and 0xFF).toInt()), lin(((argb shr 8) and 0xFF).toInt()), lin((argb and 0xFF).toInt()))
    private fun luminance(argb: Long) = rgb(argb).let { (r, g, b) -> 0.2126 * r + 0.7152 * g + 0.0722 * b }
    private fun contrast(a: Double, b: Double) = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)

    private fun oklab(argb: Long): DoubleArray {
        val (r, g, b) = rgb(argb)
        val l = cbrt(0.4122214708 * r + 0.5363371758 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }
    private fun distance(a: Long, b: Long) = oklab(a).zip(oklab(b)) { x, y -> (x - y) * (x - y) }.sum().let { sqrt(it) }

    @Test
    fun twentyForEachTheme() {
        assertEquals(NamePalette.SIZE, NamePalette.dark.size)
        assertEquals(NamePalette.SIZE, NamePalette.light.size)
    }

    @Test
    fun everyColourReadsOnItsBackground() {
        NamePalette.dark.forEach { assertTrue("%08X on black".format(it), contrast(luminance(it), 0.0) >= 5.0) }
        NamePalette.light.forEach { assertTrue("%08X on white".format(it), contrast(luminance(it), 1.0) >= 4.5) }
    }

    @Test
    fun noTwoColoursLookAlike() {
        for (p in listOf(NamePalette.dark, NamePalette.light)) {
            // Neighbours (handed out one after the other) clearly apart, and no two anywhere close.
            p.toList().zipWithNext().forEach { (a, b) -> assertTrue("%08X / %08X".format(a, b), distance(a, b) >= 0.08) }
            for (i in p.indices) for (j in i + 1 until p.size) {
                assertTrue("%08X / %08X".format(p[i], p[j]), distance(p[i], p[j]) >= 0.04)
            }
        }
    }

    @Test
    fun theFirstTenAreSpreadRoundTheWheel() {
        for (p in listOf(NamePalette.dark, NamePalette.light)) {
            val hue = p.map { c -> oklab(c).let { Math.toDegrees(kotlin.math.atan2(it[2], it[1])).mod(360.0) } }
            fun apart(a: Double, b: Double) = ((a - b).mod(360.0)).let { minOf(it, 360 - it) }
            for (i in 0 until 4) assertEquals(72.0, apart(hue[i], hue[i + 1]), 6.0)
            for (i in 0 until 5) assertEquals(36.0, apart(hue[i], hue[i + 5]), 6.0)
        }
    }

    @Test
    fun peopleComeBeforeBotsAndDeactivatedAccounts() {
        // A server's bots are usually its oldest accounts: they don't take the colours that are
        // furthest apart before anybody who talks.
        val bot = User(id = "b0", username = "system-bot", createAt = 1, isBot = true)
        val gone = User(id = "g0", username = "gone", createAt = 2, deleteAt = 5)
        val alice = User(id = "p1", username = "alice", createAt = 10)
        val bob = User(id = "p2", username = "bob", createAt = 20)
        val out = handOutNameColors(emptyMap(), listOf(bot, gone, bob, alice))
        assertEquals(0, out["p1"])
        assertEquals(1, out["p2"])
        assertEquals(2, out["b0"])
        assertEquals(3, out["g0"])
    }
}
