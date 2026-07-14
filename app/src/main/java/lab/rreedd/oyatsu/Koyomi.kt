package lab.rreedd.oyatsu

import java.util.Calendar
import java.util.GregorianCalendar
import kotlin.math.floor

/**
 * 和暦・和風月名・日付(漢数字)・干支(年/日)・雑節・祝日の計算。
 *
 * https://github.com/tknv/oyatsu-cli の Rust実装 (koyomi.rs / holidays.rs) を
 * Kotlin に移植したもの。二十四節気・日の出日の入りの天文計算自体は
 * 既存の [MeeusSunCalc] を再利用する。
 */
object Koyomi {

    private val STEMS = arrayOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    private val BRANCHES =
        arrayOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    private val KANJI_DIGITS = arrayOf("〇", "一", "二", "三", "四", "五", "六", "七", "八", "九")

    // ─── 和風月名 ─────────────────────────────────────────────

    fun japaneseMonthName(cal: Calendar): String {
        val names = listOf(
            "睦月", "如月", "弥生", "卯月", "皐月", "水無月",
            "文月", "葉月", "長月", "神無月", "霜月", "師走"
        )
        return names[cal.get(Calendar.MONTH)]
    }

    // ─── 日付 (漢数字表記、廿を用いる) ────────────────────────

    fun kanjiNumber(n: Int): String {
        return when {
            n in 1..9 -> KANJI_DIGITS[n]
            n == 10 -> "十"
            n in 11..19 -> "十" + KANJI_DIGITS[n - 10]
            n == 20 -> "廿"
            n in 21..29 -> "廿" + KANJI_DIGITS[n - 20]
            n == 30 -> "三十"
            n in 31..39 -> "三十" + KANJI_DIGITS[n - 30]
            else -> n.toString()
        }
    }

    fun kanjiDay(cal: Calendar): String = kanjiNumber(cal.get(Calendar.DAY_OF_MONTH)) + "日"

    // ─── 和暦 ────────────────────────────────────────────────

    private data class EraStart(val year: Int, val month: Int, val day: Int, val name: String)

    private val ERAS = listOf(
        EraStart(2019, 5, 1, "令和"),
        EraStart(1989, 1, 8, "平成"),
        EraStart(1926, 12, 25, "昭和"),
        EraStart(1912, 7, 30, "大正"),
        EraStart(1868, 1, 25, "明治"),
    )

    private fun toKanjiEraNumber(num: Int): String {
        if (num <= 0) return ""
        if (num == 1) return "元"
        val powers = arrayOf("", "十", "百", "千")
        val s = num.toString()
        val len = s.length
        var result = ""
        for (i in s.indices) {
            val d = s[i] - '0'
            val pow = len - 1 - i
            if (d > 0) {
                if (!(d == 1 && pow == 1)) result += KANJI_DIGITS[d]
                if (pow > 0) result += powers[pow]
            }
        }
        return result
    }

    fun japaneseYear(cal: Calendar): String {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        for (era in ERAS) {
            if (y > era.year || (y == era.year && (m > era.month || (m == era.month && d >= era.day)))) {
                return "${era.name}${toKanjiEraNumber(y - era.year + 1)}年"
            }
        }
        return "${y}年"
    }

    // ─── 六十干支 (年) ────────────────────────────────────────

    fun sixtyKanjiCycleYear(cal: Calendar): String {
        val year = cal.get(Calendar.YEAR)
        val stemIdx = floorMod(year - 4, 10)
        val branchIdx = floorMod(year - 4, 12)
        return STEMS[stemIdx] + BRANCHES[branchIdx]
    }

    // ─── 日干支 (六十干支のうち、その日固有の組み合わせ) ────────
    //
    // 基準日 2000年1月1日 (JD=2451545) は「戊午」の日。
    // 十干は (JD+9) mod 10、十二支は (JD+1) mod 12 で求める。

    fun dayKanjiCycle(cal: Calendar): String {
        val jd0h = julianDay0h(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        val jd12h = Math.round(jd0h + 0.5)
        val stemIdx = floorModLong(jd12h + 9, 10)
        val branchIdx = floorModLong(jd12h + 1, 12)
        return STEMS[stemIdx] + BRANCHES[branchIdx]
    }

    // ─── 雑節 ────────────────────────────────────────────────

    /** 指定日が雑節に該当する場合、その名称を返す (該当しなければ null)。 */
    fun zassetsu(cal: Calendar): String? {
        val year = cal.get(Calendar.YEAR)
        val today = Triple(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))

        fun termDate(lon: Double, approxMonth: Int, approxDay: Int, y: Int = year): Triple<Int, Int, Int> {
            val approxJd = julianDay0h(y, approxMonth, approxDay)
            val termJd = MeeusSunCalc.findSolarTermJulianDay(lon, approxJd)
            return jdToDate(termJd + 9.0 / 24.0) // JST変換
        }

        val risshun = termDate(315.0, 2, 4)
        val shunbun = termDate(0.0, 3, 21)
        val shubun = termDate(180.0, 9, 23)

        if (today == addDays(risshun, -1)) return "節分"
        if (today == addDays(risshun, 87)) return "八十八夜"
        if (today == addDays(risshun, 209)) return "二百十日"
        if (today == addDays(shunbun, -3) || today == addDays(shubun, -3)) return "彼岸入り"

        val doyoWinter = termDate(297.0, 1, 17)
        val doyoSpring = termDate(27.0, 4, 17)
        val doyoSummer = termDate(117.0, 7, 19)
        val doyoAutumn = termDate(207.0, 10, 20)
        if (today == doyoWinter || today == doyoSpring || today == doyoSummer || today == doyoAutumn) {
            return "土用入り"
        }

        val nyubai = termDate(80.0, 6, 11)
        if (today == nyubai) return "入梅"

        val hangesho = termDate(100.0, 7, 2)
        if (today == hangesho) return "半夏生"

        val shunsha = calcShanichi(year, isSpring = true)
        val shusha = calcShanichi(year, isSpring = false)
        if (today == shunsha || today == shusha) return "社日"

        return null
    }

    /** その年の社日 (春社または秋社) を計算する。 */
    private fun calcShanichi(year: Int, isSpring: Boolean): Triple<Int, Int, Int> {
        val lon = if (isSpring) 0.0 else 180.0
        val approxMonth = if (isSpring) 3 else 9
        val approxDay = if (isSpring) 21 else 23

        val approxJd = julianDay0h(year, approxMonth, approxDay)
        val termJdUtc = MeeusSunCalc.findSolarTermJulianDay(lon, approxJd)
        val termJdJst = termJdUtc + 9.0 / 24.0
        val eqDate = jdToDate(termJdJst)

        // JSTでの時刻 (0.0〜24.0)
        val hours = ((termJdJst + 0.5).mod(1.0)) * 24.0

        val jikkan = jikkanOfDate(eqDate)
        var offset = floorMod(4 - jikkan, 10)
        if (offset > 5) {
            offset -= 10
        } else if (offset == 5) {
            // 春分・秋分が「癸」の日の場合、明治14年以後のルール:
            // 午前中なら前(-5日)、午後なら後(+5日)
            offset = if (hours < 12.0) -5 else 5
        }

        return addDays(eqDate, offset)
    }

    /** 指定日の「日の十干」を返す (0:甲, 1:乙, ..., 9:癸)。 */
    private fun jikkanOfDate(date: Triple<Int, Int, Int>): Int {
        val jd0h = julianDay0h(date.first, date.second, date.third)
        val jd12h = Math.round(jd0h + 0.5)
        return floorModLong(jd12h + 9, 10)
    }

    // ─── 祝日 ────────────────────────────────────────────────

    private data class HolidayEntry(val year: Int, val month: Int, val day: Int, val name: String)

    // oyatsu-cli の data/cal-YYYY.csv を移植。name が空文字列の日は
    // 「名称のない休日」(振替休日など)。
    private val HOLIDAYS = listOf(
        HolidayEntry(2026, 1, 1, "四方節"),
        HolidayEntry(2026, 1, 12, "成人の日"),
        HolidayEntry(2026, 2, 11, "紀元節"),
        HolidayEntry(2026, 2, 23, "天長節"),
        HolidayEntry(2026, 3, 20, "春季皇霊祭"),
        HolidayEntry(2026, 4, 29, "昭和の日"),
        HolidayEntry(2026, 5, 3, "憲法記念日"),
        HolidayEntry(2026, 5, 4, "みどりの日"),
        HolidayEntry(2026, 5, 5, "こどもの日"),
        HolidayEntry(2026, 5, 6, ""),
        HolidayEntry(2026, 7, 20, "海の日"),
        HolidayEntry(2026, 8, 11, "山の日"),
        HolidayEntry(2026, 9, 21, "敬老の日"),
        HolidayEntry(2026, 9, 22, ""),
        HolidayEntry(2026, 9, 23, "秋季皇霊祭"),
        HolidayEntry(2026, 10, 12, "スポーツの日"),
        HolidayEntry(2026, 11, 3, "明治節"),
        HolidayEntry(2026, 11, 23, "新嘗祭"),

        HolidayEntry(2027, 1, 1, "四方節"),
        HolidayEntry(2027, 1, 11, "成人の日"),
        HolidayEntry(2027, 2, 11, "紀元節"),
        HolidayEntry(2027, 2, 23, "天長節"),
        HolidayEntry(2027, 3, 21, "春季皇霊祭"),
        HolidayEntry(2027, 3, 22, ""),
        HolidayEntry(2027, 4, 29, "昭和の日"),
        HolidayEntry(2027, 5, 3, "憲法記念日"),
        HolidayEntry(2027, 5, 4, "みどりの日"),
        HolidayEntry(2027, 5, 5, "こどもの日"),
        HolidayEntry(2027, 7, 19, "海の日"),
        HolidayEntry(2027, 8, 11, "山の日"),
        HolidayEntry(2027, 9, 20, "敬老の日"),
        HolidayEntry(2027, 9, 23, "秋季皇霊祭"),
        HolidayEntry(2027, 10, 11, "スポーツの日"),
        HolidayEntry(2027, 11, 3, "明治節"),
        HolidayEntry(2027, 11, 23, "新嘗祭"),
    )

    /** 指定日が祝日であれば表示名を返す (名称のない休日の場合は「休日」)。 */
    fun holidayName(cal: Calendar): String? {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val entry = HOLIDAYS.find { it.year == y && it.month == m && it.day == d } ?: return null
        return if (entry.name.isEmpty()) "休日" else entry.name
    }

    // ─── ユリウス日ヘルパー (0h UT / 日付のみ、時刻補正なし) ──────

    fun julianDay0h(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    /** ユリウス日 (UTC) から (year, month, day) を求める (Meeus Ch.7 逆算)。 */
    fun jdToDate(jd: Double): Triple<Int, Int, Int> {
        val z = floor(jd + 0.5)
        val a: Double = if (z < 2_299_161.0) {
            z
        } else {
            val alpha = floor((z - 1_867_216.25) / 36_524.25)
            z + 1 + alpha - floor(alpha / 4.0)
        }
        val b = a + 1524
        val c = floor((b - 122.1) / 365.25)
        val d = floor(365.25 * c)
        val e = floor((b - d) / 30.6001)

        val day = (b - d - floor(30.6001 * e)).toInt()
        val month = if (e < 14) (e - 1).toInt() else (e - 13).toInt()
        val year = if (month > 2) (c - 4716).toInt() else (c - 4715).toInt()
        return Triple(year, month, day)
    }

    private fun addDays(date: Triple<Int, Int, Int>, days: Int): Triple<Int, Int, Int> {
        val c = GregorianCalendar(date.first, date.second - 1, date.third)
        c.add(Calendar.DAY_OF_YEAR, days)
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun floorMod(x: Int, m: Int): Int = ((x % m) + m) % m
    private fun floorModLong(x: Long, m: Long): Int = (((x % m) + m) % m).toInt()
}
