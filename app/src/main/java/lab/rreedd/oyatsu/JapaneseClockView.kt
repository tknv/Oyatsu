package lab.rreedd.oyatsu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import androidx.core.graphics.toColorInt

class JapaneseClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val TAG = "JapaneseClockView"

    // 日の出・日の入り時刻を保持する変数
    private var sunriseTime: Calendar? = null
    private var sunsetTime: Calendar? = null

    // HandlerをメインスレッドのLooperに関連付けて初期化する
    private val updateHandler = Handler(Looper.getMainLooper())

    // 定期的な描画更新のためのRunnable
    private val updateRunnable = object : Runnable {
        override fun run() {
            invalidate() // onDrawを呼び出して再描画

            // 次の更新までの遅延時間を計算
            val currentTime = Calendar.getInstance()
            val (_, sunInfo) = calculateJapaneseTimeAndSunInfo(currentTime) // 更新時間を計算するために呼び出し
            val currentSunrise = sunInfo["sunrise"]
            val currentSunset = sunInfo["sunset"]

            var delayMillis = 60 * 1000L // デフォルトは1分 (1分置きの更新)

            if (currentSunrise != null && currentSunset != null) {
                val dayDurationMillis = currentSunset.timeInMillis - currentSunrise.timeInMillis
                val nextDaySunrise = Calendar.getInstance().apply {
                    timeInMillis = currentSunrise.timeInMillis + 24 * 3600 * 1000L
                }
                val nightDurationMillis = nextDaySunrise.timeInMillis - currentSunset.timeInMillis

                val nowMillis = currentTime.timeInMillis

                // 現在が昼間か夜間かで更新間隔を計算
                // 有効な日の出・日の入り時刻がない場合はデフォルト遅延のまま
                if (dayDurationMillis > 0 && nightDurationMillis > 0) {
                    if (nowMillis >= currentSunrise.timeInMillis && nowMillis < currentSunset.timeInMillis) {
                        // 昼間: 日の出から日の入りまでを384で割った時間で更新
                        delayMillis = (dayDurationMillis / 384.0).toLong()
                    } else {
                        // 夜間: 日の入りから翌日の出までを384で割った時間で更新
                        delayMillis = (nightDurationMillis / 384.0).toLong()
                    }
                }
            }
            // 最小更新間隔を設ける (例: 1秒)
            if (delayMillis < 1000L) delayMillis = 1000L

            Log.d(TAG, "Next update in: ${delayMillis / 1000.0} seconds")
            updateHandler.postDelayed(this, delayMillis)
        }
    }

    // ペイントオブジェクト
    private val textPaint = Paint().apply {
        color = Color.WHITE // 和時刻の文字色
        textSize = 36f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.BLACK // 時計の円周線や目盛り線の色
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val handPaint = Paint().apply {
        color = Color.WHITE // 針の色
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val dotPaint = Paint().apply {
        color = Color.GRAY // 点の色
        textSize = 20f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    // 十二支のラベル (午を起点に右回り、等間隔で固定)
    private val japaneseTimeLabelsFixedOrder = arrayOf(
        "午", "未", "申", "酉", "戌", "亥", "子", "丑", "寅", "卯", "辰", "巳"
    )

    // 時間の等分を表すラベル (一つ、二つ、三つ、四つ)
    private val hourNumber = arrayOf("一つ", "二つ", "三つ", "四つ")

    // 計算用。日の出から始まる順 (卯、辰、巳、午、未、申, 酉, 戌, 亥, 子, 丑, 寅)
    private val japaneseTimeLabelsFullCycle = arrayOf(
        "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥", "子", "丑", "寅"
    )

    init {
        // ビューがアタッチされたら更新を開始
        startUpdating()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startUpdating()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopUpdating()
    }

    fun setSunriseSunsetTimes(sunrise: Calendar?, sunset: Calendar?) {
        var correctedSunset = sunset
        // 日の入りが日の出より早い場合、日付がずれていると判断して補正する
        if (sunrise != null && correctedSunset != null && correctedSunset.timeInMillis < sunrise.timeInMillis) {
            Log.d(TAG, "Sunset time is before sunrise. Correcting sunset date to the next day.")
            // 元のCalendarオブジェクトを変更しないようにコピーを作成して補正
            correctedSunset = (correctedSunset.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, 1)
            }
            Log.d(TAG, String.format("Corrected sunset time: %s",
                Date(correctedSunset.timeInMillis)
            ))
        }

        this.sunriseTime = sunrise
        this.sunsetTime = correctedSunset // 補正後の値を設定
        invalidate() // 時刻が設定されたら再描画
        // 日の出・日の入り時刻が変わった場合は、更新間隔も再計算するためRunnableを一度リセット
        stopUpdating()
        startUpdating()
    }

    /**
     * 定期的な更新を開始する
     */
    private fun startUpdating() {
        updateHandler.removeCallbacks(updateRunnable) // 既存のコールバックを削除
        updateHandler.post(updateRunnable) // 即座に実行し、その後定期的に実行
    }

    /**
     * 定期的な更新を停止する
     */
    private fun stopUpdating() {
        updateHandler.removeCallbacks(updateRunnable) // コールバックを停止
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) / 2f * 0.8f // 時計の半径

        val currentTime = Calendar.getInstance()
        val (japaneseTimeDisplay, sunInfo) = calculateJapaneseTimeAndSunInfo(currentTime)

        val currentSunrise = sunInfo["sunrise"]
        val currentSunset = sunInfo["sunset"]

        // 時計の円を描画 (背景の上に描画)
        canvas.drawCircle(centerX, centerY, radius, linePaint)

        // 現在の和時計の時刻を中央に表示（例: 午一つ）
        textPaint.textSize = 48f // 中央の文字サイズ
        val textHeight = textPaint.descent() - textPaint.ascent()
        canvas.drawText(japaneseTimeDisplay, centerX, centerY - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
        textPaint.textSize = 36f // 元に戻す (デフォルトサイズ)

        // 和時計の十二支ラベルと目盛り (不定時法に従い配置)
        if (currentSunrise != null && currentSunset != null) {
            drawJapaneseClockMarks(canvas, centerX, centerY, radius, sunInfo)
            // 外周にシステム時計の数字
            drawSystemClockMarks(canvas, centerX, centerY, radius * 1.25f, currentSunrise, currentSunset)
        }

        // 和時刻に従う針を描画
        if (currentSunrise != null && currentSunset != null) {
            drawJapaneseHourHand(canvas, centerX, centerY, radius * 0.65f, currentTime, currentSunrise, currentSunset)
        }
    }

    /**
     * 和時計の十二支ラベルと不定時法の目盛りを描画する。
     * 十二支ラベルは等間隔に配置される（午が頂点）。
     * 四等分点は不定時法に従い配置される。
     * @param canvas Canvas
     * @param centerX 中心X座標
     * @param centerY 中心Y座標
     * @param radius 時計の半径
     * @param sunInfo 日の出・日の入り時刻
     */
    private fun drawJapaneseClockMarks(canvas: Canvas, centerX: Float, centerY: Float, radius: Float, sunInfo: Map<String, Calendar>) {
        val currentSunrise = sunInfo["sunrise"]
        val currentSunset = sunInfo["sunset"]

        if (currentSunrise == null || currentSunset == null) {
            Log.e(TAG, "日の出・日の入り時刻が設定されていません。和時計の表示ができません。")
            return
        }

        val dayDurationMillis = currentSunset.timeInMillis - currentSunrise.timeInMillis
        val nextDaySunrise = Calendar.getInstance().apply {
            timeInMillis = currentSunrise.timeInMillis + 24 * 3600 * 1000L
        }
        val nightDurationMillis = nextDaySunrise.timeInMillis - currentSunset.timeInMillis

        if (dayDurationMillis <= 0 || nightDurationMillis <= 0) {
            Log.e(TAG, "日の出・日の入り時間の計算に問題があります。")
            return
        }

        val dayUnitMillis = dayDurationMillis / 6.0
        val nightUnitMillis = nightDurationMillis / 6.0

        val shortLineLength = 0.05f * radius // 短い線の長さ

        // ラベルの描画 (十二支は等間隔で固定配置)
        val labelTextSize = 36f * 1.3f // 30%大きくする
        textPaint.textSize = labelTextSize

        val totalLabels = japaneseTimeLabelsFixedOrder.size // 12
        val angleIncrement = 360f / totalLabels // 30度ずつ

        // 午の刻が真上 (0度) になるように、各ラベルの位置を計算
        // 0番目の要素"午"が上に来るようにする
        japaneseTimeLabelsFixedOrder.forEachIndexed { index, label ->
            // 角度は「午」が0度で時計回り。 index 0 = 午
            val angleDegrees = 0f + index * angleIncrement
            val angleRadians = Math.toRadians(angleDegrees.toDouble()).toFloat()

            val labelRadius = radius * 0.90f // 時計の円周の内側に配置
            val x = centerX + labelRadius * sin(angleRadians)
            val y = centerY - labelRadius * cos(angleRadians) // Y軸は上方向が負なので、cosの符号を反転

            // テキストのベースライン補正
            canvas.drawText(label, x, y + textPaint.textSize / 2.5f, textPaint)
        }
        textPaint.textSize = 36f // 元に戻す

        // 不定時法の四等分点を表示 (線と点で描画)
        val allJapanesePeriods = mutableListOf<Calendar>()
        allJapanesePeriods.addAll(listOf(
            currentSunrise, // 卯一つ
            Calendar.getInstance().apply { timeInMillis = currentSunrise.timeInMillis + (1 * dayUnitMillis).toLong() }, // 辰一つ
            Calendar.getInstance().apply { timeInMillis = currentSunrise.timeInMillis + (2 * dayUnitMillis).toLong() }, // 巳一つ
            Calendar.getInstance().apply { timeInMillis = currentSunrise.timeInMillis + (3 * dayUnitMillis).toLong() }, // 午一つ
            Calendar.getInstance().apply { timeInMillis = currentSunrise.timeInMillis + (4 * dayUnitMillis).toLong() }, // 未一つ
            Calendar.getInstance().apply { timeInMillis = currentSunrise.timeInMillis + (5 * dayUnitMillis).toLong() }, // 申一つ
            currentSunset, // 酉一つ
            Calendar.getInstance().apply { timeInMillis = currentSunset.timeInMillis + (1 * nightUnitMillis).toLong() }, // 戌一つ
            Calendar.getInstance().apply { timeInMillis = currentSunset.timeInMillis + (2 * nightUnitMillis).toLong() }, // 亥一つ
            Calendar.getInstance().apply { timeInMillis = currentSunset.timeInMillis + (3 * nightUnitMillis).toLong() }, // 子一つ
            Calendar.getInstance().apply { timeInMillis = currentSunset.timeInMillis + (4 * nightUnitMillis).toLong() }, // 丑一つ
            Calendar.getInstance().apply { timeInMillis = currentSunset.timeInMillis + (5 * nightUnitMillis).toLong() } // 寅一つ
        ))

        for (i in 0 until allJapanesePeriods.size) {
            val currentHourStartTimeMillis = allJapanesePeriods[i].timeInMillis
            val nextHourStartTimeMillis = if (i < allJapanesePeriods.size - 1) {
                allJapanesePeriods[i + 1].timeInMillis
            } else {
                nextDaySunrise.timeInMillis // 最後の寅の次は翌日の日の出
            }

            val currentHourDuration = nextHourStartTimeMillis - currentHourStartTimeMillis
            val subUnitDuration = currentHourDuration / 4.0

            for (j in 1..3) { // 「一つ」は開始点なので、二つ、三つ、四つの線と点を描画
                val subunitTimeMillis = currentHourStartTimeMillis + (j * subUnitDuration).toLong()
                // この角度計算は、現在の和時計の「午一つ」が頂点に固定された等間隔の和時計上で、
                // 指定されたシステム時刻（subunitTimeMillis）がどの和時刻に該当し、
                // その和時刻の角度がどこにあるかを返す
                val angle = getTimeAngleForJapaneseClock(
                    subunitTimeMillis,
                    currentSunrise,
                    currentSunset,
                    dayDurationMillis,
                    nightDurationMillis
                )

                // 線を描画
                val outerX = centerX + radius * sin(angle)
                val outerY = centerY - radius * cos(angle)
                val innerX = centerX + (radius - shortLineLength) * sin(angle)
                val innerY = centerY - (radius - shortLineLength) * cos(angle)
                canvas.drawLine(outerX, outerY, innerX, innerY, linePaint)

                // 「・」を描画
                val dotRadius = radius * 0.9f // 点の描画半径
                val dotX = centerX + dotRadius * sin(angle)
                val dotY = centerY - dotRadius * cos(angle) + dotPaint.textSize / 2.5f // Y軸補正
                canvas.drawText("・", dotX, dotY, dotPaint)
            }
        }
    }


    /**
     * 指定されたシステム時刻が、午の刻を頂点とし、十二支が等間隔に配置された和時計の円周上で
     * どの角度に相当するかを計算する。
     *
     * @param targetTimeMillis 評価対象のシステム時刻 (ms)
     * @param sunrise 日の出時刻
     * @param sunset 日の入り時刻
     * @param dayDurationMillis 昼の長さ (ms)
     * @param nightDurationMillis 夜の長さ (ms)
     * @return 和時計の円周上での角度 (ラジアン, 午が0度で時計回り)
     */
    private fun getTimeAngleForJapaneseClock(targetTimeMillis: Long, sunrise: Calendar, sunset: Calendar, dayDurationMillis: Long, nightDurationMillis: Long): Float {
        val todaySunrise = sunrise.timeInMillis
        val todaySunset = sunset.timeInMillis
        val nextDaySunrise = sunrise.timeInMillis + 24 * 3600 * 1000L // 翌日の日の出

        // ターゲット時刻を日の出から翌日の日の出までの24時間サイクルに正規化
        var effectiveTargetTimeMillis = targetTimeMillis
        while (effectiveTargetTimeMillis < todaySunrise) {
            effectiveTargetTimeMillis += 24 * 3600 * 1000L
        }
        while (effectiveTargetTimeMillis >= nextDaySunrise) {
            effectiveTargetTimeMillis -= 24 * 3600 * 1000L
        }

        val isDayTime = effectiveTargetTimeMillis >= todaySunrise && effectiveTargetTimeMillis < todaySunset

        val hourIndex: Int // 0-11 for 卯-寅 (japaneseTimeLabelsFullCycle のインデックス)
        val subHourProgress: Float // 0.0-1.0 within the sub-hour (一つ-四つ)

        if (isDayTime) {
            val timePassed = effectiveTargetTimeMillis - todaySunrise
            val unitDuration = dayDurationMillis / 6.0
            val exactHour = timePassed / unitDuration
            hourIndex = exactHour.toInt() // 卯=0, 辰=1, ..., 申=5
            subHourProgress = (exactHour - hourIndex).toFloat()
        } else {
            val timePassed = effectiveTargetTimeMillis - todaySunset
            val unitDuration = nightDurationMillis / 6.0
            val exactHour = timePassed / unitDuration
            hourIndex = exactHour.toInt() + 6 // 酉=6, 戌=7, ..., 寅=11
            subHourProgress = (exactHour - exactHour.toInt()).toFloat() // 小数部分のみ
        }

        // 和時計の「午一つ」が頂点（0度）に来るように調整
        val totalJapaneseHours = japaneseTimeLabelsFullCycle.size.toFloat() // 12刻
        val degreesPerJapaneseHour = 360f / totalJapaneseHours // 各刻の角度 (30度)

        // 現在の和時刻の、卯一つからの相対的な進行度 (0.0 - 11.99...)
        // 各刻の四等分点の進捗も考慮に入れる
        // --- 修正箇所 ---
        // subHourProgress は既に刻の中での 0.0 から 1.0 (次の刻の始まり) の進行度を表している
        // 例: 卯一つ = 0.0, 卯二つ = 0.25, 卯三つ = 0.5, 卯四つ = 0.75
        val progressWithinJapaneseHour = subHourProgress

        val overallJapaneseProgress = hourIndex + progressWithinJapaneseHour // 卯一つからの総進行度（刻単位）

        // 「午一つ」の相対的な位置（卯一つから数えて）
        val goNoKokuIndex = japaneseTimeLabelsFullCycle.indexOf("午").toFloat() // 3.0

        // 最終的な角度 (午一つを0度、時計回り)
        // (現在の和時刻の進捗 - 午一つまでの進捗 + 全体刻数) % 全体刻数 * 各刻の角度
        val finalAngleDegrees = ((overallJapaneseProgress - goNoKokuIndex + totalJapaneseHours) % totalJapaneseHours) * degreesPerJapaneseHour

        return Math.toRadians(finalAngleDegrees.toDouble()).toFloat()
    }


    /**
     * 和時計の時刻文字列を計算する。時間分秒は含まない。
     * @param currentTime 現在時刻
     * @return 和時計の時刻文字列 (例: 午一つ)
     */
    private fun calculateJapaneseTimeAndSunInfo(currentTime: Calendar): Pair<String, Map<String, Calendar>> {
        val sunInfo = mutableMapOf<String, Calendar>()
        val currentSunrise = sunriseTime
        val currentSunset = sunsetTime

        if (currentSunrise == null || currentSunset == null) {
            Log.e(TAG, "日の出・日の入り時刻が設定されていません。")
            return Pair("時刻未設定", sunInfo)
        }

        sunInfo["sunrise"] = currentSunrise
        sunInfo["sunset"] = currentSunset

        val currentTimeMillis = currentTime.timeInMillis
        val dayDurationMillis = currentSunset.timeInMillis - currentSunrise.timeInMillis
        val nextDaySunrise = Calendar.getInstance().apply {
            timeInMillis = currentSunrise.timeInMillis + 24 * 3600 * 1000L
        }
        val nightDurationMillis = nextDaySunrise.timeInMillis - currentSunset.timeInMillis

        // 現在の時刻が、日の出から日の出までのサイクルでどこに位置するかを判断
        var effectiveCurrentTimeMillis = currentTimeMillis
        while (effectiveCurrentTimeMillis < currentSunrise.timeInMillis) {
            effectiveCurrentTimeMillis += 24 * 3600 * 1000L
        }
        while (effectiveCurrentTimeMillis >= nextDaySunrise.timeInMillis) {
            effectiveCurrentTimeMillis -= 24 * 3600 * 1000L
        }


        val dayTimeLabels = arrayOf("卯", "辰", "巳", "午", "未", "申")
        val nightTimeLabels = arrayOf("酉", "戌", "亥", "子", "丑", "寅")

        var hourName = ""
        var subHourName = ""

        // まず、現在時刻が昼の刻の範囲内か夜の刻の範囲内かを判断する
        if (effectiveCurrentTimeMillis >= currentSunrise.timeInMillis && effectiveCurrentTimeMillis < currentSunset.timeInMillis) {
            // 昼の時間帯 (日の出から日の入り)
            val timePassedMillis = effectiveCurrentTimeMillis - currentSunrise.timeInMillis
            val timeUnitMillis = dayDurationMillis / 6.0 // 昼の1刻の長さ

            var timeUnitIndex = (timePassedMillis / timeUnitMillis).toInt()
            val remainderMillis = timePassedMillis % timeUnitMillis
            // 浮動小数点誤差対策: わずかな値を加算して丸め誤差を防ぐ
            var timeSubUnitIndex = ((remainderMillis + 0.001) / (timeUnitMillis / 4.0)).toInt()

            // 境界値の調整
            if (timeUnitIndex >= dayTimeLabels.size) timeUnitIndex = dayTimeLabels.size - 1
            if (timeSubUnitIndex >= hourNumber.size) timeSubUnitIndex = hourNumber.size - 1
            if (timeUnitIndex < 0) timeUnitIndex = 0
            if (timeSubUnitIndex < 0) timeSubUnitIndex = 0

            hourName = dayTimeLabels[timeUnitIndex]
            subHourName = hourNumber[timeSubUnitIndex]
        } else {
            // 夜の時間帯 (日の入りから翌日の出)
            val timePassedMillis = effectiveCurrentTimeMillis - currentSunset.timeInMillis

            val timeUnitMillis = nightDurationMillis / 6.0 // 夜の1刻の長さ

            var timeUnitIndex = (timePassedMillis / timeUnitMillis).toInt()
            val remainderMillis = timePassedMillis % timeUnitMillis
            // 浮動小数点誤差対策: わずかな値を加算して丸め誤差を防ぐ
            var timeSubUnitIndex = ((remainderMillis + 0.001) / (timeUnitMillis / 4.0)).toInt()

            // 境界値の調整
            if (timeUnitIndex >= nightTimeLabels.size) timeUnitIndex = nightTimeLabels.size - 1
            if (timeSubUnitIndex >= hourNumber.size) timeSubUnitIndex = hourNumber.size - 1
            if (timeUnitIndex < 0) timeUnitIndex = 0
            if (timeSubUnitIndex < 0) timeSubUnitIndex = 0

            hourName = nightTimeLabels[timeUnitIndex]
            subHourName = hourNumber[timeSubUnitIndex]
        }

        return Pair("$hourName$subHourName", sunInfo)
    }

    /**
     * システム時計の数字を描画する。
     * 和時計の外周に、和時刻の換算位置に基づいて配置する。
     * @param canvas Canvas
     * @param centerX 中心X座標
     * @param centerY 中心Y座標
     * @param outerRadius システム時計の外周半径 (和時計の外側)
     * @param sunrise 日の出時刻
     * @param sunset 日の入り時刻
     */
    private fun drawSystemClockMarks(canvas: Canvas, centerX: Float, centerY: Float, outerRadius: Float, sunrise: Calendar, sunset: Calendar) {
        // 基本のテキストサイズと半径を設定
        textPaint.textSize = 36f * 0.4f // 0.4 デフォルトのテキストサイズ
        val textRadius = outerRadius * 0.9f // 1.05 デフォルトの描画半径

//        textPaint.color = Color.BLACK // システム時刻の文字色を黒に
        // Material Design 3のprimaryカラーを使用（自動的にDark/Lightモードに対応）
        textPaint.color = ContextCompat.getColor(context, R.color.system_clock_text_color)

        val dayDurationMillis = sunset.timeInMillis - sunrise.timeInMillis
        val nextDaySunriseMillis = sunrise.timeInMillis + 24 * 3600 * 1000L
        val nightDurationMillis = nextDaySunriseMillis - sunset.timeInMillis

        if (dayDurationMillis <= 0 || nightDurationMillis <= 0) {
            Log.e(TAG, "日の出・日の入り時間の計算に問題があるため、システム時刻を描画できません。")
            return
        }

        // Calendarオブジェクトのコピーを事前に作成
        val tempCalendar = Calendar.getInstance()
        tempCalendar.time = sunrise.time // 日の出の日付を基準にする

        // 1時間ごとのシステム時刻の数字を配置
        for (i in 0 until 24) { // 0時から23時まで
            textPaint.textSize = 36f * 0.8f // 0.4 各ループでリセット (動的なサイズ調整を削除したため固定)

            // tempCalendar をリセットし、指定の時刻を設定
            tempCalendar.set(Calendar.HOUR_OF_DAY, i)
            tempCalendar.set(Calendar.MINUTE, 0)
            tempCalendar.set(Calendar.SECOND, 0)
            tempCalendar.set(Calendar.MILLISECOND, 0)

            // 設定した時刻が日の出の日付より前なら、日付を1日進める
            if (tempCalendar.timeInMillis < sunrise.timeInMillis) {
                tempCalendar.add(Calendar.DATE, 1)
            }
            // 設定した時刻が翌日の日の出を過ぎるなら、日付を1日戻す
            if (tempCalendar.timeInMillis >= nextDaySunriseMillis) {
                tempCalendar.add(Calendar.DATE, -1)
            }

            // このシステム時刻が、和時計の円周上のどこに位置するかを計算
            val angleRadians = getTimeAngleForJapaneseClock(
                tempCalendar.timeInMillis,
                sunrise,
                sunset,
                dayDurationMillis,
                nightDurationMillis
            )

            val displayHour = if (i == 0) "0" else i.toString() // 0時は「0」と表示

            val textX = centerX + textRadius * sin(angleRadians)
            val textY = centerY - textRadius * cos(angleRadians) + textPaint.textSize / 2.5f // Y軸補正
            canvas.drawText(displayHour, textX, textY, textPaint)

            // デバッグログ追加
            val angleDegrees = Math.toDegrees(angleRadians.toDouble()).toFloat()
            Log.d(TAG, "System Hour ${displayHour}: Angle = ${String.format("%.2f", angleDegrees)} degrees")
        }

        textPaint.textSize = 36f // 元に戻す
        textPaint.color = Color.WHITE // 和時刻の文字色に戻す
    }


    /**
     * 和時計の時針を描画する。
     * 不定時法に従って動く針。
     * 未の時刻のみ特別な色と太さで描画する。
     * @param canvas Canvas
     * @param centerX 中心X座標
     * @param centerY 中心Y座標
     * @param length 針の長さ
     * @param currentTime 現在時刻
     * @param sunrise 日の出時刻
     * @param sunset 日の入り時刻
     */
    private fun drawJapaneseHourHand(canvas: Canvas, centerX: Float, centerY: Float, length: Float, currentTime: Calendar, sunrise: Calendar, sunset: Calendar) {
        val dayDurationMillis = sunset.timeInMillis - sunrise.timeInMillis
        val nextDaySunrise = Calendar.getInstance().apply { timeInMillis = sunrise.timeInMillis + 24 * 3600 * 1000L }
        val nightDurationMillis = nextDaySunrise.timeInMillis - sunset.timeInMillis

        if (dayDurationMillis <= 0 || nightDurationMillis <= 0) {
            Log.e(TAG, "日の出・日の入り時間の計算に問題があるため、和時計の針を描画できません。")
            return
        }

        // 現在の和時刻を取得
        val (japaneseTimeDisplay, _) = calculateJapaneseTimeAndSunInfo(currentTime)

        // 未の時刻かどうかを判定
        val isHitsujiTime = japaneseTimeDisplay.contains("未")

        Log.d(TAG, "DEBUG: japaneseTimeDisplay = '$japaneseTimeDisplay', isHitsujiTime = $isHitsujiTime")

        // 未の時刻なら特別な色と太さのPaintを使用
        val customPaint = Paint().apply {
            if (isHitsujiTime) {
                color = "#97524e".toColorInt()
                strokeWidth = 16f
                Log.d(TAG, "DEBUG: Creating HITSUJI paint - color=#97524e, width=10f")
            } else {
                color = Color.WHITE
                strokeWidth = 8f
                Log.d(TAG, "DEBUG: Creating NORMAL paint - color=WHITE, width=8f")
            }
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        // Paintの設定を確認
        Log.d(TAG, "DEBUG: Paint config BEFORE drawHand - color=${customPaint.color}, strokeWidth=${customPaint.strokeWidth}, style=${customPaint.style}")

        val angleRadians = getTimeAngleForJapaneseClock(currentTime.timeInMillis, sunrise, sunset, dayDurationMillis, nightDurationMillis)
        val angleDegrees = Math.toDegrees(angleRadians.toDouble()).toFloat()

        Log.d(TAG, "Japanese Hour Hand: angle=${String.format("%.2f", angleDegrees)}°, time=${SimpleDateFormat("HH:mm:ss", Locale.US).format(currentTime.time)}, display='$japaneseTimeDisplay', isHitsuji=$isHitsujiTime")

        // 直接ここで描画する（drawHandを経由しない）
        val angleRad = Math.toRadians(angleDegrees.toDouble()).toFloat()
        val endX = centerX + length * sin(angleRad)
        val endY = centerY - length * cos(angleRad)

        Log.d(TAG, "DEBUG: Drawing line directly from ($centerX, $centerY) to ($endX, $endY) with strokeWidth=${customPaint.strokeWidth}, color=${customPaint.color}")

        canvas.drawLine(centerX, centerY, endX, endY, customPaint)
    }

    /**
     * 針を描画するヘルパー関数。
     * @param canvas Canvas
     * @param centerX 中心X座標
     * @param centerY 中心Y座標
     * @param length 針の長さ
     * @param angleDegrees 針の角度 (0度が上、時計回りに増加)
     * @param paint 針のペイント
     */
    private fun drawHand(canvas: Canvas, centerX: Float, centerY: Float, length: Float, angleDegrees: Float, paint: Paint) {
        val angleRadians = Math.toRadians(angleDegrees.toDouble()).toFloat()
        val endX = centerX + length * sin(angleRadians)
        val endY = centerY - length * cos(angleRadians)

        Log.d(TAG, "DEBUG drawHand: Using paint with strokeWidth=${paint.strokeWidth}, color=${paint.color}")

        // 重要: 引数のpaintを使う！（handPaintではない）
        canvas.drawLine(centerX, centerY, endX, endY, paint)
    }
}