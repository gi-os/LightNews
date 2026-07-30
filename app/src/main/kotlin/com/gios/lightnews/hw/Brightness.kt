package com.gios.lightnews.hw

import android.app.Activity
import android.provider.Settings
import kotlin.math.roundToInt

/**
 * Stepping the screen brightness the way the wheel does everywhere else on the phone.
 *
 * Two paths, and which one runs is decided per call rather than once at startup, because
 * the appop can be granted while the app is running:
 *
 *  - **System brightness** (`Settings.System.SCREEN_BRIGHTNESS`) when `WRITE_SETTINGS` is
 *    held. This is the real thing: it persists, and it survives leaving the app. It needs
 *    `adb shell appops set com.gios.lightnews WRITE_SETTINGS allow` once — the permission
 *    in the manifest is not by itself grantable from inside the app on LightOS, since
 *    there is no Settings screen to send the user to.
 *  - **Window brightness** otherwise, so the wheel does something sensible before that
 *    grant happens. It only dims this app's window and unwinds when the app is left.
 *
 * The scale is not assumed. Android's brightness maximum is an internal resource — 255 on
 * most phones, but 1023, 2047 and 4095 all ship — so it is derived instead: the platform
 * keeps `screen_brightness` (int) and `screen_brightness_float` (0..1) in step, and their
 * ratio is the maximum. If the float row is missing, 255 is the fallback.
 */
class Brightness(private val activity: Activity) {

    private val cr = activity.contentResolver

    /** Remembered only for the window path, where there is nothing to read back. */
    private var windowLevel = -1f

    fun canWriteSystem(): Boolean =
        runCatching { Settings.System.canWrite(activity) }.getOrDefault(false)

    /**
     * Move brightness by [notches] (positive is brighter) and report the resulting level
     * as a percentage, for the on-screen readout.
     */
    fun step(notches: Int): Int =
        if (canWriteSystem()) stepSystem(notches) else stepWindow(notches)

    private fun stepSystem(notches: Int): Int {
        val max = systemMax()
        val floor = (max * 0.01f).roundToInt().coerceAtLeast(1)
        val current = runCatching {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(max / 2)

        // At least one raw unit per notch, however coarse the scale turns out to be.
        val delta = (max.toFloat() / STEPS * notches).roundToInt()
            .let { if (it == 0) notches.coerceIn(-1, 1) else it }
        val next = (current + delta).coerceIn(floor, max)

        runCatching {
            // Auto-brightness would fight every write and win a second later.
            Settings.System.putInt(
                cr,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next)
            // Keep the float row consistent when the platform is using it, otherwise the
            // display service can restore the old value from it.
            if (floatBrightness() >= 0f) {
                Settings.System.putFloat(cr, SCREEN_BRIGHTNESS_FLOAT, next.toFloat() / max)
            }
        }

        // A window override left over from before the grant would sit on top of all this.
        releaseWindow()
        return (next * 100f / max).roundToInt()
    }

    private fun stepWindow(notches: Int): Int {
        val start = if (windowLevel >= 0f) windowLevel else 0.5f
        val next = (start + notches.toFloat() / STEPS).coerceIn(WINDOW_FLOOR, 1f)
        windowLevel = next
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = next }
        return (next * 100).roundToInt()
    }

    private fun releaseWindow() {
        if (windowLevel < 0f) return
        windowLevel = -1f
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = -1f // BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun systemMax(): Int {
        val int = runCatching {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        val float = floatBrightness()
        // Below 2% the division amplifies rounding error into nonsense.
        if (int > 0 && float >= 0.02f && float <= 1f) {
            return (int / float).roundToInt().coerceIn(64, 65_535)
        }
        return DEFAULT_MAX
    }

    /** The 0..1 mirror of the int setting, or -1 if this build doesn't keep one. */
    private fun floatBrightness(): Float =
        runCatching { Settings.System.getFloat(cr, SCREEN_BRIGHTNESS_FLOAT) }.getOrDefault(-1f)

    private companion object {
        /** Notches from dimmest to brightest. The wheel fires fast; coarse is wrong. */
        const val STEPS = 24

        const val DEFAULT_MAX = 255

        /** Never 0f: a window at zero is a black screen with no way to see the way out. */
        const val WINDOW_FLOOR = 0.02f

        /** `Settings.System.SCREEN_BRIGHTNESS_FLOAT` is @hide; the key name is not. */
        const val SCREEN_BRIGHTNESS_FLOAT = "screen_brightness_float"
    }
}
