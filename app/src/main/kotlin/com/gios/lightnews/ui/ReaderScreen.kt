package com.gios.lightnews.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnews.data.NewsletterEntity
import com.gios.lightnews.data.Rendered
import com.gios.lightnews.ui.theme.Dim
import com.gios.lightnews.util.RenderMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The reader. One newsletter per page, swipe for the next.
 *
 * Reading is what marks a message read, and "read" means the page has settled for a
 * moment — flicking past six issues to reach the seventh should not silently clear all
 * six, which is what marking on page change would do.
 */
@Composable
fun ReaderScreen(
    vm: NewsViewModel,
    startId: String,
    onBack: () -> Unit,
) {
    val items by vm.items.collectAsStateWithLifecycle()

    /*
     * The order is frozen for the life of the screen: the list re-sorts as messages
     * sync, and having the page jump under your thumb mid-read is worse than a slightly
     * stale order. Held in state rather than remember{items} because the first emission
     * can be empty — a fresh ViewModel restoring this back-stack entry after process
     * death — and freezing that would strand the reader on page zero.
     */
    var frozen by remember { mutableStateOf<List<NewsletterEntity>?>(null) }
    LaunchedEffect(items) { if (frozen == null && items.isNotEmpty()) frozen = items }
    val ordered = frozen

    if (ordered == null) {
        // With a back affordance: if the table is empty — signed out elsewhere, or
        // pruned — this state never resolves, and the launcher is the only way out of a
        // screen whose only content is the word "Opening".
        EmptyState("Opening…", action = "BACK", onAction = onBack)
        return
    }

    val startIndex = remember(ordered) { ordered.indexOfFirst { it.id == startId } }
    if (startIndex < 0) {
        // Pruned between the tap and here. Landing on page zero instead would mark the
        // wrong newsletter read a second and a half later.
        EmptyState("That issue is gone.", action = "BACK", onAction = onBack)
        return
    }

    Pages(vm = vm, ordered = ordered, startIndex = startIndex, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Pages(
    vm: NewsViewModel,
    ordered: List<NewsletterEntity>,
    startIndex: Int,
    onBack: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pager = rememberPagerState(initialPage = startIndex) { ordered.size }
    val current = ordered.getOrNull(pager.currentPage)
    val scope = rememberCoroutineScope()

    LaunchedEffect(pager.settledPage) {
        val settled = ordered.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        delay(DWELL_MS)
        vm.markRead(settled.id)
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = {
                    Column {
                        Text(
                            current?.fromName.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${pager.currentPage + 1} of ${ordered.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Dim,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // One tap between white-on-black and the newsletter's own artwork.
                    // A word, not a glyph: material-icons-core ships no contrast icon,
                    // and the extended set is 10 MB for one drawable.
                    TextButton(onClick = { vm.toggleRenderMode() }) {
                        Text(
                            if (settings.mode == RenderMode.DARK) "DARK" else "PAPER",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                },
            )
        },
    ) { pad ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.padding(pad).fillMaxSize(),
            // One neighbour, so a swipe doesn't land on a blank page. Two documents and
            // two WebViews is about as much as this device should hold at once.
            beyondViewportPageCount = 1,
            key = { ordered[it].id },
        ) { page ->
            ArticlePage(
                vm = vm,
                item = ordered[page],
                // The WebView swallows every touch that lands on an article, so it is also
                // the thing that tells us a page turn was meant.
                onSwipe = { direction ->
                    val target = (page + direction).coerceIn(0, ordered.lastIndex)
                    if (target != page) scope.launch { pager.animateScrollToPage(target) }
                },
            )
        }
    }
}

@Composable
private fun ArticlePage(
    vm: NewsViewModel,
    item: NewsletterEntity,
    onSwipe: (Int) -> Unit,
) {
    val context = LocalContext.current
    val webViewAvailable = remember { WebViewSupport.isAvailable(context) }
    val settings by vm.settings.collectAsStateWithLifecycle()

    // Keyed on the settings too, so a mode change clears the old document instead of
    // showing it under the new background until the re-render lands.
    var rendered by remember(item.id, settings.mode, settings.images, settings.blockAds) {
        mutableStateOf<Rendered?>(null)
    }
    LaunchedEffect(item.id, settings.mode, settings.images, settings.blockAds) {
        rendered = vm.rendered(item.id, webViewAvailable)
    }

    // No Compose header any more: subject, sender and date are part of the document, so
    // the page holds exactly one scroller and the title scrolls away with the copy.
    Box(Modifier.fillMaxSize()) {
        when (val body = rendered) {
            null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Opening…", style = MaterialTheme.typography.bodyMedium, color = Dim)
            }

            is Rendered.Html -> HtmlView(
                document = body.document,
                mode = settings.mode,
                loadImages = settings.images,
                modifier = Modifier.fillMaxSize(),
                onSwipe = onSwipe,
            )

            is Rendered.Text -> SelectionContainer {
                Text(
                    body.body.ifBlank { "Nothing cached for this issue yet." },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
    }
}

private const val DWELL_MS = 1_500L
