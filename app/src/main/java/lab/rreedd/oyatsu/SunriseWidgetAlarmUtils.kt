package lab.rreedd.oyatsu

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
//import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.work.*
import androidx.core.content.edit
import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator
import com.luckycatlabs.sunrisesunset.dto.Location
import lab.rreedd.oyatsu.SunriseWidgetAlarmUtils.ACTION_ALARM_UPDATE
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

private const val TAG = "OyatsuWidget"
/**
 * ウィジェット更新のアラームスケジューリングを扱うユーティリティクラス
 */
object SunriseWidgetAlarmUtils {
//    private const val TAG = "SunriseWidgetAlarm"
    const val PREFS_NAME = "lab.rreedd.oyatsu.OyatsuWidgetPrefs"

    // 定数定義
    const val ACTION_ALARM_UPDATE = "lab.rreedd.oyatsu.ALARM_UPDATE"
    const val PREF_SUNRISE_TIME_PREFIX = "sunrise_time_"
    const val PREF_SUNSET_TIME_PREFIX = "sunset_time_"
    const val PREF_LATITUDE_PREFIX = "latitude_"
    const val PREF_LONGITUDE_PREFIX = "longitude_"

    // デフォルト位置情報（例：東京）
    const val DEFAULT_LATITUDE = 35.6895
    const val DEFAULT_LONGITUDE = 139.6917

    fun getCoordinates(context: Context, appWidgetId: Int): Pair<Double, Double> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val latitude = prefs.getFloat(PREF_LATITUDE_PREFIX + appWidgetId, DEFAULT_LATITUDE.toFloat()).toDouble()
        val longitude = prefs.getFloat(PREF_LONGITUDE_PREFIX + appWidgetId, DEFAULT_LONGITUDE.toFloat()).toDouble()
        return latitude to longitude
    }

    fun validateAndSaveCoordinates(context: Context, appWidgetId: Int, latitude: Double?, longitude: Double?) {
        if (latitude == null || longitude == null || !isValidLatitude(latitude) || !isValidLongitude(longitude)) {
            Toast.makeText(context, "無効な緯度または経度が入力されました", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat(PREF_LATITUDE_PREFIX + appWidgetId, latitude.toFloat())
            putFloat(PREF_LONGITUDE_PREFIX + appWidgetId, longitude.toFloat())
            apply()
        }
    }

    fun updateWidgetCoordinates(context: Context, appWidgetId: Int, views: RemoteViews) {
        val (latitude, longitude) = getCoordinates(context, appWidgetId)
        views.setTextViewText(R.id.editTextLatitude, "緯度: $latitude")
        views.setTextViewText(R.id.editTextLongitude, "経度: $longitude")
        Log.d("SunriseWidgetAlarmUtils", "Widget $appWidgetId updated with coordinates: Lat=$latitude, Lon=$longitude")
    }

    private fun isValidLatitude(latitude: Double): Boolean {
        return latitude in -90.0..90.0
    }

    private fun isValidLongitude(longitude: Double): Boolean {
        return longitude in -180.0..180.0
    }
    
    /**
     * ウィジェットの次回更新をスケジュールする
     */
    fun scheduleNextUpdate(context: Context, appWidgetId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 日の出/日の入り時刻を取得
        val todaySunrise = getSunriseSunsetTime(prefs, appWidgetId, true, false)
        val todaySunset = getSunriseSunsetTime(prefs, appWidgetId, false, false)

        if (todaySunrise == null || todaySunset == null) {
            Log.w(
                TAG,
                "Cannot schedule next update for $appWidgetId: Sunrise/sunset time not available (likely missing location)."
            )
            // 日の出/日の入り時刻が取得できない場合、スケジュールできない
            cancelAlarm(context, appWidgetId)
            return
        }

        // 次の更新時刻を計算
        val nextUpdateMillis = calculateNextUpdateTime(todaySunrise.timeInMillis, todaySunset.timeInMillis)

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

        Log.i(TAG, "Scheduled WorkManager job for widget $appWidgetId at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(nextUpdateMillis))}")
    }

    fun getSunriseSunsetTime(prefs: android.content.SharedPreferences, appWidgetId: Int, isSunrise: Boolean, forceRecalc: Boolean): Calendar? {
        val today = Calendar.getInstance()
        val todayStr = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(today.time)
        val timeKey = if (isSunrise) PREF_SUNRISE_TIME_PREFIX else PREF_SUNSET_TIME_PREFIX
        val savedDateStr = prefs.getString(timeKey + appWidgetId + "_date", null)
        val savedTimeMillis = prefs.getLong(timeKey + appWidgetId + "_millis", -1L)

        if (!forceRecalc && todayStr == savedDateStr && savedTimeMillis != -1L) {
            return Calendar.getInstance().apply { timeInMillis = savedTimeMillis }
        }

        val latitude = prefs.getFloat(PREF_LATITUDE_PREFIX + appWidgetId, DEFAULT_LATITUDE.toFloat()).toDouble()
        val longitude = prefs.getFloat(PREF_LONGITUDE_PREFIX + appWidgetId, DEFAULT_LONGITUDE.toFloat()).toDouble()

        val calculator = SunriseSunsetCalculator(Location(latitude, longitude), TimeZone.getDefault())
        val sunriseSunset = if (isSunrise) calculator.getOfficialSunriseCalendarForDate(today) else calculator.getOfficialSunsetCalendarForDate(today)

        prefs.edit {
            putString(timeKey + appWidgetId + "_date", todayStr)
            putLong(timeKey + appWidgetId + "_millis", sunriseSunset.timeInMillis)
        }

        return sunriseSunset
    }
}

class SunriseUpdateWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        val appWidgetId = inputData.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)

        if (appWidgetId == -1) {
            Log.e(TAG, "Invalid widget ID in WorkManager")
            return Result.failure()
        }

        val context = applicationContext
        SunriseWidgetAlarmUtils.scheduleNextUpdate(context, appWidgetId)
        Log.i(TAG, "WorkManager executed for widget $appWidgetId")

        return Result.success()
    }
}

/**
     * 現在時刻に基づいて次の更新時刻を計算する
     */
    private fun calculateNextUpdateTime(sunriseMillis: Long, sunsetMillis: Long): Long {
        val nowMillis = System.currentTimeMillis()
        // 明日の日の出時刻
        val nextSunriseMillis = sunriseMillis + 24 * 60 * 60 * 1000

        return if (nowMillis in sunriseMillis until sunsetMillis) {
            calculateDaytimeNextUpdate(nowMillis, sunriseMillis, sunsetMillis)
        } else {
            calculateNighttimeNextUpdate(nowMillis, sunriseMillis, sunsetMillis, nextSunriseMillis)
        }
    }

    /**
     * 日中（日の出から日の入りまで）の次の更新時刻を計算
     */
    private fun calculateDaytimeNextUpdate(nowMillis: Long, sunriseMillis: Long, sunsetMillis: Long): Long {
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

        return if (nextSubUnitIndex < 4) {
            // 同じ大区間内の次の小区間
            sunriseMillis + (currentUnitIndex * timeUnitMillis).toLong() + (nextSubUnitIndex * timeSubUnitMillis).toLong()
        } else {
            // 次の大区間の最初の小区間
            val nextUnitIndex = currentUnitIndex + 1
            if (nextUnitIndex < 6) {
                sunriseMillis + (nextUnitIndex * timeUnitMillis).toLong()
            } else {
                // 日中の最後の区間を過ぎた場合は日没時
                sunsetMillis
            }
        }
    }

    /**
     * 夜間（日の入りから次の日の出まで）の次の更新時刻を計算
     */
    private fun calculateNighttimeNextUpdate(nowMillis: Long, sunriseMillis: Long, sunsetMillis: Long, nextSunriseMillis: Long): Long {
        // 夜間の開始時刻（日没時刻）
        val nightStartMillis = if (nowMillis >= sunsetMillis) {
            // 今日の日没後
            sunsetMillis
        } else {
            // 今日の日の出前（前日の日没後）
            sunsetMillis - 24 * 60 * 60 * 1000
        }

        val nightDurationMillis = if (nowMillis >= sunsetMillis) {
            // 今日の日没後
            nextSunriseMillis - sunsetMillis
        } else {
            // 今日の日の出前（前日の日没後）
            sunriseMillis - (sunsetMillis - 24 * 60 * 60 * 1000)
        }

        val timeUnitMillis = nightDurationMillis / 6.0 // 大区間（6等分）
        val timeSubUnitMillis = timeUnitMillis / 4.0 // 小区間（各大区間をさらに4等分）

        // 夜間経過時間の計算（日没からの経過時間）
        val timePassedMillis = if (nowMillis >= sunsetMillis) {
            // 今日の日没後
            nowMillis - sunsetMillis
        } else {
            // 今日の日の出前（前日の日没後）
            nowMillis - (sunsetMillis - 24 * 60 * 60 * 1000)
        }

        // 現在の大区間インデックス（0-5）
        val currentUnitIndex = (timePassedMillis / timeUnitMillis).toInt()

        // 現在の小区間インデックス（0-3）
        val currentSubUnitIndex = ((timePassedMillis % timeUnitMillis) / timeSubUnitMillis).toInt()

        // 次の小区間の開始時刻を計算
        val nextSubUnitIndex = currentSubUnitIndex + 1

        return if (nextSubUnitIndex < 4) {
            // 同じ大区間内の次の小区間
            nightStartMillis + (currentUnitIndex * timeUnitMillis).toLong() + (nextSubUnitIndex * timeSubUnitMillis).toLong()
        } else {
            // 次の大区間の最初の小区間
            val nextUnitIndex = currentUnitIndex + 1
            if (nextUnitIndex < 6) {
                nightStartMillis + (nextUnitIndex * timeUnitMillis).toLong()
            } else {
                // 夜間の最後の区間を過ぎた場合は次の日の出時
                if (nowMillis >= sunsetMillis) {
                    nextSunriseMillis
                } else {
                    sunriseMillis
                }
            }
        }
    }

/**
     * ウィジェット更新用のPendingIntentを作成
     */
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

    /**
     * 既存のアラームをキャンセル
     */
    fun cancelAlarm(context: Context, appWidgetId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createAlarmPendingIntent(context, appWidgetId)
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled existing alarm for widget $appWidgetId")
    }
