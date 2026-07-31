package de.leserkonto.app.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores the library card number + password encrypted at rest.
 *
 * Uses [EncryptedSharedPreferences] backed by a key in the Android Keystore, so
 * the password is never written to disk in plain text. The Keystore key is
 * hardware-backed on most devices.
 *
 * IMPORTANT: creating the [EncryptedSharedPreferences] (Keystore key generation
 * + Tink init) and reading/writing it can take a noticeable amount of time and
 * must never run on the main thread, or the app will ANR. All access therefore
 * goes through [Dispatchers.IO] via suspend functions.
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext

    // Created lazily on first use; because every accessor below switches to IO
    // first, the (potentially slow) creation also happens off the main thread.
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "leserkonto_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    suspend fun username(): String? = withContext(Dispatchers.IO) { prefs.getString(KEY_USER, null) }

    suspend fun password(): String? = withContext(Dispatchers.IO) { prefs.getString(KEY_PASS, null) }

    suspend fun hasCredentials(): Boolean = withContext(Dispatchers.IO) {
        !prefs.getString(KEY_USER, null).isNullOrBlank() &&
            !prefs.getString(KEY_PASS, null).isNullOrBlank()
    }

    suspend fun save(username: String, password: String) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_USER, username.trim())
            .putString(KEY_PASS, password)
            .apply()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_USER).remove(KEY_PASS).apply()
    }

    private companion object {
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
    }
}
