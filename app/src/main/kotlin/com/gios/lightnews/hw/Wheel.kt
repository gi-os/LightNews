package com.gios.lightnews.hw

import android.webkit.WebView
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Wheel notches on their way from the activity to whatever is on screen.
 *
 * One notch per event, positive for up. The activity is the only thing that can see the
 * key — a `dispatchKeyEvent` override is what lets it win against a focused WebView — but
 * only the current screen knows what scrolling means, so the two are joined by a flow
 * rather than by the activity reaching into the UI.
 *
 * A [SharedFlow] with no replay, deliberately: a notch that arrives while nothing is
 * listening is gone, which is what you want. Buffered generously because the sensor emits
 * bursts far faster than a frame.
 */
class WheelBus {
    private val _notches = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    val notches: SharedFlow<Int> = _notches.asSharedFlow()

    fun send(notches: Int) {
        _notches.tryEmit(notches)
    }
}

val LocalWheelBus = staticCompositionLocalOf<WheelBus?> { null }

/**
 * Distance per notch. About six notches to a screenful on the LPIII panel — enough that a
 * flick of the wheel moves you somewhere, short enough that you can land on a paragraph.
 */
private val NOTCH = 64.dp

/**
 * Run [onNotch] for every notch while [active].
 *
 * [active] exists for the reader: `beyondViewportPageCount = 1` keeps the neighbouring
 * newsletters composed, and without a gate all three pages would scroll together.
 */
@Composable
fun WheelNotches(active: Boolean = true, onNotch: suspend (Int) -> Unit) {
    val bus = LocalWheelBus.current ?: return
    // The lambda is a fresh object on every recomposition, so keying the effect on it
    // would tear down and rebuild the collector constantly — and drop notches in the gap.
    val handler by rememberUpdatedState(onNotch)
    LaunchedEffect(bus, active) {
        if (!active) return@LaunchedEffect
        bus.notches.collect { handler(it) }
    }
}

/**
 * Point the wheel at a Compose scroller. Works for both `ScrollState` and `LazyListState`.
 *
 * Instant, not animated. An animation per notch would queue up behind a fast turn and
 * arrive late, and on a greyscale LCD a hard jump reads more cleanly than a smear.
 */
@Composable
fun WheelScroll(state: ScrollableState, active: Boolean = true) {
    val step = with(LocalDensity.current) { NOTCH.toPx() }
    WheelNotches(active) { notches ->
        // Wheel up moves up the document, so the scroll offset decreases.
        state.scrollBy(-notches * step)
    }
}

/** The same, for the reader's WebView, which Compose knows nothing about. */
@Composable
fun WheelScroll(web: WebView?, active: Boolean = true) {
    val step = with(LocalDensity.current) { NOTCH.toPx() }.toInt()
    WheelNotches(active && web != null) { notches ->
        web?.wheelScrollBy(-notches * step)
    }
}

/**
 * WebView scrolls past its content quite happily, leaving the document parked off screen,
 * so the range is clamped by hand. `computeVerticalScrollRange` is the rendered height,
 * which is what `scrollTo` is measured against.
 */
private fun WebView.wheelScrollBy(px: Int) {
    val limit = (computeVerticalScrollRange() - height).coerceAtLeast(0)
    scrollTo(scrollX, (scrollY + px).coerceIn(0, limit))
}
