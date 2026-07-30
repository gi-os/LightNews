package com.gios.lightnews.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.gios.lightnews.ui.theme.Dim
import kotlinx.coroutines.delay

/**
 * The brightness level, briefly, while the wheel is being turned.
 *
 * Without it the wheel is guesswork on a screen whose backlight change is the only
 * feedback — and at the dim end of the scale that change is nearly invisible. Sits at the
 * bottom rather than the middle so it never covers what is being read, and clears itself
 * after [DWELL_MS] of no further notches.
 */
@Composable
fun BrightnessReadout(percent: Int?, onExpired: () -> Unit) {
    // Keyed on the value, so every notch restarts the countdown instead of the bar
    // vanishing mid-turn.
    LaunchedEffect(percent) {
        if (percent == null) return@LaunchedEffect
        delay(DWELL_MS)
        onExpired()
    }

    if (percent != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "BRIGHTNESS ${percent ?: 0}%",
                style = MaterialTheme.typography.labelSmall,
                color = Dim,
            )
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color(0xFF303030)),
            ) {
                // A plain fraction of the parent: LinearProgressIndicator animates, and an
                // animation that lags a fast turn reads as lag in the wheel itself.
                Box(
                    Modifier
                        .fillMaxWidth((percent ?: 0) / 100f)
                        .height(2.dp)
                        .background(Color.White),
                )
            }
        }
    }
}

private const val DWELL_MS = 900L
