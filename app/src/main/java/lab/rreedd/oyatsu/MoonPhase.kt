package lab.rreedd.oyatsu

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 月相 (朔望) の計算。
 *
 * Jean Meeus「Astronomical Algorithms」第2版 Ch.49「Phases of the Moon」に
 * 準拠し、朔(New)・上弦(FirstQuarter)・望(Full)・下弦(LastQuarter) の
 * 正確な瞬時を求める。https://github.com/tknv/oyatsu-cli の moonface.rs を移植。
 *
 * ΔT (地球時-世界時の差, ≈70秒) は無視する (日付判定レベルには十分な精度)。
 */
enum class MoonPhaseType(val nameJa: String) {
    NEW("朔"),
    FIRST_QUARTER("上弦"),
    FULL("望"),
    LAST_QUARTER("下弦");

    companion object {
        fun fromOffset(offset: Int): MoonPhaseType = when (offset) {
            0 -> NEW
            1 -> FIRST_QUARTER
            2 -> FULL
            3 -> LAST_QUARTER
            else -> throw IllegalArgumentException("phase offset は 0..=3: $offset")
        }
    }
}

object MoonPhase {

    private fun r(d: Double) = Math.toRadians(d)
    private fun rev360(x: Double) = x.mod(360.0)

    private class PhaseAngles(
        val e: Double,
        val m: Double,
        val mp: Double,
        val f: Double,
        val omega: Double,
        val a: DoubleArray,
    )

    /** 指定日に対応する、朔望月インデックス k の近似値。 */
    private fun approxK(year: Int, dayOfYear: Int): Double {
        val yearFrac = year + dayOfYear / 365.25
        return (yearFrac - 2000.0) * 12.3685
    }

    private fun meanJdeAndAngles(k: Double): Pair<Double, PhaseAngles> {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t

        val jde0 = 2_451_550.09766 +
            29.530588861 * k +
            0.00015437 * t2 -
            0.000000150 * t3 +
            0.00000000073 * t4

        val e = 1.0 - 0.002516 * t - 0.0000074 * t2

        val m = rev360(2.5534 + 29.10535669 * k - 0.0000218 * t2 - 0.00000011 * t3)
        val mp = rev360(
            201.5643 + 385.81693528 * k + 0.0107582 * t2 + 0.00001238 * t3 - 0.000000058 * t4
        )
        val f = rev360(
            160.7108 + 390.67050284 * k - 0.0016118 * t2 - 0.00000227 * t3 + 0.000000011 * t4
        )
        val omega = rev360(124.7746 - 1.56375588 * k + 0.0020672 * t2 + 0.00000215 * t3)

        val a = doubleArrayOf(
            rev360(299.77 + 0.107408 * k - 0.009173 * t2),
            rev360(251.88 + 0.016321 * k),
            rev360(251.83 + 26.651886 * k),
            rev360(349.42 + 36.412478 * k),
            rev360(84.66 + 18.206239 * k),
            rev360(141.74 + 53.303771 * k),
            rev360(207.14 + 2.453732 * k),
            rev360(154.84 + 7.306860 * k),
            rev360(34.52 + 27.261239 * k),
            rev360(207.19 + 0.121824 * k),
            rev360(291.34 + 1.844379 * k),
            rev360(161.72 + 24.198154 * k),
            rev360(239.56 + 25.513099 * k),
            rev360(331.55 + 3.592518 * k),
        )

        return Pair(jde0, PhaseAngles(e, m, mp, f, omega, a))
    }

    private fun correctionNewFull(p: PhaseAngles): Double {
        val e = p.e
        val m = r(p.m); val mp = r(p.mp); val f = r(p.f); val omega = r(p.omega)
        return -0.40720 * sin(mp) +
            0.17241 * e * sin(m) +
            0.01608 * sin(2.0 * mp) +
            0.01039 * sin(2.0 * f) +
            0.00739 * e * sin(mp - m) -
            0.00514 * e * sin(mp + m) +
            0.00208 * e * e * sin(2.0 * m) -
            0.00111 * sin(mp - 2.0 * f) -
            0.00057 * sin(mp + 2.0 * f) +
            0.00056 * e * sin(2.0 * mp + m) -
            0.00042 * sin(3.0 * mp) +
            0.00042 * e * sin(m + 2.0 * f) +
            0.00038 * e * sin(m - 2.0 * f) -
            0.00024 * e * sin(2.0 * mp - m) -
            0.00017 * sin(omega) -
            0.00007 * sin(mp + 2.0 * m) +
            0.00004 * sin(2.0 * mp - 2.0 * f) +
            0.00004 * sin(3.0 * m) +
            0.00003 * sin(mp + m - 2.0 * f) +
            0.00003 * sin(2.0 * mp + 2.0 * f) -
            0.00003 * sin(mp + m + 2.0 * f) +
            0.00003 * sin(mp - m + 2.0 * f) -
            0.00002 * sin(mp - m - 2.0 * f) -
            0.00002 * sin(3.0 * mp + m) +
            0.00002 * sin(4.0 * mp)
    }

    private fun correctionQuarter(p: PhaseAngles): Double {
        val e = p.e
        val m = r(p.m); val mp = r(p.mp); val f = r(p.f); val omega = r(p.omega)
        return -0.62801 * sin(mp) +
            0.17172 * e * sin(m) -
            0.01183 * e * sin(mp + m) +
            0.00862 * sin(2.0 * mp) +
            0.00804 * sin(2.0 * f) +
            0.00454 * e * sin(mp - m) +
            0.00204 * e * e * sin(2.0 * m) -
            0.00180 * sin(mp - 2.0 * f) -
            0.00070 * sin(mp + 2.0 * f) -
            0.00040 * sin(3.0 * mp) -
            0.00034 * e * sin(2.0 * mp - m) +
            0.00032 * e * sin(m + 2.0 * f) +
            0.00032 * e * sin(m - 2.0 * f) -
            0.00028 * e * e * sin(mp + 2.0 * m) +
            0.00027 * e * sin(2.0 * mp + m) -
            0.00017 * sin(omega) -
            0.00005 * sin(mp - m - 2.0 * f) +
            0.00004 * sin(2.0 * mp + 2.0 * f) -
            0.00004 * sin(mp + m + 2.0 * f) +
            0.00004 * sin(mp - 2.0 * m) +
            0.00003 * sin(mp + m - 2.0 * f) +
            0.00003 * sin(3.0 * m) +
            0.00002 * sin(2.0 * mp - 2.0 * f) +
            0.00002 * sin(mp - m + 2.0 * f) -
            0.00002 * sin(mp + 3.0 * m)
    }

    private fun quarterW(p: PhaseAngles): Double {
        val e = p.e
        val m = r(p.m); val mp = r(p.mp); val f = r(p.f)
        return 0.00306 - 0.00038 * e * cos(m) + 0.00026 * cos(mp) -
            0.00002 * cos(mp - m) + 0.00002 * cos(mp + m) + 0.00002 * cos(2.0 * f)
    }

    private fun planetaryCorrection(p: PhaseAngles): Double {
        val coeff = doubleArrayOf(
            0.000325, 0.000165, 0.000164, 0.000126, 0.000110, 0.000062, 0.000060,
            0.000056, 0.000047, 0.000042, 0.000040, 0.000037, 0.000035, 0.000023,
        )
        var sum = 0.0
        for (i in coeff.indices) {
            sum += coeff[i] * sin(r(p.a[i]))
        }
        return sum
    }

    private fun phaseJde(kBase: Double, phaseOffset: Int): Double {
        val k = kBase + phaseOffset * 0.25
        val (jde0, p) = meanJdeAndAngles(k)
        val planetary = planetaryCorrection(p)
        return when (phaseOffset) {
            0, 2 -> jde0 + correctionNewFull(p) + planetary
            1 -> jde0 + correctionQuarter(p) + quarterW(p) + planetary
            3 -> jde0 + correctionQuarter(p) - quarterW(p) + planetary
            else -> throw IllegalArgumentException("phase offset は 0..=3: $phaseOffset")
        }
    }

    /** JDE(TD) を UTC の絶対時刻 (epoch millis) に変換する (Meeus Ch.7 逆算 + 時刻)。 */
    private fun jdeToUtcMillis(jde: Double): Long {
        val jd05 = jde + 0.5
        val z = floor(jd05)
        val dayFracPart = jd05 - z
        val zI = z.toLong()

        val a: Long = if (zI < 2_299_161L) {
            zI
        } else {
            val alpha = floor((z - 1_867_216.25) / 36_524.25).toLong()
            zI + 1 + alpha - alpha / 4
        }
        val b = a + 1524
        val c = floor((b - 122.1) / 365.25).toLong()
        val d = floor(365.25 * c).toLong()
        val e = floor((b - d) / 30.6001).toLong()

        val dayInt = b - d - floor(30.6001 * e).toLong()
        val month = if (e < 14) (e - 1).toInt() else (e - 13).toInt()
        val year = if (month > 2) (c - 4716).toInt() else (c - 4715).toInt()

        val secsTotal = Math.round(dayFracPart * 86_400.0)

        val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, dayInt.toInt(), 0, 0, 0)
        cal.add(Calendar.SECOND, secsTotal.toInt())
        return cal.timeInMillis
    }

    private data class NextMoonEvent(val phase: MoonPhaseType, val utcMillis: Long)

    /** `afterMillis` 以降で最初に到来する朔望を返す。`afterCalendar` はその暦日(年・年内通算日)の参照に使う。 */
    private fun nextPhase(afterMillis: Long, afterCalendar: Calendar): NextMoonEvent {
        val kCenter = Math.round(
            approxK(afterCalendar.get(Calendar.YEAR), afterCalendar.get(Calendar.DAY_OF_YEAR)).toDouble()
        ).toDouble()

        var bestMillis: Long? = null
        var bestPhase: MoonPhaseType? = null

        // ±2 朔望月分探索すれば必ず「次の朔望」が見つかる (安全マージン込み)。
        for (kOff in -2..2) {
            val kBase = kCenter + kOff
            for (phaseOffset in 0..3) {
                val jde = phaseJde(kBase, phaseOffset)
                val millis = jdeToUtcMillis(jde)
                if (millis >= afterMillis) {
                    val better = bestMillis == null || millis < bestMillis!!
                    if (better) {
                        bestMillis = millis
                        bestPhase = MoonPhaseType.fromOffset(phaseOffset)
                    }
                }
            }
        }

        return NextMoonEvent(
            bestPhase ?: MoonPhaseType.NEW,
            bestMillis ?: afterMillis,
        )
    }

    /**
     * `now` から見て次に来る朔望を日本語メッセージにする。
     * 例: "三日後に朔", "十二日後に上弦", "今夜は望", "今夜は下弦"
     */
    fun describeJa(now: Calendar): String {
        val ev = nextPhase(now.timeInMillis, now)

        val today = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val eventDay = Calendar.getInstance(now.timeZone).apply {
            timeInMillis = ev.utcMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }

        val daysUntil = (eventDay.timeInMillis - today.timeInMillis) / 86_400_000L
        val name = ev.phase.nameJa

        return if (daysUntil <= 0) {
            "今夜は$name"
        } else {
            "${Koyomi.kanjiNumber(daysUntil.toInt())}日後に$name"
        }
    }
}
