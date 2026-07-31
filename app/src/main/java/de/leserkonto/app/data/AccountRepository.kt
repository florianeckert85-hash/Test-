package de.leserkonto.app.data

import de.leserkonto.app.data.model.AccountData
import de.leserkonto.app.data.model.Loan
import de.leserkonto.app.data.model.OpacResult
import de.leserkonto.app.data.opac.OpacClient
import de.leserkonto.app.data.store.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the reader account. Combines the stored
 * credentials with the [OpacClient] and caches the last successful snapshot in
 * memory so the UI (and the background worker) share one consistent state.
 *
 * All credential access is suspend (runs off the main thread inside
 * [CredentialStore]) to avoid Keystore-induced ANRs.
 */
class AccountRepository(
    private val client: OpacClient,
    private val credentials: CredentialStore,
) {
    private val _account = MutableStateFlow<AccountData?>(null)
    val account: StateFlow<AccountData?> = _account.asStateFlow()

    suspend fun hasCredentials(): Boolean = credentials.hasCredentials()

    suspend fun saveCredentials(username: String, password: String) =
        credentials.save(username, password)

    suspend fun logout() {
        credentials.clear()
        _account.value = null
    }

    /** Verifies credentials by attempting a login. Does not persist them. */
    suspend fun verify(username: String, password: String): OpacResult<Unit> =
        client.login(username, password)

    /** Refreshes the account from the OPAC and caches it. */
    suspend fun refresh(): OpacResult<AccountData> {
        val user = credentials.username() ?: return OpacResult.Error("Keine Zugangsdaten gespeichert")
        val pass = credentials.password() ?: return OpacResult.Error("Keine Zugangsdaten gespeichert")
        val result = client.loadAccount(user, pass)
        if (result is OpacResult.Success) _account.value = result.value
        return result
    }

    suspend fun renew(loan: Loan): OpacResult<Unit> {
        val user = credentials.username() ?: return OpacResult.Error("Keine Zugangsdaten gespeichert")
        val pass = credentials.password() ?: return OpacResult.Error("Keine Zugangsdaten gespeichert")
        return client.renew(user, pass, loan)
    }

    suspend fun renewAll(): OpacResult<Unit> {
        val user = credentials.username() ?: return OpacResult.Error("Keine Zugangsdaten gespeichert")
        val pass = credentials.password() ?: return OpacResult.Error("Keine Zugangsdaten gespeichert")
        return client.renewAll(user, pass)
    }

    suspend fun captureHtml(): OpacResult<String> {
        val user = credentials.username() ?: return OpacResult.Error("Keine Zugangsdaten gespeichert")
        val pass = credentials.password() ?: return OpacResult.Error("Keine Zugangsdaten gespeichert")
        return client.captureAccountHtml(user, pass)
    }

    /** Diagnostic probe of the login page; credentials are taken as typed (may be blank). */
    suspend fun diagnose(username: String, password: String): OpacResult<String> =
        client.diagnoseLogin(username, password)
}
