package com.gios.lightnews.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.gios.lightnews.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** The refresh token was revoked; only a fresh consent will fix it. */
class ReauthRequired(message: String) : IOException(message)

/**
 * OAuth against Google, by hand.
 *
 * This used to be AppAuth, and AppAuth could not sign in on a Light Phone III. Its
 * BrowserSelector enumerates browsers through PackageManager and keeps only those whose
 * intent filter claims CATEGORY_BROWSABLE *and* the bare http scheme with no host — its
 * definition of a "full browser". The LightOS browser doesn't qualify, the candidate list
 * comes back empty, and the library throws before a single request is made. Hence the
 * "No browser available to sign in with" this replaced.
 *
 * A plain ACTION_VIEW intent has neither restriction: implicit-intent launches don't
 * consult package visibility at all, and any activity that handles an https URL will take
 * it, "full browser" or not. So the flow lives here instead — PKCE, ACTION_VIEW, and a
 * custom-scheme redirect back into MainActivity. Eighty lines, two fewer dependencies.
 *
 * The browser question can also be sidestepped entirely: see [setCredentials] and
 * scripts/authorize.py, which is the same scan-a-credential-in trick the other Light
 * Phone apps use for their API keys.
 */
class AuthManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("lightnews_auth", Context.MODE_PRIVATE)
    private val tokenLock = Mutex()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /* ------------------------------------------------------------- configuration */

    /**
     * The OAuth client id: from settings if one was scanned or typed there, otherwise
     * whatever the build was compiled with.
     */
    val clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.GMAIL_CLIENT_ID

    /**
     * Only set when the credentials came from the desktop script, which authorises
     * through a Desktop-type client. Those have a secret — not a confidential one, Google
     * expects it to ship inside installed apps — and the token endpoint wants it back on
     * every refresh. An Android-type client has none, and then this stays null.
     */
    private val clientSecret: String?
        get() = prefs.getString(KEY_CLIENT_SECRET, null)?.takeIf { it.isNotBlank() }

    val isConfigured: Boolean get() = clientId.isNotEmpty()

    val isSignedIn: Boolean get() = refreshToken != null

    private val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)

    /** Shown in settings so it is obvious which mailbox is being read. */
    val account: String? get() = prefs.getString(KEY_EMAIL, null)

    /**
     * Store a client id typed or scanned by the user. Returns false if it doesn't look
     * like one, rather than letting the flow fail with `invalid_client` later.
     *
     * Changing it drops any existing credentials: a refresh token belongs to the client
     * that issued it.
     */
    suspend fun setClientId(raw: String): Boolean {
        // Tolerate the whole local.properties line, and stray quotes from a paste.
        val cleaned = raw.trim()
            .removePrefix("lightnews:")
            .removePrefix("gmailClientId=")
            .trim()
            .trim('"')
        if (!cleaned.endsWith(CLIENT_ID_SUFFIX) || cleaned.length <= CLIENT_ID_SUFFIX.length) {
            return false
        }
        if (cleaned == clientId) return true
        tokenLock.withLock {
            prefs.edit()
                .putString(KEY_CLIENT_ID, cleaned)
                .remove(KEY_CLIENT_SECRET)
                .remove(KEY_REFRESH)
                .remove(KEY_ACCESS)
                .remove(KEY_EXPIRY)
                .remove(KEY_EMAIL)
                .apply()
        }
        return true
    }

    /**
     * Adopt a refresh token obtained somewhere else — scripts/authorize.py, run on a
     * computer. This is the path that needs no browser on the phone at all.
     */
    suspend fun setCredentials(json: JSONObject): Boolean {
        val id = json.optString("client_id").trim()
        val secret = json.optString("client_secret").trim()
        val refresh = json.optString("refresh_token").trim()
        if (!id.endsWith(CLIENT_ID_SUFFIX) || refresh.isEmpty()) return false
        tokenLock.withLock {
            prefs.edit()
                .putString(KEY_CLIENT_ID, id)
                .putString(KEY_CLIENT_SECRET, secret.ifEmpty { null })
                .putString(KEY_REFRESH, refresh)
                .putString(KEY_EMAIL, json.optString("email").ifBlank { null })
                .remove(KEY_ACCESS)
                .remove(KEY_EXPIRY)
                .apply()
        }
        return true
    }

    /* ------------------------------------------------------------ authorization */

    /**
     * The consent URL, with a fresh PKCE verifier and state persisted for the redirect to
     * check against.
     */
    fun authorizationUri(): Uri {
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        prefs.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()

        return Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", BuildConfig.OAUTH_REDIRECT)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", challengeOf(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            // offline for a refresh token, and consent every time because Google issues
            // one only on the first grant — a re-authorisation without it comes back with
            // an access token and no way to renew it.
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()
    }

    /**
     * Handle the redirect the browser sent back. False for a user who backed out, and for
     * a state mismatch — the check that stops another app on the device from feeding us
     * an authorization code of its own.
     */
    suspend fun onRedirect(uri: Uri): Boolean {
        val expectedState = prefs.getString(KEY_STATE, null)
        val verifier = prefs.getString(KEY_VERIFIER, null)
        prefs.edit().remove(KEY_STATE).remove(KEY_VERIFIER).apply()

        if (expectedState == null || verifier == null) return false
        if (uri.getQueryParameter("state") != expectedState) return false
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: return false

        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId)
            .add("redirect_uri", BuildConfig.OAUTH_REDIRECT)
            .add("code_verifier", verifier)
        clientSecret?.let { form.add("client_secret", it) }

        val body = runCatching { post(form.build()) }.getOrNull() ?: return false
        val refresh = body.optString("refresh_token").takeIf { it.isNotBlank() } ?: return false

        tokenLock.withLock {
            prefs.edit()
                .putString(KEY_REFRESH, refresh)
                .putString(KEY_ACCESS, body.optString("access_token"))
                .putLong(KEY_EXPIRY, expiryFrom(body.optInt("expires_in", 0)))
                .putString(KEY_EMAIL, emailFromIdToken(body.optString("id_token")))
                .apply()
        }
        return true
    }

    /* ------------------------------------------------------------------- tokens */

    /**
     * A valid access token, refreshing if needed.
     *
     * invalid_grant here is almost always the seven-day expiry Google applies to consent
     * screens still in Testing. Nothing is recoverable at that point, so drop the
     * credentials and make the UI ask for consent again rather than retrying forever.
     */
    suspend fun accessToken(): String = tokenLock.withLock {
        val cached = prefs.getString(KEY_ACCESS, null)
        if (!cached.isNullOrBlank() && System.currentTimeMillis() < prefs.getLong(KEY_EXPIRY, 0L)) {
            return cached
        }
        val refresh = refreshToken ?: throw ReauthRequired("not signed in")

        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", clientId)
        clientSecret?.let { form.add("client_secret", it) }

        val body = try {
            post(form.build())
        } catch (e: TokenError) {
            if (e.error == "invalid_grant") {
                clearTokens()
                throw ReauthRequired("authorisation expired")
            }
            throw e
        }
        val access = body.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IOException("token response carried no access_token")
        prefs.edit()
            .putString(KEY_ACCESS, access)
            .putLong(KEY_EXPIRY, expiryFrom(body.optInt("expires_in", 0)))
            .apply()
        access
    }

    /** Force the next accessToken() to hit the token endpoint. */
    suspend fun invalidateAccessToken() = tokenLock.withLock {
        prefs.edit().remove(KEY_EXPIRY).apply()
    }

    suspend fun signOut() = tokenLock.withLock { clearTokens() }

    private fun clearTokens() {
        // The client id survives: it is device configuration, not an account.
        prefs.edit()
            .remove(KEY_REFRESH)
            .remove(KEY_ACCESS)
            .remove(KEY_EXPIRY)
            .remove(KEY_EMAIL)
            .remove(KEY_STATE)
            .remove(KEY_VERIFIER)
            .apply()
    }

    /* ----------------------------------------------------------------- plumbing */

    private class TokenError(val error: String, message: String) : IOException(message)

    private suspend fun post(form: FormBody): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(form).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
            if (!response.isSuccessful) {
                throw TokenError(
                    json.optString("error"),
                    "HTTP " + response.code + ": " + text.take(200),
                )
            }
            json
        }
    }

    /** A minute of slack, so a token can't expire between the check and the request. */
    private fun expiryFrom(expiresInSeconds: Int): Long =
        System.currentTimeMillis() + (expiresInSeconds.coerceAtLeast(60) - 60) * 1000L

    /**
     * The email claim, straight off the JWT payload. Google has just issued this over
     * TLS, so there is nothing to verify here — it only reads a claim for display.
     */
    private fun emailFromIdToken(jwt: String): String? = runCatching {
        val payload = jwt.split('.').getOrNull(1) ?: return@runCatching null
        val json = String(
            Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            Charsets.UTF_8,
        )
        JSONObject(json).optString("email").ifBlank { null }
    }.getOrNull()

    private fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return Base64.encodeToString(buffer, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_EXPIRY = "access_expiry"
        private const val KEY_EMAIL = "account_email"
        private const val KEY_VERIFIER = "pkce_verifier"
        private const val KEY_STATE = "auth_state"
        private const val CLIENT_ID_SUFFIX = ".apps.googleusercontent.com"

        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val SCOPES =
            "openid email https://www.googleapis.com/auth/gmail.modify"
    }
}
