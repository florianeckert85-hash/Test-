package de.leserkonto.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Minimal on-device crash capture. Installs a default uncaught-exception
 * handler that writes the last stack trace to a file, so a user can share it
 * from the login screen for troubleshooting (no logcat/PC needed). The previous
 * handler is still invoked so Android shows its usual "app stopped" behaviour.
 */
object CrashLog {

    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val report = buildString {
                    appendLine("Zeit: ${Date()}")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(sw.toString())
                }
                File(appContext.filesDir, FILE).writeText(report)
            } catch (_: Throwable) {
                // Never let crash logging cause a secondary crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? =
        File(context.applicationContext.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE).delete()
    }
}
