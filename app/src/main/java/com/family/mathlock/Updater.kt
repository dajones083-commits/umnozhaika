package com.family.mathlock

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Обновление из GitHub Releases: version.txt — номер последней сборки,
 * Umnozhaika.apk — сам файл. Установка через PackageInstaller.
 */
object Updater {
    private const val BASE = "https://github.com/dajones083-commits/umnozhaika/releases/download/latest/"
    private val busy = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    fun currentVersion(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
        }
    }

    /**
     * Проверить и, если есть новая версия, скачать и установить.
     * [onStatus] вызывается в главном потоке с понятным текстом.
     */
    fun checkAndInstall(ctx: Context, userInitiated: Boolean, onStatus: (String) -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            onStatus("Проверка уже идёт…")
            return
        }
        val app = ctx.applicationContext
        fun say(s: String) {
            main.post { onStatus(s) }
        }
        Thread {
            try {
                say("Проверяю обновления…")
                val remote = fetchText(BASE + "version.txt")?.trim()?.toLongOrNull()
                val cur = currentVersion(app)
                if (remote == null) {
                    say("Не удалось проверить: нет интернета или репозиторий на GitHub не публичный")
                } else if (remote <= cur) {
                    say("Установлена последняя версия (сборка №$cur)")
                } else if (!canInstall(app)) {
                    say("Есть новая версия (сборка №$remote). Разрешите «Умножайке» устанавливать приложения и нажмите ещё раз")
                    if (userInitiated) main.post { openInstallPermission(app) }
                } else {
                    say("Скачиваю сборку №$remote…")
                    val file = File(app.cacheDir, "update.apk")
                    if (!download(BASE + "Umnozhaika.apk", file)) {
                        say("Не удалось скачать обновление")
                    } else {
                        say("Устанавливаю сборку №$remote…")
                        install(app, file)
                    }
                }
            } catch (e: Exception) {
                say("Ошибка обновления: ${e.message}")
            } finally {
                busy.set(false)
            }
        }.start()
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Umnozhaika-Updater")
        c.useCaches = false
        return c
    }

    private fun fetchText(url: String): String? {
        val c = open(url)
        return try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, dest: File): Boolean {
        val c = open(url)
        return try {
            if (c.responseCode != 200) return false
            c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest.length() > 100_000
        } catch (e: Exception) {
            false
        } finally {
            c.disconnect()
        }
    }

    private fun install(ctx: Context, file: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: если прошлую версию ставило само приложение — обновится без вопросов.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("app.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            session.commit(pending.intentSender)
        }
    }
}

/** Ответ системы об установке: просит подтверждение или сообщает результат. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "неизвестная ошибка"
                Toast.makeText(context, "Обновление не установлено: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
