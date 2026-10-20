package dev.spookcue.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorThemeTest {

    @Test
    fun testPureBlackIsAbsoluteBlack() {
        assertEquals(1.0f, PureBlack.alpha, 0.001f)
        assertEquals(0.0f, PureBlack.red, 0.001f)
        assertEquals(0.0f, PureBlack.green, 0.001f)
        assertEquals(0.0f, PureBlack.blue, 0.001f)
    }

    @Test
    fun testCrimsonPaletteIntegrity() {
        // Red component should dominate green and blue in crimson tokens
        assertTrue(CrimsonPrimary.red > CrimsonPrimary.green)
        assertTrue(CrimsonPrimary.red > CrimsonPrimary.blue)

        assertTrue(CrimsonDark.red > CrimsonDark.green)
        assertTrue(CrimsonDark.red > CrimsonDark.blue)

        assertTrue(CrimsonLight.red > CrimsonLight.green)
        assertTrue(CrimsonLight.red > CrimsonLight.blue)

        assertTrue(CrimsonBorder.red > CrimsonBorder.green)
    }

    @Test
    fun testTextContrast() {
        // TextPrimary should be high luminance (near white)
        assertTrue(TextPrimary.red > 0.9f)
        assertTrue(TextPrimary.green > 0.9f)
        assertTrue(TextPrimary.blue > 0.9f)

        // TextSecondary should be medium luminance
        assertTrue(TextSecondary.red > 0.5f)
        assertTrue(TextSecondary.red < TextPrimary.red)
    }
}
