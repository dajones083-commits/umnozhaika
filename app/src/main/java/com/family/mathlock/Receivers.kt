package com.family.mathlock

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Запуск после перезагрузки и после обновления приложения. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs(context).enabled) {
            LockService.start(context)
            Watchdog.schedule(context)
        }
    }
}

/** Администратор устройства: без его отключения приложение нельзя удалить. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val p = Prefs(context)
        if (p.enabled && !p.recentlyAuthed()) {
            // Закрываем экран подтверждения замком на 3 минуты — нужен PIN родителя.
            p.clearFree()
            p.setTamperLock(3 * 60_000L)
            LockService.start(context)
        }
        return "Это снимет защиту «Умножайки». Отключать может только родитель."
    }
}
