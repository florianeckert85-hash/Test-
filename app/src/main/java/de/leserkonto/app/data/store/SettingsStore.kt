package de.leserkonto.app.data.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "leserkonto_settings")

/** User preferences: reminder lead time and automatic-renewal behaviour. */
class SettingsStore(private val context: Context) {

    data class Settings(
        val reminderDaysBefore: Int = DEFAULT_REMINDER_DAYS,
        val autoRenew: Boolean = false,
        /** How many days before the due date the app attempts an auto-renewal. */
        val autoRenewDaysBefore: Int = DEFAULT_AUTO_RENEW_DAYS,
        val notificationsEnabled: Boolean = true,
    )

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            reminderDaysBefore = p[REMINDER_DAYS] ?: DEFAULT_REMINDER_DAYS,
            autoRenew = p[AUTO_RENEW] ?: false,
            autoRenewDaysBefore = p[AUTO_RENEW_DAYS] ?: DEFAULT_AUTO_RENEW_DAYS,
            notificationsEnabled = p[NOTIFICATIONS] ?: true,
        )
    }

    suspend fun setReminderDaysBefore(days: Int) =
        context.dataStore.edit { it[REMINDER_DAYS] = days.coerceIn(0, 14) }

    suspend fun setAutoRenew(enabled: Boolean) =
        context.dataStore.edit { it[AUTO_RENEW] = enabled }

    suspend fun setAutoRenewDaysBefore(days: Int) =
        context.dataStore.edit { it[AUTO_RENEW_DAYS] = days.coerceIn(1, 7) }

    suspend fun setNotificationsEnabled(enabled: Boolean) =
        context.dataStore.edit { it[NOTIFICATIONS] = enabled }

    companion object {
        const val DEFAULT_REMINDER_DAYS = 3
        const val DEFAULT_AUTO_RENEW_DAYS = 2

        private val REMINDER_DAYS = intPreferencesKey("reminder_days_before")
        private val AUTO_RENEW = booleanPreferencesKey("auto_renew")
        private val AUTO_RENEW_DAYS = intPreferencesKey("auto_renew_days_before")
        private val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
    }
}
