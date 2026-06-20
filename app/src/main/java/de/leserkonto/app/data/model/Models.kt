package de.leserkonto.app.data.model

import java.time.Instant
import java.time.LocalDate

/**
 * Configuration of the library OPAC to talk to.
 *
 * For the Stadtbibliothek Wehr (Komm.ONE / BIBLIOTHECA) the defaults below are
 * correct. They are kept configurable so the same app works for any other
 * Komm.ONE library branch (just change [branch]) or a self-hosted BIBLIOTHECA.
 */
data class LibraryConfig(
    val baseUrl: String = "https://bibliothek.komm.one",
    val branch: String = "wehr",
) {
    /** Entry/account URL, e.g. https://bibliothek.komm.one/wehr/Leserkonto */
    val accountUrl: String
        get() = "${baseUrl.trimEnd('/')}/$branch/Leserkonto"

    /** Catalogue search URL, e.g. https://bibliothek.komm.one/wehr/Mediensuche */
    val searchUrl: String
        get() = "${baseUrl.trimEnd('/')}/$branch/Mediensuche"
}

/** A single borrowed item shown in the reader account. */
data class Loan(
    /** Stable identifier we can use to renew this single item (if the site exposes one). */
    val id: String?,
    val title: String,
    val author: String?,
    val mediaType: String?,
    val dueDate: LocalDate?,
    /** Whether the site currently offers a renewal action for this item. */
    val renewable: Boolean,
    val renewalsRemaining: Int?,
    /** Free-text status as shown by the OPAC, e.g. "verlängerbar", "vorgemerkt". */
    val status: String?,
    val branch: String?,
    /** Cover image URL, if the catalogue provides a real one (null = use a placeholder). */
    val coverUrl: String? = null,
    /** Link to the catalogue detail page for this item, if available. */
    val detailUrl: String? = null,
) {
    /** Days until due (negative = overdue). Null if no due date could be parsed. */
    fun daysUntilDue(today: LocalDate = LocalDate.now()): Long? =
        dueDate?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }
}

/** Snapshot of the reader account at a point in time. */
data class AccountData(
    val loans: List<Loan>,
    val fees: String?,
    val fetchedAt: Instant = Instant.now(),
)

/** Result wrapper for all OPAC operations. */
sealed interface OpacResult<out T> {
    data class Success<T>(val value: T) : OpacResult<T>
    data class Error(val message: String, val cause: Throwable? = null) : OpacResult<Nothing>
}

inline fun <T> OpacResult<T>.onSuccess(block: (T) -> Unit): OpacResult<T> {
    if (this is OpacResult.Success) block(value)
    return this
}

inline fun <T> OpacResult<T>.onError(block: (String) -> Unit): OpacResult<T> {
    if (this is OpacResult.Error) block(message)
    return this
}
