package com.family.mathlock

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * «Сторож»: если Android выгрузил службу замка, она поднимается обратно сама.
 * Два независимых механизма: будильник каждые 5 минут и системная задача каждые 15 минут.
 */
object Watchdog {
    private const val JOB_ID = 4242
    private const val ALARM_REQ = 7
    private const val INTERVAL_MS = 5 * 60_000L

    private fun alarmIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app, ALARM_REQ, Intent(app, WatchdogReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(ctx: Context, delayMs: Long = INTERVAL_MS) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(app)
        val at = SystemClock.elapsedRealtime() + delayMs
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } catch (e2: Exception) {
            }
        }
        scheduleJob(app)
    }

    private fun scheduleJob(app: Context) {
        val js = app.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(app, WatchdogJob::class.java))
            .setPeriodic(15 * 60_000L)
            .setPersisted(true)
            .build()
        try {
            js.schedule(job)
        } catch (e: Exception) {
        }
    }

    fun cancel(ctx: Context) {
        val app = ctx.applicationContext
        try {
            app.getSystemService(AlarmManager::class.java).cancel(alarmIntent(app))
            app.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        } catch (e: Exception) {
        }
    }

    /** Проверить, жива ли служба, и при необходимости поднять её. */
    fun kick(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.enabled) {
            cancel(ctx)
            return
        }
        if (!LockService.isRunning) p.addRestart()
        LockService.start(ctx)
        schedule(ctx)
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Watchdog.kick(context)
    }
}

class WatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Watchdog.kick(this)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
