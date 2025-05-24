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
private const val DEFAULT_LATITUDE = 35.681444600642514 // デフォルト緯度（東京駅） - 位置情報が取れない場合に使用
private const val DEFAULT_LONGITUDE = 139.76579265965165 // デフォルト経度（東京駅）

class Oyatsu : AppWidgetProvider() {
    // 和時計のための定数
    private val dayTimeLabels = arrayOf("卯", "辰", "巳", "午", "未", "申")
    private val nightTimeLabels = arrayOf("酉", "戌", "亥", "子", "丑", "寅")
    private val hourNumber = arrayOf("一つ", "二つ", "三つ", "四つ")

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate called for ids: ${appWidgetIds.joinToString()}")
        appWidgetIds.forEach { appWidgetId ->
            val today = Calendar.getInstance()
            updateAppWidgetInternal(
                today,
                context,
                appWidgetManager,
                appWidgetId,
                forceRecalc = false // onUpdateでは通常は再計算不要
            )
            SunriseWidgetAlarmUtils.scheduleNextUpdate(today, context, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        Log.d(TAG, "onEnabled called")
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisAppWidget = ComponentName(context.packageName, javaClass.name)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
        val today = Calendar.getInstance()
        appWidgetIds.forEach { appWidgetId ->
            proceedWithWidgetUpdate(today, context, appWidgetId, forceSunriseRecalc = true)
            logAllJapaneseTimesForToday(context, appWidgetId) // ログ出力
        }
    }

    override fun onDisabled(context: Context) {
        // Enter relevant functionality for when the last widget is disabled
        Log.d(TAG, "Last widget disabled. Cancelling all alarms.")
        // すべてのウィジェットのアラームをキャンセル
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisAppWidget = ComponentName(context.packageName, javaClass.name)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
        appWidgetIds.forEach { appWidgetId ->
            SunriseWidgetAlarmUtils.cancelAlarm(context, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        Log.d(TAG, "onDeleted called for ids: ${appWidgetIds.joinToString()}")
        appWidgetIds.forEach { appWidgetId ->
            SunriseWidgetAlarmUtils.cancelAlarm(context, appWidgetId)
            // 関連する SharedPreferences データを削除
//            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
//                remove(PREF_LATITUDE_PREFIX + appWidgetId)
//                remove(PREF_LONGITUDE_PREFIX + appWidgetId)
//                remove(SunriseWidgetAlarmUtils.PREF_SUNRISE_TIME_PREFIX + appWidgetId)
//                remove(SunriseWidgetAlarmUtils.PREF_SUNSET_TIME_PREFIX + appWidgetId)
//                remove(SunriseWidgetAlarmUtils.PREF_LAST_CALC_DATE_PREFIX + appWidgetId) // 追加
//                apply()
//            }
//            Log.i(TAG, "Cleaned up data for deleted widget ID: $appWidgetId")
        }
    }

    /**
     * ブロードキャストインテントを受信したときに呼び出される。
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val today = Calendar.getInstance()

        Log.d(TAG, "onReceive: action = $action, appWidgetId = $appWidgetId")
        // Handle widget update actions, including those from LocationInputActivity
        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE == action) {
            val appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            if (appWidgetIds != null) {
                Log.d(
                    TAG,
                    "Received ACTION_APPWIDGET_UPDATE for IDs: ${appWidgetIds.joinToString()}"
                )
                val appWidgetManager = AppWidgetManager.getInstance(context)
                onUpdate(context, appWidgetManager, appWidgetIds)
                /* // This will call our onUpdate method
                super.onReceive(context, intent) // Important to let the base class handle standard updates
                // Explicitly update based on potentially new coordinates
                val appWidgetManager = AppWidgetManager.getInstance(context)
                appWidgetIds.forEach { appWidgetId ->
                     proceedWithWidgetUpdate(today, context, appWidgetId, forceSunriseRecalc = true)
                */
            }
            return // Consume this action
            /*  } else if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                // Handle case where a single appWidgetId is provided (e.g. from config activity)
                Log.d(TAG, "Received ACTION_APPWIDGET_UPDATE for single ID: $appWidgetId")
                super.onReceive(context, intent) // Let base class handle standard onUpdate calls
                // proceedWithWidgetUpdate(today, context, appWidgetId, forceSunriseRecalc = true)
                return
            }
            */
        } else {
            super.onReceive(
                context,
                intent
            ) // Essential for other actions like onUpdate, onDeleted etc.
        }

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisAppWidget = ComponentName(context.packageName, javaClass.name)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)

        when (action) {
            ACTION_ALARM_UPDATE -> {
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Log.d(
                        TAG,
                        "Received custom alarm for widget ID: $appWidgetId (likely from SunriseWidgetAlarmUtils)"
                    )
                    proceedWithWidgetUpdate(today, context, appWidgetId, forceSunriseRecalc = false)
                } else {
                    Log.w(TAG, "Received alarm intent without valid widget ID, updating all.")
                    appWidgetIds.forEach { id ->
                        proceedWithWidgetUpdate(
                            today,
                            context,
                            id,
                            forceSunriseRecalc = true
                        )
                        logAllJapaneseTimesForToday(
                            context,
                            id
                        ) // Log all times due to time/date change
                    }
                }
            }

            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                Log.d(TAG, "Received $action. Rescheduling/recalculating for all widgets.")
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Log.d(
                        TAG,
                        "Received $action on widget ID: $appWidgetId"
                    )
                    proceedWithWidgetUpdate(today, context, appWidgetId, forceSunriseRecalc = false)
                } else {
                    Log.w(TAG, "Received Tap on widget without valid widget ID, updating all.")
                    appWidgetIds.forEach { id ->
                        proceedWithWidgetUpdate(
                            today,
                            context,
                            id,
                            forceSunriseRecalc = true
                        )
                    }
                }
            }

            LocationInputActivity.ACTION_LOCATION_UPDATED -> {
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Log.d(
                        TAG,
                        "Received location update on widget ID: $appWidgetId"
                    )
                    proceedWithWidgetUpdate(today, context, appWidgetId, forceSunriseRecalc = false)
                } else {
                    Log.w(TAG, "Received location update on widget without valid widget ID, updating all.")
                    appWidgetIds.forEach { id ->
                        proceedWithWidgetUpdate(
                            today,
                            context,
                            id,
                            forceSunriseRecalc = true
                        )
                    }
                }
            }

            Intent.ACTION_SCREEN_ON -> { // 画面ON時に更新をトリガー
                Log.d(TAG, "Screen ON detected. Triggering widget update for all widgets.")
                appWidgetIds.forEach { id ->
                    onUpdate(context, appWidgetManager, appWidgetIds)
                }
            }

            ACTION_WIDGET_CLICK_UPDATE -> { // ウィジェットクリック時 (新しいカスタムアクション)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Log.d(
                        TAG,
                        "Received Tap on widget ID: $appWidgetId"
                    )
                    proceedWithWidgetUpdate(today, context, appWidgetId, forceSunriseRecalc = false)
                } else {
                    Log.w(TAG, "Received Tap on widget without valid widget ID, updating all.")
                    appWidgetIds.forEach { id ->
                        proceedWithWidgetUpdate(
                            today,
                            context,
                            id,
                            forceSunriseRecalc = true
                        )
                    }
                }
            }
        }
    }

    /**
     * 指定されたウィジェットIDの表示を更新する内部メソッド。
     * @param pseudToday 現在日付の基準となるCalendarインスタンス。時刻は現在時刻。
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
        Log.d(TAG, "Updating widget ID: $appWidgetId, forceRecalc: $forceRecalc")
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

        Log.d(TAG, "Using location for widget $appWidgetId: Lat=$latitude, Lon=$longitude.")

        // --- 1. 既存の暦情報計算 ---
        val calendar = Calendar.getInstance() // 現在時刻を使用
        val gregorianDate = SimpleDateFormat("yyyy年MM月dd日", Locale.JAPAN).format(calendar.time)
        val japaneseMonthName = getJapaneseMonthName(calendar)
        val solarTerm = getSolarTerm(calendar)
        val sixtyKanjiCycle = getSixtyKanjiCycle(calendar)
        val japaneseYear = getJapaneseYear(calendar)

        // --- 2. 日の出・日の入り時刻と和時計に基づく時刻の計算・表示 ---
        val todaySunriseTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(
            context,
            appWidgetId,
            latitude,
            longitude,
            pseudToday,
            true,
            forceRecalc
        )
        val todaySunsetTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(
            context,
            appWidgetId,
            latitude,
            longitude,
            pseudToday,
            false,
            forceRecalc
        )

        var sunTime = ""
        val japaneseTimeText: String

        if (todaySunriseTime != null && todaySunsetTime != null) {
            val resultPair =
                calculateJapaneseTime(Calendar.getInstance(), todaySunriseTime, todaySunsetTime)
            japaneseTimeText = resultPair.first
            sunTime = resultPair.second
            Log.d(TAG, "Widget $appWidgetId: $japaneseTimeText (sunTime: $sunTime)")
            // ★ウィジェット全体をタップしたら更新をトリガーするPendingIntentを設定
            val selfUpdateIntent = Intent(context, Oyatsu::class.java).apply {
                action = ACTION_WIDGET_CLICK_UPDATE
                // このインテントをブロードキャストする際に、どのウィジェットがクリックされたかを識別するために
                // putExtraでappWidgetIdsを渡すこともできるが、このケースでは onReceive で
                // 全てのウィジェットを更新するので必須ではない。しかし、ベストプラクティスとして渡す。
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
                // PendingIntent をウィジェットIDごとにユニークにするためにデータURIを追加
                data = Uri.parse("oyatsu://widget/click/$appWidgetId")
            }
            val selfUpdatePendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId, // ユニークなリクエストコード
                selfUpdateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            views.setOnClickPendingIntent(
                R.id.widget_root_layout,
                selfUpdatePendingIntent
            ) // ウィジェット全体のレイアウトに設定

            // ★時刻表示部分をタップしたら設定画面を開くPendingIntentを設定
            val configIntent = Intent(context, LocationInputActivity::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE // 設定画面を開くためのアクション
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse("oyatsu://widget/config/$appWidgetId") // ユニークなURI
            }
            /* val configPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId + 1000, // 他のPendingIntentとリクエストコードが被らないようにオフセットを追加
                configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            views.setOnClickPendingIntent(R.id.text_jikoku_solar_term_sixty_cycle, configPendingIntent) // 時刻表示のTextViewに設定
            */

        } else {
            Log.w(
                TAG,
                "Widget $appWidgetId: Failed to calculate sunrise/sunset. Using default text."
            )
            japaneseTimeText = context.getString(R.string.location_not_set_tap_to_set)
            sunTime = context.getString(R.string.fetching_location)
        }

        views.setTextViewText(R.id.text_japanese_year_month, "$japaneseYear $japaneseMonthName")
        views.setTextViewText(R.id.text_gregorian_date, gregorianDate)
        views.setTextViewText(
            R.id.text_jikoku_solar_term_sixty_cycle,
            "$japaneseTimeText $solarTerm $sixtyKanjiCycle"
        )
        views.setTextViewText(R.id.text_sun_time, sunTime)

        // ウィジェットを更新
        try {
            appWidgetManager.updateAppWidget(appWidgetId, views)
            Log.d(TAG, "Widget $appWidgetId view updated successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating widget view for ID $appWidgetId", e)
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
     * 和時計（不定時法）の時刻を計算する
     * 日の出から日の入りまでを6等分（卯、辰、巳、午、未、申）
     * 日の入りから翌日の出までを6等分（酉、戌、亥、子、丑、寅）
     * それぞれの時間帯をさらに4等分（一つ、二つ、三つ、四つ）
     */
    private fun calculateJapaneseTime(
        now: Calendar,
        sunriseTime: Calendar,
        sunsetTime: Calendar
    ): Pair<String, String> {
        val currentTimeMillis = now.timeInMillis
        val sunriseMillis = sunriseTime.timeInMillis
        val sunsetMillis = sunsetTime.timeInMillis

        // 日の出・日の入り時刻（24時間表記）
        val sunriseStr = SimpleDateFormat("HH:mm", Locale.JAPAN).format(sunriseTime.time)
        val sunsetStr = SimpleDateFormat("HH:mm", Locale.JAPAN).format(sunsetTime.time)
        val sunInfo = "日出$sunriseStr-日入$sunsetStr"

        // 日の出から日の入りまでの日中の期間
        val daytimeDurationMillis = sunsetMillis - sunriseMillis

        // 翌日の日の出を計算
        val nextDaySunriseTime = sunriseTime.clone() as Calendar
        nextDaySunriseTime.add(Calendar.DAY_OF_YEAR, 1)
        val nextSunriseMillis = nextDaySunriseTime.timeInMillis

        // 日の入りから翌日の日の出までの夜間の期間
        val nighttimeDurationMillis = nextSunriseMillis - sunsetMillis

        val durationMillis: Long
        val startMillis: Long
        val labels: Array<String>

        val isDaytime = currentTimeMillis >= sunriseMillis && currentTimeMillis < sunsetMillis

        if (isDaytime) {
            // 日中
            durationMillis = daytimeDurationMillis
            startMillis = sunriseMillis
            labels = dayTimeLabels
        } else {
            // 夜間
            if (currentTimeMillis >= sunsetMillis) {
                // 今日の日の入り後から明日の日の出前まで
                durationMillis = nighttimeDurationMillis
                startMillis = sunsetMillis
            } else {
                // 今日の日の出前（つまり昨日の日の入り後から今日の日の出前まで）
                val previousDaySunsetTime = sunsetTime.clone() as Calendar
                previousDaySunsetTime.add(Calendar.DAY_OF_YEAR, -1) // 昨日の日の入り
                val previousSunsetMillis = previousDaySunsetTime.timeInMillis
                durationMillis = sunriseMillis - previousSunsetMillis
                startMillis = previousSunsetMillis
            }
            labels = nightTimeLabels
        }

        if (durationMillis <= 0) {
            Log.e(
                TAG,
                "Duration for Japanese time calculation is zero or negative: $durationMillis"
            )
            return Pair("時間計算エラー", sunInfo)
        }

        val timeUnitMillis = durationMillis / 6.0 // 1時間単位（不定時法）
        val timePassedMillis = currentTimeMillis - startMillis

        // 6つの時間帯のどこにいるか (0-5)
        var timeUnitIndex = (timePassedMillis / timeUnitMillis).toInt()

        // 4等分のどこにいるか (0-3)
        val remainderMillis = timePassedMillis % timeUnitMillis
        var timeSubUnitIndex = (remainderMillis / (timeUnitMillis / 4.0)).toInt()

        // 境界値の調整
        // durationMillis は double で計算しているため、timeUnitIndex が 6 になることがある。
        // また、timeSubUnitIndex が 4 になることもあるため、インデックスを範囲内に収める。
        if (timeUnitIndex >= labels.size) {
            timeUnitIndex = labels.size - 1
            timeSubUnitIndex = hourNumber.size - 1
        }
        if (timeSubUnitIndex >= hourNumber.size) {
            timeSubUnitIndex = hourNumber.size - 1
        }
        if (timeUnitIndex < 0) {
            timeUnitIndex = 0
            timeSubUnitIndex = 0
        }

        val hourName = labels[timeUnitIndex]
        val subHourName = hourNumber[timeSubUnitIndex]

        return Pair("$hourName$subHourName", sunInfo)
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
        updateAppWidgetInternal(
            pseudToday,
            context,
            appWidgetManager,
            appWidgetId,
            forceSunriseRecalc
        )
        // 次の更新をスケジュール
        SunriseWidgetAlarmUtils.scheduleNextUpdate(pseudToday, context, appWidgetId)
    }

    /**
     * ロケーションまたは時刻設定の変更があった場合に、その日一日の calculateJapaneseTime を全てログに表示する
     */
    private fun logAllJapaneseTimesForToday(context: Context, appWidgetId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = Calendar.getInstance()

        val latitudeString = prefs.getString(PREF_LATITUDE_PREFIX + appWidgetId, null)
        val longitudeString = prefs.getString(PREF_LONGITUDE_PREFIX + appWidgetId, null)

        val latitude = try {
            latitudeString?.toDouble() ?: DEFAULT_LATITUDE
        } catch (e: NumberFormatException) {
            DEFAULT_LATITUDE
        }
        val longitude = try {
            longitudeString?.toDouble() ?: DEFAULT_LONGITUDE
        } catch (e: NumberFormatException) {
            DEFAULT_LONGITUDE
        }

        // ここでは強制的に再計算させる
        val sunriseTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(
            context,
            appWidgetId,
            latitude,
            longitude,
            today,
            true,
            true
        )
        val sunsetTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(
            context,
            appWidgetId,
            latitude,
            longitude,
            today,
            false,
            true
        )

        if (sunriseTime == null || sunsetTime == null) {
            Log.e(
                TAG,
                "Failed to get sunrise/sunset times for logging all Japanese times for widget ID: $appWidgetId."
            )
            return
        }

        Log.d(
            TAG,
            "--- Logging all Japanese times for widget ID: $appWidgetId (Today: ${
                SimpleDateFormat(
                    "yyyy/MM/dd",
                    Locale.JAPAN
                ).format(today.time)
            }) ---"
        )

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

                // calculateJapaneseTimeに直接tempCalendarを渡す
                val (japaneseTime, sunInfo) = calculateJapaneseTime(
                    tempCalendar,
                    sunriseTime,
                    sunsetTime
                )
                Log.d(
                    TAG,
                    "Time: ${
                        SimpleDateFormat(
                            "HH:mm",
                            Locale.JAPAN
                        ).format(tempCalendar.time)
                    } -> Japanese: $japaneseTime, Sun: $sunInfo"
                )
            }
        }
        Log.d(TAG, "--- End of Japanese times log for widget ID: $appWidgetId ---")
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
        val month = calendar.get(Calendar.MONTH) // 0-11
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val solarTerms = listOf(
            "小寒", "大寒", "立春", "雨水", "啓蟄", "春分", "清明", "穀雨",
            "立夏", "小満", "芒種", "夏至", "小暑", "大暑", "立秋", "処暑",
            "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"
        )
        val approxTermDates = listOf(
            Pair(0, 5), Pair(0, 20), Pair(1, 4), Pair(1, 19), Pair(2, 5), Pair(2, 20),
            Pair(3, 4), Pair(3, 20), Pair(4, 5), Pair(4, 21), Pair(5, 5), Pair(5, 21),
            Pair(6, 7), Pair(6, 22), Pair(7, 7), Pair(7, 23), Pair(8, 7), Pair(8, 23),
            Pair(9, 8), Pair(9, 23), Pair(10, 7), Pair(10, 22), Pair(11, 7), Pair(11, 21)
        )

        var currentSolarTerm = solarTerms.last()

        for (i in approxTermDates.indices) {
            val (termMonth, termDay) = approxTermDates[i]
            if (month > termMonth || (month == termMonth && day >= termDay)) {
                currentSolarTerm = solarTerms[i]
            } else {
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
        // 1864年が甲子 (1864 % 10 = 4 (甲は0番目なので +6), 1864 % 12 = 4 (子は0番目なので +8))
        // 実際の計算: (西暦年 - 4) % 10 -> 天干のインデックス (甲=0)
        // (西暦年 - 4) % 12 -> 地支のインデックス (子=0)
        // または、西暦年の剰余から直接インデックスを求める
        val stemIndex = (year - 4) % 10
        val branchIndex = (year - 4) % 12
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

        val reiwaStartDate = Triple(2019, 5, 1)
        val heiseiStartDate = Triple(1989, 1, 8)
        val showaStartDate = Triple(1926, 12, 25)
        val taishoStartDate = Triple(1912, 7, 30)
        val meijiStartDate = Triple(1868, 1, 25)

        return when {
            year > reiwaStartDate.first || (year == reiwaStartDate.first && (month > reiwaStartDate.second || (month == reiwaStartDate.second && day >= reiwaStartDate.third)))
                -> "令和${toKanjiNumber(year - reiwaStartDate.first + 1)}年"

            year > heiseiStartDate.first || (year == heiseiStartDate.first && (month > heiseiStartDate.second || (month == heiseiStartDate.second && day >= heiseiStartDate.third)))
                -> "平成${toKanjiNumber(year - heiseiStartDate.first + 1)}年"

            year > showaStartDate.first || (year == showaStartDate.first && (month > showaStartDate.second || (month == showaStartDate.second && day >= showaStartDate.third)))
                -> "昭和${toKanjiNumber(year - showaStartDate.first + 1)}年"

            year > taishoStartDate.first || (year == taishoStartDate.first && (month > taishoStartDate.second || (month == taishoStartDate.second && day >= taishoStartDate.third)))
                -> "大正${toKanjiNumber(year - taishoStartDate.first + 1)}年"

            year >= meijiStartDate.first
                -> "明治${toKanjiNumber(year - meijiStartDate.first + 1)}年"

            else -> "${year}年"
        }
    }

    /** 数値を漢数字（元号用）に変換 */
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

// WidgetUpdateReceiver および ScreenOnReceiver は変更なし
// ...
//class WidgetUpdateReceiver : BroadcastReceiver() {
//    override fun onReceive(context: Context, intent: Intent?) {
//        val appWidgetId = intent?.getIntExtra("appWidgetId", -1) ?: -1
//        if (appWidgetId != -1) {
//            Log.i("WidgetUpdateReceiver", "Widget $appWidgetId update triggered.")
//            val today = Calendar.getInstance()
//            proceedWithWidgetUpdate(today, context, appWidgetId, forceSunriseRecalc = true)
//        }
//    }
//}
//
class ScreenOnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_SCREEN_ON) {
            Log.i(
                "ScreenOnReceiver",
                "Screen ON detected. Triggering widget update via AppWidgetProvider (ACTION_APPWIDGET_UPDATE)."
            )

            val appWidgetManager = AppWidgetManager.getInstance(context)
            // Oyatsu::class.java は、実際の AppWidgetProvider のクラス名に置き換えてください
            val componentName = ComponentName(context, Oyatsu::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            if (appWidgetIds == null || appWidgetIds.isEmpty()) {
                Log.d("ScreenOnReceiver", "No widget IDs found for ${componentName.className}")
                return
            }

            // AppWidgetProvider (Oyatsu) に標準の更新インテントを送信します。
            // これにより、Oyatsu の onReceive がトリガーされ、既存のロジックで
            // proceedWithWidgetUpdate が forceSunriseRecalc = true で呼び出されます。
            val updateIntent = Intent(context, Oyatsu::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            }
            context.sendBroadcast(updateIntent)
            Log.d(
                "ScreenOnReceiver",
                "Sent ACTION_APPWIDGET_UPDATE for IDs: ${appWidgetIds.joinToString()}"
            )
        }
    }
}