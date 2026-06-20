package de.leserkonto.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-schedules the periodic sync after the device boots. The [SyncWorker] itself
 * checks for stored credentials and no-ops if there are none, so it is safe to
 * always (re)schedule here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SyncScheduler.schedule(context.applicationContext)
        }
    }
}
