package dev.spookcue.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationTest {

    @Test
    fun testHomeRoute() {
        assertEquals("home", Screen.Home.route)
    }

    @Test
    fun testSettingsRoute() {
        assertEquals("settings", Screen.Settings.route)
    }

    @Test
    fun testSyncRoutePatternAndFactory() {
        assertEquals("sync/{tmdbId}", Screen.Sync.route)

        val conjunctingRoute = Screen.Sync.createRoute(138843)
        assertEquals("sync/138843", conjunctingRoute)

        val insidiousRoute = Screen.Sync.createRoute(58138)
        assertEquals("sync/58138", insidiousRoute)
    }
}
