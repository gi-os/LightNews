package com.gios.lightnews.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.gios.lightnews.hw.WheelScroll
import com.gios.lightnews.util.RenderMode

/**
 * Whether this device can render HTML at all.
 *
 * LightOS is AOSP-derived and does ship a WebView provider today, but a minimal ROM is
 * exactly the kind of build where one goes missing, and instantiating WebView without a
 * provider throws rather than returning null. Probe once and keep a plain-text path
 * behind it, so a LightOS update can degrade the app instead of breaking it.
 */
object WebViewSupport {
    @Volatile
    private var cached: Boolean? = null

    fun isAvailable(context: Context): Boolean = cached ?: synchronized(this) {
        cached ?: runCatching {
            WebView(context).also { it.destroy() }
            true
        }.getOrDefault(false).also { cached = it }
    }

    /** Call once from Application.onCreate, on the main thread. */
    fun prime(context: Context) {
        isAvailable(context)
    }
}

/**
 * A WebView that owns every gesture inside it, and reports page turns itself.
 *
 * The first attempt let Compose arbitrate and only claimed vertical drags once they passed
 * the touch slop. It still stuck: by the time the slop is crossed the pager may already
 * have taken the gesture, and a fling that starts a few degrees off vertical gets handed
 * over mid-flight. There is no nested-scroll contract between a View and a Compose
 * scrollable to negotiate this properly.
 *
 * So there is now exactly one arbiter. requestDisallowInterceptTouchEvent(true) fires on
 * ACTION_DOWN, before anything can be claimed, which means the pager never sees a touch
 * that lands on an article. The WebView scrolls, and on release it decides whether the
 * gesture was a page turn and says so through [onSwipe]. Pages that aren't WebViews — the
 * plain-text fallback — still drag the pager normally, because none of this runs there.
 */
private class ReaderWebView(
    context: Context,
    private val onSwipe: (Int) -> Unit,
) : WebView(context) {

    private val turnThreshold = 76f * context.resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var multiTouch = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                multiTouch = false
                // Claimed immediately, not on the first move: whoever asks second loses.
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            // A pinch is a zoom, never a page turn.
            MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true

            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!multiTouch && kotlin.math.abs(dx) > turnThreshold &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
                ) {
                    // Dragging left means going forwards, the way a page moves under a thumb.
                    onSwipe(if (dx < 0) 1 else -1)
                }
            }

            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(event)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlView(
    document: String,
    mode: RenderMode,
    loadImages: Boolean,
    modifier: Modifier = Modifier,
    onSwipe: (Int) -> Unit = {},
    wheelActive: Boolean = true,
) {
    // Held in a ref so the factory's callback always reaches the current lambda rather
    // than the one captured when the WebView was created.
    val swipe = rememberUpdatedState(onSwipe)

    // The wheel scrolls the document. Chromium has no idea what WHEEL_CW is, and there is
    // no nested-scroll bridge from a View to Compose, so the scroll is applied by hand to
    // the WebView that is actually on screen.
    var webRef by remember { mutableStateOf<WebView?>(null) }
    WheelScroll(webRef, wheelActive)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            ReaderWebView(context) { direction -> swipe.value(direction) }.apply {
                settings.apply {
                    // Newsletters are documents. No newsletter needs a script, and off is
                    // both faster and one fewer way for an email to do something clever.
                    javaScriptEnabled = false
                    loadsImagesAutomatically = loadImages
                    blockNetworkImage = !loadImages
                    // CSS px on a 1080-wide 3.92" panel is small; floor it.
                    minimumFontSize = 14
                    minimumLogicalFontSize = 14
                    // Pinch-zoom stays, but a zoomed page can be panned horizontally, and a
                    // horizontal pan is indistinguishable from turning the page. Text is
                    // already reflowed to the panel width, so the zoom is a rarely-needed
                    // escape hatch rather than the way the app is meant to be read.
                    builtInZoomControls = true
                    displayZoomControls = false
                    useWideViewPort = false
                    loadWithOverviewMode = false
                    setSupportZoom(true)
                }
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setBackgroundColor(if (mode == RenderMode.DARK) AndroidColor.BLACK else AndroidColor.WHITE)
                webViewClient = object : WebViewClient() {
                    /** Every tap leaves the app; nothing is browsed inside the reader. */
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith(BASE_URL)) {
                            // An anchor into the same document: let Chromium scroll to it.
                            if (url.startsWith("$BASE_URL#")) return false
                            // Otherwise it's a root-relative or empty href that resolved
                            // against the synthetic base. Following it would replace the
                            // newsletter with a DNS error page and there is no reload
                            // button in this reader, so swallow it.
                            return true
                        }
                        openExternally(context, request.url)
                        return true
                    }
                }
                tag = document
                loadDataWithBaseURL(BASE_URL, document, "text/html", "UTF-8", null)
            }
        },
        update = { web ->
            webRef = web
            web.settings.loadsImagesAutomatically = loadImages
            web.settings.blockNetworkImage = !loadImages
            web.setBackgroundColor(if (mode == RenderMode.DARK) AndroidColor.BLACK else AndroidColor.WHITE)
            // View.setTag(int, Object) insists on a real resource id, so use the single
            // untyped tag to remember which document is already loaded.
            if (web.tag != document) {
                web.tag = document
                web.loadDataWithBaseURL(BASE_URL, document, "text/html", "UTF-8", null)
            }
        },
        onRelease = {
            webRef = null
            it.destroy()
        },
    )
}

/**
 * A base URL, because in-document anchors cannot resolve against an opaque origin — but
 * a deliberately unresolvable one. Point this at a real host and every root-relative path
 * in every newsletter becomes an outbound request to that host: a 404 at best, a
 * first-party tracking request at worst. `.invalid` is reserved by RFC 2606 and can never
 * resolve, so those requests fail locally instead of leaking.
 *
 * http, not https, and not for laziness: an https base makes every plain-http image in
 * the document mixed content, whose handling WebView documents as varying by release. An
 * http base takes mixed content out of the picture, and nothing is ever fetched from the
 * base itself.
 */
private const val BASE_URL = "http://newsletter.invalid/"

private fun openExternally(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
