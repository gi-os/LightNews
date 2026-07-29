package com.gios.lightnews.data

import android.content.Context
import com.gios.lightnews.auth.AuthManager
import com.gios.lightnews.auth.ReauthRequired
import com.gios.lightnews.gmail.GmailClient
import com.gios.lightnews.gmail.GmailHttpError
import com.gios.lightnews.gmail.InlineImage
import com.gios.lightnews.gmail.RawMessage
import com.gios.lightnews.util.ArticleMeta
import com.gios.lightnews.util.HtmlRewriter
import com.gios.lightnews.util.RenderMode
import com.gios.lightnews.util.formatWhen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface SyncResult {
    /** [more] is true when the label held more new messages than one run will fetch. */
    data class Ok(val added: Int, val more: Boolean) : SyncResult

    /** The label name in settings does not exist in this mailbox. */
    data object NoLabel : SyncResult
    data object NeedsAuth : SyncResult
    data class Failed(val reason: String) : SyncResult
}

class NewsRepository private constructor(context: Context) {

    private val app = context.applicationContext
    private val dao = NewsDatabase.get(app).newsDao()
    private val prefs = app.getSharedPreferences("lightnews", Context.MODE_PRIVATE)
    private val bodyDir = File(app.filesDir, "bodies").apply { mkdirs() }

    /** Guards everything that writes the cache: sync, and signing out from under it. */
    private val syncLock = Mutex()

    @Volatile
    private var sweptOrphans = false

    /**
     * Fetches that keep failing — a message too large to buffer, most likely. Without
     * this they are retried on every sync forever, and twenty of them would keep the
     * "there is a backlog" flag permanently true. In memory on purpose: a restart is a
     * reasonable moment to try again.
     */
    private val failedFetches = mutableMapOf<String, Int>()

    val auth = AuthManager(app)
    private val gmail = GmailClient(auth)

    fun observeAll(): Flow<List<NewsletterEntity>> = dao.observeAll()
    fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()

    /* ------------------------------------------------------------------ settings */

    var labelName: String
        get() = prefs.getString(KEY_LABEL, DEFAULT_LABEL).orEmpty().ifBlank { DEFAULT_LABEL }
        set(value) {
            prefs.edit()
                .putString(KEY_LABEL, value.trim())
                // The cached id belongs to the old name.
                .remove(KEY_LABEL_ID)
                .apply()
        }

    var renderMode: RenderMode
        get() = if (prefs.getString(KEY_MODE, "dark") == "paper") RenderMode.PAPER else RenderMode.DARK
        set(value) {
            prefs.edit().putString(KEY_MODE, if (value == RenderMode.PAPER) "paper" else "dark").apply()
        }

    var loadImages: Boolean
        get() = prefs.getBoolean(KEY_IMAGES, true)
        private set(value) = prefs.edit().putBoolean(KEY_IMAGES, value).apply()

    /**
     * Strip sponsor blocks at render time — not at fetch time, so switching it off shows
     * the ads again without refetching the mailbox.
     */
    var blockAds: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_ADS, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_ADS, value).apply()

    var lastSyncMs: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        private set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    /**
     * Turning images on has to throw the cached bodies away.
     *
     * Inline images are resolved once, when a message is cached, because a WebView
     * cannot attach an OAuth header to fetch a MIME part later. A body cached with
     * images off therefore has no inline art in it and never will, so the bodies have to
     * go — the next sync refetches any row whose body is missing. Staging files are left
     * alone; deleting one under an in-flight write only wastes a fetch.
     */
    suspend fun setLoadImages(enabled: Boolean) {
        if (enabled == loadImages) return
        loadImages = enabled
        if (!enabled) return
        withContext(Dispatchers.IO) {
            bodyDir.listFiles()
                ?.filter { it.name.endsWith(".html") || it.name.endsWith(".txt") }
                ?.forEach { it.delete() }
        }
    }

    /* ---------------------------------------------------------------------- read */

    /**
     * Local state flips first so the swipe stays instant. If Gmail is unreachable the
     * row keeps pendingRead and the next sync pushes it, which is the only way an
     * offline read on a phone can be honest.
     *
     * Deliberately outside syncLock: blocking a read on an in-flight sync would stall
     * the reader for as long as the sync takes. What makes that safe is that nothing in
     * a sync overwrites a pendingRead row — not the reconciliation queries, and not
     * store().
     */
    suspend fun markRead(id: String) {
        val row = dao.get(id) ?: return
        if (!row.unread && !row.pendingRead) return
        dao.setRead(id, unread = false, pending = true)
        if (id in pushRead(listOf(id))) dao.setRead(id, unread = false, pending = false)
    }

    /** One batchModify request rather than a hundred serial ones. */
    suspend fun markAllRead() {
        val unread = dao.all().filter { it.unread }
        if (unread.isEmpty()) return
        unread.forEach { dao.setRead(it.id, unread = false, pending = true) }
        settle(pushRead(unread.map { it.id }))
    }

    /**
     * Clear UNREAD on Gmail; returns the ids it accepted.
     *
     * batchModify is all-or-nothing, so one message deleted in Gmail fails the whole
     * call — and a batch that always fails means pendingRead never clears for anything,
     * which in turn stops the cache from ever being trimmed. So a failed batch is
     * retried one id at a time, and a 400/404 counts as settled: the message is gone,
     * there is nothing left to mark.
     */
    private suspend fun pushRead(ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        if (tryNet { gmail.markRead(ids); true } == true) return ids.toSet()
        val settled = mutableSetOf<String>()
        for (id in ids) {
            val accepted = try {
                gmail.markRead(listOf(id))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The message is gone from Gmail: the read has nowhere left to go, so
                // treat it as settled or the flag never clears and the cache never trims.
                e is GmailHttpError && (e.code == 400 || e.code == 404)
            }
            if (accepted) settled += id
        }
        return settled
    }

    /**
     * A network call whose failure is not fatal. Cancellation is rethrown rather than
     * counted as a failure: a plain runCatching swallows it, and then a sync cancelled
     * on the way out of the app records a "failure" for every message it had left to
     * fetch — which is enough to blacklist them for the life of the process.
     */
    private suspend fun <T> tryNet(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun settle(ids: Set<String>) =
        ids.forEach { dao.setRead(it, unread = false, pending = false) }

    /* ---------------------------------------------------------------------- body */

    /**
     * Rendered form of the body, ready for the WebView or the text fallback.
     *
     * Nothing here may throw. The cache file can be deleted by a sync running in the
     * same process, jsoup can throw on a sufficiently broken document, and Room can
     * throw on a disk error — and this is called from a LaunchedEffect, where an
     * exception takes the process down instead of showing an error.
     */
    suspend fun rendered(id: String, webViewAvailable: Boolean): Rendered =
        withContext(Dispatchers.IO) {
            runCatching {
                val row = dao.get(id)
                val meta = row?.let {
                    ArticleMeta(it.subject, it.fromEmail, formatWhen(it.dateMs))
                }
                val html = bodyFile(id).takeIf { it.length() > 0L }?.readText()
                if (html == null) {
                    val text = textFile(id).takeIf { it.length() > 0L }?.readText()
                    return@runCatching if (text != null) {
                        Rendered.Text(headed(meta, text))
                    } else {
                        snippet(id)
                    }
                }
                if (webViewAvailable) {
                    Rendered.Html(
                        HtmlRewriter.rewrite(html, renderMode, loadImages, blockAds, meta),
                    )
                } else {
                    Rendered.Text(HtmlRewriter.toReadableText(html, meta, blockAds))
                }
            }.getOrElse { snippet(id) }
        }

    private fun headed(meta: ArticleMeta?, body: String): String =
        if (meta == null) body else "${meta.subject}\n${meta.from} · ${meta.date}\n\n$body"

    private suspend fun snippet(id: String): Rendered.Text = Rendered.Text(
        runCatching {
            val row = dao.get(id) ?: return@runCatching ""
            headed(ArticleMeta(row.subject, row.fromEmail, formatWhen(row.dateMs)), row.snippet)
        }.getOrNull().orEmpty(),
    )

    /* ---------------------------------------------------------------------- sync */

    suspend fun sync(): SyncResult = syncLock.withLock {
        if (!auth.isConfigured || !auth.isSignedIn) return SyncResult.NeedsAuth
        try {
            val justSettled = pushPendingReads()

            var labelId = labelId() ?: return SyncResult.NoLabel
            val page = try {
                gmail.listIds(labelId, unreadOnly = false, max = WINDOW)
            } catch (e: GmailHttpError) {
                // A cached label id goes stale if the label is deleted and remade, or if
                // a different account signs in. Gmail answers 400/404 — re-resolve once,
                // rather than failing every sync from here to the end of time.
                if (e.code != 400 && e.code != 404) throw e
                prefs.edit().remove(KEY_LABEL_ID).apply()
                labelId = labelId() ?: return SyncResult.NoLabel
                gmail.listIds(labelId, unreadOnly = false, max = WINDOW)
            }

            val observed = page.ids
            val inWindow = observed.toSet()
            val known = dao.allIds().toSet()
            sweepOrphans(known)

            // A row whose body file went missing counts as missing too. Without that,
            // the `known` check would skip it forever and the reader would be stuck
            // showing a one-line snippet for that issue.
            val missing = observed.filter {
                (it !in known || !hasBody(it)) && (failedFetches[it] ?: 0) < MAX_FETCH_ATTEMPTS
            }
            var added = 0
            for (id in missing.take(FETCH_PER_SYNC)) {
                val message = tryNet { gmail.fetch(id) }
                if (message == null) {
                    failedFetches[id] = (failedFetches[id] ?: 0) + 1
                    continue
                }
                failedFetches.remove(id)
                if (store(message)) added++
            }

            // Read-state reconciliation is a second, cheap list call — but it is only
            // valid for the ids this run actually saw. Applied to the whole table it
            // would mark everything past the page boundary read, permanently.
            val unreadPage = gmail.listIds(labelId, unreadOnly = true, max = WINDOW)
            val unread = unreadPage.ids.toSet()
            // Minus what this run just pushed: messages.list is index-backed and can
            // still report an id as UNREAD seconds after Gmail accepted the change, and
            // those rows no longer carry pendingRead to protect them.
            markUnreadLocally(((unread intersect inWindow) - justSettled).toList())
            // And only flip rows to read if that list was exhaustive. A truncated page
            // says nothing about the messages below its last id, so reading silence as
            // "already read" would quietly hide issues that are genuinely unread.
            if (unreadPage.complete) markReadLocally((inWindow - unread).toList())

            // Prune only when the whole label came back in one page; otherwise messages
            // past the page boundary look deleted and vanish on every sync.
            if (page.complete) {
                val stale = known - inWindow
                if (stale.isNotEmpty()) {
                    deleteRows(stale.toList())
                    stale.forEach { discardBody(it) }
                }
            }
            trimCache()
            lastSyncMs = System.currentTimeMillis()
            SyncResult.Ok(added, more = missing.size > FETCH_PER_SYNC)
        } catch (e: ReauthRequired) {
            SyncResult.NeedsAuth
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Signing out takes the mailbox with it: rows, bodies, cached label id, timestamps.
     *
     * Under syncLock, or a sync already past its network calls would write rows and
     * files back in behind the wipe and the next account would inherit them.
     */
    suspend fun signOut() = syncLock.withLock {
        auth.signOut()
        dao.deleteAll()
        withContext(Dispatchers.IO) { bodyDir.listFiles()?.forEach { it.delete() } }
        prefs.edit().remove(KEY_LABEL_ID).remove(KEY_LAST_SYNC).apply()
        failedFetches.clear()
        sweptOrphans = false
    }

    /** Returns the ids Gmail accepted, so the caller can distrust its own unread list. */
    private suspend fun pushPendingReads(): Set<String> {
        val pending = dao.pendingReads()
        if (pending.isEmpty()) return emptySet()
        val settled = pushRead(pending.map { it.id })
        settle(settled)
        return settled
    }

    /** Cached because resolving the name costs a round trip on every sync otherwise. */
    private suspend fun labelId(): String? {
        prefs.getString(KEY_LABEL_ID, null)?.let { return it }
        val resolved = gmail.findLabelId(labelName) ?: return null
        prefs.edit().putString(KEY_LABEL_ID, resolved).apply()
        return resolved
    }

    /**
     * Body into place first, row second.
     *
     * A process killed between the two then leaves an orphaned file, which sweepOrphans
     * reclaims and the next sync refetches. The other order leaves a row claiming HTML
     * that is not there, and nothing repairs that. The insert goes through
     * upsertKeepingRead, because a refetch must not resurrect a read the user has already
     * made but Gmail has not accepted yet.
     */
    private suspend fun store(message: RawMessage): Boolean = withContext(Dispatchers.IO) {
        val html = message.html
        val staged = File(bodyDir, "${message.id}.staging")
        val target = if (html != null) bodyFile(message.id) else textFile(message.id)
        if (html != null) {
            val inline = if (loadImages) fetchInline(message) else emptyMap()
            staged.writeText(HtmlRewriter.inlineCids(html, inline))
        } else {
            staged.writeText(message.text.orEmpty().ifBlank { message.snippet })
        }
        // Clears both forms, so a message that turns text-only doesn't leave a stale
        // .html behind — which rendered() would go on preferring forever.
        discardBody(message.id)
        if (!staged.renameTo(target)) {
            staged.delete()
            return@withContext false
        }
        dao.upsertKeepingRead(
            NewsletterEntity(
                id = message.id,
                threadId = message.threadId,
                fromName = message.fromName,
                fromEmail = message.fromEmail,
                subject = message.subject,
                snippet = message.snippet,
                dateMs = message.dateMs,
                unread = message.unread,
                hasHtml = html != null,
            ),
        )
        true
    }

    /** Inline images, smallest first, until the budget runs out. Logos fit; hero art doesn't. */
    private suspend fun fetchInline(message: RawMessage): Map<String, InlineImage> {
        var budget = INLINE_BUDGET_BYTES
        return buildMap {
            for (part in message.inlineParts.sortedBy { it.size }) {
                if (part.size > budget) break
                val bytes = tryNet { gmail.attachment(message.id, part.attachmentId) } ?: continue
                budget -= bytes.size
                put(part.contentId, InlineImage(part.mimeType, bytes))
            }
        }
    }

    private fun bodyFile(id: String) = File(bodyDir, "$id.html")
    private fun textFile(id: String) = File(bodyDir, "$id.txt")

    /** Zero length counts as absent: a torn write would otherwise never be refetched. */
    private fun hasBody(id: String) =
        bodyFile(id).length() > 0L || textFile(id).length() > 0L

    private fun discardBody(id: String) {
        runCatching { bodyFile(id).delete() }
        runCatching { textFile(id).delete() }
    }

    /**
     * Bodies with no row: left behind by a destructive Room migration, or staged by a
     * process that died mid-write. Nothing else ever reclaims them.
     *
     * Safe against live files only because store() is the sole writer of this directory
     * and only ever runs under syncLock, which this holds too — don't move either.
     */
    private suspend fun sweepOrphans(known: Set<String>) {
        if (sweptOrphans) return
        withContext(Dispatchers.IO) {
            bodyDir.listFiles()?.forEach { file ->
                val id = file.name.substringBeforeLast('.')
                if (file.name.endsWith(".staging") || id !in known) file.delete()
            }
        }
        sweptOrphans = true
    }

    /** Keep the newest KEEP issues; a year of dailies would otherwise fill the phone. */
    private suspend fun trimCache() {
        val rows = dao.all()
        if (rows.size <= KEEP) return
        // An unpushed read is normally exempt, because dropping the row loses the read
        // silently. Past HARD_KEEP it goes anyway: a push that fails permanently would
        // otherwise let the cache grow without limit.
        val drop = (rows.drop(KEEP).filterNot { it.pendingRead } + rows.drop(HARD_KEEP))
            .distinctBy { it.id }
        if (drop.isEmpty()) return
        deleteRows(drop.map { it.id })
        drop.forEach { discardBody(it.id) }
    }

    /*
     * SQLite caps the number of bound variables, so every IN (:ids) query is chunked.
     * The cap is generous, but "unbounded list into one query" is how a sync starts
     * failing with `too many SQL variables` and never recovers.
     */
    private suspend fun deleteRows(ids: List<String>) =
        ids.chunked(SQL_CHUNK).forEach { dao.delete(it) }

    private suspend fun markReadLocally(ids: List<String>) =
        ids.chunked(SQL_CHUNK).forEach { dao.markReadIn(it) }

    private suspend fun markUnreadLocally(ids: List<String>) =
        ids.chunked(SQL_CHUNK).forEach { dao.markUnreadIn(it) }

    companion object {
        private const val DEFAULT_LABEL = "LightNewsletter"
        private const val KEY_LABEL = "label_name"
        private const val KEY_LABEL_ID = "label_id"
        private const val KEY_MODE = "render_mode"
        private const val KEY_IMAGES = "load_images"
        private const val KEY_BLOCK_ADS = "block_ads"
        private const val KEY_LAST_SYNC = "last_sync"

        /** How many of the label's newest messages one sync looks at. */
        private const val WINDOW = 100

        /**
         * How many issues the cache keeps. Deliberately larger than WINDOW: at exactly
         * WINDOW, a single row outside the window — a message deleted in Gmail, or one
         * holding an unpushed read — costs a cache slot, so the oldest in-window message
         * is fetched and trimmed on every single sync, forever.
         */
        private const val KEEP = 130

        /** The point at which even an unpushed read stops protecting a row. */
        private const val HARD_KEEP = 260

        /**
         * Per-run fetch cap. A first sync of a hundred issues, each with attachments,
         * would otherwise run past WorkManager's ten-minute budget and be killed
         * mid-message. The rest arrive on the next run.
         */
        private const val FETCH_PER_SYNC = 20
        private const val MAX_FETCH_ATTEMPTS = 3
        private const val INLINE_BUDGET_BYTES = 400_000
        private const val SQL_CHUNK = 400

        @Volatile
        private var instance: NewsRepository? = null

        fun get(context: Context): NewsRepository = instance ?: synchronized(this) {
            instance ?: NewsRepository(context).also { instance = it }
        }
    }
}

sealed interface Rendered {
    data class Html(val document: String) : Rendered
    data class Text(val body: String) : Rendered
}
