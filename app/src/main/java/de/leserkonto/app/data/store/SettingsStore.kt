package de.leserkonto.app.data.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "leserkonto_settings")

/** User preferences: reminder stages and automatic-renewal behaviour. */
class SettingsStore(private val context: Context) {

    data class Settings(
        /**
         * Days-before-due at which a reminder fires. A value of 0 means "on the
         * due date", negative values mean "overdue by that many days". Multiple
         * stages produce multiple (escalating) reminders.
         */
        val reminderOffsets: Set<Int> = DEFAULT_REMINDER_OFFSETS,
        val autoRenew: Boolean = false,
        /**
         * When the automatic renewal runs, relative to the due date, in days:
         * +2 = two days before due … 0 = on the due date … −1 = one day overdue.
         */
        val autoRenewDayOffset: Int = DEFAULT_AUTO_RENEW_OFFSET,
        val notificationsEnabled: Boolean = true,
    )

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            reminderOffsets = p[REMINDER_OFFSETS]?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?: DEFAULT_REMINDER_OFFSETS,
            autoRenew = p[AUTO_RENEW] ?: false,
            autoRenewDayOffset = p[AUTO_RENEW_OFFSET] ?: DEFAULT_AUTO_RENEW_OFFSET,
            notificationsEnabled = p[NOTIFICATIONS] ?: true,
        )
    }

    suspend fun toggleReminderOffset(day: Int, enabled: Boolean) =
        context.dataStore.edit { p ->
            val current = (p[REMINDER_OFFSETS] ?: DEFAULT_REMINDER_OFFSETS.map { it.toString() }.toSet())
                .toMutableSet()
            if (enabled) current.add(day.toString()) else current.remove(day.toString())
            p[REMINDER_OFFSETS] = current
        }

    suspend fun setAutoRenew(enabled: Boolean) =
        context.dataStore.edit { it[AUTO_RENEW] = enabled }

    suspend fun setAutoRenewDayOffset(offset: Int) =
        context.dataStore.edit { it[AUTO_RENEW_OFFSET] = offset.coerceIn(AUTO_RENEW_MIN, AUTO_RENEW_MAX) }

    suspend fun setNotificationsEnabled(enabled: Boolean) =
        context.dataStore.edit { it[NOTIFICATIONS] = enabled }

    // --- reminder de-duplication markers (so each stage notifies only once) ---

    suspend fun notifiedMarkers(): Set<String> =
        context.dataStore.data.first()[NOTIFIED_MARKERS] ?: emptySet()

    /** Replaces the stored markers (used to also prune markers of gone items). */
    suspend fun replaceNotifiedMarkers(markers: Set<String>) =
        context.dataStore.edit { it[NOTIFIED_MARKERS] = markers }

    companion object {
        val DEFAULT_REMINDER_OFFSETS = setOf(3, 1, 0)
        const val DEFAULT_AUTO_RENEW_OFFSET = 2
        const val AUTO_RENEW_MIN = -1
        const val AUTO_RENEW_MAX = 2

        /** Selectable reminder stages, most-advance first. */
        val SELECTABLE_OFFSETS = listOf(7, 5, 3, 2, 1, 0, -1)

        private val REMINDER_OFFSETS = stringSetPreferencesKey("reminder_offsets")
        private val AUTO_RENEW = booleanPreferencesKey("auto_renew")
        private val AUTO_RENEW_OFFSET = intPreferencesKey("auto_renew_day_offset")
        private val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        private val NOTIFIED_MARKERS = stringSetPreferencesKey("reminder_notified_markers")
    }
}
