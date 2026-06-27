package lab.rreedd.oyatsu

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

object MeeusSunCalc {

    /**
     * Meeusアルゴリズムに基づく正確な二十四節気の取得
     */
    fun getSolarTerm(calendar: Calendar): String {
        val terms = arrayOf(
            "春分", "清明", "穀雨", "立夏", "小満", "芒種", "夏至", "小暑", "大暑", "立秋", "処暑", "白露",
            "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至", "小寒", "大寒", "立春", "雨水", "啓蟄"
        )
        
        val jd = getJulianDay(calendar)
        val longitude = getApparentSolarLongitude(jd)
        
        // 太陽黄経は春分(0度)から始まり、15度ごとに進行する
        val index = (floor(longitude / 15.0).toInt() + 24) % 24
        return terms[index]
    }

    /**
     * Meeusアルゴリズム（NOAA準拠）に基づく日の出・日の入り計算
     */
    fun getSunriseSunsetTime(
        targetDate: Calendar,
        latitude: Double,
        longitude: Double,
        isSunrise: Boolean
    ): Calendar? {
        val tzOffsetHours = targetDate.timeZone.getOffset(targetDate.timeInMillis) / 3600000.0

        // その日の午前0時（ローカルタイム）を基準にする
        val cal = targetDate.clone() as Calendar
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // UTCでのユリウス日に変換
        val jd = getJulianDay(cal) - (tzOffsetHours / 24.0)
        val t = (jd - 2451545.0) / 36525.0

        val L0 = (280.46646 + 36000.76983 * t + 0.0003032 * t * t) % 360.0
        val M = (357.52911 + 35999.05029 * t - 0.0001537 * t * t) % 360.0
        val e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t

        val cM = Math.toRadians(M)
        val C = sin(cM) * (1.914602 - 0.004817 * t - 0.000014 * t * t) +
                sin(2 * cM) * (0.019993 - 0.000101 * t) +
                sin(3 * cM) * 0.000289
        
        val sunTrueLong = L0 + C
        val omega = 125.04 - 1934.136 * t
        val lambda = sunTrueLong - 0.00569 - 0.00478 * sin(Math.toRadians(omega))

        val epsilon0 = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0 - (46.815 * t + 0.00059 * t * t - 0.001813 * t * t * t) / 3600.0
        val epsilon = epsilon0 + 0.00256 * cos(Math.toRadians(omega))

        val declination = Math.toDegrees(asin(sin(Math.toRadians(epsilon)) * sin(Math.toRadians(lambda))))
        
        val y = tan(Math.toRadians(epsilon / 2.0)).pow(2)
        val eqTime = 4.0 * Math.toDegrees(y * sin(2.0 * Math.toRadians(L0)) -
                2.0 * e * sin(Math.toRadians(M)) +
                4.0 * e * y * sin(Math.toRadians(M)) * cos(2.0 * Math.toRadians(L0)) -
                0.5 * y * y * sin(4.0 * Math.toRadians(L0)) -
                1.25 * e * e * sin(2.0 * Math.toRadians(M)))

        // 太陽高度 -0.833度 (大気差を考慮した日の出・日の入りの基準)
        val haArg = cos(Math.toRadians(90.833)) / (cos(Math.toRadians(latitude)) * cos(Math.toRadians(declination))) -
                tan(Math.toRadians(latitude)) * tan(Math.toRadians(declination))

        // 白夜・極夜の判定
        if (haArg < -1.0 || haArg > 1.0) {
            return null
        }

        var ha = Math.toDegrees(acos(haArg))
        if (isSunrise) ha = -ha

        val solarNoon = (720.0 - 4.0 * longitude - eqTime + tzOffsetHours * 60.0)
        val timeLocalMinutes = solarNoon + ha * 4.0

        val resultCal = cal.clone() as Calendar
        resultCal.add(Calendar.MINUTE, timeLocalMinutes.toInt())
        resultCal.add(Calendar.SECOND, ((timeLocalMinutes - timeLocalMinutes.toInt()) * 60).toInt())

        return resultCal
    }

    private fun getApparentSolarLongitude(jd: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        var L0 = (280.46646 + 36000.76983 * t + 0.0003032 * t * t) % 360.0
        if (L0 < 0) L0 += 360.0

        var M = (357.52911 + 35999.05029 * t - 0.0001537 * t * t) % 360.0
        if (M < 0) M += 360.0

        val radM = Math.toRadians(M)
        val C = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(radM) +
                (0.019993 - 0.000101 * t) * sin(2 * radM) +
                0.000289 * sin(3 * radM)

        val trueLong = L0 + C
        val omega = 125.04 - 1934.136 * t
        
        var apparentLong = trueLong - 0.00569 - 0.00478 * sin(Math.toRadians(omega))
        apparentLong %= 360.0
        if (apparentLong < 0) apparentLong += 360.0

        return apparentLong
    }

    private fun getJulianDay(calendar: Calendar): Double {
        var year = calendar.get(Calendar.YEAR)
        var month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val min = calendar.get(Calendar.MINUTE)
        val sec = calendar.get(Calendar.SECOND)

        if (month <= 2) {
            year -= 1
            month += 12
        }

        val A = year / 100
        val B = 2 - A + A / 4

        val jd = floor(365.25 * (year + 4716)) + floor(30.6001 * (month + 1)) + day + B - 1524.5
        val dayFraction = (hour + min / 60.0 + sec / 3600.0) / 24.0
        return jd + dayFraction
    }
}