package com.gios.lightnews.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.View
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
            WebView(context).apply {
                settings.apply {
                    // Newsletters are documents. No newsletter needs a script, and off is
                    // both faster and one fewer way for an email to do something clever.
                    javaScriptEnabled = false
                    loadsImagesAutomatically = loadImages
                    blockNetworkImage = !loadImages
                    // CSS px on a 1080-wide 3.92" panel is small; floor it.
                    minimumFontSize = 14
                    minimumLogicalFontSize = 14
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
