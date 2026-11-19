package ceo.villa.matty

import ceo.villa.matty.data.session.ThemeMode
import ceo.villa.matty.ui.theme.Palette
import ceo.villa.matty.ui.theme.paletteFor
import kotlin.test.Test
import kotlin.test.assertEquals

class DarkStyleTest {
    @Test fun systemAtNightFollowsTheChosenDarkStyle() {
        assertEquals(Palette.Oled, paletteFor(ThemeMode.System, systemDark = true, systemDim = false))
        assertEquals(Palette.Dim, paletteFor(ThemeMode.System, systemDark = true, systemDim = true))
    }

    @Test fun systemByDayIsLightWhicheverStyleIsChosen() {
        assertEquals(Palette.Light, paletteFor(ThemeMode.System, systemDark = false, systemDim = false))
        assertEquals(Palette.Light, paletteFor(ThemeMode.System, systemDark = false, systemDim = true))
    }

    @Test fun aFixedThemeIgnoresTheDarkStyle() {
        for (night in listOf(true, false)) for (dim in listOf(true, false)) {
            assertEquals(Palette.Oled, paletteFor(ThemeMode.Oled, night, dim))
            assertEquals(Palette.Dim, paletteFor(ThemeMode.Dim, night, dim))
            assertEquals(Palette.Light, paletteFor(ThemeMode.Light, night, dim))
        }
    }
}
