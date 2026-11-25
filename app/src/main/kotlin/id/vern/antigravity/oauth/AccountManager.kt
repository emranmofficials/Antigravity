package id.vern.antigravity.oauth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.util.Base64
import android.content.Context
import id.vern.antigravity.data.SubscriptionTierType
import id.vern.antigravity.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed AES-GCM helper used for token material in the account store.
 * No plaintext token is ever written to disk.
 */
internal object TokenCipher {
    private const val TAG = "TokenCipher"
    private const val KEY_ALIAS = "ag_accounts_token_aes_gcm"
    private const val CIPHER_PREFIX = "agenc1:"
    private const val IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    private val keyLock = Any()
    @Volatile
    private var cachedKey: SecretKey? = null

    private fun getOrCreateKey(): SecretKey? = synchronized(keyLock) {
        cachedKey?.let { return@synchronized it }
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            val key = existing?.secretKey ?: run {
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(spec)
                generator.generateKey()
            }
            cachedKey = key
            key
        } catch (e: Exception) {
            AppLogger.e(TAG, "Unable to access account token key: ${e.message}")
            null
        }
    }

    fun encrypt(plainText: String): String? {
        if (plainText.isEmpty()) return ""
        val key = getOrCreateKey() ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv ?: return null
            val payload = iv + cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            CIPHER_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to encrypt token: ${e.message}")
            null
        }
    }

    fun decrypt(cipherText: String): String? {
        if (cipherText.isEmpty()) return ""
        if (!cipherText.startsWith(CIPHER_PREFIX)) return null
        val key = getOrCreateKey() ?: return null
        return try {
            val payload = Base64.decode(cipherText.removePrefix(CIPHER_PREFIX), Base64.NO_WRAP)
            if (payload.size <= IV_LENGTH_BYTES) return null
            val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
            val encrypted = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to decrypt token: ${e.message}")
            null
        }
    }
}

/**
 * Public account model. [tokenJson] stays source-compatible for callers but is
 * now decrypted lazily from the Keystore-encrypted payload instead of being
 * read from a plaintext JSON field.
 */
class StoredAccount internal constructor(
    val id: String,
    val email: String,
    val name: String,
    val picture: String? = null,
    internal val encryptedTokenJson: String = "",
    val tier: String = "FREE",
    val lastUsedAt: Long = System.currentTimeMillis(),
    internal val transientTokenJson: String? = null
) {
    val tokenJson: String by lazy {
        TokenCipher.decrypt(encryptedTokenJson) ?: transientTokenJson.orEmpty()
    }

    val tierType: SubscriptionTierType get() = when (tier.uppercase()) {
        "ULTRA" -> SubscriptionTierType.ULTRA
        "PRO" -> SubscriptionTierType.PRO
        "ENTERPRISE" -> SubscriptionTierType.ENTERPRISE
        else -> SubscriptionTierType.FREE
    }

    /** Value-style copy used by the account store; never persists plaintext. */
    fun copy(
        id: String = this.id,
        email: String = this.email,
        name: String = this.name,
        picture: String? = this.picture,
        tier: String = this.tier,
        lastUsedAt: Long = this.lastUsedAt,
        tokenJson: String? = null
    ): StoredAccount {
        val encrypted = if (tokenJson != null) {
            TokenCipher.encrypt(tokenJson).orEmpty()
        } else {
            encryptedTokenJson
        }
        val fallback = if (tokenJson != null && encrypted.isBlank()) {
            tokenJson
        } else {
            transientTokenJson
        }
        return StoredAccount(id, email, name, picture, encrypted, tier, lastUsedAt, fallback)
    }

    override fun toString(): String {
        return "StoredAccount(id=$id, email=$email, name=$name, picture=$picture, tier=$tier, lastUsedAt=$lastUsedAt, tokenJson=***)"
    }
}

object AccountManager {
    private const val TAG = "AccountManager"
    private const val ACCOUNTS_FILE_NAME = "accounts_store.json"
    private const val STORE_VERSION = 2
    private val lock = Any()

    private fun getStoreFile(context: Context): File {
        val dir = File(context.filesDir, ".gemini")
        hardenDirectory(dir)
        return File(dir, ACCOUNTS_FILE_NAME)
    }

    /** Owner-only permissions for the store file and its parent directory. */
    private fun hardenDirectory(dir: File) {
        try {
            dir.mkdirs()
            dir.setReadable(false, false)
            dir.setWritable(false, false)
            dir.setExecutable(false, false)
            dir.setReadable(true, true)
            dir.setWritable(true, true)
            dir.setExecutable(true, true)
            try {
                Os.chmod(dir.absolutePath, 0x700)
            } catch (_: Throwable) {
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to harden account directory: ${e.message}")
        }
    }

    private fun hardenFile(file: File) {
        try {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
            try {
                Os.chmod(file.absolutePath, 0x600)
            } catch (_: Throwable) {
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to harden account store permissions: ${e.message}")
        }
    }

    fun getAccounts(context: Context): List<StoredAccount> = synchronized(lock) {
        val file = getStoreFile(context)
        if (!file.isFile || file.length() == 0L) {
            return emptyList()
        }
        hardenFile(file)
        return try {
            val content = file.readText(Charsets.UTF_8)
            val root = JSONObject(content)
            val arr = root.optJSONArray("accounts") ?: return emptyList()
            val legacyFormat = root.optInt("version", 1) < STORE_VERSION
            var needsPersist = legacyFormat
            val list = mutableListOf<StoredAccount>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                val email = obj.optString("email")
                var name = obj.optString("name")
                var picture = obj.optString("picture").takeIf { it.isNotBlank() }
                // Old stores kept the raw token here; new stores only have tokenCipher.
                val legacyToken = obj.optString("tokenJson")
                val encryptedToken = obj.optString("tokenCipher")
                val tokenForProfile = if (legacyToken.isNotBlank()) {
                    legacyToken
                } else {
                    TokenCipher.decrypt(encryptedToken).orEmpty()
                }
                val tier = obj.optString("tier", "FREE")
                val lastUsedAt = obj.optLong("lastUsedAt", System.currentTimeMillis())

                if (tokenForProfile.isNotBlank()) {
                    val profile = OAuthManager.parseProfileFromTokenJson(tokenForProfile)
                    val realName = profile.name?.takeIf { it.isNotBlank() }
                    val realPic = profile.picture?.takeIf { it.isNotBlank() }
                    if (realName != null && realName != name) {
                        name = realName
                        needsPersist = true
                    }
                    if (realPic != null && realPic != picture) {
                        picture = realPic
                        needsPersist = true
                    }
                }

                if (legacyToken.isNotBlank()) {
                    needsPersist = true
                }

                if (id.isNotBlank() && email.isNotBlank()) {
                    val encryptedForAccount = if (legacyToken.isNotBlank()) {
                        TokenCipher.encrypt(legacyToken).orEmpty()
                    } else {
                        encryptedToken
                    }
                    val transient = if (legacyToken.isNotBlank() && encryptedForAccount.isBlank()) {
                        legacyToken
                    } else {
                        null
                    }
                    list.add(
                        StoredAccount(
                            id = id,
                            email = email,
                            name = name,
                            picture = picture,
                            encryptedTokenJson = encryptedForAccount,
                            tier = tier,
                            lastUsedAt = lastUsedAt,
                            transientTokenJson = transient
                        )
                    )
                }
            }
            if (needsPersist) {
                val activeId = root.optString("activeAccountId").takeIf { it.isNotBlank() }
                persist(context, activeId, list)
            }
            list.sortedByDescending { it.lastUsedAt }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading accounts: ${e.message}")
            emptyList()
        }
    }

    fun getActiveAccountId(context: Context): String? = synchronized(lock) {
        val file = getStoreFile(context)
        if (!file.isFile || file.length() == 0L) {
            return null
        }
        hardenFile(file)
        return try {
            val content = file.readText(Charsets.UTF_8)
            val root = JSONObject(content)
            root.optString("activeAccountId").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    fun getActiveAccount(context: Context): StoredAccount? {
        val accounts = getAccounts(context)
        if (accounts.isEmpty()) return null
        val activeId = getActiveAccountId(context)
        return accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()
    }

    private fun persist(context: Context, activeId: String?, accounts: List<StoredAccount>) {
        val file = getStoreFile(context)
        try {
            val root = JSONObject()
            root.put("version", STORE_VERSION)
            root.put("activeAccountId", activeId ?: "")
            val arr = JSONArray()
            for (acc in accounts) {
                val obj = JSONObject()
                obj.put("id", acc.id)
                obj.put("email", acc.email)
                obj.put("name", acc.name)
                obj.put("picture", acc.picture ?: "")
                val encrypted = acc.encryptedTokenJson.ifBlank {
                    TokenCipher.encrypt(acc.transientTokenJson.orEmpty()).orEmpty()
                }
                if (encrypted.isNotBlank()) {
                    obj.put("tokenCipher", encrypted)
                }
                obj.put("tier", acc.tier)
                obj.put("lastUsedAt", acc.lastUsedAt)
                arr.put(obj)
            }
            root.put("accounts", arr)
            file.parentFile?.let { hardenDirectory(it) }
            file.writeText(root.toString(2), Charsets.UTF_8)
            hardenFile(file)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to persist accounts: ${e.message}")
        }
    }

    fun saveOrUpdateAccount(
        context: Context,
        email: String,
        name: String,
        picture: String?,
        tokenJson: String,
        tier: String? = null,
        makeActive: Boolean = true
    ): StoredAccount = synchronized(lock) {
        val current = getAccounts(context).toMutableList()
        val index = current.indexOfFirst { it.email.equals(email, ignoreCase = true) }
        val now = System.currentTimeMillis()
        val existingTier = if (index >= 0) current[index].tier else "FREE"
        val resolvedTier = tier?.uppercase() ?: existingTier
        val finalName = if (name.isNotBlank()) name else if (index >= 0 && current[index].name.isNotBlank()) current[index].name else email.substringBefore("@")
        val finalPic = picture?.takeIf { it.isNotBlank() } ?: if (index >= 0) current[index].picture else null

        val encryptedToken = TokenCipher.encrypt(tokenJson).orEmpty()
        val stored = StoredAccount(
            id = email,
            email = email,
            name = finalName,
            picture = finalPic,
            encryptedTokenJson = encryptedToken,
            tier = resolvedTier,
            lastUsedAt = now,
            transientTokenJson = if (encryptedToken.isBlank() && tokenJson.isNotBlank()) tokenJson else null
        )

        if (index >= 0) {
            current[index] = stored
        } else {
            current.add(0, stored)
        }

        val activeId = if (makeActive) email else (getActiveAccountId(context) ?: email)
        persist(context, activeId, current)
        stored
    }

    fun updateAccountProfile(context: Context, email: String, name: String?, picture: String?) = synchronized(lock) {
        val current = getAccounts(context).toMutableList()
        val index = current.indexOfFirst { it.email.equals(email, ignoreCase = true) }
        if (index >= 0) {
            val item = current[index]
            val updated = item.copy(
                name = name?.takeIf { it.isNotBlank() } ?: item.name,
                picture = picture?.takeIf { it.isNotBlank() } ?: item.picture
            )
            current[index] = updated
            persist(context, getActiveAccountId(context), current)
        }
    }

    fun updateAccountTier(context: Context, email: String, tier: String) = synchronized(lock) {
        val current = getAccounts(context).toMutableList()
        val index = current.indexOfFirst { it.email.equals(email, ignoreCase = true) }
        if (index >= 0) {
            val item = current[index]
            val updated = item.copy(tier = tier.uppercase())
            current[index] = updated
            persist(context, getActiveAccountId(context), current)
            AppLogger.d(TAG, "Updated tier for ${item.email}: ${tier.uppercase()}")
        }
    }

    fun switchAccount(context: Context, accountId: String): Boolean = synchronized(lock) {
        val accounts = getAccounts(context)
        val target = accounts.firstOrNull { it.id.equals(accountId, ignoreCase = true) || it.email.equals(accountId, ignoreCase = true) }
            ?: return false

        val updatedList = accounts.map {
            if (it.id == target.id) it.copy(lastUsedAt = System.currentTimeMillis()) else it
        }
        persist(context, target.id, updatedList)

        OAuthManager.writeRawTokenJson(context, target.tokenJson)
        val prefs = context.getSharedPreferences(OAuthManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(OAuthManager.KEY_USER_EMAIL, target.email)
            .putString(OAuthManager.KEY_USER_NAME, target.name)
            .putString(OAuthManager.KEY_USER_PICTURE, target.picture)
            .apply()

        AppLogger.d(TAG, "Switched active account to: ${target.email}")
        true
    }

    fun removeAccount(context: Context, accountId: String): Boolean = synchronized(lock) {
        val accounts = getAccounts(context).toMutableList()
        val index = accounts.indexOfFirst { it.id.equals(accountId, ignoreCase = true) || it.email.equals(accountId, ignoreCase = true) }
        if (index < 0) return false

        val removed = accounts.removeAt(index)
        val currentActive = getActiveAccountId(context)
        val newActiveId = if (currentActive == removed.id || currentActive == removed.email) {
            accounts.firstOrNull()?.id
        } else {
            currentActive
        }

        persist(context, newActiveId, accounts)

        if (newActiveId != null) {
            val next = accounts.first { it.id == newActiveId }
            OAuthManager.writeRawTokenJson(context, next.tokenJson)
            val prefs = context.getSharedPreferences(OAuthManager.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(OAuthManager.KEY_USER_EMAIL, next.email)
                .putString(OAuthManager.KEY_USER_NAME, next.name)
                .putString(OAuthManager.KEY_USER_PICTURE, next.picture)
                .apply()
        } else {
            OAuthManager.clearToken(context)
        }

        AppLogger.d(TAG, "Removed account: ${removed.email}")
        true
    }

    fun importExistingTokenIfEmpty(context: Context) {
        val accounts = getAccounts(context)
        if (accounts.isNotEmpty()) return

        val email = OAuthManager.getUserEmail(context) ?: return
        val name = OAuthManager.getUserName(context) ?: email.substringBefore("@")
        val picture = OAuthManager.getUserPicture(context)
        val tokenFile = OAuthManager.tokenFile(context)
        if (!tokenFile.isFile || tokenFile.length() == 0L) return

        try {
            val tokenJson = tokenFile.readText(Charsets.UTF_8)
            saveOrUpdateAccount(
                context = context,
                email = email,
                name = name,
                picture = picture,
                tokenJson = tokenJson,
                tier = "FREE",
                makeActive = true
            )
            AppLogger.d(TAG, "Imported existing token as initial account: $email")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to import existing token: ${e.message}")
        }
    }
}
