package com.gios.lightnews.ui

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
import com.gios.lightnews.ui.theme.Dim
import com.gios.lightnews.util.RenderMode
import com.gios.lightnews.util.formatAge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: NewsViewModel,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingLabel by remember { mutableStateOf(false) }

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
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SectionLabel("MAILBOX")
            MenuRow(
                label = if (vm.isSignedIn) "Signed in" else "Not signed in",
                sub = vm.account ?: if (vm.isConfigured) "tap to authorise" else "no client id in this build",
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
