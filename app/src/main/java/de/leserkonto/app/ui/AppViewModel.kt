package de.leserkonto.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.leserkonto.app.AppContainer
import de.leserkonto.app.data.AccountRepository
import de.leserkonto.app.data.model.AccountData
import de.leserkonto.app.data.model.Loan
import de.leserkonto.app.data.model.OpacResult
import de.leserkonto.app.data.store.SettingsStore
import de.leserkonto.app.work.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.Context

/** UI state for the whole app (login + account + settings live in one screen graph). */
data class UiState(
    val loggedIn: Boolean = false,
    val loading: Boolean = false,
    val account: AccountData? = null,
    val error: String? = null,
    val message: String? = null,
    val renewingId: String? = null,
    val settings: SettingsStore.Settings = SettingsStore.Settings(),
    /** One-shot diagnostic report to be shared via the system share sheet. */
    val diagnostics: String? = null,
)

class AppViewModel(
    private val repo: AccountRepository,
    private val settingsStore: SettingsStore,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState(loggedIn = repo.hasCredentials))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { s -> _state.update { it.copy(settings = s) } }
        }
        if (repo.hasCredentials) refresh()
    }

    // ----------------------------------------------------------------- login

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = "Bitte Ausweisnummer und Passwort eingeben.") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.verify(username, password)) {
                is OpacResult.Success -> {
                    repo.saveCredentials(username, password)
                    SyncScheduler.schedule(appContext)
                    _state.update { it.copy(loggedIn = true, loading = false) }
                    refresh()
                }
                is OpacResult.Error ->
                    _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    /** Runs a login-page diagnosis and exposes the report for sharing. */
    fun diagnose(username: String, password: String) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.diagnose(username.trim(), password)) {
                is OpacResult.Success ->
                    _state.update { it.copy(loading = false, diagnostics = r.value) }
                is OpacResult.Error ->
                    _state.update { it.copy(loading = false, error = "Diagnose fehlgeschlagen: ${r.message}") }
            }
        }
    }

    fun consumeDiagnostics() = _state.update { it.copy(diagnostics = null) }

    fun logout() {
        repo.logout()
        SyncScheduler.cancel(appContext)
        _state.update { UiState(loggedIn = false, settings = it.settings) }
    }

    // --------------------------------------------------------------- account

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.refresh()) {
                is OpacResult.Success ->
                    _state.update { it.copy(loading = false, account = r.value) }
                is OpacResult.Error ->
                    _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    fun renew(loan: Loan) {
        _state.update { it.copy(renewingId = loan.id ?: loan.title, error = null, message = null) }
        viewModelScope.launch {
            val result = repo.renew(loan)
            handleRenewResult(result, single = true)
        }
    }

    fun renewAll() {
        _state.update { it.copy(loading = true, error = null, message = null) }
        viewModelScope.launch {
            val result = repo.renewAll()
            handleRenewResult(result, single = false)
        }
    }

    private suspend fun handleRenewResult(result: OpacResult<Unit>, single: Boolean) {
        when (result) {
            is OpacResult.Success -> {
                repo.refresh()
                _state.update {
                    it.copy(
                        loading = false, renewingId = null,
                        account = repo.account.value ?: it.account,
                        message = if (single) "Medium verlängert." else "Verlängerung durchgeführt.",
                    )
                }
            }
            is OpacResult.Error ->
                _state.update { it.copy(loading = false, renewingId = null, error = result.message) }
        }
    }

    // -------------------------------------------------------------- settings

    fun setReminderDays(days: Int) = viewModelScope.launch { settingsStore.setReminderDaysBefore(days) }
    fun setAutoRenew(enabled: Boolean) = viewModelScope.launch { settingsStore.setAutoRenew(enabled) }
    fun setAutoRenewDays(days: Int) = viewModelScope.launch { settingsStore.setAutoRenewDaysBefore(days) }
    fun setNotifications(enabled: Boolean) = viewModelScope.launch { settingsStore.setNotificationsEnabled(enabled) }

    fun consumeMessages() = _state.update { it.copy(error = null, message = null) }

    companion object {
        fun factory(container: AppContainer, appContext: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(container.accountRepository, container.settingsStore, appContext) as T
        }
    }
}
