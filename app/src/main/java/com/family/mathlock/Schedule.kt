package com.family.mathlock

import android.content.Context
import android.provider.Settings
import java.util.Calendar
import java.util.Locale

object Schedule {
    fun fmt(min: Int): String = String.format(Locale.US, "%02d:%02d", min / 60, min % 60)

    /** Автоматическое время и часовой пояс включены (иначе ребёнок может «перевести часы»). */
    fun autoTimeOk(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        return try {
            Settings.Global.getInt(cr, Settings.Global.AUTO_TIME, 1) == 1 &&
                Settings.Global.getInt(cr, Settings.Global.AUTO_TIME_ZONE, 1) == 1
        } catch (e: Exception) {
            true
        }
    }

    fun inWindow(p: Prefs, cal: Calendar = Calendar.getInstance()): Boolean {
        if (p.weekendAllDay) {
            val d = cal.get(Calendar.DAY_OF_WEEK)
            if (d == Calendar.SATURDAY || d == Calendar.SUNDAY) return true
        }
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val s = p.startMinutes
        val e = p.endMinutes
        return when {
            s == e -> true
            s < e -> m in s until e
            else -> m >= s || m < e
        }
    }

    /** Расписание активно сейчас. Если автовремя выключено — считаем активным всегда. */
    fun isActiveNow(ctx: Context, p: Prefs): Boolean = !autoTimeOk(ctx) || inWindow(p)

    fun shouldLock(ctx: Context, p: Prefs): Boolean {
        if (!p.enabled) return false
        if (p.tamperLockActive()) return true
        if (p.freeRemainingMs() > 0) return false
        return p.testLockActive() || isActiveNow(ctx, p)
    }

    /** Сколько миллисекунд до ближайшего окончания блокировки (например, до 07:00). */
    fun msUntilEnd(p: Prefs): Long {
        val now = System.currentTimeMillis()
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, p.endMinutes / 60)
        c.set(Calendar.MINUTE, p.endMinutes % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis - now
    }
}
