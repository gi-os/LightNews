package com.gios.lightnews.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.gios.lightnews.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.browser.AnyBrowserMatcher
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The refresh token was revoked; only a fresh consent will fix it. */
class ReauthRequired(message: String) : IOException(message)

/**
 * OAuth against Google without Play Services.
 *
 * Play Services only ever supplied the account-picker shortcut. The plain
 * authorization-code flow from RFC 8252 needs a browser and nothing else, which the
 * Light Phone III has. Two LightOS-specific concessions:
 *
 *  - AnyBrowserMatcher, because the stock browser does not advertise Custom Tabs and
 *    AppAuth's default matcher would otherwise refuse to start.
 *  - The redirect is a custom scheme matching the package name (see
 *    app/build.gradle.kts), so the browser hands control straight back to this package
 *    instead of routing through a loopback socket, which Google will not accept for an
 *    Android client anyway.
 */
class AuthManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("lightnews_auth", Context.MODE_PRIVATE)
    private val refreshLock = Mutex()

    val service: AuthorizationService = AuthorizationService(
        context.applicationContext,
        AppAuthConfiguration.Builder()
            .setBrowserMatcher(AnyBrowserMatcher.INSTANCE)
            .build(),
    )

    // Written from IO threads under refreshLock, read from the main thread by the
    // settings screen, so the reader needs a happens-before edge.
    @Volatile
    private var state: AuthState = prefs.getString(KEY_STATE, null)
        ?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }
        ?: AuthState()

    /**
     * The OAuth client id, from settings if one was entered there, otherwise whatever the
     * build was compiled with.
     *
     * Runtime entry is possible at all because the redirect scheme is the package name,
     * not the reversed client id — so the manifest doesn't depend on which id is in use,
     * and a released APK with no id compiled in is still a working app.
     */
    val clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.GMAIL_CLIENT_ID

    val isConfigured: Boolean get() = clientId.isNotEmpty()

    /**
     * Store a client id typed or scanned by the user. Returns false if it doesn't look
     * like one, rather than letting the browser fail with `invalid_client` later.
     *
     * Changing it drops any existing credentials: a refresh token belongs to the client
     * that issued it, and AppAuth would keep presenting the old one.
     */
    suspend fun setClientId(raw: String): Boolean {
        // Tolerate the whole local.properties line, and the URL-ish forms a QR might hold.
        val cleaned = raw.trim()
            .removePrefix("lightnews:")
            .removePrefix("gmailClientId=")
            .trim()
            .trim('"')
        if (!cleaned.endsWith(CLIENT_ID_SUFFIX) || cleaned.length <= CLIENT_ID_SUFFIX.length) {
            return false
        }
        if (cleaned == clientId) return true
        refreshLock.withLock {
            state = AuthState()
            prefs.edit().putString(KEY_CLIENT_ID, cleaned).remove(KEY_STATE).apply()
        }
        return true
    }

    val isSignedIn: Boolean get() = state.isAuthorized

    /**
     * Shown in settings so it is obvious which mailbox is being read.
     *
     * Read straight out of the JWT payload rather than through AuthState.parsedIdToken,
     * whose IdToken type is not part of AppAuth's public surface. The token has already
     * been validated by AppAuth at this point; this is only reading a claim off it.
     */
    val account: String?
        get() = state.idToken?.let { jwt ->
            runCatching {
                val payload = jwt.split('.').getOrNull(1) ?: return@runCatching null
                val json = String(
                    Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                    Charsets.UTF_8,
                )
                JSONObject(json).optString("email").ifBlank { null }
            }.getOrNull()
        }

    fun authorizationIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            CONFIG,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(BuildConfig.OAUTH_REDIRECT),
        )
            .setScopes("openid", "email", SCOPE_GMAIL)
            // Consent every time: Google issues a refresh token on the first grant only,
            // so a re-authorisation after the seven-day expiry would otherwise come back
            // with an access token and no way to renew it.
            .setPrompt(AuthorizationRequest.Prompt.CONSENT)
            // access_type is Google's own parameter, hence not one AppAuth models. prompt
            // is standard OIDC, and passing that one here would be rejected as built-in.
            .setAdditionalParameters(mapOf("access_type" to "offline"))
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    /** Feed the browser's result back in; returns false if the user backed out. */
    suspend fun onAuthorizationResult(data: Intent?): Boolean {
        val intent = data ?: return false
        val response = AuthorizationResponse.fromIntent(intent)
        val error = AuthorizationException.fromIntent(intent)
        // AuthState.update asserts that exactly one of the two is present, so a result
        // Intent carrying neither extra would throw rather than read as a cancellation.
        if (response == null && error == null) return false
        refreshLock.withLock {
            state.update(response, error)
            persist()
        }
        if (response == null) return false

        // The exchange itself runs outside the lock — it is a network call — but every
        // mutation of the state goes back through it. Without that, a background sync
        // refreshing at the same moment can persist its older state over a fresh
        // sign-in, and the app comes up signed out on the next launch.
        val tokens = runCatching {
            suspendCancellableCoroutine { cont ->
                service.performTokenRequest(response.createTokenExchangeRequest()) { result, ex ->
                    if (result != null) {
                        cont.resume(result)
                    } else {
                        cont.resumeWithException(ex ?: IOException("token exchange failed"))
                    }
                }
            }
        }.getOrNull() ?: return false

        refreshLock.withLock {
            state.update(tokens, null)
            persist()
        }
        return true
    }

    /**
     * A valid access token, refreshing if needed.
     *
     * invalid_grant here is almost always the seven-day expiry that Google applies to
     * consent screens still in Testing. Nothing is recoverable at that point, so drop
     * the state and make the UI ask for consent again rather than retrying forever.
     */
    suspend fun accessToken(): String = refreshLock.withLock {
        if (!state.isAuthorized) throw ReauthRequired("not signed in")
        suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(service) { token, _, ex ->
                persist()
                when {
                    token != null -> cont.resume(token)
                    ex?.error == "invalid_grant" -> {
                        // Clear inline rather than calling signOut(): the lock is already
                        // held here, and Mutex is not reentrant.
                        state = AuthState()
                        prefs.edit().remove(KEY_STATE).apply()
                        cont.resumeWithException(ReauthRequired("authorisation expired"))
                    }
                    else -> cont.resumeWithException(ex ?: IOException("no access token"))
                }
            }
        }
    }

    /**
     * Force the next accessToken() to hit the token endpoint. Needed when the server
     * rejects a token the client still believes is valid.
     */
    suspend fun invalidateAccessToken() = refreshLock.withLock {
        state.needsTokenRefresh = true
    }

    /** Takes the same lock as accessToken(), so a refresh in flight can't resurrect it. */
    suspend fun signOut() = refreshLock.withLock {
        state = AuthState()
        prefs.edit().remove(KEY_STATE).apply()
    }

    private fun persist() {
        prefs.edit().putString(KEY_STATE, state.jsonSerializeString()).apply()
    }

    companion object {
        private const val KEY_STATE = "auth_state"
        private const val KEY_CLIENT_ID = "client_id"
        private const val CLIENT_ID_SUFFIX = ".apps.googleusercontent.com"
        const val SCOPE_GMAIL = "https://www.googleapis.com/auth/gmail.modify"

        val CONFIG = AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
            Uri.parse("https://oauth2.googleapis.com/token"),
        )
    }
}
