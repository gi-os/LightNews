package com.gios.lightnews.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.lightnews.data.NewsRepository
import com.gios.lightnews.data.NewsletterEntity
import com.gios.lightnews.data.Rendered
import com.gios.lightnews.data.SyncResult
import com.gios.lightnews.util.RenderMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

data class UiState(
    val syncing: Boolean = false,
    val message: String? = null,
    val needsAuth: Boolean = false,
    val labelMissing: Boolean = false,
)

/**
 * Settings as a value rather than getters on the view model: the reader has to
 * re-render when the rendering mode changes, and that only happens for free if the
 * change arrives as new state.
 */
data class Settings(
    val label: String,
    val mode: RenderMode,
    val images: Boolean,
    val blockAds: Boolean,
    val lastSyncMs: Long,
    val clientIdSet: Boolean,
    /** Enough of the id to recognise, never the whole thing on a shared screen. */
    val clientIdHint: String,
)

class NewsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = NewsRepository.get(app)

    val items: StateFlow<List<NewsletterEntity>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unreadCount: StateFlow<Int> =
        repo.observeUnreadCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _state = MutableStateFlow(UiState(needsAuth = !repo.auth.isSignedIn))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var syncJob: Job? = null

    private val _settings = MutableStateFlow(snapshot())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val isSignedIn: Boolean get() = repo.auth.isSignedIn
    val account: String? get() = repo.auth.account

    init {
        // Opening this app is a deliberate act on a phone like this one, so always look
        // for mail — unless the background worker just did.
        if (repo.auth.isSignedIn && System.currentTimeMillis() - repo.lastSyncMs > 60_000) sync()
    }

    private fun snapshot() = Settings(
        label = repo.labelName,
        mode = repo.renderMode,
        images = repo.loadImages,
        blockAds = repo.blockAds,
        lastSyncMs = repo.lastSyncMs,
        clientIdSet = repo.auth.isConfigured,
        clientIdHint = repo.auth.clientId.substringBefore('-').take(14),
    )

    fun sync() {
        if (_state.value.syncing) return
        syncJob = viewModelScope.launch {
            _state.value = _state.value.copy(syncing = true, message = null)
            // A sync fetches a capped number of messages per pass. While someone is
            // watching the progress bar, keep going rather than stopping at twenty.
            var rounds = 0
            var result: SyncResult
            do {
                result = repo.sync()
                rounds++
            } while (result is SyncResult.Ok && result.more && rounds < MAX_SYNC_ROUNDS)

            _state.value = when (val outcome = result) {
                is SyncResult.Ok -> UiState()
                SyncResult.NeedsAuth -> UiState(needsAuth = true)
                SyncResult.NoLabel -> UiState(labelMissing = true)
                is SyncResult.Failed -> UiState(message = outcome.reason)
            }
            _settings.value = snapshot()
        }
    }

    /** For failures the UI itself discovers, like there being no browser to sign in with. */
    fun reportError(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    fun markRead(id: String) = viewModelScope.launch { repo.markRead(id) }

    fun markAllRead() = viewModelScope.launch { repo.markAllRead() }

    suspend fun rendered(id: String, webViewAvailable: Boolean): Rendered =
        repo.rendered(id, webViewAvailable)

    /* --------------------------------------------------------------------- auth */

    /** The consent URL. MainActivity hands it to whatever will open a web page. */
    fun authorizationUri(): Uri = repo.auth.authorizationUri()

    /** The redirect coming back from that page. */
    fun onRedirect(uri: Uri) = viewModelScope.launch {
        val ok = runCatching { repo.auth.onRedirect(uri) }.getOrDefault(false)
        _settings.value = snapshot()
        _state.value = _state.value.copy(
            needsAuth = !ok,
            message = if (ok) null else "Sign-in didn't complete",
        )
        if (ok) sync()
    }

    /** Also drops the cached mailbox, so the next account doesn't inherit this one's mail. */
    fun signOut() = viewModelScope.launch {
        // Stop the in-flight sync first: signOut takes the same lock, and letting a sync
        // finish afterwards would put the old account's state back.
        syncJob?.cancel()
        // Optimistic, because repo.signOut() queues on the sync lock, which a blocking
        // socket read can hold for the length of the read timeout. The wipe is what takes
        // a moment; the decision is already made.
        _state.value = UiState(needsAuth = true)
        repo.signOut()
        _settings.value = snapshot()
        _state.value = UiState(needsAuth = true)
    }

    /**
     * Everything that arrives by scan or paste, of which there are two kinds: a bare
     * client id, and the JSON credential blob from scripts/authorize.py that carries a
     * refresh token and so skips consent on the phone entirely.
     *
     * Validating here is worth it: the alternative is a round trip that ends on Google's
     * own invalid_client page, which says nothing about what to fix.
     */
    fun applyScanned(raw: String) = viewModelScope.launch {
        val text = raw.trim()
        val accepted = if (text.startsWith("{")) {
            runCatching { repo.auth.setCredentials(JSONObject(text)) }.getOrDefault(false)
        } else {
            repo.auth.setClientId(text)
        }
        _settings.value = snapshot()
        if (!accepted) {
            _state.value = _state.value.copy(message = "That isn't a client ID or a setup code")
            return@launch
        }
        _state.value = UiState(needsAuth = !repo.auth.isSignedIn)
        // A credential blob arrives already signed in, so there is mail to fetch.
        if (repo.auth.isSignedIn) sync()
    }

    /* ----------------------------------------------------------------- settings */

    fun setLabel(name: String) {
        repo.labelName = name
        _settings.value = snapshot()
        _state.value = _state.value.copy(labelMissing = false)
        sync()
    }

    fun toggleRenderMode() {
        repo.renderMode = if (repo.renderMode == RenderMode.DARK) RenderMode.PAPER else RenderMode.DARK
        _settings.value = snapshot()
    }

    /** Render-time only, so this needs no refetch — just a re-render of the open page. */
    fun setBlockAds(enabled: Boolean) {
        repo.blockAds = enabled
        _settings.value = snapshot()
    }

    fun setLoadImages(enabled: Boolean) = viewModelScope.launch {
        // Turning images on invalidates every cached body, so the refetch that follows
        // is not optional. sync() no-ops while one is already running, hence the join:
        // without it, toggling images mid-sync leaves the whole cache empty until the
        // hourly worker gets around to it.
        repo.setLoadImages(enabled)
        _settings.value = snapshot()
        if (enabled) {
            syncJob?.join()
            sync()
        }
    }

    private companion object {
        const val MAX_SYNC_ROUNDS = 5
    }
}
