package com.gios.lightnews

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gios.lightnews.ui.ListScreen
import com.gios.lightnews.ui.NewsViewModel
import com.gios.lightnews.ui.ReaderScreen
import com.gios.lightnews.ui.SettingsScreen
import com.gios.lightnews.ui.theme.LightNewsTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LightNewsTheme {
                val nav = rememberNavController()
                val vm: NewsViewModel = viewModel()

                /*
                 * The consent screen runs in the phone's browser, not in this app — a
                 * WebView here would be rejected by Google as a disallowed user agent.
                 * AppAuth hands the redirect back through the activity result.
                 */
                val signIn = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result -> vm.onAuthResult(result.data) }

                // The companion page can prefix the payload; setClientId accepts either.
                val scanClientId = rememberLauncherForActivityResult(ScanContract()) { result ->
                    result.contents?.let { vm.setClientId(it) }
                }

                val startSignIn = {
                    // AppAuth throws ActivityNotFoundException when it finds no browser,
                    // which is the plausible LightOS failure. Silent would leave the one
                    // button the whole app depends on looking simply dead.
                    runCatching { signIn.launch(vm.authorizationIntent()) }
                        .onFailure { vm.reportError("No browser available to sign in with") }
                }

                NavHost(nav, startDestination = "list") {
                    composable("list") {
                        ListScreen(
                            vm = vm,
                            onOpen = { id -> nav.navigate("reader/$id") },
                            onSettings = { nav.navigate("settings") },
                            onSignIn = { startSignIn() },
                        )
                    }
                    composable(
                        "reader/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        ReaderScreen(
                            vm = vm,
                            startId = entry.arguments!!.getString("id")!!,
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            vm = vm,
                            onSignIn = { startSignIn() },
                            onScanClientId = {
                                scanClientId.launch(
                                    ScanOptions()
                                        .setBeepEnabled(false)
                                        .setPrompt("Scan the client ID code"),
                                )
                            },
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
