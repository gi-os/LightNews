package com.gios.lightnews.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnews.BuildConfig
import com.gios.lightnews.hw.WheelScroll
import com.gios.lightnews.ui.theme.Dim
import com.gios.lightnews.util.RenderMode
import com.gios.lightnews.util.formatAge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: NewsViewModel,
    onSignIn: () -> Unit,
    onScanClientId: () -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingLabel by remember { mutableStateOf(false) }
    var editingClientId by remember { mutableStateOf(false) }

    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(scroll),
        ) {
            SectionLabel("MAILBOX")
            MenuRow(
                label = "Client ID",
                sub = if (settings.clientIdSet) {
                    "${settings.clientIdHint}… · Google OAuth client"
                } else {
                    "needed before you can sign in"
                },
                detail = if (settings.clientIdSet) "CHANGE" else "SET",
                onClick = { editingClientId = true },
            )
            Rule()
            MenuRow(
                label = if (vm.isSignedIn) "Signed in" else "Not signed in",
                sub = vm.account
                    ?: if (settings.clientIdSet) "tap to authorise" else "set a client ID first",
                detail = if (vm.isSignedIn) "SIGN OUT" else null,
                onClick = { if (vm.isSignedIn) vm.signOut() else onSignIn() },
            )
            Rule()
            MenuRow(
                label = "Label",
                sub = "only this label is ever read",
                detail = settings.label,
                onClick = { editingLabel = true },
            )
            Rule()

            SectionLabel("READING")
            MenuRow(
                label = "Rendering",
                sub = if (settings.mode == RenderMode.DARK) {
                    "white on black, tables unwrapped"
                } else {
                    "the newsletter's own design"
                },
                detail = if (settings.mode == RenderMode.DARK) "DARK" else "PAPER",
                onClick = { vm.toggleRenderMode() },
            )
            Rule()
            MenuRow(
                label = "Block ads",
                sub = "sponsor blocks, and anything from an ad network",
                detail = if (settings.blockAds) "ON" else "OFF",
                onClick = { vm.setBlockAds(!settings.blockAds) },
            )
            Rule()
            MenuRow(
                label = "Images",
                sub = "off is faster and blocks tracking pixels",
                detail = if (settings.images) "ON" else "OFF",
                onClick = { vm.setLoadImages(!settings.images) },
            )
            Rule()

            SectionLabel("SYNC")
            MenuRow(
                label = "Check now",
                sub = if (settings.lastSyncMs == 0L) "never synced" else "last ${formatAge(settings.lastSyncMs)} ago",
                detail = if (state.syncing) "…" else "GO",
                onClick = { vm.sync() },
            )
            Rule()
            Text(
                "Gmail's push notifications need Firebase, which needs Play Services, " +
                    "which LightOS doesn't have — so this polls hourly on wi-fi, plus " +
                    "whenever you open the app.",
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Rule()
            Text(
                "LightNews ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = Dim,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (editingClientId) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            containerColor = Color.Black,
            titleContentColor = Color.White,
            textContentColor = Color.White,
            onDismissRequest = { editingClientId = false },
            title = { Text("Google client ID", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text(
                        "Scanning beats typing 70 characters. Open the companion page on " +
                            "a computer, paste the ID there, and scan the code it draws — " +
                            "or scan the code from scripts/authorize.py, which signs in " +
                            "without needing a browser on the phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Dim,
                    )
                    Box(Modifier.padding(top = 14.dp)) {
                        // Named, because WideButton's trailing parameter is the modifier.
                        WideButton(
                            label = "SCAN QR",
                            onClick = {
                                editingClientId = false
                                onScanClientId()
                            },
                        )
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = false,
                        maxLines = 3,
                        label = { Text("or paste it") },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        editingClientId = false
                        vm.applyScanned(draft)
                    },
                ) { Text("SAVE", color = if (draft.isBlank()) Dim else Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { editingClientId = false }) { Text("CANCEL", color = Dim) }
            },
        )
    }

    if (editingLabel) {
        var draft by remember { mutableStateOf(settings.label) }
        AlertDialog(
            containerColor = Color.Black,
            titleContentColor = Color.White,
            textContentColor = Color.White,
            onDismissRequest = { editingLabel = false },
            title = { Text("Gmail label", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("exact label name") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editingLabel = false
                    vm.setLabel(draft)
                }) { Text("SAVE", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { editingLabel = false }) { Text("CANCEL", color = Dim) }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Dim,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}
