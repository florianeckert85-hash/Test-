package de.leserkonto.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.leserkonto.app.LeserkontoApplication
import de.leserkonto.app.data.model.Loan
import de.leserkonto.app.data.model.OpacResult
import kotlinx.coroutines.flow.first

/**
 * Periodic background job: refreshes the account, optionally auto-renews items
 * that are about to be due, and fires a local reminder notification for items
 * that are due within the configured lead time.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val container get() = (applicationContext as LeserkontoApplication).container

    override suspend fun doWork(): Result {
        val repo = container.accountRepository
        if (!repo.hasCredentials()) return Result.success()

        val settings = container.settingsStore.settings.first()

        // 1) Refresh from the OPAC.
        val account = when (val r = repo.refresh()) {
            is OpacResult.Success -> r.value
            is OpacResult.Error -> return Result.retry()
        }

        // 2) Optional auto-renewal for items due within autoRenewDaysBefore.
        var renewedCount = 0
        if (settings.autoRenew) {
            val dueForRenew = account.loans.filter { loan ->
                loan.renewable && (loan.daysUntilDue()?.let { it in 0..settings.autoRenewDaysBefore.toLong() } == true)
            }
            for (loan in dueForRenew) {
                if (repo.renew(loan) is OpacResult.Success) renewedCount++
            }
            if (renewedCount > 0) {
                // Re-fetch so the reminder below reflects the new due dates.
                (repo.refresh() as? OpacResult.Success)?.let { /* cache updated */ }
                container.notificationHelper.notifyAutoRenewed(renewedCount)
            }
        }

        // 3) Reminder for everything still due soon (or overdue).
        if (settings.notificationsEnabled) {
            val current = repo.account.value ?: account
            val dueSoon = current.loans.filter { loan: Loan ->
                loan.daysUntilDue()?.let { it <= settings.reminderDaysBefore } == true
            }.sortedBy { it.dueDate }
            container.notificationHelper.notifyDueSoon(dueSoon)
        }

        return Result.success()
    }
}
