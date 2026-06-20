package de.leserkonto.app.data.opac

import de.leserkonto.app.data.model.AccountData
import de.leserkonto.app.data.model.Loan
import de.leserkonto.app.data.model.OpacResult

/**
 * Abstraction over a library OPAC. The rest of the app only depends on this
 * interface, so a different library system could be supported by providing
 * another implementation.
 */
interface OpacClient {

    /** Logs in with the given card number / password. Returns Unit on success. */
    suspend fun login(username: String, password: String): OpacResult<Unit>

    /**
     * Loads the reader account (borrowed items + fees). Performs a login first
     * if the current session is not authenticated.
     */
    suspend fun loadAccount(username: String, password: String): OpacResult<AccountData>

    /** Renews a single item. */
    suspend fun renew(username: String, password: String, loan: Loan): OpacResult<Unit>

    /** Renews every renewable item ("Alles verlängern"). */
    suspend fun renewAll(username: String, password: String): OpacResult<Unit>

    /**
     * Debug helper: returns the raw HTML of the account page after login.
     * Used by the in-app "HTML exportieren" button so the parsing selectors
     * can be verified/tuned against the real site.
     */
    suspend fun captureAccountHtml(username: String, password: String): OpacResult<String>
}
