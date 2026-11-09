package com.family.mathlock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Настройки родителя (защищены PIN-кодом). */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private var built = false
    private var dialogShown = false
    private var ignoreSwitch = false

    private lateinit var col: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var enableSwitch: Switch
    private lateinit var permOverlay: Button
    private lateinit var permNotif: Button
    private lateinit var permBattery: Button
    private lateinit var permAdmin: Button
    private lateinit var permTime: Button
    private lateinit var startBtn: Button
    private lateinit var endBtn: Button
    private lateinit var weekendCb: CheckBox
    private lateinit var wrongCb: CheckBox
    private lateinit var minField: EditText
    private lateinit var maxField: EditText
    private lateinit var secField: EditText
    private lateinit var mpqField: EditText
    private lateinit var penaltyField: EditText
    private lateinit var durField: EditText
    private lateinit var limitField: EditText
    private lateinit var phoneField: EditText
    private lateinit var pinField: EditText
    private lateinit var secretField: EditText
    private lateinit var statsText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
    }

    override fun onResume() {
        super.onResume()
        if (hasCode() && !prefs.recentlyAuthed()) {
            if (!dialogShown) askPin()
            return
        }
        showUi()
    }

    private fun showUi() {
        if (!built) buildUi()
        refresh()
        if (prefs.enabled) LockService.start(this)
    }

    // ---------------------------------------------------------------- PIN

    private fun askPin() {
        dialogShown = true
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        et.hint = "PIN"
        et.gravity = Gravity.CENTER
        val locked = prefs.pinLockedMs()
        AlertDialog.Builder(this)
            .setTitle("Код родителя")
            .setMessage(
                if (locked > 0) "Слишком много попыток. Подождите ${locked / 1000 + 1} сек."
                else "Введите PIN, чтобы открыть настройки"
            )
            .setView(et)
            .setCancelable(false)
            .setPositiveButton("Войти") { _, _ ->
                dialogShown = false
                if (prefs.tryPin(et.text.toString().trim())) {
                    showUi()
                } else {
                    toast("Неверный код")
                    askPin()
                }
            }
            .setNegativeButton("Выход") { _, _ ->
                dialogShown = false
                finish()
            }
            .show()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        built = true
        col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(36), dp(18), dp(48))
        val scroll = ScrollView(this)
        scroll.addView(col)
        setContentView(scroll)

        val title = TextView(this)
        title.text = "🔒 Умножайка"
        title.textSize = 28f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(C.BG)
        col.addView(title)
        note("Настройки для родителя. В заданные часы телефон блокируется — чтобы открыть его, ребёнок решает примеры на умножение.")

        statusText = note("")
        enableSwitch = Switch(this)
        enableSwitch.text = "Защита включена"
        enableSwitch.textSize = 18f
        enableSwitch.setOnCheckedChangeListener { _, on -> if (!ignoreSwitch) onToggle(on) }
        col.addView(enableSwitch, mlp(12))

        header("1. Разрешения (все должны быть ✅)")
        permOverlay = action("") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        permNotif = action("") { requestNotif() }
        permBattery = action("") { requestBattery() }
        permAdmin = action("") { requestAdmin() }
        permTime = action("") { safeStart(Intent(Settings.ACTION_DATE_SETTINGS)) }

        header("2. Расписание")
        startBtn = action("") { pickTime(true) }
        endBtn = action("") { pickTime(false) }
        weekendCb = check("В выходные (сб и вс) блокировать весь день", prefs.weekendAllDay)

        header("3. Примеры")
        minField = number("Множители: от", prefs.minFactor)
        maxField = number("Множители: до", prefs.maxFactor)
        secField = number("Секунд на один ответ", prefs.secondsPerQuestion)
        mpqField = number("Минут за один решённый пример", prefs.minutesPerQuestion)
        penaltyField = number("Штраф за неправильный ответ, минут", prefs.penaltyMinutes)
        durField = field("Варианты времени, минуты через запятую", prefs.durationsCsv, InputType.TYPE_CLASS_TEXT)
        limitField = number("Лимит в день, минут (0 = без лимита)", prefs.dailyLimitMinutes)
        wrongCb = check("Неправильный ответ — начать все примеры заново", prefs.wrongResets)

        header("4. Родитель")
        phoneField = field("Телефон для кнопки «Позвонить» на замке", prefs.parentPhone, InputType.TYPE_CLASS_PHONE)
        pinField = field(
            "PIN родителя (4 цифры). Оставьте пустым, чтобы не менять",
            "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )

        secretField = field(
            "Секретный код (вводится вместо ответа в любом примере — сбрасывает примеры и штраф, открывает меню родителя)",
            prefs.secretCode,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )

        action("💾 Сохранить", C.ACCENT, Color.BLACK) { save() }
        action("🧪 Проверить замок прямо сейчас") { testLock() }

        header("Статистика за сегодня")
        statsText = note("")
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val notifOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminOk = dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
        val timeOk = Schedule.autoTimeOk(this)

        setPerm(permOverlay, overlayOk, "Показ поверх других окон (обязательно)")
        setPerm(permNotif, notifOk, "Уведомления (сколько минут осталось)")
        setPerm(permBattery, batteryOk, "Работа без ограничений батареи")
        setPerm(permAdmin, adminOk, "Защита от удаления")
        setPerm(permTime, timeOk, "Автоматическое время и часовой пояс")

        startBtn.text = "Начало блокировки: ${Schedule.fmt(prefs.startMinutes)}"
        endBtn.text = "Конец блокировки: ${Schedule.fmt(prefs.endMinutes)}"
        pinField.hint = if (prefs.pin.isEmpty()) "Придумайте PIN" else "PIN задан ✔"

        ignoreSwitch = true
        enableSwitch.isChecked = prefs.enabled
        ignoreSwitch = false

        statusText.text = status()
        statsText.text = stats()
    }

    private fun status(): String {
        if (!prefs.enabled) return "Сейчас: защита выключена"
        val free = prefs.freeRemainingMs()
        val main = when {
            Schedule.shouldLock(this, prefs) -> "Сейчас: телефон заблокирован"
            free > 0 && Schedule.isActiveNow(this, prefs) -> "Сейчас: открыто ещё ${(free + 59_999) / 60_000} мин"
            else -> "Сейчас: вне расписания, замок включится в ${Schedule.fmt(prefs.startMinutes)}"
        }
        val warn = if (!Schedule.autoTimeOk(this)) "\n⚠ Автовремя выключено — замок действует круглосуточно" else ""
        return main + warn
    }

    private fun stats(): String {
        val list = ArrayList<Triple<Int, Int, Int>>()
        for (a in 1..20) for (b in 1..20) {
            val m = prefs.mistakes(a, b)
            if (m > 0) list.add(Triple(a, b, m))
        }
        val weak = list.sortedByDescending { it.third }.take(10)
            .joinToString(", ") { "${it.first}×${it.second}" }
        return "Заработано минут: ${prefs.earnedToday}\n" +
            "Решено примеров: ${prefs.solvedToday}\n" +
            "Ошибок: ${prefs.mistakesToday}\n" +
            "Звонков с замка: ${prefs.callsToday} из ${LockOverlay.MAX_CALLS}\n" +
            "Трудные примеры: " + weak.ifEmpty { "пока нет" }
    }

    // ---------------------------------------------------------------- actions

    private fun onToggle(on: Boolean) {
        if (on) {
            val problem = when {
                !hasCode() -> "Сначала задайте PIN или секретный код и нажмите «Сохранить»"
                !Settings.canDrawOverlays(this) -> "Сначала разрешите показ поверх других окон"
                else -> null
            }
            if (problem != null) {
                toast(problem)
                refresh()
                return
            }
            prefs.enabled = true
            LockService.start(this)
        } else {
            prefs.enabled = false
            stopService(Intent(this, LockService::class.java))
        }
        refresh()
    }

    private fun num(et: EditText, lo: Int, hi: Int, def: Int): Int =
        (et.text.toString().trim().toIntOrNull() ?: def).coerceIn(lo, hi)

    private fun save() {
        val newPin = pinField.text.toString().trim()
        if (newPin.isNotEmpty() && !Regex("\\d{4}").matches(newPin)) {
            toast("PIN должен состоять из 4 цифр")
            return
        }
        prefs.minFactor = num(minField, 1, 20, 2)
        prefs.maxFactor = num(maxField, prefs.minFactor, 20, 10)
        prefs.secondsPerQuestion = num(secField, 2, 60, 5)
        prefs.minutesPerQuestion = num(mpqField, 1, 60, 3)
        prefs.penaltyMinutes = num(penaltyField, 0, 60, 2)
        val durs = durField.text.toString().split(",", " ", ";")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..600 }
            .distinct()
            .sorted()
        prefs.durationsCsv = (if (durs.isEmpty()) listOf(30) else durs).joinToString(",")
        prefs.dailyLimitMinutes = num(limitField, 0, 1440, 0)
        prefs.wrongResets = wrongCb.isChecked
        prefs.weekendAllDay = weekendCb.isChecked
        prefs.parentPhone = phoneField.text.toString().trim()
        val newSecret = secretField.text.toString().trim()
        if (newSecret.isNotEmpty() && !Regex("\\d{4,6}").matches(newSecret)) {
            toast("Секретный код — от 4 до 6 цифр")
            return
        }
        prefs.secretCode = newSecret
        if (newPin.isNotEmpty()) {
            prefs.pin = newPin
            prefs.markAuthed()
            pinField.setText("")
        }

        minField.setText(prefs.minFactor.toString())
        maxField.setText(prefs.maxFactor.toString())
        secField.setText(prefs.secondsPerQuestion.toString())
        mpqField.setText(prefs.minutesPerQuestion.toString())
        penaltyField.setText(prefs.penaltyMinutes.toString())
        durField.setText(prefs.durationsCsv)
        limitField.setText(prefs.dailyLimitMinutes.toString())

        if (hasCode()) prefs.markAuthed()
        toast(if (!hasCode()) "Сохранено. Не забудьте задать PIN!" else "Сохранено")
        refresh()
        if (prefs.enabled) LockService.start(this)
    }

    private fun testLock() {
        if (!prefs.enabled) {
            toast("Сначала включите защиту")
            return
        }
        prefs.clearFree()
        prefs.setTestLock(2 * 60_000L)
        LockService.start(this)
        toast("Замок появится через секунду. Выйти: «Родитель» → PIN")
    }

    private fun pickTime(start: Boolean) {
        val cur = if (start) prefs.startMinutes else prefs.endMinutes
        TimePickerDialog(this, { _, h, m ->
            val v = h * 60 + m
            if (start) {
                prefs.startMinutes = v
            } else {
                prefs.endMinutes = v
            }
            refresh()
        }, cur / 60, cur % 60, true).show()
    }

    private fun requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun requestBattery() {
        val ok = safeStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        if (!ok) safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun requestAdmin() {
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, AdminReceiver::class.java))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Защищает «Умножайку» от удаления ребёнком.")
        safeStart(i)
    }

    // ---------------------------------------------------------------- helpers

    private fun hasCode(): Boolean = prefs.pin.isNotEmpty() || prefs.secretCode.isNotEmpty()

    private fun safeStart(i: Intent): Boolean = try {
        startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun mlp(top: Int = 4): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(MATCH, WRAP)
        p.topMargin = dp(top)
        return p
    }

    private fun header(t: String) {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 20f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setTextColor(C.BG)
        col.addView(tv, mlp(28))
    }

    private fun note(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 15f
        tv.setTextColor(Color.DKGRAY)
        col.addView(tv, mlp(8))
        return tv
    }

    private fun action(t: String, bg: Int = C.KEY, fg: Int = Color.WHITE, onClick: () -> Unit): Button {
        val b = button(t, bg, fg, 16f, onClick)
        b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        b.setPadding(dp(16), dp(8), dp(16), dp(8))
        col.addView(b, mlp(8))
        return b
    }

    private fun setPerm(b: Button, ok: Boolean, t: String) {
        b.text = if (ok) "✅ $t" else "❌ $t — нажмите"
        b.background = roundedBg(if (ok) C.GREEN else C.RED, dp(16).toFloat())
    }

    private fun check(t: String, value: Boolean): CheckBox {
        val cb = CheckBox(this)
        cb.text = t
        cb.textSize = 16f
        cb.isChecked = value
        col.addView(cb, mlp(8))
        return cb
    }

    private fun field(label: String, value: String, type: Int): EditText {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 14f
        tv.setTextColor(Color.DKGRAY)
        col.addView(tv, mlp(12))
        val et = EditText(this)
        et.setText(value)
        et.inputType = type
        et.textSize = 18f
        col.addView(et, mlp(0))
        return et
    }

    private fun number(label: String, v: Int): EditText = field(label, v.toString(), InputType.TYPE_CLASS_NUMBER)
}
