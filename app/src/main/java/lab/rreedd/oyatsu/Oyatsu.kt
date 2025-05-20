package lab.rreedd.oyatsu

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val TAG = "OyatsuWidget"
private const val PREFS_NAME = "lab.rreedd.oyatsu.OyatsuWidgetPrefs"
private const val PREF_LATITUDE_PREFIX = "latitude_"       // 例: latitude_123
private const val PREF_LONGITUDE_PREFIX = "longitude_"      // 例: longitude_123
private const val ACTION_ALARM_UPDATE = "lab.rreedd.oyatsu.ACTION_ALARM_UPDATE" 
private const val DEFAULT_LATITUDE = 35.681444600642514 // デフォルト緯度（東京駅） - 位置情報が取れない場合に使用
private const val DEFAULT_LONGITUDE = 139.76579265965165 // デフォルト経度（東京駅）

class Oyatsu : AppWidgetProvider() {
    // 和時計のための定数
    private val dayTimeLabels = arrayOf("卯", "辰", "巳", "午", "未", "申")
    private val nightTimeLabels = arrayOf("酉", "戌", "亥", "子", "丑", "寅")
    private val hourNumber = arrayOf("一つ", "二つ", "三つ", "四つ")

    /**
     * ウィジェットが更新されるタイミングで呼び出される。
     * (updatePeriodMillis, AlarmManager, 設定変更など)
     */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate called for ids: ${appWidgetIds.joinToString()}")
        // onUpdateはシステムから様々なタイミングで呼ばれるため、ここで位置情報取得をトリガーすると頻繁になりすぎる可能性がある。
        // 通常はAlarmManagerからのカスタムアクション(ACTION_ALARM_UPDATE)で位置情報取得と更新を行うのが良い。
        // ここでは、念のため位置情報がない場合に取得を試みるロジックは残しておく。
        appWidgetIds.forEach { appWidgetId ->
            // 現在の緯度経度を更新
            val views = RemoteViews(context.packageName, R.layout.widget_oyatsu)
            val today = Calendar.getInstance()
            SunriseWidgetAlarmUtils.updateWidgetCoordinates(context, appWidgetId, views)
            proceedWithWidgetUpdate(
                today,
                context,
                appWidgetId,
                forceSunriseRecalc = true
            )
        }
    }

    override fun onEnabled(context: Context) {
        Log.d(TAG, "onEnabled called")
        // When the first widget is added, you might want to prompt for location
        // or ensure default is set up.
        // For now, existing widgets will update with stored/default location.
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisAppWidget = ComponentName(context.packageName, javaClass.name)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
        val today = Calendar.getInstance()
        appWidgetIds.forEach { appWidgetId ->
            // Ensure initial update and schedule
            proceedWithWidgetUpdate(today, context, appWidgetId, forceSunriseRecalc = true)
        }
    }

    /**
     * このプロバイダーの最後のウィジェットインスタンスが削除されたときに呼び出される。
     */
    override fun onDisabled(context: Context) {
        Log.d(TAG, "onDisabled called")
        // ここで、まだキャンセルされていないすべてのアラームをキャンセルすることも考慮できるが、
        // onDeleted で個別にキャンセルするのがより確実。
    }

    /**
     * ウィジェットインスタンスが削除されたときに呼び出される。
     */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        Log.d(TAG, "onDeleted called for ids: ${appWidgetIds.joinToString()}")
        appWidgetIds.forEach { appWidgetId ->
            // 削除されたウィジェットに関連付けられたアラームをキャンセル
            cancelAlarm(context, appWidgetId)
            // 関連する SharedPreferences データを削除
//            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
//                remove(PREF_SUNRISE_TIME_PREFIX + appWidgetId + "_millis")
//                remove(PREF_SUNRISE_TIME_PREFIX + appWidgetId + "_date")
//                remove(PREF_SUNSET_TIME_PREFIX + appWidgetId + "_millis")  // 日の入り時刻も削除
//                remove(PREF_SUNSET_TIME_PREFIX + appWidgetId + "_date")    // 日の入り日付も削除
//                remove(PREF_LATITUDE_PREFIX + appWidgetId)
//                remove(PREF_LONGITUDE_PREFIX + appWidgetId)
//            }
//            Log.i(TAG, "Cleaned up data for deleted widget ID: $appWidgetId")
        }
    }

    /**
     * ブロードキャストインテントを受信したときに呼び出される。
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "onReceive: action = $action from intent: $intent")
        val today = Calendar.getInstance()
        // Handle widget update actions, including those from LocationInputActivity
        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE == action) {
            val appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            if (appWidgetIds != null) {
                 Log.d(TAG, "Received ACTION_APPWIDGET_UPDATE for IDs: ${appWidgetIds.joinToString()}")
                // This will call our onUpdate method
                super.onReceive(context, intent) // Important to let the base class handle standard updates
                // Explicitly update based on potentially new coordinates
                val appWidgetManager = AppWidgetManager.getInstance(context)
                appWidgetIds.forEach { appWidgetId ->
                    updateAppWidgetInternal(today, context, appWidgetManager, appWidgetId, true) // Force recalc after location change
                    SunriseWidgetAlarmUtils.scheduleNextUpdate(today, context, appWidgetId)
                }
                return // Consume this action
            }
        } else {
             super.onReceive(context, intent) // Essential for other actions like onUpdate, onDeleted etc.
        }


        when (action) {
            ACTION_ALARM_UPDATE -> {
                val appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Log.d(TAG, "Received custom alarm for widget ID: $appWidgetId (likely from SunriseWidgetAlarmUtils)")
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    updateAppWidgetInternal(today, context, appWidgetManager, appWidgetId, false) // Regular update
                    SunriseWidgetAlarmUtils.scheduleNextUpdate(today, context, appWidgetId) // Reschedule
                } else {
                    Log.w(TAG, "Received alarm intent without valid widget ID.")
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                 Log.d(TAG, "Received $action. Rescheduling/recalculating for all widgets.")
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisAppWidget = ComponentName(context.packageName, javaClass.name)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
                appWidgetIds.forEach { appWidgetId ->
                    Log.d(TAG, "Processing widget ID: $appWidgetId due to $action")
                    // For these system events, recalculate and reschedule
                    updateAppWidgetInternal(today, context, appWidgetManager, appWidgetId, true) // forceRecalc
                    SunriseWidgetAlarmUtils.scheduleNextUpdate(today, context, appWidgetId)
                }
            }
            // Add a custom action for location updates from LocationInputActivity
            LocationInputActivity.ACTION_LOCATION_UPDATED -> {
                val appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Log.d(TAG, "Received location updated for widget ID: $appWidgetId. Forcing recalculation.")
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    updateAppWidgetInternal(today, context, appWidgetManager, appWidgetId, true) // Force recalc
                    SunriseWidgetAlarmUtils.scheduleNextUpdate(today, context, appWidgetId)
                    logAllJapaneseTimesForToday(context, appWidgetId) // Log all times
                }
            }
        }
    }

    /**
     * 指定されたウィジェットIDの表示を更新する内部メソッド。
     * @param context Context
     * @param appWidgetManager AppWidgetManager
     * @param appWidgetId 更新するウィジェットのID
     * @param forceRecalc trueの場合、保存された値に関わらず日の出入り時刻を再計算する
     */
    private fun updateAppWidgetInternal(
        pseudToday: Calendar,
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        forceRecalc: Boolean
    ) {
        Log.d(TAG, "Updating widget ID: $appWidgetId, forceSunriseRecalc: $forceRecalc")
        val views = RemoteViews(context.packageName, R.layout.widget_oyatsu)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Load latitude and longitude
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
        // Check if coordinates are default and prompt user if so (only if not already prompted recently)
        val isDefaultLocation = (latitude == DEFAULT_LATITUDE && longitude == DEFAULT_LONGITUDE)
        val locationSet = prefs.contains(PREF_LATITUDE_PREFIX + appWidgetId)

        Log.d(TAG, "Using location for widget $appWidgetId: Lat=$latitude, Lon=$longitude. IsDefault: $isDefaultLocation, IsSet: $locationSet")

        // --- 1. 既存の暦情報計算 ---
        val calendar = Calendar.getInstance()
        val gregorianDate = SimpleDateFormat("yyyy年MM月dd日", Locale.JAPAN).format(calendar.time)
        val japaneseMonthName = getJapaneseMonthName(calendar)
        val solarTerm = getSolarTerm(calendar)
        val sixtyKanjiCycle = getSixtyKanjiCycle(calendar)
        val japaneseYear = getJapaneseYear(calendar)

        // --- 2. 日の出・日の入り時刻と和時計に基づく時刻の計算・表示 ---
        // 日の出・日の入り時刻を取得
        val todaySunriseTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(pseudToday, prefs, appWidgetId, true, forceRecalc) // 通常は再計算不要
        val todaySunsetTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(pseudToday, prefs, appWidgetId, false, forceRecalc) // 通常は再計算不要
        // sunTime変数をスコープ外でも使えるよう宣言
        var sunTime = ""
        val japaneseTimeText: String

        if (todaySunriseTime != null && todaySunsetTime != null) {
            val resultPair = calculateJapaneseTime(todaySunriseTime, todaySunsetTime)
            japaneseTimeText = resultPair.first
            sunTime = resultPair.second
            Log.d(TAG, "Widget $appWidgetId: $japaneseTimeText (sunTime: $sunTime)")
        } else {
            Log.w(TAG, "Widget $appWidgetId: Failed to calculate sunrise/sunset. Using default text.")
            japaneseTimeText = if (!locationSet) context.getString(R.string.location_not_set_tap_to_set) else "時刻計算エラー"
            sunTime = context.getString(R.string.fetching_location) // Or some error indicator
            // Potentially schedule a quick retry if it was due to a transient issue, though less likely without GPS
            // SunriseWidgetAlarmUtils.scheduleQuickUpdate(context, appWidgetId) // If you implement this
        }

        // widget_oyatsu.xml で表示する項目
        views.setTextViewText(R.id.text_japanese_year_month, "$japaneseYear $japaneseMonthName")
        views.setTextViewText(R.id.text_gregorian_date, gregorianDate)
        views.setTextViewText(
            R.id.text_jikoku_solar_term_sixty_cycle,
            "$japaneseTimeText $solarTerm $sixtyKanjiCycle"
        )
        views.setTextViewText(R.id.text_sun_time, sunTime)

        // --- ウィジェットを更新 ---
        try {
            appWidgetManager.updateAppWidget(appWidgetId, views)
            Log.d(TAG, "Widget $appWidgetId view updated successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating widget view for ID $appWidgetId", e)
        }
    }

    /**
     * 和時計（不定時法）の時刻を計算する
     * 日の出から日の入りまでを6等分（卯、辰、巳、午、未、申）
     * 日の入りから翌日の出までを6等分（酉、戌、亥、子、丑、寅）
     * それぞれの時間帯をさらに4等分（一つ、二つ、三つ、四つ）
     */
    private fun calculateJapaneseTime(sunriseTime: Calendar, sunsetTime: Calendar): Pair<String, String> {
        val now = Calendar.getInstance()
        val currentTimeMillis = now.timeInMillis
        val sunriseMillis = sunriseTime.timeInMillis
        val sunsetMillis = sunsetTime.timeInMillis

        // 次の日の日の出時刻を推定（単純に24時間後と仮定）
        val nextSunriseMillis = sunriseMillis + 24 * 60 * 60 * 1000

        // 日中か夜間かを判断
        val isDaytime = currentTimeMillis in sunriseMillis until sunsetMillis

        if (isDaytime) {
            // 日中: 日の出から日の入りまでを6等分
            val dayDurationMillis = sunsetMillis - sunriseMillis
            if (dayDurationMillis <= 0) return Pair("時間計算エラー", "日照時間異常")
            val timeUnitMillis = dayDurationMillis / 6.0 // 1時間単位（不定時法）
            val timePassedMillis = currentTimeMillis - sunriseMillis

            val timeUnitIndex = (timePassedMillis / timeUnitMillis).toInt() // 0-5
            val timeSubUnitIndex = ((timePassedMillis % timeUnitMillis) / (timeUnitMillis / 4.0)).toInt() // 0-3

            // 時間が範囲を超えないように調整
            val safeTimeUnitIndex = timeUnitIndex.coerceIn(0, 5)
            val safeTimeSubUnitIndex = timeSubUnitIndex.coerceIn(0, 3)

            val hourName = dayTimeLabels[safeTimeUnitIndex]
            val subHourName = hourNumber[safeTimeSubUnitIndex]

            // 日の出・日の入り時刻（24時間表記）も表示
            val sunriseStr = SimpleDateFormat("HH:mm", Locale.JAPAN).format(sunriseTime.time)
            val sunsetStr = SimpleDateFormat("HH:mm", Locale.JAPAN).format(sunsetTime.time)

            return Pair("$hourName$subHourName","日出$sunriseStr-日入$sunsetStr")
        } else {
            // 夜間: 日の入りから翌日の日の出までを6等分
            val nightDurationMillis = if (currentTimeMillis >= sunsetMillis) {
                // 今日の日没から翌日の日の出まで
                nextSunriseMillis - sunsetMillis
            } else {
                // 昨日の日没から今日の日の出まで（現在時刻は今日の日の出前）
                sunriseMillis - (sunsetMillis - 24 * 60 * 60 * 1000)
            }

            val timeUnitMillis = nightDurationMillis / 6.0 // 1時間単位（不定時法）
            val timePassedMillis = if (currentTimeMillis >= sunsetMillis) {
                // 今日の日没後
                currentTimeMillis - sunsetMillis
            } else {
                // 今日の日の出前
                currentTimeMillis - (sunsetMillis - 24 * 60 * 60 * 1000) + nightDurationMillis
            }

            val timeUnitIndex = (timePassedMillis / timeUnitMillis).toInt() // 0-5
            val timeSubUnitIndex = ((timePassedMillis % timeUnitMillis) / (timeUnitMillis / 4.0)).toInt() // 0-3

            // 時間が範囲を超えないように調整
            val safeTimeUnitIndex = timeUnitIndex.coerceIn(0, 5)
            val safeTimeSubUnitIndex = timeSubUnitIndex.coerceIn(0, 3)

            val hourName = nightTimeLabels[safeTimeUnitIndex]
            val subHourName = hourNumber[safeTimeSubUnitIndex]

            // 日の出・日の入り時刻（24時間表記）も表示
            val sunriseStr = SimpleDateFormat("HH:mm", Locale.JAPAN).format(sunriseTime.time)
            val sunsetStr = SimpleDateFormat("HH:mm", Locale.JAPAN).format(sunsetTime.time)

            return Pair("$hourName$subHourName", "日出 $sunriseStr-日入 $sunsetStr")
        }
    }

    /**
     * 位置情報処理後のウィジェット更新とスケジュール処理
     */
    fun proceedWithWidgetUpdate(
        pseudToday: Calendar,
        context: Context,
        appWidgetId: Int,
        forceSunriseRecalc: Boolean
    ) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        // ウィジェットを更新
        updateAppWidgetInternal(pseudToday, context, appWidgetManager, appWidgetId, forceSunriseRecalc)
        // 次の更新をスケジュール
        SunriseWidgetAlarmUtils.scheduleNextUpdate(pseudToday, context, appWidgetId)
    }

    // --- AlarmManager 関連 ---
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
     * 指定されたウィジェットIDのアラームをキャンセルする。
     * @param context Context
     * @param appWidgetId キャンセルするウィジェットのID
     */
    private fun cancelAlarm(context: Context, appWidgetId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createAlarmPendingIntent(context, appWidgetId) // 同一のIntentで作成
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel() // PendingIntent自体もキャンセル
        Log.i(TAG, "Canceled alarm for widget ID: $appWidgetId")
    }

    /**
     * ロケーションまたは時刻設定の変更があった場合に、その日一日の calculateJapaneseTime を全てログに表示する
     */
    private fun logAllJapaneseTimesForToday(context: Context, appWidgetId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = Calendar.getInstance()

        val sunriseTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(today, prefs, appWidgetId, true, true)
        val sunsetTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(today, prefs, appWidgetId, false, true)

        if (sunriseTime == null || sunsetTime == null) {
            Log.e(TAG, "Failed to get sunrise/sunset times for logging all Japanese times.")
            return
        }

        Log.d(TAG, "--- Logging all Japanese times for widget ID: $appWidgetId (Today: ${SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(today.time)}) ---")

        val tempCalendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, today.get(Calendar.YEAR))
            set(Calendar.MONTH, today.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, today.get(Calendar.DAY_OF_MONTH))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        for (hour in 0 until 24) {
            for (minute in 0 until 60) {
                tempCalendar.set(Calendar.HOUR_OF_DAY, hour)
                tempCalendar.set(Calendar.MINUTE, minute)

                val originalNow = Calendar.getInstance() // Store original now
                Calendar.getInstance().timeInMillis = tempCalendar.timeInMillis // Temporarily set 'now' for calculation

                val (japaneseTime, sunInfo) = calculateJapaneseTime(sunriseTime, sunsetTime)
                Log.d(TAG, "Time: ${SimpleDateFormat("HH:mm", Locale.JAPAN).format(tempCalendar.time)} -> Japanese: $japaneseTime, Sun: $sunInfo")

                Calendar.getInstance().timeInMillis = originalNow.timeInMillis // Restore original now
            }
        }
        Log.d(TAG, "--- End of Japanese times log ---")
    }

    /** 和風月名を取得 */
    private fun getJapaneseMonthName(calendar: Calendar): String {
        val japaneseMonths = listOf(
            "睦月", "如月", "弥生", "卯月", "皐月", "水無月",
            "文月", "葉月", "長月", "神無月", "霜月", "師走"
        )
        return japaneseMonths[calendar.get(Calendar.MONTH)] // 0-11に直接対応
    }

    /** 二十四節気を取得 (簡略版 - 正確性は低い) */
    private fun getSolarTerm(calendar: Calendar): String {
        // 注意：この計算方法は非常に簡略化されており、年によって日付がずれるため不正確です。
        // 正確な計算には国立天文台の発表する暦要項などに基づく複雑な計算が必要です。
        // ここでは元のロジックを維持しつつ、少し改善を試みます。
//        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) // 0-11
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        // 二十四節気の名前
        val solarTerms = listOf(
            "小寒", "大寒", "立春", "雨水", "啓蟄", "春分", "清明", "穀雨",
            "立夏", "小満", "芒種", "夏至", "小暑", "大暑", "立秋", "処暑",
            "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"
        )
        // 各節気の「おおよそ」の日付 (月, 日) - 年によって1日程度ずれる
        // より正確にするには、年ごとに計算するか、正確なデータテーブルが必要
        val approxTermDates = listOf(
            Pair(0, 5), Pair(0, 20), Pair(1, 4), Pair(1, 19), Pair(2, 5), Pair(2, 20), // 春分
            Pair(3, 4), Pair(3, 20), Pair(4, 5), Pair(4, 21), Pair(5, 5), Pair(5, 21), // 夏至
            Pair(6, 7), Pair(6, 22), Pair(7, 7), Pair(7, 23), Pair(8, 7), Pair(8, 23), // 秋分
            Pair(9, 8), Pair(9, 23), Pair(10, 7), Pair(10, 22), Pair(11, 7), Pair(11, 21) // 冬至
        )

        var currentSolarTerm = solarTerms.last() // 年末は冬至とする

        for (i in approxTermDates.indices) {
            val (termMonth, termDay) = approxTermDates[i]
            // 現在の日付が、節気の日付以降かどうかを比較
            if (month > termMonth || (month == termMonth && day >= termDay)) {
                currentSolarTerm = solarTerms[i]
            } else {
                // 比較している節気の日付より前なら、ループを抜ける（前の節気が有効）
                break
            }
        }
        return currentSolarTerm
    }

    /** 六十干支（年干支）を取得 */
    private fun getSixtyKanjiCycle(calendar: Calendar): String {
        val stems = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸") // 十干
        val branches =
            listOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥") // 十二支

        val year = calendar.get(Calendar.YEAR)
        val stemIndex = (year + 6) % 10
        val branchIndex = (year + 8) % 12
        val stem = stems[stemIndex]
        val branch = branches[branchIndex]
        Log.d(TAG, "今年(" + year + "年)の干支: $stem$branch")
        return stem + branch
    }

    /** 和暦年号を取得 */
    private fun getJapaneseYear(calendar: Calendar): String {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1 // 1-12
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        // 元号の開始年月日 (YYYY, MM, DD)
        val reiwaStartDate = Triple(2019, 5, 1)
        val heiseiStartDate = Triple(1989, 1, 8)
        val showaStartDate = Triple(1926, 12, 25)
        val taishoStartDate = Triple(1912, 7, 30)
        val meijiStartDate = Triple(1868, 1, 25) // グレゴリオ暦の明治改元日(M1.9.8)より前だが、一般に西暦1868年=明治元年とされる開始日

        return when {
            // 令和
            year > reiwaStartDate.first || (year == reiwaStartDate.first && (month > reiwaStartDate.second || (month == reiwaStartDate.second && day >= reiwaStartDate.third)))
                -> "令和${toKanjiNumber(year - reiwaStartDate.first + 1)}年" // 令和N年 = 西暦 - 2019 + 1

            // 平成
            year > heiseiStartDate.first || (year == heiseiStartDate.first && (month > heiseiStartDate.second || (month == heiseiStartDate.second && day >= heiseiStartDate.third)))
                -> "平成${toKanjiNumber(year - heiseiStartDate.first + 1)}年" // 平成N年 = 西暦 - 1989 + 1

            // 昭和
            year > showaStartDate.first || (year == showaStartDate.first && (month > showaStartDate.second || (month == showaStartDate.second && day >= showaStartDate.third)))
                -> "昭和${toKanjiNumber(year - showaStartDate.first + 1)}年" // 昭和N年 = 西暦 - 1926 + 1

            // 大正
            year > taishoStartDate.first || (year == taishoStartDate.first && (month > taishoStartDate.second || (month == taishoStartDate.second && day >= taishoStartDate.third)))
                -> "大正${toKanjiNumber(year - taishoStartDate.first + 1)}年" // 大正N年 = 西暦 - 1912 + 1

            // 明治 (明治元年は1868年だが、改元日より前の日付も含む場合があるため簡略化)
            year >= meijiStartDate.first // 1868年以降
                -> "明治${toKanjiNumber(year - meijiStartDate.first + 1)}年" // 明治N年 = 西暦 - 1868 + 1

            else -> "${year}年" // 明治より前は西暦表示
        }
    }

    /** 数値を漢数字（元号用）に変換 */
    private fun toKanjiNumber(num: Int): String {
        if (num <= 0) return "" // 0年や負数はなし
        if (num == 1) return "元" // 1年目は「元」

        val kanjiDigits = arrayOf("〇", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        val kanjiPowers = arrayOf("", "十", "百", "千") // 位取り

        val sNum = num.toString()
        var result = ""
        val len = sNum.length

        for (i in 0 until len) {
            val digit = sNum[i].toString().toInt()
            val powerIndex = len - 1 - i // 0:一の位, 1:十の位, ...

            if (digit > 0) {
                // 「一十」を「十」と表示するための処理 (百、千の位の「一」は表示)
                if (!(digit == 1 && powerIndex == 1)) { // 十の位の「一」のみ省略
                    result += kanjiDigits[digit]
                }
                // 位取りを追加 (千、百、十)
                if (powerIndex > 0) {
                    result += kanjiPowers[powerIndex]
                }
            }
        }
        // 例: 2 -> 二, 10 -> 十, 11 -> 十一, 20 -> 二十, 21 -> 二十一, 100 -> 百, 101 -> 百一, 110 -> 百十, 111 -> 百十一
        return result
    }
}

class WidgetUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appWidgetId = intent?.getIntExtra("appWidgetId", -1) ?: -1
        if (appWidgetId != -1) {
            Log.i("WidgetUpdateReceiver", "Widget $appWidgetId update triggered.")
            // ウィジェット更新ロジックをここに追加
        }
    }
}

class ScreenOnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_SCREEN_ON) {
            Log.i("ScreenOnReceiver", "Screen ON detected. Triggering widget update.")
            val appWidgetIds = AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, Oyatsu::class.java)
            )
            val today = Calendar.getInstance()
            appWidgetIds.forEach { appWidgetId ->
                SunriseWidgetAlarmUtils.scheduleNextUpdate(today, context, appWidgetId)
            }
        }
    }
}