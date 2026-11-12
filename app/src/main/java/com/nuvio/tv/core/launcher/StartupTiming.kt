package com.nuvio.tv.core.launcher

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Milliseconds since this process started, at the points that decide how soon Nuvio takes over after a restart:
 * Application start, the dependency graph being ready, the end of Application.onCreate, the accessibility service
 * binding, and the takeover itself. Written to a small file as well as the log, because the system log keeps only
 * the last few minutes and the whole question is what happened in the first 30 seconds after boot.
 *
 * Read it with: adb shell cat /sdcard/Android/data/com.nuvio.tv.launcher/files/startup-timing.txt
 */
object StartupTiming {
    private const val TAG = "StartupTiming"
    private val lines = ArrayList<String>()
    @Volatile private var appContext: Context? = null

    fun mark(context: Context?, label: String) {
        val since = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        val line = "+$since ms  [${Thread.currentThread().name}]  $label"
        Log.i(TAG, line)
        if (context != null && appContext == null) appContext = context.applicationContext
        synchronized(lines) {
            lines += line
            val ctx = appContext ?: return
            runCatching {
                val dir = ctx.getExternalFilesDir(null) ?: return
                File(dir, "startup-timing.txt").writeText(
                    "process start (uptime ms): ${Process.getStartElapsedRealtime()}\n" + lines.joinToString("\n") + "\n"
                )
            }
        }
    }
}
