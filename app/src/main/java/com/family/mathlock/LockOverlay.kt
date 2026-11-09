package com.family.mathlock

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Полноэкранное окно поверх всех приложений: выбор времени, примеры, PIN родителя.
 */
class LockOverlay(base: Context, private val prefs: Prefs, private val host: Host) {

    interface Host {
        fun grant(minutes: Int, solved: Int)
        fun parentUnlock(minutes: Int)
        fun parentPauseUntilMorning()
        fun parentOpenSettings()
        fun call()
    }

    private val ctx: Context = ContextThemeWrapper(base, android.R.style.Theme_Material_NoActionBar)
    private val wm = base.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val root = FrameLayout(ctx)
    private var attached = false
    private var screenToken = 0
    private var onSuccessScreen = false

    // состояние серии примеров
    private var problems: List<Problem> = emptyList()
    private var index = 0
    private var chosenMinutes = 0
    private val input = StringBuilder()
    private var deadline = 0L
    private var running = false

    private lateinit var progressText: TextView
    private lateinit var rewardText: TextView
    private lateinit var questionText: TextView
    private lateinit var answerText: TextView
    private lateinit var timerBar: ProgressBar

    init {
        root.setBackgroundColor(C.BG)
        root.isClickable = true
    }

    val isShowing: Boolean
        get() = attached

    fun show(): Boolean {
        if (attached) return true
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return try {
            showMenu()
            wm.addView(root, lp)
            attached = true
            true
        } catch (e: Exception) {
            false
        }
    }

    fun hide() {
        stopTimer()
        handler.removeCallbacksAndMessages(null)
        if (attached) {
            try {
                wm.removeView(root)
            } catch (e: Exception) {
            }
            attached = false
        }
    }

    /** Экран погас — бросаем серию и возвращаемся к меню. */
    fun resetToMenu() {
        if (attached && !onSuccessScreen) showMenu()
    }

    // ------------------------------------------------------------------ helpers

    private fun column(): LinearLayout {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(ctx.dp(20), ctx.dp(48), ctx.dp(20), ctx.dp(32))
        return col
    }

    private fun setContent(v: View, scroll: Boolean = false) {
        screenToken++
        onSuccessScreen = false
        root.removeAllViews()
        if (scroll) {
            val sv = ScrollView(ctx)
            sv.isFillViewport = true
            sv.addView(v, FrameLayout.LayoutParams(MATCH, WRAP))
            root.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    private fun wrap(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(MATCH, WRAP)
        p.topMargin = ctx.dp(top)
        p.bottomMargin = ctx.dp(bottom)
        return p
    }

    private fun full(heightDp: Int = 64): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(MATCH, ctx.dp(heightDp))
        p.topMargin = ctx.dp(6)
        p.bottomMargin = ctx.dp(6)
        return p
    }

    private fun spacer(): View {
        val v = View(ctx)
        v.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        return v
    }

    private fun smallButton(title: String, onClick: () -> Unit): Button =
        ctx.button(title, C.CARD, C.LIGHT, 15f, onClick)

    private fun problemsFor(minutes: Int): Int {
        val mpq = maxOf(1, prefs.minutesPerQuestion)
        return maxOf(1, (minutes + mpq - 1) / mpq)
    }

    /** Сколько минут ребёнок получит с учётом штрафа (минимум 1). */
    private fun rewardFor(minutes: Int): Int = maxOf(1, minutes - prefs.pendingPenalty)

    private fun keypad(onKey: (String) -> Unit): LinearLayout {
        val grid = LinearLayout(ctx)
        grid.orientation = LinearLayout.VERTICAL
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("C", "0", "⌫")
        )
        for (r in rows) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            for (k in r) {
                val isDigit = k.length == 1 && k[0].isDigit()
                val b = ctx.button(k, if (isDigit) C.KEY else C.KEY_ALT, sizeSp = 28f) { onKey(k) }
                val p = LinearLayout.LayoutParams(0, ctx.dp(66), 1f)
                p.setMargins(ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5))
                row.addView(b, p)
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        return grid
    }

    private fun editInput(k: String, maxLen: Int) {
        when (k) {
            "C" -> {
                input.setLength(0)
            }
            "⌫" -> {
                if (input.isNotEmpty()) input.setLength(input.length - 1)
            }
            else -> {
                if (input.length < maxLen) input.append(k)
            }
        }
    }

    // ------------------------------------------------------------------ меню

    private fun showMenu() {
        stopTimer()
        val col = column()
        col.addView(ctx.label("🔒", 54f))
        col.addView(ctx.label("Телефон на замке", 30f, bold = true), wrap(top = 8))

        if (prefs.tamperLockActive()) {
            col.addView(
                ctx.label("Кто-то пытался отключить защиту.\nНужен код родителя.", 18f, C.LIGHT),
                wrap(top = 16)
            )
        } else {
            val mpq = prefs.minutesPerQuestion
            col.addView(
                ctx.label(
                    "Реши примеры — и телефон откроется!\n" +
                        "1 пример = $mpq мин • на ответ ${prefs.secondsPerQuestion} сек\n" +
                        "Ошибка = всё сначала и −${prefs.penaltyMinutes} мин",
                    16f, C.LIGHT
                ),
                wrap(top = 12, bottom = 8)
            )
            val penalty = prefs.pendingPenalty
            if (penalty > 0) {
                col.addView(ctx.label("Штраф за ошибки: −$penalty мин", 17f, C.ACCENT, bold = true), wrap(bottom = 8))
            }

            val left = prefs.minutesLeftToday()
            val options = prefs.durations.filter { it <= left }.toMutableList()
            if (options.isEmpty() && left in 1..10_000) options.add(left)

            for (m in options) {
                val n = problemsFor(m)
                val reward = rewardFor(m)
                val title = if (reward < m) {
                    "$m → $reward мин  •  $n ${plural(n, "пример", "примера", "примеров")}"
                } else {
                    "Открыть на $m мин  •  $n ${plural(n, "пример", "примера", "примеров")}"
                }
                col.addView(ctx.button(title, C.ACCENT, C.DARK, 19f) { startQuiz(m) }, full(66))
            }
            if (options.isEmpty()) {
                col.addView(ctx.label("На сегодня лимит исчерпан 🙂\nОтдохни от телефона!", 18f, C.ACCENT), wrap(top = 16))
            } else if (prefs.dailyLimitMinutes > 0) {
                col.addView(ctx.label("Сегодня можно ещё $left мин", 15f, C.LIGHT), wrap(top = 8))
            }
        }

        col.addView(spacer())
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        val callsLeft = MAX_CALLS - prefs.callsToday
        val callBtn = ctx.button(
            if (callsLeft > 0) "📞 Позвонить" else "📞 Лимит звонков",
            C.KEY_ALT, sizeSp = 16f
        ) { if (MAX_CALLS - prefs.callsToday > 0) host.call() }
        val p1 = LinearLayout.LayoutParams(0, ctx.dp(52), 1f)
        p1.marginEnd = ctx.dp(6)
        row.addView(callBtn, p1)
        val p2 = LinearLayout.LayoutParams(0, ctx.dp(52), 1f)
        p2.marginStart = ctx.dp(6)
        row.addView(ctx.button("👤 Родитель", C.KEY_ALT, sizeSp = 16f) { showPin() }, p2)
        col.addView(row, wrap(top = 16))
        setContent(col, scroll = true)
    }

    // ------------------------------------------------------------------ примеры

    private fun startQuiz(minutes: Int) {
        chosenMinutes = minutes
        problems = Quiz.generate(prefs, problemsFor(minutes))
        index = 0
        buildQuizUi()
        nextQuestion()
    }

    private fun buildQuizUi() {
        val col = column()
        progressText = ctx.label("", 18f, C.LIGHT)
        col.addView(progressText, wrap())
        rewardText = ctx.label("", 15f, C.ACCENT)
        col.addView(rewardText, wrap(top = 2))

        timerBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
        timerBar.max = 1000
        timerBar.progress = 1000
        timerBar.progressTintList = ColorStateList.valueOf(C.ACCENT)
        timerBar.progressBackgroundTintList = ColorStateList.valueOf(C.CARD)
        val tp = LinearLayout.LayoutParams(MATCH, ctx.dp(16))
        tp.topMargin = ctx.dp(12)
        col.addView(timerBar, tp)

        questionText = ctx.label("", 60f, bold = true)
        col.addView(questionText, wrap(top = 20))

        answerText = ctx.label("?", 48f, C.ACCENT, bold = true)
        answerText.background = roundedBg(C.CARD, ctx.dp(16).toFloat())
        answerText.minHeight = ctx.dp(84)
        val ap = LinearLayout.LayoutParams(ctx.dp(200), WRAP)
        ap.topMargin = ctx.dp(10)
        ap.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(answerText, ap)

        col.addView(spacer())
        col.addView(keypad { onQuizKey(it) }, wrap())
        col.addView(smallButton("Отмена") { showMenu() }, full(44))
        setContent(col)
    }

    private val timerTick = object : Runnable {
        override fun run() {
            if (!running) return
            val total = prefs.secondsPerQuestion * 1000L
            val left = deadline - SystemClock.uptimeMillis()
            if (left <= 0) {
                timerBar.progress = 0
                val target = problems[index].answer.toString()
                if (input.length >= target.length && input.toString() != target) {
                    onWrong() // набрал неверный ответ и ждал — это ошибка, а не просто «не успел»
                    if (running) onFail(timeout = true)
                } else {
                    onFail(timeout = true)
                }
                return
            }
            timerBar.progress = (left * 1000 / total).toInt()
            timerBar.progressTintList = ColorStateList.valueOf(if (left < 1500) C.RED else C.ACCENT)
            handler.postDelayed(this, 40)
        }
    }

    private fun nextQuestion() {
        val p = problems[index]
        progressText.text = "Пример ${index + 1} из ${problems.size}"
        rewardText.text = "Награда: ${rewardFor(chosenMinutes)} мин"
        questionText.text = "${p.a} × ${p.b}"
        input.setLength(0)
        answerText.text = "?"
        answerText.setTextColor(C.ACCENT)
        deadline = SystemClock.uptimeMillis() + prefs.secondsPerQuestion * 1000L
        running = true
        handler.removeCallbacks(timerTick)
        handler.post(timerTick)
    }

    private fun stopTimer() {
        running = false
        handler.removeCallbacks(timerTick)
    }

    private fun onQuizKey(k: String) {
        if (!running) return
        editInput(k, 4)
        answerText.text = if (input.isEmpty()) "?" else input.toString()
        val typed = input.toString()
        val secret = prefs.secretCode

        // Секретный код родителя: сбрасывает примеры и штраф, открывает меню родителя.
        if (secret.isNotEmpty() && typed == secret) {
            stopTimer()
            prefs.clearPenalty()
            prefs.markAuthed()
            showParentMenu()
            return
        }

        val target = problems[index].answer.toString()
        if (typed == target) {
            onCorrect()
        } else if (typed.length >= target.length) {
            // Пока набранное совпадает с началом секретного кода — ждём (родитель может дописать код).
            // Если время выйдет в этом состоянии, это засчитается как ошибка.
            if (secret.isNotEmpty() && secret.startsWith(typed) && typed.length < secret.length) return
            onWrong()
        }
    }

    private fun onCorrect() {
        stopTimer()
        val p = problems[index]
        prefs.recordAnswer(p.a, p.b, true)
        answerText.setTextColor(C.GREEN)
        index++
        if (index >= problems.size) {
            val reward = rewardFor(chosenMinutes)
            showSuccess(reward)
            host.grant(reward, problems.size)
        } else {
            val t = screenToken
            handler.postDelayed({ if (attached && t == screenToken) nextQuestion() }, 300)
        }
    }

    private fun onWrong() {
        val p = problems[index]
        prefs.recordAnswer(p.a, p.b, false)
        prefs.addPenalty(prefs.penaltyMinutes)
        if (prefs.wrongResets) {
            onFail(timeout = false)
        } else {
            input.setLength(0)
            answerText.setTextColor(C.RED)
            answerText.text = "✗"
            rewardText.text = "Награда: ${rewardFor(chosenMinutes)} мин"
            handler.postDelayed({
                if (running) {
                    answerText.setTextColor(C.ACCENT)
                    if (input.isEmpty()) answerText.text = "?"
                }
            }, 400)
        }
    }

    private fun onFail(timeout: Boolean) {
        stopTimer()
        val p = problems[index]
        if (timeout) prefs.recordAnswer(p.a, p.b, false)

        val col = column()
        col.addView(spacer())
        col.addView(ctx.label(if (timeout) "⏰" else "❌", 64f))
        col.addView(ctx.label(if (timeout) "Время вышло!" else "Неправильно!", 30f, bold = true), wrap(top = 8))
        col.addView(ctx.label("Запомни:", 18f, C.LIGHT), wrap(top = 24))
        col.addView(ctx.label("${p.a} × ${p.b} = ${p.answer}", 52f, C.ACCENT, bold = true), wrap(top = 4))
        val penaltyLine = if (!timeout && prefs.penaltyMinutes > 0) "Штраф −${prefs.penaltyMinutes} мин. " else ""
        col.addView(
            ctx.label("${penaltyLine}Начинаем сначала.\nТеперь награда: ${rewardFor(chosenMinutes)} мин", 18f, C.LIGHT),
            wrap(top = 16)
        )
        col.addView(spacer())
        val again = ctx.button("Ещё раз", C.ACCENT, C.DARK, 22f) { startQuiz(chosenMinutes) }
        again.isEnabled = false
        again.alpha = 0.4f
        col.addView(again, full(64))
        col.addView(smallButton("Назад") { showMenu() }, full(44))
        setContent(col)
        // 2 секунды, чтобы точно прочитал правильный ответ
        val t = screenToken
        handler.postDelayed({
            if (t == screenToken) {
                again.isEnabled = true
                again.alpha = 1f
            }
        }, 2000)
    }

    private fun showSuccess(minutes: Int) {
        val col = column()
        col.addView(spacer())
        col.addView(ctx.label("🎉", 72f))
        col.addView(ctx.label("Молодец!", 34f, bold = true), wrap(top = 8))
        col.addView(ctx.label("Телефон открыт на $minutes мин", 20f, C.ACCENT), wrap(top = 12))
        col.addView(spacer())
        setContent(col)
        onSuccessScreen = true
    }

    // ------------------------------------------------------------------ родитель

    private fun showPin() {
        stopTimer()
        input.setLength(0)
        val col = column()
        col.addView(ctx.label("Код родителя", 26f, bold = true), wrap(top = 16))
        val dots = ctx.label("○ ○ ○ ○", 40f, C.ACCENT)
        col.addView(dots, wrap(top = 24))
        val msg = ctx.label("", 16f, C.LIGHT)
        col.addView(msg, wrap(top = 8))
        val locked = prefs.pinLockedMs()
        if (locked > 0) msg.text = "Слишком много попыток. Подождите ${locked / 1000 + 1} сек"
        col.addView(spacer())
        col.addView(keypad { k ->
            editInput(k, 4)
            dots.text = (0 until 4).joinToString(" ") { if (it < input.length) "●" else "○" }
            if (input.length == 4) {
                val ok = prefs.tryPin(input.toString())
                input.setLength(0)
                if (ok) {
                    showParentMenu()
                } else {
                    dots.text = "○ ○ ○ ○"
                    val l = prefs.pinLockedMs()
                    msg.text = if (l > 0) "Слишком много попыток. Подождите ${l / 1000 + 1} сек" else "Неверный код"
                }
            }
        }, wrap())
        col.addView(smallButton("Назад") { showMenu() }, full(44))
        setContent(col)
    }

    private fun showParentMenu() {
        val col = column()
        col.addView(ctx.label("Меню родителя", 26f, bold = true), wrap(top = 16, bottom = 16))
        val opts = listOf("Открыть на 30 минут" to 30, "Открыть на 1 час" to 60, "Открыть на 3 часа" to 180)
        for ((title, mins) in opts) {
            col.addView(ctx.button(title, C.KEY, sizeSp = 19f) { host.parentUnlock(mins) }, full(60))
        }
        col.addView(
            ctx.button("Выключить до ${Schedule.fmt(prefs.endMinutes)}", C.KEY, sizeSp = 19f) {
                host.parentPauseUntilMorning()
            },
            full(60)
        )
        col.addView(ctx.button("⚙ Настройки", C.KEY_ALT, sizeSp = 19f) { host.parentOpenSettings() }, full(60))
        col.addView(spacer())
        col.addView(smallButton("Назад") { showMenu() }, full(44))
        setContent(col, scroll = true)
        // забыли меню открытым — через минуту закроется само
        val t = screenToken
        handler.postDelayed({ if (attached && t == screenToken) showMenu() }, 60_000)
    }

    companion object {
        const val MAX_CALLS = 5
    }
}
