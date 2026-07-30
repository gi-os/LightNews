package com.gios.lightnews.hw

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.MediaStore
import android.view.KeyEvent

/**
 * The wheel and the camera button, wired up.
 *
 * Lives in the activity because [Activity.dispatchKeyEvent] is the one place that sees a
 * key before the view hierarchy does. `DecorView` hands the event to the window callback —
 * the activity — before `superDispatchKeyEvent` walks the views, so returning true here
 * beats a focused WebView, a text field, and the on-screen keyboard.
 *
 * The gesture split:
 *
 *  - **Turn the wheel** → notches to the screen, which scrolls.
 *  - **Hold the wheel in and turn** → brightness, the phone's own behaviour.
 *  - **Click the wheel** (no turn) → flashlight.
 *  - **Camera button** → the Light camera.
 *
 * The press-and-turn split is possible because a held `WHEEL_CLICK` produces no key
 * repeat: DOWN arrives, wheel notches arrive, UP arrives. So the press is a modifier, and
 * whether it was *only* a press is known by the time UP lands. Same shape as LightVoice's
 * push-to-talk, minus the accessibility service, because here the app has focus already.
 */
class LightControls(
    private val activity: Activity,
    private val wheel: WheelBus,
    private val brightness: Brightness,
    private val onBrightnessChanged: (Int) -> Unit,
) {

    private var clickHeld = false

    /** Whether this press has already been spent adjusting brightness. */
    private var clickSpent = false

    /** True if [event] was one of ours and has been dealt with. */
    fun dispatch(event: KeyEvent): Boolean {
        val key = LightKeys.of(event) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN

        when (key) {
            LightKey.WheelClick -> {
                if (down) {
                    if (event.repeatCount == 0) {
                        clickHeld = true
                        clickSpent = false
                    }
                } else {
                    clickHeld = false
                    // A press that moved the wheel was a brightness gesture; firing the
                    // torch on the way out of it would be a nasty surprise in the dark.
                    if (!clickSpent && CLICK_TOGGLES_TORCH) Torch.toggle(activity)
                }
            }

            LightKey.WheelUp, LightKey.WheelDown -> {
                // One notch is a complete DOWN+UP pair, so act on DOWN and swallow the UP.
                if (!down) return true
                val notches = if (key == LightKey.WheelUp) 1 else -1
                if (clickHeld) {
                    clickSpent = true
                    onBrightnessChanged(brightness.step(notches))
                } else {
                    wheel.send(notches)
                }
            }

            LightKey.Camera -> if (down && event.repeatCount == 0) openCamera()

            // The camera button's first stage. It arrives paired with Camera and the order
            // varies between presses, so it is swallowed and ignored rather than acted on.
            LightKey.Focus -> Unit
        }
        return true
    }

    /**
     * The implicit intent is what the home button's camera key resolves to as well —
     * `com.android.camera2/com.android.camera.CameraActivity` on this build. The explicit
     * fallback covers a future LightOS that stops publishing the intent filter.
     */
    private fun openCamera() {
        val attempts = listOf(
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(Intent.ACTION_MAIN)
                .setClassName("com.android.camera2", "com.android.camera.CameraActivity"),
        )
        for (intent in attempts) {
            val ok = runCatching {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
    }

    private companion object {
        /**
         * Whether a bare wheel click drives the torch from in here.
         *
         * The click is delivered to the focused app like every other key, so on the home
         * screen it is LightOS that lights the flashlight — inside a sideloaded app nothing
         * does. Owning it makes the behaviour the same everywhere. Set this false if a
         * LightOS update starts handling the click above the app and the two fight.
         */
        const val CLICK_TOGGLES_TORCH = true
    }
}

/**
 * The flashlight. `setTorchMode` needs no permission and no open camera session, which is
 * the whole reason this is three lines rather than a CameraX dependency.
 */
private object Torch {

    private var on = false

    fun toggle(context: Context) {
        val manager = context.getSystemService(CameraManager::class.java) ?: return
        runCatching {
            val id = manager.cameraIdList.firstOrNull { candidate ->
                manager.getCameraCharacteristics(candidate)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            on = !on
            manager.setTorchMode(id, on)
        }.onFailure {
            // Another app holding the camera, or the torch already changed under us.
            on = false
        }
    }
}
