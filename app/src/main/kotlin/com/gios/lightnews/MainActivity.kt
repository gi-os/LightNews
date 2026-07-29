package com.gios.lightnews

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** The OAuth redirect, waiting to be handed to the ViewModel once composition runs. */
    private val redirect = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureRedirect(intent)

        setContent {
            LightNewsTheme {
                val nav = rememberNavController()
                val vm: NewsViewModel = viewModel()

                val pendingRedirect by redirect.collectAsStateWithLifecycle()
                LaunchedEffect(pendingRedirect) {
                    pendingRedirect?.let {
                        vm.onRedirect(it)
                        redirect.value = null
                    }
                }

                /*
                 * Consent runs in whatever will open a web page — a plain ACTION_VIEW, not
                 * a Custom Tab and not a browser this app went looking for. AppAuth's
                 * BrowserSelector rejected the LightOS browser for not being a "full
                 * browser"; an implicit intent asks no such question, and doesn't consult
                 * package visibility either. The redirect returns through the custom-scheme
                 * intent filter, so nothing depends on which app opened the page.
                 */
                val startSignIn = {
                    val opened = runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, vm.authorizationUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.isSuccess
                    if (!opened) vm.reportError(NO_BROWSER)
                }

                // One scanner for both payloads: a bare client id, or the JSON credential
                // blob that scripts/authorize.py produces when there is no browser at all.
                val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
                    result.contents?.let { vm.applyScanned(it) }
                }
                val startScan = {
                    scan.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setBeepEnabled(false)
                            .setPrompt("Scan the code from the setup page"),
                    )
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
                            onScanClientId = { startScan() },
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureRedirect(intent)
    }

    /** Only our own scheme counts; the launcher intent carries no data at all. */
    private fun captureRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == Uri.parse(BuildConfig.OAUTH_REDIRECT).scheme) redirect.value = uri
    }

    private companion object {
        const val NO_BROWSER =
            "Nothing here can open a web page. Run scripts/authorize.py on a computer and scan the code."
    }
}
