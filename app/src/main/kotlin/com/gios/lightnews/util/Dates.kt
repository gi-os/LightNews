package com.gios.lightnews.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Newsletter ages, at a glance. Relative up to a week — the only question you ever ask
 * of a newsletter is whether it's today's — then an absolute date.
 */
fun formatAge(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val delta = (nowMs - timestampMs).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestampMs))
    }
}

fun formatWhen(timestampMs: Long): String =
    SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(timestampMs))
