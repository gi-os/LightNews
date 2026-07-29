package com.gios.lightnews.ui

import android.app.Application
import android.content.Intent
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
    val lastSyncMs: Long,
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

    val isConfigured: Boolean get() = repo.auth.isConfigured
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
        lastSyncMs = repo.lastSyncMs,
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

    fun authorizationIntent(): Intent = repo.auth.authorizationIntent()

    fun onAuthResult(data: Intent?) = viewModelScope.launch {
        val ok = runCatching { repo.auth.onAuthorizationResult(data) }.getOrDefault(false)
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
