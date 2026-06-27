package lab.rreedd.oyatsu

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val TAG = "OyatsuWidget"
private const val PREFS_NAME = "lab.rreedd.oyatsu.OyatsuWidgetPrefs"
private const val PREF_LATITUDE_PREFIX = "latitude_"
private const val PREF_LONGITUDE_PREFIX = "longitude_"
private const val ACTION_ALARM_UPDATE = "lab.rreedd.oyatsu.ACTION_ALARM_UPDATE"
private const val ACTION_WIDGET_CLICK_UPDATE = "lab.rreedd.oyatsu.ACTION_WIDGET_CLICK_UPDATE"
private const val DEFAULT_LATITUDE = 35.681444600642514
private const val DEFAULT_LONGITUDE = 139.76579265965165

class Oyatsu : AppWidgetProvider() {
    private val dayTimeLabels = arrayOf("卯", "辰", "巳", "午", "未", "申")
    private val nightTimeLabels = arrayOf("酉", "戌", "亥", "子", "丑", "寅")
    private val hourNumber = arrayOf("一つ", "二つ", "三つ", "四つ")

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            val today = Calendar.getInstance()
            updateAppWidgetInternal(today, context, appWidgetManager, appWidgetId, forceRecalc = false)
            SunriseWidgetAlarmUtils.scheduleNextUpdate(today, context, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisAppWidget = ComponentName(context.packageName, javaClass.name)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
        val today = Calendar.getInstance()
        appWidgetIds.forEach { appWidgetId ->
            proceedWithWidgetUpdate(today, context, appWidgetId, forceSunriseRecalc = true)
            logAllJapaneseTimesForToday(context, appWidgetId)
        }
    }

    override fun onDisabled(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisAppWidget = ComponentName(context.packageName, javaClass.name)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
        appWidgetIds.forEach { appWidgetId ->
            SunriseWidgetAlarmUtils.cancelAlarm(context, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            SunriseWidgetAlarmUtils.cancelAlarm(context, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val today = Calendar.getInstance()

        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE == action) {
            val appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            if (appWidgetIds != null) {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                onUpdate(context, appWidgetManager, appWidgetIds)
            }
            return
        } else {
            super.onReceive(context, intent)
        }

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisAppWidget = ComponentName(context.packageName, javaClass.name)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)

        when (action) {
            ACTION_ALARM_UPDATE, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, LocationInputActivity.ACTION_LOCATION_UPDATED, ACTION_WIDGET_CLICK_UPDATE -> {
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    proceedWithWidgetUpdate(today, context, appWidgetId, forceSunriseRecalc = (action != ACTION_ALARM_UPDATE))
                } else {
                    appWidgetIds.forEach { id ->
                        proceedWithWidgetUpdate(today, context, id, forceSunriseRecalc = true)
                    }
                }
            }
            Intent.ACTION_SCREEN_ON -> {
                appWidgetIds.forEach { _ -> onUpdate(context, appWidgetManager, appWidgetIds) }
            }
        }
    }

    private fun updateAppWidgetInternal(pseudToday: Calendar, context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, forceRecalc: Boolean) {
        val views = RemoteViews(context.packageName, R.layout.widget_oyatsu)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val latitudeString = prefs.getString(PREF_LATITUDE_PREFIX + appWidgetId, null)
        val longitudeString = prefs.getString(PREF_LONGITUDE_PREFIX + appWidgetId, null)
        val latitude = latitudeString?.toDoubleOrNull() ?: DEFAULT_LATITUDE
        val longitude = longitudeString?.toDoubleOrNull() ?: DEFAULT_LONGITUDE

        val calendar = Calendar.getInstance()
        val gregorianDate = SimpleDateFormat("yyyy年MM月dd日", Locale.JAPAN).format(calendar.time)
        val japaneseMonthName = getJapaneseMonthName(calendar)
        
        // Meeusアルゴリズムによる高精度二十四節気取得
        val solarTerm = MeeusSunCalc.getSolarTerm(calendar)
        val sixtyKanjiCycle = getSixtyKanjiCycle(calendar)
        val japaneseYear = getJapaneseYear(calendar)

        val todaySunriseTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(context, appWidgetId, latitude, longitude, pseudToday, true, forceRecalc)
        var todaySunsetTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(context, appWidgetId, latitude, longitude, pseudToday, false, forceRecalc)

        if (todaySunriseTime != null && todaySunsetTime != null && todaySunsetTime.timeInMillis < todaySunriseTime.timeInMillis) {
            todaySunsetTime = (todaySunsetTime.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        }

        var sunTime = ""
        val japaneseTimeText: String
        var isHitsujiTime = false

        if (todaySunriseTime != null && todaySunsetTime != null) {
            val resultPair = calculateJapaneseTime(Calendar.getInstance(), todaySunriseTime, todaySunsetTime)
            japaneseTimeText = resultPair.first
            sunTime = resultPair.second
            isHitsujiTime = japaneseTimeText.startsWith("未")

            if (isHitsujiTime) {
                views.setInt(R.id.widget_root_layout, "setBackgroundResource", R.drawable.tokyo_29_1)
            } else {
                views.setInt(R.id.widget_root_layout, "setBackgroundResource", android.R.color.transparent)
            }

            val selfUpdateIntent = Intent(context, Oyatsu::class.java).apply {
                action = ACTION_WIDGET_CLICK_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
                data = Uri.parse("oyatsu://widget/click/$appWidgetId")
            }
            val selfUpdatePendingIntent = PendingIntent.getBroadcast(context, appWidgetId, selfUpdateIntent, PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            views.setOnClickPendingIntent(R.id.widget_root_layout, selfUpdatePendingIntent)
        } else {
            japaneseTimeText = context.getString(R.string.location_not_set_tap_to_set)
            sunTime = context.getString(R.string.fetching_location)
            views.setInt(R.id.widget_root_layout, "setBackgroundResource", android.R.color.transparent)
        }

        views.setTextViewText(R.id.text_japanese_year_month, "$japaneseYear $japaneseMonthName")
        views.setTextViewText(R.id.text_gregorian_date, gregorianDate)
        views.setTextViewText(R.id.text_jikoku_solar_term_sixty_cycle, "$japaneseTimeText $solarTerm $sixtyKanjiCycle")
        views.setTextViewText(R.id.text_sun_time, sunTime)

        try {
            appWidgetManager.updateAppWidget(appWidgetId, views)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating widget view for ID $appWidgetId", e)
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

    private fun cancelAlarm(context: Context, appWidgetId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createAlarmPendingIntent(context, appWidgetId)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun calculateJapaneseTime(now: Calendar, sunriseTime: Calendar, sunsetTime: Calendar): Pair<String, String> {
        val currentTimeMillis = now.timeInMillis
        val sunriseMillis = sunriseTime.timeInMillis
        val sunsetMillis = sunsetTime.timeInMillis

        val sunriseStr = SimpleDateFormat("HH:mm", Locale.JAPAN).format(sunriseTime.time)
        val sunsetStr = SimpleDateFormat("HH:mm", Locale.JAPAN).format(sunsetTime.time)
        val sunInfo = "日出$sunriseStr-日入$sunsetStr"

        val daytimeDurationMillis = sunsetMillis - sunriseMillis
        val nextDaySunriseTime = sunriseTime.clone() as Calendar
        nextDaySunriseTime.add(Calendar.DAY_OF_YEAR, 1)
        val nextSunriseMillis = nextDaySunriseTime.timeInMillis
        val nighttimeDurationMillis = nextSunriseMillis - sunsetMillis

        val durationMillis: Long
        val startMillis: Long
        val labels: Array<String>
        val isDaytime = currentTimeMillis in sunriseMillis until sunsetMillis

        if (isDaytime) {
            durationMillis = daytimeDurationMillis
            startMillis = sunriseMillis
            labels = dayTimeLabels
        } else {
            if (currentTimeMillis >= sunsetMillis) {
                durationMillis = nighttimeDurationMillis
                startMillis = sunsetMillis
            } else {
                val previousDaySunsetTime = sunsetTime.clone() as Calendar
                previousDaySunsetTime.add(Calendar.DAY_OF_YEAR, -1)
                val previousSunsetMillis = previousDaySunsetTime.timeInMillis
                durationMillis = sunriseMillis - previousSunsetMillis
                startMillis = previousSunsetMillis
            }
            labels = nightTimeLabels
        }

        if (durationMillis <= 0) return Pair("時間計算エラー", sunInfo)

        val timeUnitMillis = durationMillis / 6.0
        val timePassedMillis = currentTimeMillis - startMillis

        var timeUnitIndex = (timePassedMillis / timeUnitMillis).toInt()
        var timeSubUnitIndex = ((timePassedMillis % timeUnitMillis) / (timeUnitMillis / 4.0)).toInt()

        if (timeUnitIndex >= labels.size) { timeUnitIndex = labels.size - 1; timeSubUnitIndex = hourNumber.size - 1 }
        if (timeSubUnitIndex >= hourNumber.size) timeSubUnitIndex = hourNumber.size - 1
        if (timeUnitIndex < 0) { timeUnitIndex = 0; timeSubUnitIndex = 0 }

        return Pair("${labels[timeUnitIndex]}${hourNumber[timeSubUnitIndex]}", sunInfo)
    }

    fun proceedWithWidgetUpdate(pseudToday: Calendar, context: Context, appWidgetId: Int, forceSunriseRecalc: Boolean) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        updateAppWidgetInternal(pseudToday, context, appWidgetManager, appWidgetId, forceSunriseRecalc)
        SunriseWidgetAlarmUtils.scheduleNextUpdate(pseudToday, context, appWidgetId)
    }

    private fun logAllJapaneseTimesForToday(context: Context, appWidgetId: Int) {
        // [ログ出力部分は変更なし]
    }

    private fun getJapaneseMonthName(calendar: Calendar): String {
        val japaneseMonths = listOf("睦月", "如月", "弥生", "卯月", "皐月", "水無月", "文月", "葉月", "長月", "神無月", "霜月", "師走")
        return japaneseMonths[calendar.get(Calendar.MONTH)]
    }

    private fun getSixtyKanjiCycle(calendar: Calendar): String {
        val stems = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
        val branches = listOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
        val year = calendar.get(Calendar.YEAR)
        return stems[(year - 4) % 10] + branches[(year - 4) % 12]
    }

    private fun getJapaneseYear(calendar: Calendar): String {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val reiwa = Triple(2019, 5, 1)
        val heisei = Triple(1989, 1, 8)
        val showa = Triple(1926, 12, 25)
        val taisho = Triple(1912, 7, 30)
        val meiji = Triple(1868, 1, 25)

        return when {
            year > reiwa.first || (year == reiwa.first && (month > reiwa.second || (month == reiwa.second && day >= reiwa.third))) -> "令和${toKanjiNumber(year - reiwa.first + 1)}年"
            year > heisei.first || (year == heisei.first && (month > heisei.second || (month == heisei.second && day >= heisei.third))) -> "平成${toKanjiNumber(year - heisei.first + 1)}年"
            year > showa.first || (year == showa.first && (month > showa.second || (month == showa.second && day >= showa.third))) -> "昭和${toKanjiNumber(year - showa.first + 1)}年"
            year > taisho.first || (year == taisho.first && (month > taisho.second || (month == taisho.second && day >= taisho.third))) -> "大正${toKanjiNumber(year - taisho.first + 1)}年"
            year >= meiji.first -> "明治${toKanjiNumber(year - meiji.first + 1)}年"
            else -> "${year}年"
        }
    }

    private fun toKanjiNumber(num: Int): String {
        if (num <= 0) return ""
        if (num == 1) return "元"
        val kanjiDigits = arrayOf("〇", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        val kanjiPowers = arrayOf("", "十", "百", "千")
        val sNum = num.toString()
        var result = ""
        val len = sNum.length
        for (i in 0 until len) {
            val digit = sNum[i].toString().toInt()
            val powerIndex = len - 1 - i
            if (digit > 0) {
                if (!(digit == 1 && powerIndex == 1)) result += kanjiDigits[digit]
                if (powerIndex > 0) result += kanjiPowers[powerIndex]
            }
        }
        return result
    }
}

class ScreenOnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_SCREEN_ON) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, Oyatsu::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            if (appWidgetIds == null || appWidgetIds.isEmpty()) return
            val updateIntent = Intent(context, Oyatsu::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            }
            context.sendBroadcast(updateIntent)
        }
    }
}