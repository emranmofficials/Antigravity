package id.vern.antigravity.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import id.vern.antigravity.R
import id.vern.antigravity.oauth.StoredAccount
import id.vern.antigravity.oauth.AccountManager
import id.vern.antigravity.oauth.OAuthManager
import id.vern.antigravity.executor.EngineExecutor
import id.vern.antigravity.service.CloudCodeQuotaService
import id.vern.antigravity.service.AgyModelsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthDelegate {
    init {
        Companion.registerForDeepLinks(this)
    }

    private val mutableUserEmail = MutableStateFlow<String?>(null)
    val userEmail: StateFlow<String?> = mutableUserEmail.asStateFlow()

    private val mutableUserName = MutableStateFlow("")
    val userName: StateFlow<String> = mutableUserName.asStateFlow()

    private val mutableUserPicture = MutableStateFlow<String?>(null)
    val userPicture: StateFlow<String?> = mutableUserPicture.asStateFlow()

    private val mutableAccounts = MutableStateFlow<List<StoredAccount>>(emptyList())
    val accounts: StateFlow<List<StoredAccount>> = mutableAccounts.asStateFlow()

    private val mutableIsAuthenticating = MutableStateFlow(false)
    val isAuthenticating: StateFlow<Boolean> = mutableIsAuthenticating.asStateFlow()

    private val mutableAuthError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = mutableAuthError.asStateFlow()

    private val mutableShowGoogleSignInDialog = MutableStateFlow(false)
    val showGoogleSignInDialog: StateFlow<Boolean> = mutableShowGoogleSignInDialog.asStateFlow()

    private val mutablePendingAuthUrl = MutableStateFlow<String?>(null)
    val pendingAuthUrl: StateFlow<String?> = mutablePendingAuthUrl.asStateFlow()

    fun showGoogleSignInDialog() {
        mutableShowGoogleSignInDialog.value = true
    }

    fun dismissGoogleSignInDialog() {
        mutableShowGoogleSignInDialog.value = false
    }

    fun setPendingAuthUrl(url: String?) {
        mutablePendingAuthUrl.value = url
    }

    fun refreshAuthState(
        context: Context,
        scope: CoroutineScope,
        onRefreshUserQuota: (Context) -> Unit
    ) {
        OAuthManager.checkAndImportExistingToken(context)
        val allAccounts = AccountManager.getAccounts(context)
        mutableAccounts.value = allAccounts
        val email = OAuthManager.getUserEmail(context)
        mutableUserEmail.value = email
        val name = OAuthManager.getUserName(context) ?: if (email != null) {
            email.substringBefore("@").replace(".", " ").split(" ")
                .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
        } else ""
        mutableUserName.value = name
        val pic = OAuthManager.getUserPicture(context)
        if (!pic.isNullOrBlank()) {
            mutableUserPicture.value = pic
        }

        onRefreshUserQuota(context)

        scope.launch {
            if (OAuthManager.refreshTokenIfNeeded(context)) {
                val newEmail = OAuthManager.getUserEmail(context)
                mutableUserEmail.value = newEmail
                val newName = OAuthManager.getUserName(context) ?: if (newEmail != null) {
                    newEmail.substringBefore("@").replace(".", " ").split(" ")
                        .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
                } else ""
                mutableUserName.value = newName
                val refreshedPic = OAuthManager.getUserPicture(context)
                if (!refreshedPic.isNullOrBlank()) {
                    mutableUserPicture.value = refreshedPic
                }
                if (newEmail != null) {
                    onRefreshUserQuota(context)
                }
            }
            val userInfo = OAuthManager.fetchUserInfo(context)
            if (userInfo != null) {
                if (!userInfo.name.isNullOrBlank()) {
                    mutableUserName.value = userInfo.name
                }
                if (!userInfo.picture.isNullOrBlank()) {
                    mutableUserPicture.value = userInfo.picture
                }
                if (!userInfo.email.isNullOrBlank()) {
                    mutableUserEmail.value = userInfo.email
                }
            }
            mutableAccounts.value = AccountManager.getAccounts(context)
        }
    }

    fun switchAccount(
        context: Context,
        accountId: String,
        scope: CoroutineScope,
        onAccountSwitched: () -> Unit
    ) {
        scope.launch {
            val success = AccountManager.switchAccount(context, accountId)
            if (success) {
                AgyModelsService.invalidateCache()
                val prefs = context.getSharedPreferences(AntigravityViewModel.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .remove(AntigravityViewModel.KEY_CLI_MODELS_FETCHED_AT)
                    .remove(AntigravityViewModel.KEY_CLI_MODELS_JSON)
                    .remove(AntigravityViewModel.KEY_SELECTED_MODEL_CLI_ID)
                    .apply()
                onAccountSwitched()
            }
        }
    }

    fun removeAccount(
        context: Context,
        accountId: String,
        scope: CoroutineScope,
        onAccountRemoved: () -> Unit
    ) {
        scope.launch {
            val success = AccountManager.removeAccount(context, accountId)
            if (success) {
                val currentActive = AccountManager.getActiveAccount(context)
                if (currentActive != null) {
                    AgyModelsService.invalidateCache()
                    val prefs = context.getSharedPreferences(AntigravityViewModel.PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .remove(AntigravityViewModel.KEY_CLI_MODELS_FETCHED_AT)
                        .remove(AntigravityViewModel.KEY_CLI_MODELS_JSON)
                        .remove(AntigravityViewModel.KEY_SELECTED_MODEL_CLI_ID)
                        .apply()
                }
                onAccountRemoved()
            }
        }
    }

    fun startGoogleSignIn(
        context: Context,
        scope: CoroutineScope,
        onSuccess: (String?) -> Unit,
        onLog: (String, String, Boolean) -> Unit
    ) {
        val pendingUrl = mutablePendingAuthUrl.value
        if (!pendingUrl.isNullOrBlank()) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pendingUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                onLog("Auth", "Opening official engine PKCE auth URL in browser...", false)
                mutableShowGoogleSignInDialog.value = true
                return
            } catch (e: Exception) {
                onLog("Auth", "Failed to open auth URL: ${e.message}", true)
            }
        }

        if (mutableIsAuthenticating.value) {
            mutableShowGoogleSignInDialog.value = true
            return
        }

        scope.launch {
            mutableIsAuthenticating.value = true
            mutableAuthError.value = null
            mutableShowGoogleSignInDialog.value = true
            onLog("Auth", "Initiating Google OAuth flow on port 8085...", false)

            OAuthManager.startBrowserAuth(
                context = context,
                onSuccess = { email ->
                    mutableUserEmail.value = email
                    mutableIsAuthenticating.value = false
                    mutableShowGoogleSignInDialog.value = false
                    mutablePendingAuthUrl.value = null
                    onSuccess(email)
                },
                onError = { err ->
                    mutableAuthError.value = err
                    mutableIsAuthenticating.value = false
                    onLog("Auth", "Google sign-in error: $err", true)
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    fun onActivityResume(
        context: Context,
        scope: CoroutineScope,
        onCheckUpdates: (Context) -> Unit,
        onAccountChangeDetected: (String) -> Unit
    ) {
        onCheckUpdates(context)
        OAuthManager.checkAndImportExistingToken(context)
        val email = OAuthManager.getUserEmail(context)
        if (email != null && email != mutableUserEmail.value) {
            mutableUserEmail.value = email
            onAccountChangeDetected(email)
        } else if (email != null) {
            if (mutableUserPicture.value.isNullOrBlank()) {
                val pic = OAuthManager.getUserPicture(context)
                if (!pic.isNullOrBlank()) {
                    mutableUserPicture.value = pic
                } else {
                    scope.launch {
                        val userInfo = OAuthManager.fetchUserInfo(context)
                        if (!userInfo?.picture.isNullOrBlank()) {
                            mutableUserPicture.value = userInfo?.picture
                        }
                    }
                }
            }
        }
    }

    fun exchangeManualAuthCode(
        context: Context,
        rawCodeOrToken: String,
        scope: CoroutineScope,
        onStdinSent: () -> Unit,
        onSuccess: (String?) -> Unit,
        onLog: (String, String, Boolean) -> Unit
    ) {
        var trimmed = rawCodeOrToken.trim()
        if (trimmed.isEmpty()) return

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                val uri = Uri.parse(trimmed)
                val codeParam = uri.getQueryParameter("code")
                if (!codeParam.isNullOrBlank()) {
                    trimmed = codeParam
                }
            } catch (_: Exception) {}
        }
        if (trimmed.contains("%")) {
            try {
                trimmed = java.net.URLDecoder.decode(trimmed, "UTF-8")
            } catch (_: Exception) {}
        }

        val sent = EngineExecutor.writeToAgyStdin(trimmed)
        if (sent) {
            mutableShowGoogleSignInDialog.value = false
            mutablePendingAuthUrl.value = null
            onLog("Auth", "Submitted authorization code directly to active engine stdin", false)
            Toast.makeText(context, context.getString(R.string.auth_verifying_code), Toast.LENGTH_SHORT).show()
            onStdinSent()
            return
        }

        scope.launch {
            mutableIsAuthenticating.value = true
            mutableAuthError.value = null
            onLog("Auth", "Processing authentication code/token...", false)

            OAuthManager.exchangeCodeDirectly(
                context = context,
                rawInput = trimmed,
                onSuccess = { email ->
                    mutableUserEmail.value = email
                    mutableIsAuthenticating.value = false
                    mutableShowGoogleSignInDialog.value = false
                    mutablePendingAuthUrl.value = null
                    onSuccess(email)
                },
                onError = { err ->
                    mutableAuthError.value = err
                    mutableIsAuthenticating.value = false
                    onLog("Auth", "Google sign-in error: $err", true)
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    fun signOutGoogle(
        context: Context,
        onSignedOut: () -> Unit,
        onLog: (String, String) -> Unit
    ) {
        // Remember which account is being signed out before the token files are cleared.
        val signedOutEmail = OAuthManager.getUserEmail(context)
            ?.takeIf { it.isNotBlank() }
            ?: AccountManager.getActiveAccount(context)?.email

        // Drop only the signed-out account; other accounts remain available for switching.
        var removed = false
        if (!signedOutEmail.isNullOrBlank()) {
            removed = AccountManager.removeAccount(context, signedOutEmail)
        }

        // removeAccount may activate another account's token, so clear it again for an
        // explicit sign-out while keeping the remaining accounts in the store.
        OAuthManager.clearToken(context)

        mutableAccounts.value = AccountManager.getAccounts(context)
        mutableUserEmail.value = null
        mutableUserName.value = ""
        mutableUserPicture.value = null
        mutableAuthError.value = null
        mutablePendingAuthUrl.value = null
        mutableShowGoogleSignInDialog.value = false
        mutableIsAuthenticating.value = false

        val prefs = context.getSharedPreferences(AntigravityViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(AntigravityViewModel.KEY_SELECTED_MODEL)
            .remove(AntigravityViewModel.KEY_SELECTED_MODEL_CLI_ID)
            .remove(AntigravityViewModel.KEY_THINKING_BUDGET)
            .remove(AntigravityViewModel.KEY_CLI_MODELS_JSON)
            .remove(AntigravityViewModel.KEY_CLI_MODELS_FETCHED_AT)
            .remove(OAuthManager.KEY_USER_NAME)
            .remove(OAuthManager.KEY_USER_PICTURE)
            .remove(OAuthManager.KEY_USER_EMAIL)
            .apply()

        onSignedOut()
        onLog(
            "Auth",
            if (removed) "Signed out of Google account and removed it from the account store"
            else "Signed out of Google account"
        )
        Toast.makeText(context, context.getString(R.string.account_toast_signed_out), Toast.LENGTH_SHORT).show()
    }

    private fun Uri.fragmentParameter(name: String): String? {
        val fragment = fragment ?: return null
        if (fragment.isBlank()) return null
        return try {
            Uri.parse("antigravity://fragment/?$fragment").getQueryParameter(name)
        } catch (_: Exception) {
            null
        }
    }

    /** Parse and route an antigravity:// URI without assuming any particular host/path. */
    fun handleDeepLink(
        uri: Uri?,
        onAuthCode: (String) -> Unit,
        onAuthSuccess: (String?) -> Unit,
        onAuthError: (String) -> Unit,
        onWorkspaceRequested: () -> Unit,
        onUnknownLink: ((String?) -> Unit)? = null
    ): Boolean {
        val raw = uri?.toString()
        if (uri == null || !uri.scheme.equals("antigravity", ignoreCase = true)) {
            onUnknownLink?.invoke(raw)
            return false
        }

        fun param(name: String): String? =
            uri.getQueryParameter(name)?.takeIf { it.isNotBlank() }
                ?: uri.fragmentParameter(name)?.takeIf { it.isNotBlank() }

        val error = param("error")
        val code = param("code") ?: param("token")
        val email = param("email")
        var handled = false

        if (!error.isNullOrBlank()) {
            mutableAuthError.value = error
            mutableIsAuthenticating.value = false
            mutableShowGoogleSignInDialog.value = false
            onAuthError(error)
            handled = true
        }

        if (!code.isNullOrBlank()) {
            mutableAuthError.value = null
            mutablePendingAuthUrl.value = null
            mutableShowGoogleSignInDialog.value = false
            mutableIsAuthenticating.value = true
            onAuthCode(code)
            handled = true
        } else if (!email.isNullOrBlank() && error.isNullOrBlank()) {
            mutableAuthError.value = null
            mutablePendingAuthUrl.value = null
            mutableShowGoogleSignInDialog.value = false
            mutableIsAuthenticating.value = false
            mutableUserEmail.value = email
            onAuthSuccess(email)
            handled = true
        }

        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty()
        val wantsWorkspace = host.equals("open", true) ||
            host.equals("workspace", true) ||
            host.equals("chat", true) ||
            path.equals("/open", true) ||
            path.contains("workspace", true) ||
            path.contains("chat", true)
        if (wantsWorkspace) {
            onWorkspaceRequested()
            handled = true
        }

        if (!handled) {
            onUnknownLink?.invoke(raw)
        }
        return handled
    }

    companion object {
        @Volatile
        private var activeDelegate: AuthDelegate? = null

        internal fun registerForDeepLinks(instance: AuthDelegate) {
            activeDelegate = instance
        }

        /** Entry point used by MainActivity for both onCreate and onNewIntent. */
        fun handleDeepLinkIntent(
            intent: Intent?,
            onAuthCode: (String) -> Unit,
            onAuthSuccess: (String?) -> Unit,
            onAuthError: (String) -> Unit,
            onWorkspaceRequested: () -> Unit,
            onUnknownLink: ((String?) -> Unit)? = null
        ): Boolean {
            val delegate = activeDelegate ?: AuthDelegate()
            return delegate.handleDeepLink(
                uri = intent?.data,
                onAuthCode = onAuthCode,
                onAuthSuccess = onAuthSuccess,
                onAuthError = onAuthError,
                onWorkspaceRequested = onWorkspaceRequested,
                onUnknownLink = onUnknownLink
            )
        }
    }
}
