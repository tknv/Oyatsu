package lab.rreedd.oyatsu

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri // この行を追加
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator
import com.luckycatlabs.sunrisesunset.dto.Location
import java.text.SimpleDateFormat
import java.util.*
// import java.util.concurrent.TimeUnit // 使用されていないため削除

private const val TAG = "SunriseSunsetUtils"
// ウィジェット更新のアラームスケジューリングを扱うユーティリティクラス
object SunriseWidgetAlarmUtils {
    const val PREFS_NAME = "lab.rreedd.oyatsu.OyatsuWidgetPrefs"

    // 定数定義
    const val ACTION_ALARM_UPDATE = "lab.rreedd.oyatsu.ALARM_UPDATE"
    const val PREF_SUNRISE_TIME_PREFIX = "sunrise_time_"
    const val PREF_SUNSET_TIME_PREFIX = "sunset_time_"
    const val PREF_LAST_CALC_DATE_PREFIX = "last_calc_date_" // 日の出入り時刻を計算した日付を保存

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

        val storedTimeKey = (if (isSunrise) PREF_SUNRISE_TIME_PREFIX else PREF_SUNSET_TIME_PREFIX) + appWidgetId
        val storedDateKey = PREF_LAST_CALC_DATE_PREFIX + appWidgetId

        val storedMillis = prefs.getLong(storedTimeKey, -1L)
        val lastCalcDate = prefs.getString(storedDateKey, null)

        // 強制再計算でなく、かつ、保存された日付が今日と同じで、かつ、時刻が有効であれば、保存された時刻を返す
        if (!forceRecalc && storedMillis != -1L && lastCalcDate == todayDateString) {
            val storedCalendar = Calendar.getInstance()
            storedCalendar.timeInMillis = storedMillis
            Log.d(TAG, "Using stored ${if(isSunrise) "sunrise" else "sunset"} time for widget $appWidgetId: ${SimpleDateFormat("HH:mm:ss", Locale.US).format(storedCalendar.time)}")
            return storedCalendar
        }

        // 再計算が必要な場合
        Log.d(TAG, "Recalculating ${if(isSunrise) "sunrise" else "sunset"} time for widget $appWidgetId. Force: $forceRecalc, StoredDate: $lastCalcDate, TodayDate: $todayDateString")
        try {
            val location = Location(latitude, longitude)
            // タイムゾーンは、targetDateから取得する (端末の現在のタイムゾーンを使用)
            val calculator = SunriseSunsetCalculator(location, targetDate.timeZone.id)

            val calculatedTime = if (isSunrise) {
                val sunriseCalc = calculator.getOfficialSunriseCalendarForDate(targetDate)
                Log.d(TAG, "Calculated Sunrise for $todayDateString: ${sunriseCalc?.let { SimpleDateFormat("HH:mm:ss", Locale.US).format(it.time) } ?: "null"}")
                sunriseCalc
            } else {
                val sunsetCalc = calculator.getOfficialSunsetCalendarForDate(targetDate) // ここをgetOfficialSunsetCalendarForDateに修正
                Log.d(TAG, "Calculated Sunset for $todayDateString: ${sunsetCalc?.let { SimpleDateFormat("HH:mm:ss", Locale.US).format(it.time) } ?: "null"}")
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
                Log.d(TAG, "Calculated and saved ${if(isSunrise) "sunrise" else "sunset"} time for widget $appWidgetId: ${SimpleDateFormat("HH:mm:ss", Locale.US).format(it.time)}")
                return it
            } ?: run {
                Log.e(TAG, "Failed to calculate ${if(isSunrise) "sunrise" else "sunset"} time for widget $appWidgetId.")
                return null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error calculating sunrise/sunset for widget $appWidgetId: ${e.message}", e)
            return null
        }
    }

    /**
     * ウィジェット更新用のアラームをスケジュールする。
     * @param context Context
     * @param appWidgetId スケジュールするウィジェットのID
     */
    fun scheduleNextUpdate(pseudToday: Calendar, context: Context, appWidgetId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, Oyatsu::class.java).apply {
            action = ACTION_ALARM_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("oyatsu://widget/id/$appWidgetId") // PendingIntentのユニークさを確保
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId, // requestCode: ウィジェットIDごとにユニークなIntentにするため
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        // 次の分に更新されるようにスケジュールする
        val now = Calendar.getInstance()
        now.add(Calendar.MINUTE, 1) // 1分進める
        now.set(Calendar.SECOND, 0) // 秒を0にする
        now.set(Calendar.MILLISECOND, 0) // ミリ秒を0にする

        val nextUpdateMillis = now.timeInMillis

        // Android 12 (API 31) 以降での厳密なアラームの制限に対応
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31 (S) から canScheduleExactAlarms が導入
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextUpdateMillis,
                    pendingIntent
                )
            } else {
                // canScheduleExactAlarms() が false の場合
                // ユーザーに権限を付与してもらうよう促すUIを表示するなどの対応が必要
                // ここではログ出力のみ
                Log.w(TAG, "Cannot schedule exact alarms for widget $appWidgetId. User must grant SCHEDULE_EXACT_ALARM permission.")
                // 代替として setAlarmClock() や setAndAllowWhileIdle() を検討するか、
                // ユーザーに設定画面を開くよう促すIntentを発行することもできます。
                // 例: Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                // ただし、ウィジェットのバックグラウンド処理なので、ユーザーに毎回権限付与を求めるのはUX的に好ましくない場合があります。
                // setExact() の代わりに setAndAllowWhileIdle() などを使用するのも手ですが、精度は落ちます。
                // ここでは、アラームが設定できなかったことをログに記録するのみとします。
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // API 23 (M) から setExactAndAllowWhileIdle が利用可能
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextUpdateMillis,
                pendingIntent
            )
        } else {
            // Android 6.0 (M) 未満の場合
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                nextUpdateMillis,
                pendingIntent
            )
        }
        Log.d(TAG, "Scheduled next update for widget $appWidgetId at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(nextUpdateMillis))}")
    }

    /**
     * 指定されたウィジェットIDのアラームをキャンセルする。
     * @param context Context
     * @param appWidgetId キャンセルするウィジェットのID
     */
    fun cancelAlarm(context: Context, appWidgetId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, Oyatsu::class.java).apply {
            action = ACTION_ALARM_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("oyatsu://widget/id/$appWidgetId")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        Log.i(TAG, "Canceled alarm for widget ID: $appWidgetId")
    }
}