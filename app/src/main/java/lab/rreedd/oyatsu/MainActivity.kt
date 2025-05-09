package lab.rreedd.oyatsu

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val TAG = "OyatsuWidget"
private const val PREFS_NAME = "lab.rreedd.oyatsu.OyatsuWidgetPrefs"
private const val PREF_SUNRISE_TIME_PREFIX =
    "sunrise_time_" // 例: sunrise_time_123_millis, sunrise_time_123_date
private const val PREF_SUNSET_TIME_PREFIX =
    "sunset_time_" // 例: sunset_time_123_millis, sunset_time_123_date
private const val PREF_LATITUDE_PREFIX = "latitude_"       // 例: latitude_123
private const val PREF_LONGITUDE_PREFIX = "longitude_"      // 例: longitude_123
private const val ACTION_ALARM_UPDATE =
    "lab.rreedd.oyatsu.ACTION_ALARM_UPDATE" // AlarmManagerからのカスタムアクション

// --- 定数 ---
private const val DEFAULT_LATITUDE = 35.6895 // デフォルト緯度（東京駅） - 位置情報が取れない場合に使用
private const val DEFAULT_LONGITUDE = 139.6917 // デフォルト経度（東京駅）

class Oyatsu : AppWidgetProvider() {
    // 和時計のための定数
    private val dayTimeLabels = arrayOf("卯", "辰", "巳", "午", "未", "申")
    private val nightTimeLabels = arrayOf("酉", "戌", "亥", "子", "丑", "寅")
    private val hourNumber = arrayOf("一つ", "二つ", "三つ", "四つ")

    // FusedLocationProviderClient のインスタンス (遅延初期化)
    private lateinit var fusedLocationClient: FusedLocationProviderClient

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
            // 既存の日の出情報を使ってまずは表示を更新
            updateAppWidgetInternal(context, appWidgetManager, appWidgetId, false) // 通常は日の出再計算不要
            // 次の更新をスケジュールする (これもACTION_ALARM_UPDATEで処理するため、ここでは単にスケジュール設定)
            SunriseWidgetAlarmUtils.scheduleNextUpdate(context, appWidgetId)
        }
    }

    /**
     * 最初のウィジェットインスタンスが作成されたときに呼び出される。
     */
    override fun onEnabled(context: Context) {
        Log.d(TAG, "onEnabled called")
        // FusedLocationProviderClient を初期化
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        // アプリ起動時やウィジェット初回設置時にパーミッション確認・要求フローを入れるのが理想
        // ここでは、既存のウィジェットIDに対して強制的に位置情報取得と更新を試みる
        val appWidgetManager = AppWidgetManager.getInstance(context)
        // この AppWidgetProvider に関連付けられているすべてのウィジェットIDを取得
        val thisAppWidget = ComponentName(context.packageName, javaClass.name)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)

        // 初回設定として、すべてのアクティブなウィジェットに対して位置情報取得を試みる
        appWidgetIds.forEach { appWidgetId ->
            Log.d(TAG, "Initial setup for widget ID: $appWidgetId")
            // 初回なので、位置情報取得に成功したら日の出時刻を強制的に再計算させる
            requestLocationAndUpdate(context, appWidgetId, true)
        }
        // 注: onEnabled での位置情報リクエストは、アプリがバックグラウンドにいる場合に位置情報アクセス許可が
        // まだ得られていない（「アプリの使用中のみ」など）と、意図した通りに位置情報が取得できないことがあります。
        // ユーザーに初回設置時に位置情報許可ダイアログを表示させるフローを入れるのがよりユーザーフレンドリーです。
        // （本コードではユーザーが手動で設定することを前提としているため、その部分は省略しています）
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
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                remove(PREF_SUNRISE_TIME_PREFIX + appWidgetId + "_millis")
                remove(PREF_SUNRISE_TIME_PREFIX + appWidgetId + "_date")
                remove(PREF_SUNSET_TIME_PREFIX + appWidgetId + "_millis")  // 日の入り時刻も削除
                remove(PREF_SUNSET_TIME_PREFIX + appWidgetId + "_date")    // 日の入り日付も削除
                remove(PREF_LATITUDE_PREFIX + appWidgetId)
                remove(PREF_LONGITUDE_PREFIX + appWidgetId)
            }
            Log.i(TAG, "Cleaned up data for deleted widget ID: $appWidgetId")
        }
    }

    /**
     * ブロードキャストインテントを受信したときに呼び出される。
     * super.onReceive を最初に呼び出すことが重要。
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // これを呼ばないと onUpdate などがディスパッチされない

        val action = intent.action
        Log.d(TAG, "onReceive: action = $action")

        when (action) {
            ACTION_ALARM_UPDATE -> {
                // AlarmManagerからのカスタム更新アクション
                val appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Log.d(TAG, "Received custom alarm for widget ID: $appWidgetId")
                    // アラーム経由での更新時には、まず位置情報取得を試み、成功したらウィジェット更新・スケジュール
                    // 位置情報がない場合や取得失敗した場合も、保存された値で更新を試み、スケジュールは行う
                    requestLocationAndUpdate(
                        context,
                        appWidgetId,
                        false
                    ) // アラームからの更新では通常日の出再計算不要（日が跨いでいればgetSunriseTimeForWidget内で再計算される）
                    // 注: requestLocationAndUpdate の中で updateAppWidgetInternal と scheduleNextUpdate を呼んでいるため、
                    // ここでそれらを再度呼び出す必要はありません。
                } else {
                    Log.w(TAG, "Received alarm intent without valid widget ID.")
                }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Received ACTION_BOOT_COMPLETED")
                // デバイス起動時にすべてのアクティブなウィジェットのアラームを再スケジュール
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisAppWidget = ComponentName(context.packageName, javaClass.name)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
                appWidgetIds.forEach { appWidgetId ->
                    Log.d(TAG, "Rescheduling alarm for widget ID: $appWidgetId after boot")
                    // 再起動後は日の出時刻が変わっている可能性があるため、位置情報再取得と更新を試みる
                    requestLocationAndUpdate(context, appWidgetId, true) // 強制的に日の出再計算を試みる
                }
                // 注: requestLocationAndUpdate の中で scheduleNextUpdate も呼んでいます。
            }

            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                Log.d(TAG, "Received $action")
                // 日付またはタイムゾーン変更時に、全ウィジェットの日の出時刻を強制的に再計算し、アラームを再設定
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisAppWidget = ComponentName(context.packageName, javaClass.name)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
                appWidgetIds.forEach { appWidgetId ->
                    Log.d(
                        TAG,
                        "Re-calculating sunrise and rescheduling for widget ID: $appWidgetId due to $action"
                    )
                    // 位置情報は再取得せず、保存されたもの（またはデフォルト値）を使って日の出を再計算して更新・スケジュール
                    updateAppWidgetInternal(context, appWidgetManager, appWidgetId, true) // 強制再計算
                    SunriseWidgetAlarmUtils.scheduleNextUpdate(context, appWidgetId)
                }
            }
            // 他のアクション (e.g., AppWidgetManager.ACTION_APPWIDGET_UPDATE) は super.onReceive で処理される
        }
    }

    // --- ウィジェット更新ロジック ---


    /**
     * 指定されたウィジェットIDの表示を更新する内部メソッド。
     * @param context Context
     * @param appWidgetManager AppWidgetManager
     * @param appWidgetId 更新するウィジェットのID
     * @param forceRecalc trueの場合、保存された値に関わらず日の出入り時刻を再計算する
     */
    private fun updateAppWidgetInternal(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        forceRecalc: Boolean
    ) {
        Log.d(TAG, "Updating widget ID: $appWidgetId, forceSunriseRecalc: $forceRecalc")
        val views = RemoteViews(context.packageName, R.layout.widget_oyatsu)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // --- 1. 既存の暦情報計算 ---
        val calendar = Calendar.getInstance()
        val gregorianDate = SimpleDateFormat("yyyy年MM月dd日", Locale.JAPAN).format(calendar.time)
        val japaneseMonthName = getJapaneseMonthName(calendar)
        val solarTerm = getSolarTerm(calendar)
        val sixtyKanjiCycle = getSixtyKanjiCycle(calendar)
        val japaneseYear = getJapaneseYear(calendar)

        // --- 2. 日の出・日の入り時刻と和時計に基づく時刻の計算・表示 ---
        // 日の出・日の入り時刻を取得
        val todaySunriseTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(prefs, appWidgetId, true, forceRecalc) // 通常は再計算不要
        val todaySunsetTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(prefs, appWidgetId, false, forceRecalc) // 通常は再計算不要
        // sunTime変数をスコープ外でも使えるよう宣言
        var sunTime = ""
        val japaneseTimeText: String = if (todaySunriseTime != null && todaySunsetTime != null) {
            val resultPair = calculateJapaneseTime(todaySunriseTime, todaySunsetTime) // Pair を取得
            val calculatedText = resultPair.first // Pair の最初の値を取得
            sunTime = resultPair.second
            Log.d(TAG, "Widget $appWidgetId: $calculatedText (sunTime: $sunTime)") // 計算結果と sunTime をログ出力
            // if式の「結果」として calculatedText (String型) を返す
            calculatedText
        } else {
            // 日の出・日の入り時刻の計算が失敗した場合
            Log.w(TAG, "Widget $appWidgetId: Failed to calculate sunrise/sunset time.")
            // 強制的にデフォルト位置情報を保存して再試行
            saveDefaultLocation(context, appWidgetId)
            // 一度だけ再試行（無限ループ防止）
            if (!forceRecalc) {
                Log.d(TAG, "Retrying sunrise/sunset calculation with default location.")
                // 次回の更新をスケジュールする（短い間隔で）
                scheduleQuickUpdate(context, appWidgetId)
            }
            // else式の「結果」としてデフォルトメッセージ (String型) を返す
            sunTime = "計算中..."
            "時刻計算中..."
        }

        // widget_oyatsu.xml で表示する項目
        views.setTextViewText(R.id.text_japanese_year_month, "$japaneseYear $japaneseMonthName")
        views.setTextViewText(R.id.text_gregorian_date, gregorianDate)
        views.setTextViewText(
            R.id.text_jikoku_solar_term_sixty_cycle,
            "$japaneseTimeText $solarTerm $sixtyKanjiCycle"
        )
        views.setTextViewText(R.id.text_sun_time, sunTime)

        // --- 3. ウィジェットを更新 ---
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

    private fun scheduleQuickUpdate(context: Context, appWidgetId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createAlarmPendingIntent(context, appWidgetId) // あなたのユーティリティクラスを使用

        // 30秒後に再更新
        val triggerAtMillis = SystemClock.elapsedRealtime() + 30 * 1000L

        // APIレベル31 (Android 12) 以上での正確なアラームの権限チェック
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android S は API 31
            if (alarmManager.canScheduleExactAlarms()) {
                // 正確なアラームを設定する権限がある場合
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                    Log.d(TAG, "Scheduled exact quick update in 30 seconds for widget $appWidgetId")
                } catch (e: SecurityException) {
                    // ごくまれに、チェックと設定の間に権限が失われる可能性も考慮し、念のため捕捉
                    Log.e(TAG, "SecurityException while setting exact alarm (after check) for widget $appWidgetId", e)
                    showExactAlarmPermissionRequiredMessage(context) // ユーザーへの通知
                } catch (e: Exception) {
                    // その他の予期しないエラー
                    Log.e(TAG, "Unexpected error while setting exact alarm for widget $appWidgetId", e)
                }

            } else {
                // 正確なアラームを設定する権限がない場合
                Log.w(TAG, "Exact alarm permission denied for widget $appWidgetId. Cannot schedule exact alarm.")
                // ユーザーに権限がないことを通知し、設定画面への誘導を促す
                showExactAlarmPermissionRequiredMessage(context)
                // ここでは正確なアラームは設定しない。必要であればsetAndAllowWhileIdleなどで不正確なアラームを代替として設定することも可能。
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // APIレベル23 (M) から 30 (R) の場合
            // setExactAndAllowWhileIdle を使用 (Dozeモード中でも動作)
            // このAPIレベルでは SCHEDULE_EXACT_ALARM 権限のチェックは不要
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled exact quick update (M+) in 30 seconds for widget $appWidgetId")
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling quick update (M+) for widget $appWidgetId", e)
            }
        } else {
            // APIレベル22 (Lollipop MR1) 以下の場合
            // setExact を使用
            // このAPIレベルでは SCHEDULE_EXACT_ALARM 権限のチェックは不要
            try {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled exact quick update (pre-M) in 30 seconds for widget $appWidgetId")
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling quick update (pre-M) for widget $appWidgetId", e)
            }
        }
    }

    /**
     * 正確なアラーム権限が必要であることをユーザーに通知し、設定画面への誘導を促す
     */
    private fun showExactAlarmPermissionRequiredMessage(context: Context) {
        // ユーザーにわかりやすいメッセージを表示 (例: Toast, Dialog)
        Toast.makeText(context, "正確なアラームを設定するために許可が必要です。", Toast.LENGTH_LONG).show()
        // API レベル 31 (Android 12) 以上の場合
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            // ウィジェットからの呼び出しでは必ず FLAG_ACTIVITY_NEW_TASK が必要
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // 設定画面を開くIntentが処理できない場合のエラー処理
                Log.e(TAG, "Could not open exact alarm settings screen", e)
                // 一般設定画面へ移動する代替処理
                openAppSettings(context)
            }
        } else {
            // Android 12 未満ではアプリの設定画面へ誘導
            openAppSettings(context)
        }
    }
    // アプリの設定画面を開く関数
    private fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri = Uri.fromParts("package", context.packageName, null)
            intent.data = uri
            // ウィジェットからの呼び出しでは必ず FLAG_ACTIVITY_NEW_TASK が必要
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open app settings", e)
            Toast.makeText(context, "設定画面を開けませんでした。手動でシステム設定からアプリの権限を確認してください。", Toast.LENGTH_LONG).show()
        }
    }
    // --- 位置情報取得 ---
    private fun requestLocationAndUpdate(
        context: Context,
        appWidgetId: Int,
        forceSunriseRecalc: Boolean
    ) {
        Log.d(TAG, "Attempting to request location for widget ID: $appWidgetId")

        // サービスとして実行するために、WakeLockを取得
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WidgetUpdate:LocationWakeLock")
        wakeLock.acquire(30000) // 最大30秒間

        try {
            // FusedLocationProviderClient初期化
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

            // 権限確認
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {

                Log.e(TAG, "Location permissions not granted despite user having approved them")
                saveDefaultLocation(context, appWidgetId)
                proceedWithWidgetUpdate(context, appWidgetId, forceSunriseRecalc)
                if (wakeLock.isHeld) wakeLock.release()
                return
            }

            // 位置情報コールバック
            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    Log.d(TAG, "Location callback received for widget $appWidgetId")
                    fusedLocationClient.removeLocationUpdates(this)

                    val location = locationResult.lastLocation
                    if (location != null) {
                        Log.i(TAG, "Location obtained via callback for $appWidgetId: " +
                                "Lat=${location.latitude}, Lon=${location.longitude}, " +
                                "Accuracy=${location.accuracy}m")

                        // SharedPreferencesに保存
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                            putFloat(PREF_LATITUDE_PREFIX + appWidgetId, location.latitude.toFloat())
                            putFloat(PREF_LONGITUDE_PREFIX + appWidgetId, location.longitude.toFloat())
                            apply()
                        }

                        // ウィジェット更新
                        proceedWithWidgetUpdate(context, appWidgetId, true)
                    } else {
                        Log.w(TAG, "Location result received but location is null for widget $appWidgetId")
                        tryLastKnownLocation(context, appWidgetId, forceSunriseRecalc)
                    }

                    // WakeLock解放
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }

            // 最新のLocation API用の適切なリクエスト設定
            val locationRequest = LocationRequest.Builder(10000L)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdates(1)
                .setMinUpdateIntervalMillis(0)
                .setDurationMillis(15000L) // 15秒間だけ更新を受け付ける
                .build()

            // 位置情報更新をリクエスト
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            // タイムアウト処理
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // コールバックの登録を削除
                    fusedLocationClient.removeLocationUpdates(locationCallback)
                    Log.w(TAG, "Location request timed out for widget $appWidgetId")

                    // 最後の既知の位置情報を試す
                    tryLastKnownLocation(context, appWidgetId, forceSunriseRecalc)

                    // WakeLock解放
                    if (wakeLock.isHeld) wakeLock.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in timeout handler", e)
                    saveDefaultLocation(context, appWidgetId)
                    proceedWithWidgetUpdate(context, appWidgetId, forceSunriseRecalc)

                    // WakeLock解放
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }, 20000) // 20秒のタイムアウト

        } catch (e: Exception) {
            Log.e(TAG, "Exception when requesting location", e)
            saveDefaultLocation(context, appWidgetId)
            proceedWithWidgetUpdate(context, appWidgetId, forceSunriseRecalc)

            // エラー時もWakeLock解放
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    // 最後の既知の位置情報を取得する補助関数
    private fun tryLastKnownLocation(context: Context, appWidgetId: Int, forceSunriseRecalc: Boolean) {
        if (ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

            LocationServices.getFusedLocationProviderClient(context).lastLocation
                .addOnSuccessListener { lastLocation ->
                    if (lastLocation != null) {
                        Log.i(TAG, "Using last known location for widget $appWidgetId")
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                            putFloat(PREF_LATITUDE_PREFIX + appWidgetId, lastLocation.latitude.toFloat())
                            putFloat(PREF_LONGITUDE_PREFIX + appWidgetId, lastLocation.longitude.toFloat())
                            apply()
                        }
                        proceedWithWidgetUpdate(context, appWidgetId, true)
                    } else {
                        Log.w(TAG, "No last location available for widget $appWidgetId")
                        saveDefaultLocation(context, appWidgetId)
                        proceedWithWidgetUpdate(context, appWidgetId, forceSunriseRecalc)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Error getting last location for widget $appWidgetId", e)
                    saveDefaultLocation(context, appWidgetId)
                    proceedWithWidgetUpdate(context, appWidgetId, forceSunriseRecalc)
                }
        } else {
            saveDefaultLocation(context, appWidgetId)
            proceedWithWidgetUpdate(context, appWidgetId, forceSunriseRecalc)
        }
    }

    /**
     * デフォルトの位置情報（東京）を保存する
     */
    private fun saveDefaultLocation(context: Context, appWidgetId: Int) {
        Log.i(TAG, "Saving default location (Tokyo) for widget $appWidgetId")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putFloat(PREF_LATITUDE_PREFIX + appWidgetId, DEFAULT_LATITUDE.toFloat())
            putFloat(PREF_LONGITUDE_PREFIX + appWidgetId, DEFAULT_LONGITUDE.toFloat())
        }
    }

    /**
     * 位置情報処理後のウィジェット更新とスケジュール処理
     */
    private fun proceedWithWidgetUpdate(context: Context, appWidgetId: Int, forceSunriseRecalc: Boolean) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        // ウィジェットを更新
        updateAppWidgetInternal(context, appWidgetManager, appWidgetId, forceSunriseRecalc)
        // 次の更新をスケジュール
        SunriseWidgetAlarmUtils.scheduleNextUpdate(context, appWidgetId)
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
            appWidgetIds.forEach { appWidgetId ->
                SunriseWidgetAlarmUtils.scheduleNextUpdate(context, appWidgetId)
            }
        }
    }
}