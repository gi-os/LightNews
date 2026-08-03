package com.gios.lightnews

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gios.lightnews.hw.Brightness
import com.gios.lightnews.hw.LightControls
import com.gios.lightnews.hw.LocalWheelBus
import com.gios.lightnews.hw.WheelBus
import com.gios.lightnews.ui.BrightnessReadout
import com.gios.lightnews.ui.ListScreen
import com.gios.lightnews.ui.NewsViewModel
import com.gios.lightnews.ui.ReaderScreen
import com.gios.lightnews.ui.SettingsScreen
import com.gios.lightnews.ui.theme.LightNewsTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.flow.MutableStateFlow
import com.gios.lightnews.report.CrashLog
import com.gios.lightnews.report.ReportOverlay

class MainActivity : ComponentActivity() {

    /** The OAuth redirect, waiting to be handed to the ViewModel once composition runs. */
    private val redirect = MutableStateFlow<Uri?>(null)

    /** Wheel notches on their way to whichever screen is up. */
    private val wheel = WheelBus()

    /** The brightness percentage to flash on screen, or null for nothing. */
    private val brightnessReadout = MutableStateFlow<Int?>(null)

    private val controls by lazy {
        LightControls(
            activity = this,
            wheel = wheel,
            brightness = Brightness(this),
            onBrightnessChanged = { percent -> brightnessReadout.value = percent },
        )
    }

    /**
     * Every hardware key arrives here first — `DecorView` calls the window callback before
     * it walks the view hierarchy — which is what lets the wheel beat a focused WebView.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        controls.dispatch(event) || super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing, before anything else can throw: the handler chains onto whatever is
        // already installed and only writes a file, so it is safe this early.
        CrashLog.install(this)
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

                // Every screen below can reach the wheel; the readout sits above all of
                // them, because brightness is adjustable wherever you happen to be.
                CompositionLocalProvider(LocalWheelBus provides wheel) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
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

                        val percent by brightnessReadout.collectAsStateWithLifecycle()
                        BrightnessReadout(percent) { brightnessReadout.value = null }
                    }
                }
                // Shake to report, the crash offer on next launch, and the app's own noticed
                // failures. A sibling, not a wrapper — the sheet is its own window, so it covers
                // the app whether or not it contains it.
                ReportOverlay()
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
