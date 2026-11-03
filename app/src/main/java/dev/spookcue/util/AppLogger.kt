package dev.spookcue.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-reliability file & logcat logger.
 * Writes key sync, VAD, and STT diagnostic logs to device internal storage
 * (`files/logs/spookcue.log`) so they are never lost to Android logcat circular buffer roll-over.
 */
object AppLogger {

    private const val MAX_LOG_SIZE_BYTES = 2 * 1024 * 1024L // 2 MB
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            val dir = File(context.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, "spookcue.log")

            // Rotate if oversized
            if (logFile != null && logFile!!.exists() && logFile!!.length() > MAX_LOG_SIZE_BYTES) {
                val oldFile = File(dir, "spookcue.log.old")
                if (oldFile.exists()) oldFile.delete()
                logFile!!.renameTo(oldFile)
                logFile = File(dir, "spookcue.log")
            }
            writeToFile("I", "AppLogger", "=== SpookCue Diagnostic Log Session Started ===")
        }
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        writeToFile("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        writeToFile("I", tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        val fullMsg = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        writeToFile("W", tag, fullMsg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        val fullMsg = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        writeToFile("E", tag, fullMsg)
    }

    private fun writeToFile(level: String, tag: String, message: String) {
        val target = logFile ?: return
        synchronized(lock) {
            try {
                val timestamp = dateFormat.format(Date())
                FileWriter(target, true).use { writer ->
                    writer.write("$timestamp $level/$tag: $message\n")
                }
            } catch (_: Exception) {
                // Best-effort file write
            }
        }
    }
}
