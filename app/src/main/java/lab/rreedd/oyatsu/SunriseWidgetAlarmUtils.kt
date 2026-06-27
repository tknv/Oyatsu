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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val TAG = "OyatsuWidget"

object SunriseWidgetAlarmUtils {
    const val PREFS_NAME = "lab.rreedd.oyatsu.OyatsuWidgetPrefs"
    const val ACTION_ALARM_UPDATE = "lab.rreedd.oyatsu.ALARM_UPDATE"
    const val PREF_SUNRISE_TIME_PREFIX = "sunrise_time_"
    const val PREF_SUNSET_TIME_PREFIX = "sunset_time_"
    const val PREF_LATITUDE_PREFIX = "latitude_"
    const val PREF_LONGITUDE_PREFIX = "longitude_"
    const val PREF_LAST_CALC_DATE_PREFIX = "last_calc_date_"

    const val DEFAULT_LATITUDE = 35.681444600642514
    const val DEFAULT_LONGITUDE = 139.76579265965165

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

    fun getCoordinates(context: Context, appWidgetId: Int): Pair<Double, Double> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val latitudeString = prefs.getString(PREF_LATITUDE_PREFIX + appWidgetId, null)
        val longitudeString = prefs.getString(PREF_LONGITUDE_PREFIX + appWidgetId, null)

        val latitude = try { latitudeString?.toDouble() ?: DEFAULT_LATITUDE } catch (e: Exception) { DEFAULT_LATITUDE }
        val longitude = try { longitudeString?.toDouble() ?: DEFAULT_LONGITUDE } catch (e: Exception) { DEFAULT_LONGITUDE }
        return latitude to longitude
    }

    fun validateAndSaveCoordinates(context: Context, appWidgetId: Int, latitude: Double?, longitude: Double?) {
        if (latitude == null || longitude == null || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            Toast.makeText(context, "無効な緯度または経度が入力されました", Toast.LENGTH_SHORT).show()
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
    }

    fun scheduleNextUpdate(pseudToday: Calendar, context: Context, appWidgetId: Int) {
        val (currentLatitude, currentLongitude) = getCoordinates(context, appWidgetId)
        
        val todaySunrise = getSunriseSunsetTime(context, appWidgetId, currentLatitude, currentLongitude, pseudToday, true, false)
        val todaySunset = getSunriseSunsetTime(context, appWidgetId, currentLatitude, currentLongitude, pseudToday, false, false)

        if (todaySunrise == null || todaySunset == null) {
            cancelAlarm(context, appWidgetId)
            return
        }

        if (todaySunset.timeInMillis < todaySunrise.timeInMillis) {
            todaySunset.add(Calendar.DAY_OF_YEAR, 1)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putLong(PREF_SUNSET_TIME_PREFIX + appWidgetId, todaySunset.timeInMillis)
                apply()
            }
        }

        val nowMillis = System.currentTimeMillis()
        val nextUpdateMillis = calculateNextUpdateTime(nowMillis, currentLatitude, currentLongitude, todaySunrise.timeInMillis, todaySunset.timeInMillis)
        scheduleWork(context, nextUpdateMillis, appWidgetId)
    }

    private fun scheduleWork(context: Context, nextUpdateMillis: Long, appWidgetId: Int) {
        val workRequest = OneTimeWorkRequestBuilder<SunriseUpdateWorker>()
            .setInitialDelay(nextUpdateMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag("WidgetUpdate$appWidgetId")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork("WidgetUpdateWork$appWidgetId", ExistingWorkPolicy.REPLACE, workRequest)
    }

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

        val storedTimeKey = (if (isSunrise) PREF_SUNRISE_TIME_PREFIX else PREF_SUNSET_TIME_PREFIX) + appWidgetId
        val storedDateKey = PREF_LAST_CALC_DATE_PREFIX + appWidgetId

        val storedMillis = prefs.getLong(storedTimeKey, -1L)
        val lastCalcDate = prefs.getString(storedDateKey, null)

        if (!forceRecalc && storedMillis != -1L && lastCalcDate == todayDateString) {
            val storedCalendar = Calendar.getInstance().apply { timeInMillis = storedMillis }
            return storedCalendar
        }

        try {
            val calculatedTime = MeeusSunCalc.getSunriseSunsetTime(targetDate, latitude, longitude, isSunrise)
            
            calculatedTime?.let {
                it.set(Calendar.SECOND, 0)
                it.set(Calendar.MILLISECOND, 0)

                prefs.edit {
                    putLong(storedTimeKey, it.timeInMillis)
                    putString(storedDateKey, todayDateString)
                }
                return it
            } ?: run {
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating sunrise/sunset for widget $appWidgetId: ${e.message}", e)
            return null
        }
    }

    class SunriseUpdateWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
        override fun doWork(): Result {
            val appWidgetId = inputData.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            if (appWidgetId == -1) return Result.failure()
            SunriseWidgetAlarmUtils.scheduleNextUpdate(Calendar.getInstance(), applicationContext, appWidgetId)
            return Result.success()
        }
    }

    fun calculateNextUpdateTime(nowMillis: Long, latitude: Double, longitude: Double, sunriseMillis: Long, sunsetMillis: Long): Long {
        return if (nowMillis in sunriseMillis until sunsetMillis) {
            calculateDaytimeNextUpdate(nowMillis, sunriseMillis, sunsetMillis)
        } else {
            val timeZone = TimeZone.getDefault()
            val todayCalendar = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
            val nextSunriseMillis: Long
            val pastSunsetMillis: Long

            if (nowMillis < sunriseMillis) {
                nextSunriseMillis = sunriseMillis
                val yesterdayCalendar = (todayCalendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
                pastSunsetMillis = MeeusSunCalc.getSunriseSunsetTime(yesterdayCalendar, latitude, longitude, false)?.timeInMillis ?: sunsetMillis
            } else {
                pastSunsetMillis = sunsetMillis
                val tomorrowCalendar = (todayCalendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
                nextSunriseMillis = MeeusSunCalc.getSunriseSunsetTime(tomorrowCalendar, latitude, longitude, true)?.timeInMillis ?: sunriseMillis
            }
            calculateNighttimeNextUpdate(nowMillis, nextSunriseMillis, pastSunsetMillis)
        }
    }

    private fun calculateDaytimeNextUpdate(nowMillis: Long, sunriseMillis: Long, sunsetMillis: Long): Long {
        val dayDurationMillis = sunsetMillis - sunriseMillis
        val timeUnitMillis = dayDurationMillis / 6.0
        val timeSubUnitMillis = timeUnitMillis / 4.0
        val timePassedMillis = nowMillis - sunriseMillis

        val currentUnitIndex = (timePassedMillis / timeUnitMillis).toInt()
        val currentSubUnitIndex = ((timePassedMillis % timeUnitMillis) / timeSubUnitMillis).toInt()
        val nextSubUnitIndex = currentSubUnitIndex + 1

        return if (nextSubUnitIndex < 4) {
            sunriseMillis + (currentUnitIndex * timeUnitMillis).toLong() + (nextSubUnitIndex * timeSubUnitMillis).toLong()
        } else {
            val nextUnitIndex = currentUnitIndex + 1
            if (nextUnitIndex < 6) sunriseMillis + (nextUnitIndex * timeUnitMillis).toLong() else sunsetMillis
        }
    }

    private fun calculateNighttimeNextUpdate(nowMillis: Long, nextSunriseMillis: Long, pastSunsetMillis: Long): Long {
        val nightDurationMillis = nextSunriseMillis - pastSunsetMillis
        val timeUnitMillis = nightDurationMillis / 6.0
        val timeSubUnitMillis = timeUnitMillis / 4.0
        val timePassedMillis = nowMillis - pastSunsetMillis
        val currentUnitIndex = (timePassedMillis / timeUnitMillis).toInt()
        val currentSubUnitIndex = ((timePassedMillis % timeUnitMillis) / timeSubUnitMillis).toInt()
        val nextSubUnitIndex = currentSubUnitIndex + 1

        return if (nextSubUnitIndex < 4) {
            pastSunsetMillis + (currentUnitIndex * timeUnitMillis).toLong() + (nextSubUnitIndex * timeSubUnitMillis).toLong()
        } else {
            val nextUnitIndex = currentUnitIndex + 1
            if (nextUnitIndex < 6) pastSunsetMillis + (nextUnitIndex * timeUnitMillis).toLong() else nextSunriseMillis
        }
    }

    fun createAlarmPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, Oyatsu::class.java).apply {
            action = ACTION_ALARM_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, appWidgetId, intent, flags)
    }

    fun cancelAlarm(context: Context, appWidgetId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(createAlarmPendingIntent(context, appWidgetId))
    }
}