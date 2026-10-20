package dev.spookcue.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.spookcue.ui.screens.home.HomeScreen
import dev.spookcue.ui.screens.settings.SettingsScreen
import dev.spookcue.ui.screens.sync.SyncScreen

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Sync : Screen("sync/{tmdbId}") {
        fun createRoute(tmdbId: Int): String = "sync/$tmdbId"
    }
    data object Settings : Screen("settings")
}

@Composable
fun NavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onMovieSelected = { tmdbId ->
                    navController.navigate(Screen.Sync.createRoute(tmdbId))
                },
                onOpenSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.Sync.route,
            arguments = listOf(
                navArgument("tmdbId") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val tmdbId = backStackEntry.arguments?.getInt("tmdbId") ?: -1
            SyncScreen(
                tmdbId = tmdbId,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
