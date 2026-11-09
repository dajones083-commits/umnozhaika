package com.family.mathlock

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object C {
    val BG = Color.parseColor("#1A237E")
    val CARD = Color.parseColor("#283593")
    val ACCENT = Color.parseColor("#FFB300")
    val KEY = Color.parseColor("#3949AB")
    val KEY_ALT = Color.parseColor("#5C6BC0")
    val GREEN = Color.parseColor("#43A047")
    val RED = Color.parseColor("#E53935")
    val LIGHT = Color.parseColor("#C5CAE9")
    val DARK = Color.parseColor("#212121")
}

fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun roundedBg(color: Int, radiusPx: Float): GradientDrawable {
    val d = GradientDrawable()
    d.setColor(color)
    d.cornerRadius = radiusPx
    return d
}

fun Context.label(s: String, sizeSp: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView {
    val tv = TextView(this)
    tv.text = s
    tv.textSize = sizeSp
    tv.setTextColor(color)
    tv.gravity = Gravity.CENTER
    if (bold) tv.typeface = Typeface.DEFAULT_BOLD
    return tv
}

fun Context.button(
    title: String,
    bg: Int,
    fg: Int = Color.WHITE,
    sizeSp: Float = 20f,
    onClick: () -> Unit
): Button {
    val b = Button(this)
    b.text = title
    b.textSize = sizeSp
    b.setTextColor(fg)
    b.setAllCaps(false)
    b.background = roundedBg(bg, dp(16).toFloat())
    b.setOnClickListener { onClick() }
    return b
}
