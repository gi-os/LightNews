package com.gios.lightnews.util

import android.util.Base64
import com.gios.lightnews.gmail.InlineImage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

enum class RenderMode { DARK, PAPER }

/**
 * Newsletter HTML, rewritten for a 1080x1240 monochrome panel.
 *
 * Nearly every newsletter is a 600px fixed-width table built for a desktop client in
 * 2011. Two things have to happen: the table scaffolding has to stop being a table so
 * the text can reflow, and the colour has to be flattened to something a matte
 * greyscale LCD can actually show.
 *
 * DARK forces white-on-black to match LightOS. It is the better read, but it hides
 * dark logos drawn on transparent backgrounds. PAPER leaves the email's own styling
 * alone and only fixes the width, which is what brand-heavy issues want. Both modes
 * are one keypress apart in the reader for exactly that reason.
 */
object HtmlRewriter {

    /**
     * Substitute cid: references with data URIs, changing nothing else.
     *
     * Run once when the message is cached, not at render time: the bytes live in a
     * MIME part that only an authenticated request can fetch, and a WebView cannot
     * attach an OAuth header. After this pass the document is self-contained.
     */
    fun inlineCids(rawHtml: String, images: Map<String, InlineImage>): String {
        if (images.isEmpty()) return rawHtml
        val doc = Jsoup.parse(rawHtml)
        doc.outputSettings().prettyPrint(false)
        resolveInlineImages(doc, images)
        return doc.outerHtml()
    }

    fun rewrite(rawHtml: String, mode: RenderMode, loadImages: Boolean): String {
        val doc = Jsoup.parse(rawHtml)
        doc.outputSettings().prettyPrint(false)

        // base[href] has to go: it would re-anchor every relative URL and every
        // in-document anchor, and the reader decides internal-vs-external by comparing
        // against its own base.
        doc.select("script, noscript, link[rel=stylesheet], meta[http-equiv=refresh], base").remove()
        stripTrackingPixels(doc)
        unpinWidths(doc)
        // Anything still pointing at a MIME part missed the inlining pass and can never
        // load, so it goes rather than rendering as a broken-image glyph.
        doc.select("img[src^=cid:]").remove()

        if (!loadImages) {
            doc.select("img, picture, source, video, iframe").remove()
        } else {
            // Remote images are fine but must never be load-blocking-wide.
            doc.select("img").forEach { it.attr("loading", "lazy") }
        }

        if (mode == RenderMode.DARK) doc.select("[style]").forEach { stripColourFromStyle(it) }

        head(doc).prependChild(
            Element("meta")
                .attr("name", "viewport")
                .attr("content", "width=device-width, initial-scale=1, maximum-scale=3"),
        )
        head(doc).appendChild(Element("style").appendText(if (mode == RenderMode.DARK) DARK_CSS else PAPER_CSS))
        // Links open in the system browser; see the WebViewClient in HtmlView.
        return doc.outerHtml()
    }

    /** Plain text, for the case where LightOS turns out to ship no WebView provider. */
    fun toReadableText(rawHtml: String): String {
        val doc = Jsoup.parse(rawHtml)
        doc.select("script, style, head").remove()
        doc.select("br").after("\n")
        doc.select("p, div, tr, li, h1, h2, h3, h4, blockquote, table").after("\n\n")
        doc.select("li").prepend("• ")
        // Keep the link target inline — there is no hover on a phone with no cursor.
        doc.select("a[href]").forEach { a ->
            // No base URI is set on the parse, so abs:href can come back empty.
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val label = a.text().trim()
            if (href.isNotBlank() && label.isNotBlank() && !href.startsWith("mailto:")) {
                a.appendText(" <$href>")
            }
        }
        // wholeText keeps the newlines injected above, which is the entire trick.
        return doc.body().wholeText()
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .lines().joinToString("\n") { it.trim() }
            .trim()
    }

    private fun head(doc: Document): Element = doc.head()

    /**
     * 1x1 images are open-rate beacons. Dropping them is a small privacy win and stops
     * a column of broken-image glyphs when the network is slow.
     */
    private fun stripTrackingPixels(doc: Document) {
        doc.select("img").forEach { img ->
            val w = img.attr("width").toIntOrNull()
            val h = img.attr("height").toIntOrNull()
            val style = img.attr("style")
            // Anchored on the property, because an unanchored version happily reads the
            // "height: 1" inside "line-height: 1.5" and deletes a perfectly good image.
            val tinyStyle = TINY_STYLE.containsMatchIn(style)
            if ((w != null && w <= 2) || (h != null && h <= 2) || tinyStyle) img.remove()
        }
    }

    /** Fixed pixel widths are what force the horizontal scroll; drop every one of them. */
    private fun unpinWidths(doc: Document) {
        doc.select("[width]").forEach { el ->
            if (!el.normalName().equals("img", true)) el.removeAttr("width")
        }
        doc.select("[height]").forEach { el ->
            if (!el.normalName().equals("img", true)) el.removeAttr("height")
        }
        doc.select("table, td, th, tr").forEach { it.removeAttr("bgcolor").removeAttr("align") }
    }

    /**
     * cid: sources point at MIME parts, which the WebView cannot fetch — the request
     * would need an OAuth header. Inline them as data URIs instead; anything not
     * supplied (too large, or images switched off) is dropped rather than left broken.
     */
    private fun resolveInlineImages(doc: Document, inlineImages: Map<String, InlineImage>) {
        doc.select("img[src^=cid:]").forEach { img ->
            val cid = img.attr("src").removePrefix("cid:").trim('<', '>', ' ')
            val image = inlineImages[cid]
            if (image != null) {
                val b64 = Base64.encodeToString(image.bytes, Base64.NO_WRAP)
                img.attr("src", "data:${image.mimeType};base64,$b64")
            }
        }
    }

    /**
     * Inline colour beats a stylesheet even with !important, because the declarations
     * are equally weighted and the attribute wins on specificity. So in DARK mode the
     * colours come out of the attribute instead.
     */
    private fun stripColourFromStyle(el: Element) {
        val cleaned = el.attr("style")
            .split(';')
            .filterNot { decl ->
                val prop = decl.substringBefore(':').trim().lowercase()
                prop == "color" || prop.startsWith("background") || prop.startsWith("border") ||
                    prop == "font-family" || prop == "width" || prop == "min-width" ||
                    prop == "max-width" || prop == "box-shadow"
            }
            .joinToString(";")
        if (cleaned.isBlank()) el.removeAttr("style") else el.attr("style", cleaned)
    }

    /**
     * A beacon's dimensions, in a style attribute.
     *
     * Anchored on the property, because unanchored it reads the "height: 1" inside
     * "line-height: 1.5" and deletes a perfectly good image. Case-insensitive, because
     * Outlook and most mail-merge tooling emit uppercase properties. Not a raw string:
     * a raw string cannot escape the anchoring dollar.
     */
    private val TINY_STYLE = Regex(
        "(?:^|[;\\s])(?:max-|min-)?(?:width|height)\\s*:\\s*[0-2](?:px)?\\s*(?:;|!|\$)",
        RegexOption.IGNORE_CASE,
    )

    private val DARK_CSS = """
        :root { color-scheme: dark; }
        html, body { background: #000 !important; }
        body {
          margin: 0; padding: 14px 18px 72px;
          font-family: Akkurat, -apple-system, sans-serif; font-size: 17px; line-height: 1.55;
          -webkit-text-size-adjust: 100%; overflow-wrap: break-word;
        }
        * {
          background-color: transparent !important; background-image: none !important;
          color: #fff !important; border-color: #2b2b2b !important;
          box-shadow: none !important; text-shadow: none !important;
          font-family: inherit !important; max-width: 100% !important;
          letter-spacing: normal !important;
        }
        /* Unwrap the layout tables so the copy can reflow to the panel width. */
        table, tbody, thead, tfoot, tr, td, th {
          display: block !important; width: auto !important; height: auto !important;
          padding-left: 0 !important; padding-right: 0 !important;
        }
        img, video, iframe { max-width: 100% !important; height: auto !important; filter: grayscale(1) contrast(1.05); }
        a { color: #fff !important; text-decoration: underline; }
        h1, h2, h3, h4 { font-weight: 500; line-height: 1.25; margin: 1.1em 0 0.4em; }
        h1 { font-size: 23px; } h2 { font-size: 20px; } h3 { font-size: 18px; }
        p { margin: 0 0 1em; }
        hr { border: 0; border-top: 1px solid #2b2b2b; margin: 1.4em 0; }
        blockquote { margin: 0 0 1em 2px; padding-left: 12px; border-left: 2px solid #3a3a3a; }
        pre, code { white-space: pre-wrap; font-family: monospace !important; font-size: 15px; }
        ul, ol { padding-left: 22px; }
    """.trimIndent()

    private val PAPER_CSS = """
        html { -webkit-text-size-adjust: 100%; }
        /* A newsletter that sets no background assumes white. Say so, or the email's
           own dark text lands on the app's black WebView and vanishes. */
        html, body { background: #fff !important; }
        body { margin: 0 !important; padding: 0 !important; color: #111; }
        /* Only the width is really wrong; the palette was designed for white paper,
           which is what a matte monochrome panel most resembles. Small type is floored
           by WebView's minimumFontSize rather than a blanket override here, so the
           newsletter's own headline hierarchy survives. */
        * { max-width: 100% !important; }
        table, tbody, tr, td, th { width: auto !important; }
        img, video, iframe { max-width: 100% !important; height: auto !important; }
    """.trimIndent()
}
