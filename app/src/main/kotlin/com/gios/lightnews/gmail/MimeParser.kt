package com.gios.lightnews.gmail

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.Charset

/** Everything the reader needs out of one Gmail message resource. */
data class RawMessage(
    val id: String,
    val threadId: String,
    val fromName: String,
    val fromEmail: String,
    val subject: String,
    val snippet: String,
    val dateMs: Long,
    val unread: Boolean,
    val html: String?,
    val text: String?,
    val inlineParts: List<InlinePart>,
)

/** An image the newsletter references as cid:, rather than over the network. */
data class InlinePart(val contentId: String, val attachmentId: String, val mimeType: String, val size: Int)

/**
 * A fetched inline image. The mime type travels with the bytes because a data URI needs
 * a real media type, and a wildcard is not one.
 */
data class InlineImage(val mimeType: String, val bytes: ByteArray) {
    // Arrays compare by identity, so the generated equals/hashCode would be wrong.
    override fun equals(other: Any?) =
        other is InlineImage && other.mimeType == mimeType && other.bytes.contentEquals(bytes)

    override fun hashCode() = 31 * mimeType.hashCode() + bytes.contentHashCode()
}

object MimeParser {

    fun parse(json: JSONObject): RawMessage {
        val payload = json.optJSONObject("payload") ?: JSONObject()
        val headers = headerMap(payload)
        val (name, email) = splitFrom(headers["from"].orEmpty())

        val html = StringBuilder()
        val text = StringBuilder()
        val inline = mutableListOf<InlinePart>()
        walk(payload, html, text, inline)

        val labels = json.optJSONArray("labelIds")
        val unread = (0 until (labels?.length() ?: 0)).any { labels!!.getString(it) == "UNREAD" }

        return RawMessage(
            id = json.getString("id"),
            threadId = json.optString("threadId"),
            fromName = name,
            fromEmail = email,
            subject = headers["subject"].orEmpty().ifBlank { "(no subject)" },
            snippet = json.optString("snippet"),
            // internalDate is epoch millis and, unlike the Date header, is never a lie.
            dateMs = json.optString("internalDate").toLongOrNull() ?: System.currentTimeMillis(),
            unread = unread,
            html = html.toString().ifBlank { null },
            text = text.toString().ifBlank { null },
            inlineParts = inline,
        )
    }

    /**
     * Depth-first over the MIME tree, collecting every text/html and text/plain leaf.
     *
     * Concatenating rather than taking the first one is deliberate: a fair number of
     * newsletters split the body across sibling parts inside multipart/related, and
     * picking only the first gets you a header and nothing else.
     */
    private fun walk(
        part: JSONObject,
        html: StringBuilder,
        text: StringBuilder,
        inline: MutableList<InlinePart>,
    ) {
        val mime = part.optString("mimeType")
        val body = part.optJSONObject("body")
        val parts = part.optJSONArray("parts")

        if (parts != null) {
            for (i in 0 until parts.length()) walk(parts.getJSONObject(i), html, text, inline)
        }

        val data = body?.optString("data").orEmpty()
        val attachmentId = body?.optString("attachmentId").orEmpty()
        val charset = charsetOf(headerMap(part)["content-type"])

        when {
            mime == "text/html" && data.isNotEmpty() -> html.append(decodeBody(data).toString(charset))
            mime == "text/plain" && data.isNotEmpty() -> text.append(decodeBody(data).toString(charset))
            mime.startsWith("image/") && attachmentId.isNotEmpty() -> {
                val cid = headerMap(part)["content-id"]?.trim('<', '>', ' ')
                if (!cid.isNullOrEmpty()) {
                    inline += InlinePart(cid, attachmentId, mime, body?.optInt("size") ?: 0)
                }
            }
        }
    }

    fun decodeBody(data: String): ByteArray =
        runCatching { Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrDefault(ByteArray(0))

    private fun headerMap(part: JSONObject): Map<String, String> {
        val headers = part.optJSONArray("headers") ?: return emptyMap()
        return buildMap {
            for (i in 0 until headers.length()) {
                val h = headers.getJSONObject(i)
                put(h.optString("name").lowercase(), h.optString("value"))
            }
        }
    }

    /** An unknown or unsupported charset must not lose the whole email. */
    private fun charsetOf(contentType: String?): Charset {
        val declared = contentType?.substringAfter("charset=", "")?.trim('"', ' ', ';')
        if (declared.isNullOrBlank()) return Charsets.UTF_8
        return runCatching { Charset.forName(declared) }.getOrDefault(Charsets.UTF_8)
    }

    /** "Money Stuff <noreply@bloomberg.net>" -> name and address, either possibly absent. */
    private fun splitFrom(from: String): Pair<String, String> {
        val open = from.lastIndexOf('<')
        val close = from.lastIndexOf('>')
        if (open == -1 || close < open) return from.trim() to from.trim()
        val email = from.substring(open + 1, close).trim()
        val name = from.substring(0, open).trim().trim('"').ifBlank { email.substringBefore('@') }
        return name to email
    }
}
