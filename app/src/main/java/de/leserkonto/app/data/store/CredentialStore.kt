package de.leserkonto.app.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the library card number + password encrypted at rest.
 *
 * Uses [EncryptedSharedPreferences] backed by a key in the Android Keystore, so
 * the password is never written to disk in plain text. The Keystore key is
 * hardware-backed on most devices.
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "leserkonto_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var username: String?
        get() = prefs.getString(KEY_USER, null)
        private set(value) { prefs.edit().putString(KEY_USER, value).apply() }

    var password: String?
        get() = prefs.getString(KEY_PASS, null)
        private set(value) { prefs.edit().putString(KEY_PASS, value).apply() }

    val hasCredentials: Boolean
        get() = !username.isNullOrBlank() && !password.isNullOrBlank()

    fun save(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USER, username.trim())
            .putString(KEY_PASS, password)
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_USER).remove(KEY_PASS).apply()
    }

    private companion object {
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
    }
}
