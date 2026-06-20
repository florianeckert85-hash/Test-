package de.leserkonto.app

import android.app.Application
import android.content.Context
import de.leserkonto.app.data.AccountRepository
import de.leserkonto.app.data.model.LibraryConfig
import de.leserkonto.app.data.opac.BibliothecaOpenClient
import de.leserkonto.app.data.opac.OpacClient
import de.leserkonto.app.data.store.CredentialStore
import de.leserkonto.app.data.store.SettingsStore
import de.leserkonto.app.work.NotificationHelper
import de.leserkonto.app.work.SyncScheduler

/** Minimal manual DI container — created once and shared across the app. */
class AppContainer(context: Context) {
    val config = LibraryConfig()
    val opacClient: OpacClient = BibliothecaOpenClient(config)
    val credentialStore = CredentialStore(context)
    val settingsStore = SettingsStore(context)
    val accountRepository = AccountRepository(opacClient, credentialStore)
    val notificationHelper = NotificationHelper(context)
}

class LeserkontoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notificationHelper.ensureChannel()
        // Keep the daily sync scheduled whenever the app has been opened.
        if (container.credentialStore.hasCredentials) {
            SyncScheduler.schedule(this)
        }
    }
}
