package com.gios.lightnews.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnews.ui.theme.Dim
import com.gios.lightnews.util.formatAge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    vm: NewsViewModel,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onSignIn: () -> Unit,
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val unread by vm.unreadCount.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = {
                    Column {
                        Text("Newsletters", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (unread > 0) "$unread unread" else "all read",
                            style = MaterialTheme.typography.labelSmall,
                            color = Dim,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
                actions = {
                    if (unread > 0) {
                        IconButton(onClick = { vm.markAllRead() }) {
                            Icon(Icons.Default.Done, "Mark all read")
                        }
                    }
                    IconButton(onClick = { vm.sync() }) { Icon(Icons.Default.Refresh, "Sync") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (state.syncing) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = Color(0xFF303030),
                )
            }
            state.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }

            when {
                !settings.clientIdSet -> EmptyState(
                    "No Google client ID yet.\n\nMake an Android OAuth client for " +
                        "com.gios.lightnews, then scan or paste its ID in settings. " +
                        "The README has the steps.",
                    action = "SETTINGS",
                    onAction = onSettings,
                )

                state.needsAuth -> EmptyState(
                    "Sign in to the Google account that holds the ${settings.label} label.",
                    action = "SIGN IN",
                    onAction = onSignIn,
                )

                state.labelMissing -> EmptyState(
                    "No label called ${settings.label} in this mailbox.\n\nMake it in Gmail, " +
                        "filter your newsletters into it, or change the name in settings.",
                    action = "SETTINGS",
                    onAction = onSettings,
                )

                items.isEmpty() -> EmptyState(
                    "Nothing in ${settings.label} yet.",
                    action = "CHECK AGAIN",
                    onAction = { vm.sync() },
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(items, key = { it.id }) { item ->
                        ListRow(
                            sender = item.fromName,
                            subject = item.subject,
                            age = formatAge(item.dateMs),
                            unread = item.unread,
                            onClick = { onOpen(item.id) },
                        )
                        Rule()
                    }
                    item {
                        Box(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text(
                                if (settings.lastSyncMs == 0L) {
                                    "not synced yet"
                                } else {
                                    "synced ${formatAge(settings.lastSyncMs)} ago · hourly on wi-fi"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Dim,
                            )
                        }
                    }
                }
            }
        }
    }
}
