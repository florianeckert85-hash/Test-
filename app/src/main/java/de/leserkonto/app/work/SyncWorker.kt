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

        // 2) Optional auto-renewal for items inside the configured window:
        //    from autoRenewDaysBefore days before … to autoRenewDaysAfter after.
        var renewedCount = 0
        if (settings.autoRenew) {
            val lower = -settings.autoRenewDaysAfter.toLong()
            val upper = settings.autoRenewDaysBefore.toLong()
            val dueForRenew = account.loans.filter { loan ->
                loan.renewable && (loan.daysUntilDue()?.let { it in lower..upper } == true)
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

        // 3) Multi-stage reminders: notify once per crossed stage, per item.
        if (settings.notificationsEnabled && settings.reminderOffsets.isNotEmpty()) {
            val current = repo.account.value ?: account
            val alreadyNotified = container.settingsStore.notifiedMarkers()
            val activeMarkers = mutableSetOf<String>()
            val newlyDue = mutableListOf<Loan>()
            for (loan in current.loans) {
                val days = loan.daysUntilDue() ?: continue
                val crossed = settings.reminderOffsets.filter { days <= it }
                if (crossed.isEmpty()) continue
                val key = "${loan.title}|${loan.dueDate}"
                val markers = crossed.map { "$key|$it" }
                activeMarkers.addAll(markers)
                if (markers.any { it !in alreadyNotified }) newlyDue.add(loan)
            }
            if (newlyDue.isNotEmpty()) {
                container.notificationHelper.notifyDueSoon(newlyDue.sortedBy { it.dueDate })
            }
            // Keep only markers for items/stages still relevant (prunes gone items).
            container.settingsStore.replaceNotifiedMarkers(activeMarkers)
        }

        return Result.success()
    }
}
