package com.gios.lightnews.gmail

import com.gios.lightnews.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GmailHttpError(val code: Int, message: String) : IOException("HTTP $code: $message")

/**
 * The four Gmail endpoints this app needs. The official client library would pull in
 * GAX, Guava and a service-account stack for the same result.
 */
class GmailClient(private val auth: AuthManager) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Gmail addresses labels by opaque id, so the human name has to be looked up. */
    suspend fun findLabelId(name: String): String? {
        val labels = get(url("labels")).optJSONArray("labels") ?: return null
        var fallback: String? = null
        for (i in 0 until labels.length()) {
            val label = labels.getJSONObject(i)
            val labelName = label.optString("name")
            if (labelName == name) return label.optString("id")
            // Nested labels are "Parent/Child"; match the leaf so moving the label
            // under a parent in Gmail doesn't silently empty the app.
            if (labelName.substringAfterLast('/') == name) fallback = label.optString("id")
        }
        return fallback
    }

    /**
     * Message ids carrying [labelId], newest first.
     *
     * [complete] is false when Gmail paginated, which is the signal not to treat
     * absent ids as removed from the label.
     */
    suspend fun listIds(labelId: String, unreadOnly: Boolean, max: Int): ListPage {
        val builder = url("messages").newBuilder()
            .addQueryParameter("labelIds", labelId)
            .addQueryParameter("maxResults", max.toString())
        if (unreadOnly) builder.addQueryParameter("labelIds", "UNREAD")
        val json = get(builder.build())
        val array = json.optJSONArray("messages")
        val ids = buildList {
            for (i in 0 until (array?.length() ?: 0)) add(array!!.getJSONObject(i).getString("id"))
        }
        return ListPage(ids, complete = json.optString("nextPageToken").isEmpty())
    }

    suspend fun fetch(id: String): RawMessage {
        val json = get(url("messages/$id").newBuilder().addQueryParameter("format", "FULL").build())
        return MimeParser.parse(json)
    }

    /** Inline images referenced as cid:, fetched only when images are switched on. */
    suspend fun attachment(messageId: String, attachmentId: String): ByteArray {
        val json = get(url("messages/$messageId/attachments/$attachmentId"))
        return MimeParser.decodeBody(json.optString("data"))
    }

    /**
     * Clear UNREAD on one or many messages. batchModify is one request for the lot,
     * which matters when catching up on a hundred issues at once — a hundred serial
     * modify calls is how you get rate limited.
     */
    suspend fun markRead(ids: List<String>) {
        if (ids.isEmpty()) return
        if (ids.size == 1) {
            post(
                url("messages/${ids.first()}/modify"),
                JSONObject().put("removeLabelIds", JSONArray().put("UNREAD")).toString(),
            )
            return
        }
        // batchModify returns 204 with an empty body, which call() already tolerates.
        ids.chunked(BATCH_LIMIT).forEach { chunk ->
            post(
                url("messages/batchModify"),
                JSONObject()
                    .put("ids", JSONArray(chunk))
                    .put("removeLabelIds", JSONArray().put("UNREAD"))
                    .toString(),
            )
        }
    }

    private fun url(path: String): HttpUrl = "$BASE/$path".toHttpUrl()

    private suspend fun get(url: HttpUrl): JSONObject =
        call(Request.Builder().url(url).get())

    private suspend fun post(url: HttpUrl, body: String): JSONObject =
        call(Request.Builder().url(url).post(body.toRequestBody(JSON)))

    /**
     * One retry on 401. A token that expired on the clock is refreshed by AppAuth
     * before the request goes out; a token revoked mid-session only surfaces as a
     * rejection, so force a refresh and try once more before giving up.
     */
    private suspend fun call(builder: Request.Builder): JSONObject = withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            val request = builder.header("Authorization", "Bearer ${auth.accessToken()}").build()
            val parsed = http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> if (body.isBlank()) JSONObject() else JSONObject(body)
                    response.code == 401 && attempt == 0 -> null
                    else -> throw GmailHttpError(response.code, body.take(300))
                }
            }
            if (parsed != null) return@withContext parsed
            auth.invalidateAccessToken()
        }
        throw GmailHttpError(401, "still unauthorised after refresh")
    }

    companion object {
        private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

        /** Gmail's documented ceiling for batchModify. */
        private const val BATCH_LIMIT = 1000
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

data class ListPage(val ids: List<String>, val complete: Boolean)
