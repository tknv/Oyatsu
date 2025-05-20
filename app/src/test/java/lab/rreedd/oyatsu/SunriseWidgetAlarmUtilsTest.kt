package lab.rreedd.oyatsu

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator
import com.luckycatlabs.sunrisesunset.dto.Location
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowToast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// 古式時刻を表現するデータクラス（テスト用） (変更なし)
data class KoshikiTime(val eto: String, val koku: String) {
    override fun toString(): String = "$eto$koku"
}

// 各刻の時間情報を保持するデータクラス（ログ表示用） (変更なし)
data class KokuPeriod(
    val koshikiTime: KoshikiTime,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationMillis: Long
) {
    override fun toString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        return "${koshikiTime}: ${sdf.format(Date(startTimeMillis))} - ${sdf.format(Date(endTimeMillis))} (Duration: ${durationMillis / 1000.0}s)"
    }
}

// For debug log format time
private fun formatMillisToHHMMSS(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}

// For debug log format time
private fun doubleFormatMillisToHHMMSS(millis: Double): String {
    val totalSeconds = (millis / 1000).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], instrumentedPackages = arrayOf("lab.rreedd.oyatsu"))
@LooperMode(LooperMode.Mode.PAUSED)
class SunriseWidgetAlarmUtilsTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    enum class TestLocation(val id: String, val latitude: Double, val longitude: Double, var timeZoneId: String) {
        TOKYO("Tokyo", 35.681444600642514, 139.76579265965165, "Asia/Tokyo"),
        LONDON("London", 51.4767726448187, -0.0006437020557569765, "Europe/London")
    }

    // これらはインスタンス変数として保持 (テストメソッド内で使用)
    private val dayEto = listOf("卯", "辰", "巳", "午", "未", "申")
    private val nightEto = listOf("酉", "戌", "亥", "子", "丑", "寅")
    private val kokuCount = listOf("一つ", "二つ", "三つ", "四つ")

    // set system date time later
    private lateinit var testBaseCalendar: Calendar


    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        context = ApplicationProvider.getApplicationContext()
        // PREFS_NAMEはSunriseWidgetAlarmUtilsオブジェクトから直接アクセス
        sharedPreferences = context.getSharedPreferences(SunriseWidgetAlarmUtils.PREFS_NAME, Context.MODE_PRIVATE)
        editor = sharedPreferences.edit()
        editor.clear().commit()
        // sunriseWidgetAlarmUtils = SunriseWidgetAlarmUtils() // objectなのでインスタンス化不要
        // 現在のデバイスのタイムゾーンを取得
        val localTimeZoneId = TimeZone.getDefault().id
        Log.d("setUp", "Local Time Zone: $localTimeZoneId")
        // タイムゾーンをローカルタイムゾーンに変更
        TestLocation.TOKYO.timeZoneId = localTimeZoneId
        TestLocation.LONDON.timeZoneId = localTimeZoneId
        Log.d("SunriseWidgetAlarmUtilsTest", "Testing location: $localTimeZoneId")
        // 現在の日時で Calendar インスタンスを初期化
        testBaseCalendar = Calendar.getInstance()
//        testBaseCalendar = Calendar.getInstance().apply {
//            set(2025, Calendar.MAY, 17, 18, 10, 0) // 現在の日時 (例)
//            set(Calendar.MILLISECOND, 0)
//        }
        // Robolectricのシステム時刻をtestBaseCalendarの時刻に設定
//        ShadowSystemClock.setCurrentTimeMillis(testBaseCalendar.timeInMillis)
        SystemClock.setCurrentTimeMillis(testBaseCalendar.timeInMillis)
    }

    @After
    fun tearDown() {
        editor.clear().commit()
    }

    // このメソッドはテストクラスのインスタンスメソッドとして、内部でdayEtoなどを使用
    private fun getKoshikiTimeFor(
    targetTimeMillis: Long,
    sunriseMillis: Long,
    sunsetMillis: Long,
    nextSunriseMillis: Long,
    previousSunsetMillis: Long
): KoshikiTime? {
    Log.d("getKoshikiTimeFor", "targetTimeMillis: $targetTimeMillis (${Date(targetTimeMillis)})")
    Log.d("getKoshikiTimeFor", "sunriseMillis: $sunriseMillis (${Date(sunriseMillis)})")
    Log.d("getKoshikiTimeFor", "sunsetMillis: $sunsetMillis (${Date(sunsetMillis)})")
    Log.d("getKoshikiTimeFor", "nextSunriseMillis: $nextSunriseMillis (${Date(nextSunriseMillis)})")
    Log.d("getKoshikiTimeFor", "previousSunsetMillis: $previousSunsetMillis (${Date(previousSunsetMillis)})")

    return when {
        targetTimeMillis >= sunriseMillis && targetTimeMillis < sunsetMillis -> { // 昼の刻
            // TODO:: after 0 AM and before sun rise, this logic does not work
            Log.d("getKoshikiTimeFor", "--- Daytime ---")
            val dayDuration = sunsetMillis - sunriseMillis
            Log.d("getKoshikiTimeFor", "dayDuration: $dayDuration (${formatMillisToHHMMSS(dayDuration)})")
            if (dayDuration <= 0) return null
            val timeInDay = targetTimeMillis - sunriseMillis
            Log.d("getKoshikiTimeFor", "timeInDay: $timeInDay (${formatMillisToHHMMSS(timeInDay)})")
            val etoSegmentDuration = dayDuration / 6.0
            Log.d("getKoshikiTimeFor", "etoSegmentDuration: $etoSegmentDuration (${formatMillisToHHMMSS(etoSegmentDuration.toLong())})")
            val etoIndex = (timeInDay / etoSegmentDuration).toInt().coerceAtMost(5)
            Log.d("getKoshikiTimeFor", "etoIndex: $etoIndex")
            val kokuSegmentDuration = etoSegmentDuration / 4.0
            Log.d("getKoshikiTimeFor", "kokuSegmentDuration: $kokuSegmentDuration (${formatMillisToHHMMSS(kokuSegmentDuration.toLong())})")
            val timeInEtoSegment = timeInDay - (etoIndex * etoSegmentDuration)
            Log.d("getKoshikiTimeFor", "timeInEtoSegment: $timeInEtoSegment (${formatMillisToHHMMSS(timeInEtoSegment.toLong())})")
            val kokuIndex = (timeInEtoSegment / kokuSegmentDuration).toInt().coerceAtMost(3)
            Log.d("getKoshikiTimeFor", "kokuIndex: $kokuIndex")
            KoshikiTime(dayEto[etoIndex], kokuCount[kokuIndex])
        }
        targetTimeMillis >= sunsetMillis && targetTimeMillis < nextSunriseMillis -> { // 夜の刻 (日没後), 日付変更前
            Log.d("getKoshikiTimeFor", "--- Nighttime (after sunset) ---")
            val nightDuration = nextSunriseMillis - sunsetMillis
            Log.d("getKoshikiTimeFor", "nightDuration: $nightDuration (${formatMillisToHHMMSS(nightDuration)})")
            if (nightDuration <= 0) return null
            val timeInNight = targetTimeMillis - sunsetMillis
            Log.d("getKoshikiTimeFor", "timeInNight: $timeInNight (${formatMillisToHHMMSS(timeInNight)})")
            val etoSegmentDuration = nightDuration / 6.0
            Log.d("getKoshikiTimeFor", "etoSegmentDuration: $etoSegmentDuration (${formatMillisToHHMMSS(etoSegmentDuration.toLong())})")
            val etoIndex = (timeInNight / etoSegmentDuration).toInt().coerceAtMost(5)
            Log.d("getKoshikiTimeFor", "etoIndex: $etoIndex")
            val kokuSegmentDuration = etoSegmentDuration / 4.0
            Log.d("getKoshikiTimeFor", "kokuSegmentDuration: $kokuSegmentDuration (${formatMillisToHHMMSS(kokuSegmentDuration.toLong())})")
            val timeInEtoSegment = timeInNight - (etoIndex * etoSegmentDuration)
            Log.d("getKoshikiTimeFor", "timeInEtoSegment: $timeInEtoSegment (${formatMillisToHHMMSS(timeInEtoSegment.toLong())})")
            val kokuIndex = (timeInEtoSegment / kokuSegmentDuration).toInt().coerceAtMost(3)
            Log.d("getKoshikiTimeFor", "kokuIndex: $kokuIndex")
            KoshikiTime(nightEto[etoIndex], kokuCount[kokuIndex])
        }
        targetTimeMillis < sunriseMillis && targetTimeMillis >= previousSunsetMillis -> { // 夜の刻 (日の出前)、日付変更後
            Log.d("getKoshikiTimeFor", "--- Nighttime (before sunrise) ---")
            val nightDuration = sunriseMillis - previousSunsetMillis
            Log.d("getKoshikiTimeFor", "nightDuration: $nightDuration (${formatMillisToHHMMSS(nightDuration)})")
            if (nightDuration <= 0) return null
            val timeInNight = targetTimeMillis - previousSunsetMillis
            Log.d("getKoshikiTimeFor", "timeInNight: $timeInNight (${formatMillisToHHMMSS(timeInNight)})")
            val etoSegmentDuration = nightDuration / 6.0
            Log.d("getKoshikiTimeFor", "etoSegmentDuration: $etoSegmentDuration (${formatMillisToHHMMSS(etoSegmentDuration.toLong())})")
            val etoIndex = (timeInNight / etoSegmentDuration).toInt().coerceAtMost(5)
            Log.d("getKoshikiTimeFor", "etoIndex: $etoIndex")
            val kokuSegmentDuration = etoSegmentDuration / 4.0
            Log.d("getKoshikiTimeFor", "kokuSegmentDuration: $kokuSegmentDuration (${formatMillisToHHMMSS(kokuSegmentDuration.toLong())})")
            val timeInEtoSegment = timeInNight - (etoIndex * etoSegmentDuration)
            Log.d("getKoshikiTimeFor", "timeInEtoSegment: $timeInEtoSegment (${formatMillisToHHMMSS(timeInEtoSegment.toLong())})")
            val kokuIndex = (timeInEtoSegment / kokuSegmentDuration).toInt().coerceAtMost(3)
            Log.d("getKoshikiTimeFor", "kokuIndex: $kokuIndex")
            KoshikiTime(nightEto[etoIndex], kokuCount[kokuIndex])
        }
        else -> {
            Log.d("getKoshikiTimeFor", "--- Out of range ---")
            null // 範囲外
        }
    }
}


    fun koshikiTimeBoundaryTestData(): List<Array<Any?>> {
        val arguments = mutableListOf<Array<Any?>>()
        val testLocations = listOf(TestLocation.TOKYO, TestLocation.LONDON)
        val baseCal = testBaseCalendar.clone() as Calendar
        val sdfWithTimezone = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss zzzz", Locale.getDefault())
        Log.d("koshikiTimeBoundaryTestData", "baseCal: ${sdfWithTimezone.format(baseCal.time)}")

        // これらのリストはテストデータ生成にのみ使用するので、companion object内にあっても良い
        val dayEtoCompanion = listOf("卯", "辰", "巳", "午", "未", "申")
        val nightEtoCompanion = listOf("酉", "戌", "亥", "子", "丑", "寅")
        val kokuCountCompanion = listOf("一つ", "二つ", "三つ", "四つ")

        for (locationEntry in testLocations) {
            val loc = Location(locationEntry.latitude, locationEntry.longitude)
            val tz = TimeZone.getTimeZone(locationEntry.timeZoneId)
            val calculator = SunriseSunsetCalculator(loc, tz)
            Log.d("koshikiTimeBoundaryTestData", "loc: $loc, latitude: ${loc.latitude}, longitude: ${loc.longitude}, tz: $tz")

            val todayCal = baseCal.clone() as Calendar
            val sunriseCal = calculator.getOfficialSunriseCalendarForDate(todayCal.clone() as Calendar)
            val sunsetCal = calculator.getOfficialSunsetCalendarForDate(todayCal.clone() as Calendar)
            val sdf = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault())
            Log.d("koshikiTimeBoundaryTestData", "sunriseCal: $sunriseCal (${sdf.format(sunriseCal.time)}), sunsetCal: $sunsetCal (${sdf.format(sunsetCal.time)})")
            val yesterdayCal = baseCal.clone() as Calendar
            yesterdayCal.add(Calendar.DAY_OF_YEAR, -1)
            val prevSunsetCal = calculator.getOfficialSunsetCalendarForDate(yesterdayCal.clone() as Calendar)

            val tomorrowCal = baseCal.clone() as Calendar
            tomorrowCal.add(Calendar.DAY_OF_YEAR, 1)
            val nextSunriseCal = calculator.getOfficialSunriseCalendarForDate(tomorrowCal.clone() as Calendar)

            val sunriseMillis = sunriseCal.timeInMillis
            val sunsetMillis = sunsetCal.timeInMillis
            val nextSunriseMillis = nextSunriseCal.timeInMillis
            val prevSunsetMillis = prevSunsetCal.timeInMillis

            // for debug log testTime
            val sdfForTestTime = SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS z", Locale.getDefault())
            sdfForTestTime.timeZone = tz

            val dayDuration = sunsetMillis - sunriseMillis
            if (dayDuration > 0) {
                val expectedDayKokuDuration = dayDuration / 24.0
                val dayEtoUnitDuration = dayDuration / 6.0
                for (etoIdx in 0 until 6) {
                    val kokuUnitDuration = dayEtoUnitDuration / 4.0
                    for (kokuIdx in 0 until 4) {
                        val currentKokuStartTime = sunriseMillis + (etoIdx * dayEtoUnitDuration).toLong() + (kokuIdx * kokuUnitDuration).toLong()
                        val nextKokuStartTime = if (kokuIdx < 3) {
                            sunriseMillis + (etoIdx * dayEtoUnitDuration).toLong() + ((kokuIdx + 1) * kokuUnitDuration).toLong()
                        } else if (etoIdx < 5) {
                            sunriseMillis + ((etoIdx + 1) * dayEtoUnitDuration).toLong()
                        } else {
                            sunsetMillis
                        }
                        val testTime = currentKokuStartTime + 1
                        if (testTime < sunsetMillis && testTime < nextKokuStartTime) {
                            Log.d("koshikiTimeBoundaryTestData",
                                "Location: ${locationEntry.name}, Type: Day, Eto: ${dayEtoCompanion[etoIdx]}, Koku: ${kokuCountCompanion[kokuIdx]}, TestTime: ${sdfForTestTime.format(Date(testTime))} (Source: dayEtoCompanion, kokuCountCompanion)")
                            arguments.add(arrayOf(locationEntry, testTime, sunriseMillis, sunsetMillis, nextSunriseMillis, prevSunsetMillis, KoshikiTime(dayEtoCompanion[etoIdx], kokuCountCompanion[kokuIdx]), nextKokuStartTime, expectedDayKokuDuration, true))
                        }
                    }
                }
            }

            val nightDuration1 = nextSunriseMillis - sunsetMillis
            if (nightDuration1 > 0) {
                val expectedNight1KokuDuration = nightDuration1 / 24.0
                val nightEtoUnitDuration = nightDuration1 / 6.0
                for (etoIdx in 0 until 6) {
                    val kokuUnitDuration = nightEtoUnitDuration / 4.0
                    for (kokuIdx in 0 until 4) {
                        val currentKokuStartTime = sunsetMillis + (etoIdx * nightEtoUnitDuration).toLong() + (kokuIdx * kokuUnitDuration).toLong()
                        val nextKokuStartTime = if (kokuIdx < 3) {
                            sunsetMillis + (etoIdx * nightEtoUnitDuration).toLong() + ((kokuIdx + 1) * kokuUnitDuration).toLong()
                        } else if (etoIdx < 5) {
                            sunsetMillis + ((etoIdx + 1) * nightEtoUnitDuration).toLong()
                        } else {
                            nextSunriseMillis
                        }
                        val testTime = currentKokuStartTime + 1
                        if (testTime < nextSunriseMillis && testTime < nextKokuStartTime) {
                            Log.d("koshikiTimeBoundaryTestData",
                                "Location: ${locationEntry.name}, Type: Night1, Eto: ${nightEtoCompanion[etoIdx]}, Koku: ${kokuCountCompanion[kokuIdx]}, TestTime: ${sdfForTestTime.format(Date(testTime))} (Source: nightEtoCompanion, kokuCountCompanion)")
                            arguments.add(arrayOf(locationEntry, testTime, sunriseMillis, sunsetMillis, nextSunriseMillis, prevSunsetMillis, KoshikiTime(nightEtoCompanion[etoIdx], kokuCountCompanion[kokuIdx]), nextKokuStartTime, expectedNight1KokuDuration, false))
                        }
                    }
                }
            }

            val nightDuration2 = sunriseMillis - prevSunsetMillis
            if (nightDuration2 > 0) {
                val expectedNight2KokuDuration = nightDuration2 / 24.0
                val nightEtoUnitDuration = nightDuration2 / 6.0
                for (etoIdx in 0 until 6) {
                    val kokuUnitDuration = nightEtoUnitDuration / 4.0
                    for (kokuIdx in 0 until 4) {
                        val currentKokuStartTime = prevSunsetMillis + (etoIdx * nightEtoUnitDuration).toLong() + (kokuIdx * kokuUnitDuration).toLong()
                        val nextKokuStartTime = if (kokuIdx < 3) {
                            prevSunsetMillis + (etoIdx * nightEtoUnitDuration).toLong() + ((kokuIdx + 1) * kokuUnitDuration).toLong()
                        } else if (etoIdx < 5) {
                            prevSunsetMillis + ((etoIdx + 1) * nightEtoUnitDuration).toLong()
                        } else {
                            sunriseMillis
                        }
                        val testTime = currentKokuStartTime + 1
                        if (testTime < sunriseMillis && testTime < nextKokuStartTime) {
                        Log.d("koshikiTimeBoundaryTestData",
                            "Location: ${locationEntry.name}, Type: Night2, Eto: ${nightEtoCompanion[etoIdx]}, Koku: ${kokuCountCompanion[kokuIdx]}, TestTime: ${sdfForTestTime.format(Date(testTime))} (Source: nightEtoCompanion, kokuCountCompanion)")
                            arguments.add(arrayOf(locationEntry, testTime, sunriseMillis, sunsetMillis, nextSunriseMillis, prevSunsetMillis, KoshikiTime(nightEtoCompanion[etoIdx], kokuCountCompanion[kokuIdx]), nextKokuStartTime, expectedNight2KokuDuration, false))
                        }
                    }
                }
            }
        }
        val finalArguments = mutableListOf<Array<Any?>>()
        arguments.groupBy { it[0] as TestLocation }
            .forEach { (_, locArgs) ->
                val dayArgs = locArgs.filter { it[9] as Boolean }
                    .distinctBy { (it[6] as KoshikiTime).toString() }
                    .take(24)
                val nightArgs = locArgs.filter { !(it[9] as Boolean) }
                    .distinctBy { (it[6] as KoshikiTime).toString() }
                    .take(24)
                finalArguments.addAll(dayArgs)
                finalArguments.addAll(nightArgs)
            }

        testLocations.forEach { locEntry ->
            val loc = Location(locEntry.latitude, locEntry.longitude)
            val tz = TimeZone.getTimeZone(locEntry.timeZoneId)
            val calculator = SunriseSunsetCalculator(loc, tz)
            val todayCal = baseCal.clone() as Calendar
            val sunriseCal = calculator.getOfficialSunriseCalendarForDate(todayCal.clone() as Calendar)
            val sunsetCal = calculator.getOfficialSunsetCalendarForDate(todayCal.clone() as Calendar)
            val tomorrowCal = baseCal.clone() as Calendar
            tomorrowCal.add(Calendar.DAY_OF_YEAR, 1)
            val nextSunriseCal = calculator.getOfficialSunriseCalendarForDate(tomorrowCal.clone() as Calendar)
            val yesterdayCal = baseCal.clone() as Calendar
            yesterdayCal.add(Calendar.DAY_OF_YEAR, -1)
            val prevSunsetCal = calculator.getOfficialSunsetCalendarForDate(yesterdayCal.clone() as Calendar)

            val testTime = sunriseCal.timeInMillis + TimeUnit.MINUTES.toMillis(1)
            val expectedKoshiki = KoshikiTime(dayEtoCompanion[0], kokuCountCompanion[0])

            val dayDuration = sunsetCal.timeInMillis - sunriseCal.timeInMillis
            val expectedDayKokuDuration = if (dayDuration > 0) dayDuration / 24.0 else 0.0
            val dayEtoUnitDuration = if (dayDuration > 0) dayDuration / 6.0 else 0.0
            val kokuUnitDuration = if (dayEtoUnitDuration > 0) dayEtoUnitDuration / 4.0 else 0.0
            val expectedNextUpdate = sunriseCal.timeInMillis + (0 * dayEtoUnitDuration).toLong() + (1 * kokuUnitDuration).toLong()

            finalArguments.add(arrayOf(locEntry, testTime, sunriseCal.timeInMillis, sunsetCal.timeInMillis, nextSunriseCal.timeInMillis, prevSunsetCal.timeInMillis, expectedKoshiki, expectedNextUpdate, expectedDayKokuDuration, true))
        }
        return finalArguments.distinctBy { args ->
            listOf((args[0] as TestLocation).id, args[1] as Long)
        }
    }

    companion object {

        @JvmField
        val fullDayKokuLog = mutableMapOf<String, MutableList<KokuPeriod>>()

        @AfterClass
        @JvmStatic
        fun logFullDayKokuSchedule() {
            // 現在の日時で Calendar インスタンスを初期化
            var todayBaseCalendar = Calendar.getInstance()
            SystemClock.setCurrentTimeMillis(todayBaseCalendar.timeInMillis)

            // ログの開始を明確に表示
            println("=============================================")
            println("======= Full Day Koku Schedule Log ==========")
            println("=============================================")

            Log.d("logFullDayKokuSchedule", "\n--- Full Day Koku Schedule Log ---")
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.getDefault())

            // ログが空の場合は、その旨を表示
            if (fullDayKokuLog.isEmpty()) {
                println("WARNING: fullDayKokuLog is empty! No data collected during tests.")
                return
            }

            Log.d("logFullDayKokuSchedule", "Test Date: ${sdf.format(todayBaseCalendar.time)}")
            fullDayKokuLog.toSortedMap().forEach { (locationId, periods) ->
                println("\n=== Location: $locationId ===")
                Log.d("logFullDayKokuSchedule", "\nLocation: $locationId")
                val locEnum = TestLocation.valueOf(locationId.uppercase(Locale.ROOT))
                val timeZone = TimeZone.getTimeZone(locEnum.timeZoneId)
                sdf.timeZone = timeZone

                val locDto = Location(locEnum.latitude, locEnum.longitude)
                val calculator = SunriseSunsetCalculator(locDto, timeZone)
                val baseCalForLog = todayBaseCalendar.clone() as Calendar

                val sunrise = calculator.getOfficialSunriseCalendarForDate(baseCalForLog.clone() as Calendar)
                val sunset = calculator.getOfficialSunsetCalendarForDate(baseCalForLog.clone() as Calendar)
                val nextDayCal = baseCalForLog.clone() as Calendar
                nextDayCal.add(Calendar.DAY_OF_YEAR, 1)
                val nextSunrise = calculator.getOfficialSunriseCalendarForDate(nextDayCal)
                val prevDayCal = baseCalForLog.clone() as Calendar
                prevDayCal.add(Calendar.DAY_OF_YEAR, -1)
                val prevSunset = calculator.getOfficialSunsetCalendarForDate(prevDayCal)

                println("Test Date: ${sdf.format(baseCalForLog.time)}")
                println("Sunrise: ${sdf.format(sunrise.time)} (${sunrise.timeInMillis}ms)")
                println("Sunset: ${sdf.format(sunset.time)} (${sunset.timeInMillis}ms)")
                println("Previous Sunset: ${sdf.format(prevSunset.time)} (${prevSunset.timeInMillis}ms)")
                println("Next Sunrise: ${sdf.format(nextSunrise.time)} (${nextSunrise.timeInMillis}ms)")
                println("-------------------------------------")

                Log.d("logFullDayKokuSchedule", "Test Date: ${sdf.format(baseCalForLog.time)}")
                Log.d("logFullDayKokuSchedule", "Sunrise: ${sdf.format(sunrise.time)} (${sunrise.timeInMillis}ms)")
                Log.d("logFullDayKokuSchedule", "Sunset: ${sdf.format(sunset.time)} (${sunset.timeInMillis}ms)")
                Log.d("logFullDayKokuSchedule", "Previous Sunset: ${sdf.format(prevSunset.time)} (${prevSunset.timeInMillis}ms)")
                Log.d("logFullDayKokuSchedule", "Next Sunrise: ${sdf.format(nextSunrise.time)} (${nextSunrise.timeInMillis}ms)")
                Log.d("logFullDayKokuSchedule", "-------------------------------------")

                // 期間のソートと並べ替え
                val sortedPeriods = periods.sortedBy { it.startTimeMillis }
                var startIndex = sortedPeriods.indexOfFirst { it.koshikiTime.eto == "卯" && it.koshikiTime.koku == "一つ" }
                if (startIndex == -1) {
                    println("WARNING: Utsu-hitotsu not found for $locationId in sorted periods. Displaying as is.")
                    startIndex = 0
                }

                val reorderedPeriods = mutableListOf<KokuPeriod>()
                if (startIndex > 0 && sortedPeriods.isNotEmpty()) {
                    reorderedPeriods.addAll(sortedPeriods.subList(startIndex, sortedPeriods.size))
                    reorderedPeriods.addAll(sortedPeriods.subList(0, startIndex))
                } else {
                    reorderedPeriods.addAll(sortedPeriods)
                }

                // 実際の期間データをログ出力
                println("\n【古式時刻の一日スケジュール】")
                reorderedPeriods.forEach { period ->
                    val logMsg = "${period.koshikiTime.eto}${period.koshikiTime.koku}: " +
                            "Start: ${sdf.format(Date(period.startTimeMillis))} (${period.startTimeMillis % (24*60*60*1000) / (60*60*1000)}h ${period.startTimeMillis % (60*60*1000) / (60*1000)}m ${period.startTimeMillis % (60*1000) / 1000}s), " +
                            "End: ${sdf.format(Date(period.endTimeMillis))} (${period.endTimeMillis % (24*60*60*1000) / (60*60*1000)}h ${period.endTimeMillis % (60*60*1000) / (60*1000)}m ${period.endTimeMillis % (60*1000) / 1000}s), " +
                            "Duration: ${String.format("%.2f", period.durationMillis / 1000.0)}s"
                    println(logMsg)
                    Log.d("logFullDayKokuSchedule", logMsg)
                }
            }
            println("\n=============================================")
            println("======= End of Koku Schedule Log ===========")
            println("=============================================")
            Log.d("logFullDayKokuSchedule", "\n--- End of Koku Schedule Log ---")
        }
    }


    @Test
    fun testKoshikiTime_Boundaries_NextUpdate_And_Duration() {
        val allTestData = koshikiTimeBoundaryTestData()
        for (testArgs in allTestData) {
            val location = testArgs[0] as TestLocation
            val pseudCurrentTimeMillis = testArgs[1] as Long
            val sunriseMillis = testArgs[2] as Long
            val sunsetMillis = testArgs[3] as Long
            val nextSunriseMillis = testArgs[4] as Long
            val prevSunsetMillis = testArgs[5] as Long
            val expectedKoshikiTime = testArgs[6] as KoshikiTime
            val expectedNextUpdateTimeFromTestData = testArgs[7] as Long
//            val expectedKokuDuration = testArgs[8] as Long
            val expectedKokuDuration = testArgs[8] as Double

            val sdf = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault())
            Log.d("Pseud CurrentSystemTime", "Current system time (UTC): $pseudCurrentTimeMillis ms ${sdf.format(Date(pseudCurrentTimeMillis))}")
            //
            val testCaseName = "Location: ${location.id}, Time: ${Date(pseudCurrentTimeMillis)}, Expected Koshiki: $expectedKoshikiTime"
            Log.d("testKoshikiTime_Boundaries_NextUpdate_And_Duration", "Location: ${location.id}, Expected Time: ${Date(pseudCurrentTimeMillis)}, Expected Koshiki: $expectedKoshikiTime")
            Log.d(
                "testKoshikiTime_Boundaries_NextUpdate_And_Duration",
                "Location: ${location.id}, " +
                        "Time: ${sdf.format(Date(pseudCurrentTimeMillis))}, " +
                        "Sunrise: ${sdf.format(Date(sunriseMillis))}, " +
                        "Sunset: ${sdf.format(Date(sunsetMillis))}, " +
                        "Next Sunrise: ${sdf.format(Date(nextSunriseMillis))}, " +
                        "Previous Sunset: ${sdf.format(Date(prevSunsetMillis))}, " +
                        "Expected Koshiki: $expectedKoshikiTime"
            )
            val actualKoshikiTime = getKoshikiTimeFor(pseudCurrentTimeMillis, sunriseMillis, sunsetMillis, nextSunriseMillis, prevSunsetMillis)
            Log.d("actualKoshikiTime", "Location: ${location.id}, Time: ${sdf.format(Date(pseudCurrentTimeMillis))}, Sunrise: ${sdf.format(Date(sunriseMillis))}, Sunset: ${sdf.format(Date(sunsetMillis))}")
            Log.d("actualKoshikiTime", "actualKoshikiTime: $actualKoshikiTime")
            assertEquals("Koshiki time mismatch for $testCaseName", expectedKoshikiTime, actualKoshikiTime)

            val actualNextUpdateTime = calculateNextUpdateTime(pseudCurrentTimeMillis, location.latitude, location.longitude, sunriseMillis, sunsetMillis)
            val toleranceMillis = 1000L
            assertTrue(
                "Next update time mismatch for $testCaseName.\n" +
                        "Expected: ${Date(expectedNextUpdateTimeFromTestData)} (${expectedNextUpdateTimeFromTestData}ms)\n" +
                        "Actual: ${Date(actualNextUpdateTime)} (${actualNextUpdateTime}ms)\n" +
                        "Diff: ${abs(expectedNextUpdateTimeFromTestData - actualNextUpdateTime)}ms " +
                        "(${formatMillisToHHMMSS(abs(expectedNextUpdateTimeFromTestData - actualNextUpdateTime))})",
                abs(expectedNextUpdateTimeFromTestData - actualNextUpdateTime) <= toleranceMillis
            )

            val currentKokuStartTime = pseudCurrentTimeMillis -1
            val actualKokuDuration = actualNextUpdateTime - currentKokuStartTime
            //
//            val durationTolerance = expectedKokuDuration * 0.01
            val durationTolerance = expectedKokuDuration * 0.01
            val tolerance = durationTolerance.coerceAtLeast(50.0)
            val difference = abs(actualKokuDuration - expectedKokuDuration)

            // デバッグログを追加
            Log.d("Koku duration mismatch test", """
            [DEBUG] Koku Duration Analysis for $testCaseName:
            - Current Koku Start Time: $currentKokuStartTime (${Date(currentKokuStartTime)})
            - Actual Next Update Time: $actualNextUpdateTime (${Date(actualNextUpdateTime)})
            - Actual Koku Duration: $actualKokuDuration ms (${formatMillisToHHMMSS(actualKokuDuration)})
            - Expected Koku Duration: $expectedKokuDuration ms (${doubleFormatMillisToHHMMSS(expectedKokuDuration)})
            - Duration Tolerance: $durationTolerance ms (${doubleFormatMillisToHHMMSS(durationTolerance)})
            - Applied Tolerance (min 50ms): $tolerance ms (${doubleFormatMillisToHHMMSS(tolerance)})
            - Actual Difference: $difference ms (${doubleFormatMillisToHHMMSS(difference)})
            - Tolerance Check: ${difference <= tolerance}
            - Sunrise: ${Date(sunriseMillis)}
            - Sunset: ${Date(sunsetMillis)}
            - Next Sunrise: ${Date(nextSunriseMillis)}
            - Previous Sunset: ${Date(prevSunsetMillis)}
            """.trimIndent())

            assertTrue("Koku duration mismatch for $testCaseName. " +
                    "Expected duration: ${expectedKokuDuration}ms, Actual duration: ${actualKokuDuration}ms. " +
                    "Difference: ${difference}ms, Allowed tolerance: ${tolerance}ms. " +
                    "Sunrise: ${Date(sunriseMillis)}, Sunset: ${Date(sunsetMillis)}, " +
                    "NextSunrise: ${Date(nextSunriseMillis)}, PrevSunset: ${Date(prevSunsetMillis)}",
                difference <= tolerance
            )
            //

//            val durationTolerance = expectedKokuDuration * 0.01
//            assertTrue("Koku duration mismatch for $testCaseName. " +
//                    "Expected duration: ${expectedKokuDuration}ms, Actual duration: ${actualKokuDuration}ms. " +
//                    "Sunrise: ${Date(sunriseMillis)}, Sunset: ${Date(sunsetMillis)}, NextSunrise: ${Date(nextSunriseMillis)}, PrevSunset: ${Date(prevSunsetMillis)}",
//                abs(actualKokuDuration - expectedKokuDuration) <= durationTolerance.coerceAtLeast(50.0)
//            )

            val kokuPeriod = KokuPeriod(expectedKoshikiTime, currentKokuStartTime, actualNextUpdateTime, actualKokuDuration)
            fullDayKokuLog.computeIfAbsent(location.id) { mutableListOf() }.add(kokuPeriod)

            if (pseudCurrentTimeMillis == sunriseMillis + TimeUnit.MINUTES.toMillis(1) && dayEto.isNotEmpty() && kokuCount.size > 1) { // Ensure kokuCount has at least two elements
                val expectedUHitotsu = KoshikiTime(dayEto[0], kokuCount[0])
                assertEquals("日の出後1分は「${dayEto[0]}${kokuCount[0]}」であるべき ($testCaseName)", expectedUHitotsu, actualKoshikiTime)

                val dayDuration = sunsetMillis - sunriseMillis
                if (dayDuration > 0) {
                    val dayEtoUnitDuration = dayDuration / 6.0
                    val kokuUnitDuration = dayEtoUnitDuration / 4.0
                    if (kokuUnitDuration > 0) {
                        val expectedUtsuFutatsuStart = sunriseMillis + (0 * dayEtoUnitDuration).toLong() + (1 * kokuUnitDuration).toLong()
                        assertTrue("日の出後1分の場合の次の更新時刻は「${dayEto[0]}${kokuCount[1]}」の開始であるべき ($testCaseName). Expected: ${Date(expectedUtsuFutatsuStart)}, Actual: ${Date(actualNextUpdateTime)}",
                            abs(expectedUtsuFutatsuStart - actualNextUpdateTime) <= toleranceMillis
                        )
                    }
                }
            }
        }
    }

    @Test
    fun testGetCoordinates() {
        val appWidgetId = 1
        val expectedLat = 35.0
        val expectedLon = 139.0

        // 保存時に`String`として保存
        editor.putString(SunriseWidgetAlarmUtils.PREF_LATITUDE_PREFIX + appWidgetId, expectedLat.toString())
        editor.putString(SunriseWidgetAlarmUtils.PREF_LONGITUDE_PREFIX + appWidgetId, expectedLon.toString())
        editor.commit()

        // メソッドで取得し、`Double`として扱う
        val (lat, lon) = SunriseWidgetAlarmUtils.getCoordinates(context, appWidgetId)

        // 精度を考慮して比較
        assertEquals(expectedLat, lat, 0.00001)
        assertEquals(expectedLon, lon, 0.00001)
    }

    @Test
    fun testValidateAndSaveCoordinates_valid() {
        val appWidgetId = 1
        val lat = 35.0
        val lon = 139.0

        // Call method on the object
        SunriseWidgetAlarmUtils.validateAndSaveCoordinates(context, appWidgetId, lat, lon)
//        val savedLat = sharedPreferences.getString(SunriseWidgetAlarmUtils.PREF_LATITUDE_PREFIX + appWidgetId, )
////        val savedLon = sharedPreferences.getFloat(SunriseWidgetAlarmUtils.PREF_LONGITUDE_PREFIX + appWidgetId, 0f)
////
////        assertEquals(lat.toFloat(), savedLat, 0.001f)
////        assertEquals(lon.toFloat(), savedLon, 0.001f)
////        val savedLat = getLatitude(sharedPreferences, appWidgetId)
////        val savedLon = getLongitude(sharedPreferences, appWidgetId)
//
//        assertEquals(lat, savedLat)
//        assertEquals(lon, savedLon, 0.001)
        val savedLat = sharedPreferences.getString(SunriseWidgetAlarmUtils.PREF_LATITUDE_PREFIX + appWidgetId, "0.0")!!.toDouble()
        val savedLon = sharedPreferences.getString(SunriseWidgetAlarmUtils.PREF_LONGITUDE_PREFIX + appWidgetId, "0.0")!!.toDouble()

        assertEquals(lat, savedLat, 0.001)
        assertEquals(lon, savedLon, 0.001)
    }

    @Test
    fun testValidateAndSaveCoordinates_invalidLatitude() {
        val appWidgetId = 1
        val lat = 95.0 // 無効
        val lon = 139.0

        // Call method on the object
        SunriseWidgetAlarmUtils.validateAndSaveCoordinates(context, appWidgetId, lat, lon)

        val savedLat = sharedPreferences.getFloat(SunriseWidgetAlarmUtils.PREF_LATITUDE_PREFIX + appWidgetId, -999f)
        val savedLon = sharedPreferences.getFloat(SunriseWidgetAlarmUtils.PREF_LONGITUDE_PREFIX + appWidgetId, -999f)

        assertEquals(-999f, savedLat, 0f)
        assertEquals(-999f, savedLon, 0f)

        val latestToast = ShadowToast.getTextOfLatestToast()
        assertTrue("Error Toast should be shown (expected text: $latestToast)", latestToast != null && latestToast.contains("無効な緯度または経度が入力されました"))
    }


    @Test
    fun testGetSunriseSunsetTime_noCache() {
        val appWidgetId = 1
        val testCal = testBaseCalendar.clone() as Calendar

        editor.putString(SunriseWidgetAlarmUtils.PREF_LATITUDE_PREFIX + appWidgetId, TestLocation.TOKYO.latitude.toString())
        editor.putString(SunriseWidgetAlarmUtils.PREF_LONGITUDE_PREFIX + appWidgetId, TestLocation.TOKYO.longitude.toString())
        editor.commit()

        val loc = Location(TestLocation.TOKYO.latitude, TestLocation.TOKYO.longitude)
        val tz = TimeZone.getTimeZone(TestLocation.TOKYO.timeZoneId)
        val calculator = SunriseSunsetCalculator(loc, tz)
        val expectedSunrise = calculator.getOfficialSunriseCalendarForDate(testCal.clone() as Calendar)
        Log.d("testGetSunriseSunsetTime_noCache", "expectedSunrise: $expectedSunrise at: $loc - $tz")
        // Call method on the object
        val actualSunrise = SunriseWidgetAlarmUtils.getSunriseSunsetTime(testCal, sharedPreferences, appWidgetId, true, false)

        assertEquals(expectedSunrise.timeInMillis, actualSunrise?.timeInMillis)

        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(testCal.time)
        val savedDateStr = sharedPreferences.getString(SunriseWidgetAlarmUtils.PREF_SUNRISE_TIME_PREFIX + appWidgetId + "_date", null)
        val savedMillis = sharedPreferences.getLong(SunriseWidgetAlarmUtils.PREF_SUNRISE_TIME_PREFIX + appWidgetId + "_millis", -1L)

        assertEquals(dateStr, savedDateStr)
        assertEquals(expectedSunrise.timeInMillis, savedMillis)
    }

    @Test
    fun testGetSunriseSunsetTime_withCache() {
        val appWidgetId = 1
        val testCal = testBaseCalendar.clone() as Calendar
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(testCal.time)
        val expectedSunriseMillis = testCal.timeInMillis - TimeUnit.HOURS.toMillis(6)

        val timeKey = SunriseWidgetAlarmUtils.PREF_SUNRISE_TIME_PREFIX + appWidgetId
        editor.putString(timeKey + "_date", dateStr)
        editor.putLong(timeKey + "_millis", expectedSunriseMillis)
        editor.commit()

        // Call method on the object
        val actualSunrise = SunriseWidgetAlarmUtils.getSunriseSunsetTime(testCal, sharedPreferences, appWidgetId, true, false)

        assertEquals(expectedSunriseMillis, actualSunrise?.timeInMillis)

        val stillCachedDate = sharedPreferences.getString(timeKey + "_date", null)
        val stillCachedMillis = sharedPreferences.getLong(timeKey + "_millis", -1L)
        assertEquals(dateStr, stillCachedDate)
        assertEquals(expectedSunriseMillis, stillCachedMillis)
    }

    @Test
    fun testGetSunriseSunsetTime_forceRecalc() {
        val appWidgetId = 1
        val testCal = testBaseCalendar.clone() as Calendar
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(testCal.time)
        val cachedSunriseMillis = testCal.timeInMillis - TimeUnit.HOURS.toMillis(7)

        val timeKey = SunriseWidgetAlarmUtils.PREF_SUNRISE_TIME_PREFIX + appWidgetId
        editor.putString(timeKey + "_date", dateStr)
        editor.putLong(timeKey + "_millis", cachedSunriseMillis)
        editor.putString(SunriseWidgetAlarmUtils.PREF_LATITUDE_PREFIX + appWidgetId, TestLocation.TOKYO.latitude.toString())
        editor.putString(SunriseWidgetAlarmUtils.PREF_LONGITUDE_PREFIX + appWidgetId, TestLocation.TOKYO.longitude.toString())
        editor.commit()

        val loc = Location(TestLocation.TOKYO.latitude, TestLocation.TOKYO.longitude)
        val tz = TimeZone.getTimeZone(TestLocation.TOKYO.timeZoneId)
        val calculator = SunriseSunsetCalculator(loc, tz)
        val expectedRecalculatedSunrise = calculator.getOfficialSunriseCalendarForDate(testCal.clone() as Calendar)

        // Call method on the object
        val actualSunrise = SunriseWidgetAlarmUtils.getSunriseSunsetTime(testCal, sharedPreferences, appWidgetId, true, true)

        assertEquals(expectedRecalculatedSunrise.timeInMillis, actualSunrise?.timeInMillis)

        val newSavedDateStr = sharedPreferences.getString(timeKey + "_date", null)
        val newSavedMillis = sharedPreferences.getLong(timeKey + "_millis", -1L)

        assertEquals(dateStr, newSavedDateStr)
        assertEquals(expectedRecalculatedSunrise.timeInMillis, newSavedMillis)
    }
}