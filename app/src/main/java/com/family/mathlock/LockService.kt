package com.family.mathlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.widget.Toast

/**
 * Постоянная служба: раз в секунду (пока экран включён) проверяет,
 * нужно ли показывать замок, и показывает/убирает оверлей.
 */
class LockService : Service(), LockOverlay.Host {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: LockOverlay? = null
    private var lastText = ""
    private var warnedFor = -1L
    private var screenOn = true

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            if (screenOn) handler.postDelayed(this, 1000)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    handler.removeCallbacks(tick)
                    overlay?.resetToMenu()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    restartTick()
                }
                else -> restartTick()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
        val n = buildNotification("Умножайка следит за временем")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        val f = IntentFilter()
        f.addAction(Intent.ACTION_SCREEN_ON)
        f.addAction(Intent.ACTION_SCREEN_OFF)
        f.addAction(Intent.ACTION_USER_PRESENT)
        f.addAction(Intent.ACTION_TIME_CHANGED)
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }
        restartTick()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        restartTick()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
        }
        overlay?.hide()
        overlay = null
        super.onDestroy()
    }

    private fun restartTick() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun evaluate() {
        if (!prefs.enabled) {
            overlay?.hide()
            overlay = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val locked = Schedule.shouldLock(this, prefs)
        if (locked) showOverlay() else hideOverlay()

        maybeAutoUpdate(locked)

        val free = prefs.freeRemainingMs()
        val active = Schedule.isActiveNow(this, prefs)
        if (!locked && active && free in 1L..60_000L && warnedFor != prefs.freeToken()) {
            warnedFor = prefs.freeToken()
            Toast.makeText(this, "⏳ Осталась 1 минута!", Toast.LENGTH_LONG).show()
            vibrate()
        }

        val text = when {
            locked -> "Телефон заблокирован — нужно решить примеры"
            free > 0 && active -> "Открыто ещё ${(free + 59_999) / 60_000} мин"
            else -> "Замок включится в ${Schedule.fmt(prefs.startMinutes)}"
        }
        if (text != lastText) {
            lastText = text
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    /** Раз в 6 часов проверяем обновление (только когда замок не на экране). */
    private fun maybeAutoUpdate(locked: Boolean) {
        if (!prefs.autoUpdate || locked || !screenOn) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < 6 * 3_600_000L && now >= prefs.lastUpdateCheck) return
        prefs.lastUpdateCheck = now
        Updater.checkAndInstall(this, userInitiated = false) { }
    }

    private fun showOverlay() {
        if (overlay?.isShowing == true) return
        if (!Settings.canDrawOverlays(this)) return
        val o = LockOverlay(this, prefs, this)
        overlay = if (o.show()) o else null
    }

    private fun hideOverlay() {
        overlay?.hide()
        overlay = null
    }

    // ---------------------------------------------------------------- Host

    override fun grant(minutes: Int, solved: Int) {
        prefs.setFree(minutes * 60_000L)
        prefs.addEarned(minutes, solved)
        Toast.makeText(this, "Молодец! Телефон открыт на $minutes мин 🎉", Toast.LENGTH_LONG).show()
        handler.postDelayed({ evaluate() }, 1200)
    }

    override fun parentUnlock(minutes: Int) {
        prefs.clearTamper()
        prefs.setFree(minutes * 60_000L)
        handler.post { evaluate() }
    }

    override fun parentPauseUntilMorning() {
        prefs.clearTamper()
        prefs.setFree(Schedule.msUntilEnd(prefs))
        handler.post { evaluate() }
    }

    override fun parentOpenSettings() {
        parentUnlock(10)
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }

    override fun call() {
        prefs.addCall()
        prefs.extendFree(2 * 60_000L)
        val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + prefs.parentPhone))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (e: Exception) {
        }
        handler.post { evaluate() }
    }

    // ---------------------------------------------------------------- misc

    private fun vibrate() {
        try {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Замок", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle("Умножайка")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val CHANNEL = "lock"
        const val NOTIF_ID = 1

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, LockService::class.java))
            } catch (e: Exception) {
            }
        }
    }
}
