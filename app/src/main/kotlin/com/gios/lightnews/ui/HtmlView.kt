package com.gios.lightnews.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
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
 * A WebView that claims the gesture as soon as the drag is vertical.
 *
 * Inside a HorizontalPager, Compose sees every pointer event first, and a scroll that
 * starts a few degrees off vertical gets claimed by the pager — so the article jerks, the
 * fling dies halfway, and sometimes you turn the page while trying to read. There is no
 * nested-scroll contract between a View and a Compose scrollable to fall back on:
 * requestDisallowInterceptTouchEvent is the mechanism, and Compose's AndroidView host
 * honours it.
 *
 * The decision waits for the first ACTION_MOVE past the touch slop. Claiming on ACTION_DOWN
 * would be simpler and would break paging entirely, because the pager would never get a
 * horizontal drag at all.
 */
private class ReaderWebView(context: Context) : WebView(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var decided = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                decided = false
            }

            MotionEvent.ACTION_MOVE -> if (!decided) {
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                if (dy > slop && dy > dx) {
                    // Reading. Mine until the finger lifts.
                    decided = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else if (dx > slop && dx >= dy) {
                    // Turning the page. Let the pager have it.
                    decided = true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
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
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ReaderWebView(context).apply {
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
        onRelease = { it.destroy() },
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
