package lab.rreedd.oyatsu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect // Rect は使っていないので削除可能
import android.graphics.RectF // RectF は使っていないので削除可能 (もし必要なら追加)
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos // 使っていないので削除可能
import kotlin.math.min
import kotlin.math.sin // 使っていないので削除可能

class JapaneseClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val TAG = "JapaneseClockView"

    // 日の出・日の入り時刻を保持する変数
    private var sunriseTime: Calendar? = null
    private var sunsetTime: Calendar? = null

    // === ここから追加・修正する部分 ===
    // HandlerをメインスレッドのLooperに関連付けて初期化する
    // これがNullPointerExceptionの原因だった可能性が高い
    private val updateHandler = Handler(Looper.getMainLooper())

    // 定期的な描画更新のためのRunnable
    private val updateRunnable = object : Runnable {
        override fun run() {
            // UI更新ロジック (例: 時刻の再計算、描画の再実行)
            invalidate() // onDrawを呼び出してビューを再描画
            updateHandler.postDelayed(this, 1000) // 1秒ごとに更新
        }
    }
    // === ここまで追加・修正する部分 ===


    // 描画用のPaintオブジェクト群
    private val hourPaint = Paint().apply {
        color = Color.BLACK
        textSize = 36f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val minutePaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val handPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 6f
        isAntiAlias = true
        style = Paint.Style.STROKE
    }

    private val centerPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val circlePaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // init ブロックはコンストラクタが呼び出された後に実行される
    init {
        // === ここから追加・修正する部分 ===
        // updateRunnable をスケジュールする (クラッシュログの85行目はこの辺りか、またはこの直前のHandler初期化が失敗している可能性)
        updateHandler.post(updateRunnable)
        // === ここまで追加・修正する部分 ===
    }

    /**
     * 日の出・日の入り時刻を設定し、ビューを更新します。
     * @param sunrise 日の出時刻 (Calendarオブジェクト)
     * @param sunset 日の入り時刻 (Calendarオブジェクト)
     */
    fun setSunriseSunsetTimes(sunrise: Calendar?, sunset: Calendar?) {
        this.sunriseTime = sunrise
        this.sunsetTime = sunset
        invalidate() // 時刻が更新されたら再描画
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val currentTime = Calendar.getInstance()
        val formattedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(currentTime.time)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (minOf(width, height) / 2f) * 0.8f

        // 時刻表示
        canvas.drawCircle(centerX, centerY, radius, circlePaint)
        canvas.drawText(formattedTime, centerX, centerY, hourPaint) // hourPaintをテキスト表示に流用

        // 日の出・日の入り時刻と和時計の計算・表示
        if (sunriseTime != null && sunsetTime != null) {
            val (japaneseTime, sunInfo) = calculateJapaneseTime(currentTime, sunriseTime!!, sunsetTime!!)
            canvas.drawText("和時計: $japaneseTime", centerX, centerY + 50f, minutePaint) // minutePaintをテキスト表示に流用
            canvas.drawText("日出没: $sunInfo", centerX, centerY + 100f, minutePaint) // minutePaintをテキスト表示に流用
        } else {
            canvas.drawText("位置情報未設定", centerX, centerY + 50f, minutePaint)
        }
    }

    // Oyatsu.kt からコピーした和時計計算ロジック
    private fun calculateJapaneseTime(now: Calendar, sunriseTime: Calendar, sunsetTime: Calendar): Pair<String, String> {
        val dayTimeLabels = arrayOf("卯", "辰", "巳", "午", "未", "申")
        val nightTimeLabels = arrayOf("酉", "戌", "亥", "子", "丑", "寅")
        val hourNumber = arrayOf("一つ", "二つ", "三つ", "四つ")

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
            Log.e(TAG, "Duration for Japanese time calculation is zero or negative: $durationMillis")
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // ビューがウィンドウからデタッチされたら、Runnableのポストを停止してメモリリークを防ぐ
        updateHandler.removeCallbacks(updateRunnable)
    }
}