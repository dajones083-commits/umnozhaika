package com.family.mathlock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Все настройки и состояние приложения (SharedPreferences). */
class Prefs(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("mathlock", Context.MODE_PRIVATE)

    private fun getI(k: String, d: Int): Int = sp.getInt(k, d)
    private fun putI(k: String, v: Int) {
        sp.edit().putInt(k, v).apply()
    }
    private fun getB(k: String, d: Boolean): Boolean = sp.getBoolean(k, d)
    private fun putB(k: String, v: Boolean) {
        sp.edit().putBoolean(k, v).apply()
    }
    private fun getS(k: String, d: String): String = sp.getString(k, d) ?: d
    private fun putS(k: String, v: String) {
        sp.edit().putString(k, v).apply()
    }

    // ---------- Настройки родителя ----------
    var enabled: Boolean
        get() = getB("enabled", false)
        set(v) = putB("enabled", v)

    /** Начало блокировки, минуты от полуночи (15:00). */
    var startMinutes: Int
        get() = getI("start", 15 * 60)
        set(v) = putI("start", v)

    /** Конец блокировки, минуты от полуночи (07:00). */
    var endMinutes: Int
        get() = getI("end", 7 * 60)
        set(v) = putI("end", v)

    var weekendAllDay: Boolean
        get() = getB("weekendAllDay", true)
        set(v) = putB("weekendAllDay", v)

    var secondsPerQuestion: Int
        get() = getI("secPerQ", 5)
        set(v) = putI("secPerQ", v)

    var minutesPerQuestion: Int
        get() = getI("minPerQ", 3)
        set(v) = putI("minPerQ", v)

    var minFactor: Int
        get() = getI("minF", 2)
        set(v) = putI("minF", v)

    var maxFactor: Int
        get() = getI("maxF", 10)
        set(v) = putI("maxF", v)

    /** Ошибка (неверный ответ) сбрасывает серию. */
    var wrongResets: Boolean
        get() = getB("wrongResets", true)
        set(v) = putB("wrongResets", v)

    /** Штраф за неверный ответ, минут. */
    var penaltyMinutes: Int
        get() = getI("penalty", 2)
        set(v) = putI("penalty", v)

    var durationsCsv: String
        get() = getS("durations", "15,30,45,60")
        set(v) = putS("durations", v)

    val durations: List<Int>
        get() = durationsCsv.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
            .ifEmpty { listOf(30) }

    /** 0 = без лимита. */
    var dailyLimitMinutes: Int
        get() = getI("dailyLimit", 0)
        set(v) = putI("dailyLimit", v)

    var parentPhone: String
        get() = getS("phone", "")
        set(v) = putS("phone", v)

    var pin: String
        get() = getS("pin", "")
        set(v) = putS("pin", v)

    /** Секретный код родителя: вводится вместо ответа в любом примере. */
    var secretCode: String
        get() = getS("secret", "2893")
        set(v) = putS("secret", v)

    var autoUpdate: Boolean
        get() = getB("autoUpdate", true)
        set(v) = putB("autoUpdate", v)

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) {
            sp.edit().putLong("lastUpdateCheck", v).apply()
        }

    // ---------- PIN ----------
    fun pinLockedMs(): Long {
        val r = sp.getLong("pinLockUntil", 0L) - System.currentTimeMillis()
        return if (r > 0) r else 0L
    }

    fun tryPin(input: String): Boolean {
        if (pinLockedMs() > 0) return false
        if ((pin.isNotEmpty() && input == pin) || (secretCode.isNotEmpty() && input == secretCode)) {
            sp.edit().putInt("pinFails", 0).putLong("pinOkAt", SystemClock.elapsedRealtime()).apply()
            return true
        }
        val fails = getI("pinFails", 0) + 1
        val e = sp.edit().putInt("pinFails", fails)
        if (fails >= 5) {
            e.putLong("pinLockUntil", System.currentTimeMillis() + 120_000L)
            e.putInt("pinFails", 0)
        }
        e.apply()
        return false
    }

    fun markAuthed() {
        sp.edit().putLong("pinOkAt", SystemClock.elapsedRealtime()).apply()
    }

    /** PIN вводили в последние 5 минут. */
    fun recentlyAuthed(): Boolean {
        val t = sp.getLong("pinOkAt", -1L)
        return t > 0 && SystemClock.elapsedRealtime() - t in 0L..(5 * 60_000L)
    }

    // ---------- Таймеры (на монотонных часах: перевод часов не помогает) ----------
    private fun bootCount(): Int = try {
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0)
    } catch (e: Exception) {
        0
    }

    private fun remaining(key: String): Long {
        if (sp.getInt(key + "_boot", -1) != bootCount()) return 0L
        val dur = sp.getLong(key + "_dur", 0L)
        val r = sp.getLong(key, 0L) - SystemClock.elapsedRealtime()
        return if (r in 1L..dur) r else 0L
    }

    private fun setTimer(key: String, ms: Long) {
        sp.edit()
            .putLong(key, SystemClock.elapsedRealtime() + ms)
            .putLong(key + "_dur", ms)
            .putInt(key + "_boot", bootCount())
            .apply()
    }

    /** Сколько ещё телефон разблокирован. */
    fun freeRemainingMs(): Long = remaining("free")
    fun setFree(ms: Long) = setTimer("free", ms)
    fun clearFree() = setTimer("free", 0L)
    fun extendFree(ms: Long) {
        if (freeRemainingMs() < ms) setFree(ms)
    }
    fun freeToken(): Long = sp.getLong("free", 0L)

    fun testLockActive(): Boolean = remaining("test") > 0
    fun setTestLock(ms: Long) = setTimer("test", ms)

    fun tamperLockActive(): Boolean = remaining("tamper") > 0
    fun setTamperLock(ms: Long) = setTimer("tamper", ms)
    fun clearTamper() = setTimer("tamper", 0L)

    // ---------- Статистика за день (сутки считаются от времени окончания блокировки) ----------
    private fun dayKey(): String {
        val shifted = System.currentTimeMillis() - endMinutes * 60_000L
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(shifted))
    }

    private fun dayInt(k: String): Int = if (getS("day", "") == dayKey()) getI(k, 0) else 0

    val earnedToday: Int
        get() = dayInt("earned")
    val solvedToday: Int
        get() = dayInt("solved")
    val callsToday: Int
        get() = dayInt("calls")
    val mistakesToday: Int
        get() = dayInt("wrong")

    /** Накопленный штраф (минуты), снимается после успешной разблокировки. */
    val pendingPenalty: Int
        get() = dayInt("pendingPenalty")

    private fun bump(k: String, delta: Int, resetPenalty: Boolean = false) {
        val d = dayKey()
        val same = getS("day", "") == d
        val e = sp.edit()
        if (!same) {
            e.putString("day", d)
            for (key in listOf("earned", "solved", "calls", "wrong", "pendingPenalty")) e.putInt(key, 0)
        }
        val cur = if (same) getI(k, 0) else 0
        e.putInt(k, cur + delta)
        if (resetPenalty) e.putInt("pendingPenalty", 0)
        e.apply()
    }

    fun addEarned(minutes: Int, solved: Int) {
        bump("earned", minutes, resetPenalty = true)
        bump("solved", solved)
    }

    fun addCall() = bump("calls", 1)

    fun addPenalty(minutes: Int) {
        bump("wrong", 1)
        if (minutes > 0) bump("pendingPenalty", minutes)
    }

    fun clearPenalty() {
        if (getS("day", "") == dayKey()) putI("pendingPenalty", 0)
    }

    fun minutesLeftToday(): Int =
        if (dailyLimitMinutes <= 0) Int.MAX_VALUE else maxOf(0, dailyLimitMinutes - earnedToday)

    // ---------- Слабые места: на каких примерах чаще ошибается ----------
    fun mistakes(a: Int, b: Int): Int = getI("m_${a}_$b", 0)

    fun recordAnswer(a: Int, b: Int, correct: Boolean) {
        val k = "m_${a}_$b"
        val cur = getI(k, 0)
        putI(k, if (correct) maxOf(0, cur - 1) else minOf(5, cur + 1))
    }
}
