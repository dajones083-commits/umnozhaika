package com.family.mathlock

import kotlin.random.Random

data class Problem(val a: Int, val b: Int) {
    val answer: Int
        get() = a * b
}

object Quiz {
    /**
     * Набор примеров без повторов. Примеры, на которых ребёнок ошибался,
     * выпадают чаще — так «трудные» места таблицы тренируются больше.
     */
    fun generate(prefs: Prefs, count: Int, rnd: Random = Random.Default): List<Problem> {
        val lo = prefs.minFactor
        val hi = maxOf(lo, prefs.maxFactor)
        val pool = ArrayList<Problem>()
        for (a in lo..hi) for (b in lo..hi) pool.add(Problem(a, b))

        val result = ArrayList<Problem>()
        val used = HashSet<Problem>()
        while (result.size < count) {
            var candidates = pool.filter { it !in used }
            if (candidates.isEmpty()) {
                used.clear()
                candidates = pool
            }
            val weights = candidates.map { 1.0 + 2.0 * prefs.mistakes(it.a, it.b) }
            var r = rnd.nextDouble() * weights.sum()
            var pick = candidates.last()
            for (i in candidates.indices) {
                r -= weights[i]
                if (r <= 0) {
                    pick = candidates[i]
                    break
                }
            }
            result.add(pick)
            used.add(pick)
        }
        return result
    }
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val n10 = n % 10
    val n100 = n % 100
    return when {
        n10 == 1 && n100 != 11 -> one
        n10 in 2..4 && n100 !in 12..14 -> few
        else -> many
    }
}
