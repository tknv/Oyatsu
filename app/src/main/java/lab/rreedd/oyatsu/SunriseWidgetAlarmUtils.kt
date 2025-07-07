package lab.rreedd.oyatsu

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.edit
import androidx.work.*
import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator
import com.luckycatlabs.sunrisesunset.dto.Location
import lab.rreedd.oyatsu.SunriseWidgetAlarmUtils.ACTION_ALARM_UPDATE
import lab.rreedd.oyatsu.SunriseWidgetAlarmUtils.formatMillisToMMSS
import lab.rreedd.oyatsu.SunriseWidgetAlarmUtils.formatTimestampToHHMMSS
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val TAG = "OyatsuWidget"
// ウィジェット更新のアラームスケジューリングを扱うユーティリティクラス
object SunriseWidgetAlarmUtils {
    //    private const val TAG = "SunriseWidgetAlarm"
    const val PREFS_NAME = "lab.rreedd.oyatsu.OyatsuWidgetPrefs"

    // 定数定義
    const val ACTION_ALARM_UPDATE = "lab.rreedd.oyatsu.ALARM_UPDATE"
    const val PREF_SUNRISE_TIME_PREFIX = "sunrise_time_"
    const val PREF_SUNSET_TIME_PREFIX = "sunset_time_"
    const val PREF_LATITUDE_PREFIX = "latitude_"
    const val PREF_LONGITUDE_PREFIX = "longitude_"
    const val PREF_LAST_CALC_DATE_PREFIX = "last_calc_date_" // 日の出入り時刻を計算した日付を保存

    // デフォルト位置情報（例：東京）
    const val DEFAULT_LATITUDE = 35.681444600642514
    const val DEFAULT_LONGITUDE = 139.76579265965165

    // For debug time format
    fun formatMillisToMMSS(millis: Long): String {
        val totalMinutes = millis / (1000 * 60)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val seconds = (millis / 1000) % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun formatTimestampToHHMMSS(millis: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(millis))
    }
    //

    fun getCoordinates(context: Context, appWidgetId: Int): Pair<Double, Double> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val latitudeString = prefs.getString(PREF_LATITUDE_PREFIX + appWidgetId, null)
        val longitudeString = prefs.getString(PREF_LONGITUDE_PREFIX + appWidgetId, null)

        val latitude = try {
            latitudeString?.toDouble() ?: DEFAULT_LATITUDE
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Invalid latitude format for widget $appWidgetId: $latitudeString", e)
            DEFAULT_LATITUDE
        }

        val longitude = try {
            longitudeString?.toDouble() ?: DEFAULT_LONGITUDE
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Invalid longitude format for widget $appWidgetId: $longitudeString", e)
            DEFAULT_LONGITUDE
        }
        Log.d("getCoordinates", "Widget $appWidgetId: Lat=$latitude, Lon=$longitude")
        return latitude to longitude
    }

    fun validateAndSaveCoordinates(
        context: Context,
        appWidgetId: Int,
        latitude: Double?,
        longitude: Double?
    ) {
        if (latitude == null || longitude == null || !isValidLatitude(latitude) || !isValidLongitude(
                longitude
            )
        ) {
            // TODO:: do strings.xml
            Toast.makeText(context, "無効な緯度または経度が入力されました", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(PREF_LATITUDE_PREFIX + appWidgetId, latitude.toString())
            putString(PREF_LONGITUDE_PREFIX + appWidgetId, longitude.toString())
            apply()
        }
    }

    fun updateWidgetCoordinates(context: Context, appWidgetId: Int, views: RemoteViews) {
        val (latitude, longitude) = getCoordinates(context, appWidgetId)
        views.setTextViewText(R.id.editTextLatitude, "緯度: $latitude")
        views.setTextViewText(R.id.editTextLongitude, "経度: $longitude")
        Log.d(
            "SunriseWidgetAlarmUtils",
            "Widget $appWidgetId updated with coordinates: Lat=$latitude, Lon=$longitude"
        )
    }

    private fun isValidLatitude(latitude: Double): Boolean {
        return latitude in -90.0..90.0
    }

    private fun isValidLongitude(longitude: Double): Boolean {
        return longitude in -180.0..180.0
    }

    // ウィジェットの次回更新をスケジュールする
    fun scheduleNextUpdate(pseudToday: Calendar, context: Context, appWidgetId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Get lat lon
        val latitudeString = prefs.getString(PREF_LATITUDE_PREFIX + appWidgetId, null)
        val longitudeString = prefs.getString(PREF_LONGITUDE_PREFIX + appWidgetId, null)
        val currentLatitude = try {
            latitudeString?.toDouble() ?: DEFAULT_LATITUDE
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Invalid latitude format for widget $appWidgetId: $latitudeString", e)
            DEFAULT_LATITUDE
        }

        val currentLongitude = try {
            longitudeString?.toDouble() ?: DEFAULT_LONGITUDE
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Invalid longitude format for widget $appWidgetId: $longitudeString", e)
            DEFAULT_LONGITUDE
        }
        // 日の出/日の入り時刻を取得
        val todaySunrise = getSunriseSunsetTime(
            context,
            appWidgetId,
            currentLatitude,
            currentLongitude,
            pseudToday,
            true,
            false
        )
        val todaySunset = getSunriseSunsetTime(
            context,
            appWidgetId,
            currentLatitude,
            currentLongitude,
            pseudToday,
            false,
            false
        )

        if (todaySunrise == null || todaySunset == null) {
            Log.w(
                TAG,
                "Cannot schedule next update for $appWidgetId: Sunrise/sunset time not available (likely missing location)."
            )
            // 日の出/日の入り時刻が取得できない場合、スケジュールできない
            Log.d(
                "scheduleNextUpdate",
                "Cannot schedule next update for $appWidgetId: Sunrise/sunset time not available (likely missing location)."
            )
            cancelAlarm(context, appWidgetId)
            return
        }

        // 日の入り時刻が日の出時刻より前の場合、日付が翌日にまたがったと判断し、
        // 日の入りのカレンダーを1日進める。
        if (todaySunset.timeInMillis < todaySunrise.timeInMillis) {
            Log.d(TAG, "Sunset time (${formatTimestampToHHMMSS(todaySunset.timeInMillis)}) is before sunrise time (${formatTimestampToHHMMSS(todaySunrise.timeInMillis)}). Adjusting sunset date to the next day.")
            todaySunset.add(Calendar.DAY_OF_YEAR, 1)
            Log.d(TAG, "Adjusted sunset time: ${Date(todaySunset.timeInMillis)}")

            // SharedPreferencesにも補正後の値を保存し直すことで、次回以降の読み込みでも正しい値を使う
            prefs.edit {
                putLong(PREF_SUNSET_TIME_PREFIX + appWidgetId, todaySunset.timeInMillis)
                apply()
            }
        }

        // 現在の時間から次の更新時刻を計算
        val nowMillis = System.currentTimeMillis()
        val nextUpdateMillis =
            calculateNextUpdateTime(
                nowMillis,
                currentLatitude,
                currentLongitude,
                todaySunrise.timeInMillis,
                todaySunset.timeInMillis
            )
        Log.d("scheduleNextUpdate", "Next update for $appWidgetId scheduled at $nextUpdateMillis")
        scheduleWork(context, nextUpdateMillis, appWidgetId)
    }

    private fun scheduleWork(context: Context, nextUpdateMillis: Long, appWidgetId: Int) {
        val workRequest = OneTimeWorkRequestBuilder<SunriseUpdateWorker>()
            .setInitialDelay(nextUpdateMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
//                    .setRequiresDeviceIdle(true)
                    .build()
            )
            .addTag("WidgetUpdate$appWidgetId")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "WidgetUpdateWork$appWidgetId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        Log.i(
            TAG,
            "Scheduled WorkManager job for widget $appWidgetId at ${
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.ROOT
                ).format(Date(nextUpdateMillis))
            }"
        )
    }

    /**
     * 日の出または日の入り時刻を取得する。
     * 必要に応じて再計算し、SharedPreferencesに保存する。
     * @param context Context
     * @param appWidgetId ウィジェットID
     * @param latitude 緯度
     * @param longitude 経度
     * @param targetDate 計算対象の日付
     * @param isSunrise trueなら日の出、falseなら日の入り
     * @param forceRecalc trueの場合、保存された時刻に関わらず強制的に再計算する
     * @return 計算された日の出または日の入り時刻 (Calendarオブジェクト)
     */
    fun getSunriseSunsetTime(
        context: Context,
        appWidgetId: Int,
        latitude: Double,
        longitude: Double,
        targetDate: Calendar,
        isSunrise: Boolean,
        forceRecalc: Boolean
    ): Calendar? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayDateString = dateFormat.format(targetDate.time)

        val storedTimeKey =
            (if (isSunrise) PREF_SUNRISE_TIME_PREFIX else PREF_SUNSET_TIME_PREFIX) + appWidgetId
        val storedDateKey = PREF_LAST_CALC_DATE_PREFIX + appWidgetId

        val storedMillis = prefs.getLong(storedTimeKey, -1L)
        val lastCalcDate = prefs.getString(storedDateKey, null)

        // 強制再計算でなく、かつ、保存された日付が今日と同じで、かつ、時刻が有効であれば、保存された時刻を返す
        if (!forceRecalc && storedMillis != -1L && lastCalcDate == todayDateString) {
            val storedCalendar = Calendar.getInstance()
            storedCalendar.timeInMillis = storedMillis
            Log.d(
                TAG,
                "Using stored ${if (isSunrise) "sunrise" else "sunset"} time for widget $appWidgetId: ${
                    SimpleDateFormat(
                        "HH:mm:ss",
                        Locale.US
                    ).format(storedCalendar.time)
                }"
            )
            return storedCalendar
        }

        // 再計算が必要な場合
        Log.d(
            TAG,
            "Recalculating ${if (isSunrise) "sunrise" else "sunset"} time for widget $appWidgetId. Force: $forceRecalc, StoredDate: $lastCalcDate, TodayDate: $todayDateString"
        )
        try {
            val location = Location(latitude, longitude)
            // タイムゾーンは、targetDateから取得する (端末の現在のタイムゾーンを使用)
            val calculator = SunriseSunsetCalculator(location, targetDate.timeZone.id)

            val calculatedTime = if (isSunrise) {
                val sunriseCalc = calculator.getOfficialSunriseCalendarForDate(targetDate)
                Log.d(
                    TAG,
                    "Calculated Sunrise for $todayDateString: ${
                        sunriseCalc?.let {
                            SimpleDateFormat(
                                "HH:mm:ss",
                                Locale.US
                            ).format(it.time)
                        } ?: "null"
                    }")
                sunriseCalc
            } else {
                val sunsetCalc =
                    calculator.getOfficialSunsetCalendarForDate(targetDate) // ここをgetOfficialSunsetCalendarForDateに修正
                Log.d(
                    TAG,
                    "Calculated Sunset for $todayDateString: ${
                        sunsetCalc?.let {
                            SimpleDateFormat(
                                "HH:mm:ss",
                                Locale.US
                            ).format(it.time)
                        } ?: "null"
                    }")
                sunsetCalc
            }

            calculatedTime?.let {
                // 秒とミリ秒を0に設定して、分単位での比較を容易にする
                it.set(Calendar.SECOND, 0)
                it.set(Calendar.MILLISECOND, 0)

                prefs.edit {
                    putLong(storedTimeKey, it.timeInMillis)
                    putString(storedDateKey, todayDateString) // 計算した日付を保存
                }
                Log.d(
                    TAG,
                    "Calculated and saved ${if (isSunrise) "sunrise" else "sunset"} time for widget $appWidgetId: ${
                        SimpleDateFormat(
                            "HH:mm:ss",
                            Locale.US
                        ).format(it.time)
                    }"
                )
                return it
            } ?: run {
                Log.e(
                    TAG,
                    "Failed to calculate ${if (isSunrise) "sunrise" else "sunset"} time for widget $appWidgetId."
                )
                return null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error calculating sunrise/sunset for widget $appWidgetId: ${e.message}", e)
            return null
        }
    }

    class SunriseUpdateWorker(context: Context, workerParams: WorkerParameters) :
        Worker(context, workerParams) {
        override fun doWork(): Result {
            val appWidgetId = inputData.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)

            if (appWidgetId == -1) {
                Log.e(TAG, "Invalid widget ID in WorkManager")
                return Result.failure()
            }

            val context = applicationContext
            val today = Calendar.getInstance()
            SunriseWidgetAlarmUtils.scheduleNextUpdate(today, context, appWidgetId)
            Log.i(TAG, "WorkManager executed for widget $appWidgetId")

            return Result.success()
        }
    }

    //
    // ▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼
    // Error correction: The logic for calculating the next update time has been revised.
    // エラー修正：次回更新時刻の計算ロジックを修正しました。
    // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

    // 現在時刻に基づいて次の更新時刻を計算する
    fun calculateNextUpdateTime(
        idealNowMillis: Long,
        latitude: Double,
        longitude: Double,
        sunriseMillis: Long, // 今日の日の出
        sunsetMillis: Long  // 今日の日の入り
    ): Long {
        val nowMillis = idealNowMillis
        val location = Location(latitude, longitude)
        val timeZone = TimeZone.getDefault()
        val calculator = SunriseSunsetCalculator(location, timeZone)

        Log.d(
            "calcNextUpdateTime_In",
            "idealNowMillis (pseud time): $nowMillis (${Date(nowMillis)})"
        )
        Log.d("calcNextUpdateTime_In", "Today's Sunrise: $sunriseMillis (${Date(sunriseMillis)})")
        Log.d("calcNextUpdateTime_In", "Today's Sunset: $sunsetMillis (${Date(sunsetMillis)})")


        return if (nowMillis in sunriseMillis until sunsetMillis) {
            // --- Case 1: Daytime ---
            // 現在時刻が今日の日の出と日の入りの間
            Log.d("calculateNextUpdateTime", "Currently daytime.")
            calculateDaytimeNextUpdate(nowMillis, sunriseMillis, sunsetMillis)

        } else {
            // --- Case 2: Nighttime ---
            Log.d("calculateNextUpdateTime", "Currently nighttime.")
            val nextSunriseMillis: Long
            val pastSunsetMillis: Long

            val todayCalendar = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }

            if (nowMillis < sunriseMillis) {
                // --- Case 2a: Night, before today's sunrise (e.g., 00:00 - 05:30) ---
                // 現在時刻が深夜0時から今日の日の出までの間
                Log.d("calculateNextUpdateTime", "Night, before today's sunrise.")

                // 次の日の出は「今日」の日の出
                nextSunriseMillis = sunriseMillis

                // 過ぎた日の入りは「昨日」の日の入り
                val yesterdayCalendar = (todayCalendar.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, -1)
                }
                pastSunsetMillis = calculator.getOfficialSunsetCalendarForDate(yesterdayCalendar).timeInMillis

            } else { // nowMillis >= sunsetMillis
                // --- Case 2b: Night, after today's sunset (e.g., 19:00 - 23:59) ---
                // 現在時刻が今日の日の入りから深夜0時までの間
                Log.d("calculateNextUpdateTime", "Night, after today's sunset.")

                // 過ぎた日の入りは「今日」の日の入り
                pastSunsetMillis = sunsetMillis

                // 次の日の出は「明日」の日の出
                val tomorrowCalendar = (todayCalendar.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                nextSunriseMillis = calculator.getOfficialSunriseCalendarForDate(tomorrowCalendar).timeInMillis
            }

            Log.d("calculateNextUpdateTime", "Past Sunset for calc: $pastSunsetMillis (${Date(pastSunsetMillis)})")
            Log.d("calculateNextUpdateTime", "Next Sunrise for calc: $nextSunriseMillis (${Date(nextSunriseMillis)})")

            calculateNighttimeNextUpdate(nowMillis, nextSunriseMillis, pastSunsetMillis)
        }
    }

    // 日中（日の出から日の入りまで）の次の更新時刻を計算
    private fun calculateDaytimeNextUpdate(
        nowMillis: Long,
        sunriseMillis: Long,
        sunsetMillis: Long
    ): Long {
        Log.d("calcDaytimeNextUpdate", "sunriseMillis: $sunriseMillis (${Date(sunriseMillis)})")
        Log.d("calcDaytimeNextUpdate", "sunsetMillis: $sunsetMillis (${Date(sunsetMillis)})")
        val dayDurationMillis = sunsetMillis - sunriseMillis
        val timeUnitMillis = dayDurationMillis / 6.0 // 大区間（6等分）
        val timeSubUnitMillis = timeUnitMillis / 4.0 // 小区間（各大区間をさらに4等分）
        val timePassedMillis = nowMillis - sunriseMillis

        // 現在の大区間インデックス（0-5）
        val currentUnitIndex = (timePassedMillis / timeUnitMillis).toInt()

        // 現在の小区間インデックス（0-3）
        val currentSubUnitIndex = ((timePassedMillis % timeUnitMillis) / timeSubUnitMillis).toInt()

        // 次の小区間の開始時刻を計算
        val nextSubUnitIndex = currentSubUnitIndex + 1

        Log.d(
            "calcDaytimeNextUpdate",
            "dayDurationMillis: $dayDurationMillis (${formatMillisToMMSS(dayDurationMillis)})"
        )
        Log.d(
            "calcDaytimeNextUpdate",
            "timeUnitMillis: $timeUnitMillis (${formatMillisToMMSS(timeUnitMillis.toLong())})"
        )
        Log.d(
            "calcDaytimeNextUpdate",
            "timeSubUnitMillis: $timeSubUnitMillis (${formatMillisToMMSS(timeSubUnitMillis.toLong())})"
        )
        Log.d(
            "calcDaytimeNextUpdate",
            "timePassedMillis: $timePassedMillis (${formatMillisToMMSS(timePassedMillis)})"
        )
        Log.d("calcDaytimeNextUpdate", "currentUnitIndex: $currentUnitIndex")
        Log.d("calcDaytimeNextUpdate", "currentSubUnitIndex: $currentSubUnitIndex")
        Log.d("calcDaytimeNextUpdate", "nextSubUnitIndex: $nextSubUnitIndex")

        return if (nextSubUnitIndex < 4) {
            // 同じ大区間内の次の小区間
            val nextUpdateTime =
                sunriseMillis + (currentUnitIndex * timeUnitMillis).toLong() + (nextSubUnitIndex * timeSubUnitMillis).toLong()
            Log.d(
                "calcDaytimeNextUpdate",
                "nextUpdateTime (same unit): $nextUpdateTime (${
                    formatTimestampToHHMMSS(nextUpdateTime)
                })"
            )
            nextUpdateTime
        } else {
            // 次の大区間の最初の小区間
            val nextUnitIndex = currentUnitIndex + 1
            return if (nextUnitIndex < 6) {
                val nextUpdateTime = sunriseMillis + (nextUnitIndex * timeUnitMillis).toLong()
                Log.d(
                    "calcDaytimeNextUpdate",
                    "nextUpdateTime (next unit): $nextUpdateTime (${
                        formatTimestampToHHMMSS(nextUpdateTime)
                    })"
                )
                nextUpdateTime
            } else {
                // 日中の最後の区間を過ぎた場合は日没時
                Log.d(
                    "calcDaytimeNextUpdate",
                    "nextUpdateTime (sunset): $sunsetMillis (${formatTimestampToHHMMSS(sunsetMillis)})"
                )
                sunsetMillis
            }
        }
    }

    // 夜間（日の入りから次の日の出まで）の次の更新時刻を計算
    private fun calculateNighttimeNextUpdate(
        nowMillis: Long,
        nextSunriseMillis: Long, // 次の日の日の出
        pastSunsetMillis: Long // 過ぎた日の入り
    ): Long {
        val nightStartMillis = pastSunsetMillis
        val nightEndMillis = nextSunriseMillis

        val nightDurationMillis = nightEndMillis - nightStartMillis
        val timeUnitMillis = nightDurationMillis / 6.0
        val timeSubUnitMillis = timeUnitMillis / 4.0
        val timePassedMillis = nowMillis - nightStartMillis
        val currentUnitIndex = (timePassedMillis / timeUnitMillis).toInt()
        val currentSubUnitIndex = ((timePassedMillis % timeUnitMillis) / timeSubUnitMillis).toInt()
        val nextSubUnitIndex = currentSubUnitIndex + 1

        Log.d(
            "calcNightNextUpdate",
            "nightStartMillis: $nightStartMillis (${Date(nightStartMillis)})"
        )
        Log.d("calcNightNextUpdate", "nightEndMillis: $nightEndMillis (${Date(nightEndMillis)})")
        Log.d(
            "calcNightNextUpdate",
            "nightDurationMillis: $nightDurationMillis (${formatMillisToMMSS(nightDurationMillis)})"
        )
        Log.d(
            "calcNightNextUpdate",
            "timeUnitMillis: $timeUnitMillis (${
                formatMillisToMMSS(
                    timeUnitMillis.roundToInt().toLong()
                )
            })"
        )
        Log.d(
            "calcNightNextUpdate",
            "timeSubUnitMillis: $timeSubUnitMillis (${
                formatMillisToMMSS(
                    timeSubUnitMillis.roundToInt().toLong()
                )
            })"
        )
        Log.d(
            "calcNightNextUpdate",
            "timePassedMillis: $timePassedMillis (${formatMillisToMMSS(timePassedMillis)})"
        )
        Log.d("calcNightNextUpdate", "currentUnitIndex: $currentUnitIndex")
        Log.d("calcNightNextUpdate", "currentSubUnitIndex: $currentSubUnitIndex")
        Log.d("calcNightNextUpdate", "nextSubUnitIndex: $nextSubUnitIndex")

        return if (nextSubUnitIndex < 4) {
            val nextUpdateTime =
                nightStartMillis + (currentUnitIndex * timeUnitMillis).toLong() + (nextSubUnitIndex * timeSubUnitMillis).toLong()
            Log.d(
                "calcNightNextUpdate",
                "nextUpdateTime (same unit): $nextUpdateTime (${Date(nextUpdateTime)})"
            )
            nextUpdateTime
        } else {
            val nextUnitIndex = currentUnitIndex + 1
            return if (nextUnitIndex < 6) {
                val nextUpdateTime = nightStartMillis + (nextUnitIndex * timeUnitMillis).toLong()
                Log.d(
                    "calcNightNextUpdate",
                    "nextUpdateTime (next unit): $nextUpdateTime (${Date(nextUpdateTime)})"
                )
                nextUpdateTime
            } else {
                Log.d(
                    "calcNightNextUpdate",
                    "nextUpdateTime (next sunrise): $nextSunriseMillis (${Date(nextSunriseMillis)})"
                )
                nextSunriseMillis
            }
        }
    }

    // ウィジェット更新用のPendingIntentを作成
    fun createAlarmPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, Oyatsu::class.java).apply {
            action = ACTION_ALARM_UPDATE // このカスタムアクションで受信を識別
            // PendingIntentを一意にするため、Intentにデータを含めるか、requestCodeを変える
            // ここではrequestCodeにウィジェットIDを使用
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            // データURIを使う方法: data = Uri.parse("oyatsu://widget/id/$appWidgetId")
        }

        // PendingIntentのフラグ設定
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0 (M) API 23 以降: FLAG_IMMUTABLE を指定 (S以降で強く推奨)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            // Android 5.0, 5.1 (L, API 21, 22): FLAG_IMMUTABLE は使えない
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        // getBroadcastを使用してPendingIntentを作成
        return PendingIntent.getBroadcast(
            context,
            appWidgetId, // requestCode: ウィジェットIDごとにユニークなIntentにするため
            intent,
            flags
        )
    }

    // 既存のアラームをキャンセル
    fun cancelAlarm(context: Context, appWidgetId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createAlarmPendingIntent(context, appWidgetId)
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled existing alarm for widget $appWidgetId")
    }
}