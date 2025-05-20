package lab.rreedd.oyatsu

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import java.util.Calendar
import java.util.regex.Pattern
import kotlin.text.toDouble

private const val TAG_INPUT_ACTIVITY = "LocationInputActivity"
private const val PREFS_NAME = "lab.rreedd.oyatsu.OyatsuWidgetPrefs"
private const val PREF_LATITUDE_PREFIX = "latitude_"
private const val PREF_LONGITUDE_PREFIX = "longitude_"
// デフォルト値はOyatsu.ktから取得するか、同期させる
const val DEFAULT_LATITUDE_STRING = "35.681444600642514" // Tokyo Station
const val DEFAULT_LONGITUDE_STRING = "139.76579265965165" // Tokyo Station

class LocationInputActivity : AppCompatActivity() {

    private lateinit var editTextLatitude: EditText
    private lateinit var editTextLongitude: EditText
    private lateinit var buttonApply: Button
    private lateinit var japaneseClockView: JapaneseClockView // Custom viewのインスタンス

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    companion object {
        const val ACTION_LOCATION_UPDATED = "lab.rreedd.oyatsu.ACTION_LOCATION_UPDATED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_input)

        editTextLatitude = findViewById(R.id.editTextLatitude)
        editTextLongitude = findViewById(R.id.editTextLongitude)
        buttonApply = findViewById(R.id.buttonApply)
        japaneseClockView = findViewById(R.id.japaneseClockView) // JapaneseClockViewを初期化

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        loadSavedCoordinates()
        setupValidation()

        buttonApply.setOnClickListener {
            saveCoordinates()
        }

        handleIntent(intent)
        // ClockViewの初期表示を更新
        updateClockView()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            // appWidgetId を更新する可能性があるため、再設定
            appWidgetId = it.extras?.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
            handleIntent(it)
        }
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                parseCoordinatesFromText(text)
            }
        } else if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            intent.data?.let { uri ->
                if ("geo".equals(uri.scheme, ignoreCase = true)) {
                    val path = uri.schemeSpecificPart
                    // Format: geo:lat,lng?q=query or geo:0,0?q=lat,lng(label)
                    // Simple parsing for "lat,lng"
                    val parts = path.split("?")[0].split(",")
                    if (parts.size >= 2) {
                        try {
                            val lat = parts[0].toDouble()
                            val lon = parts[1].toDouble()
                            editTextLatitude.setText(lat.toString())
                            editTextLongitude.setText(lon.toString())
                            validateAndEnableApplyButton() // Validate after setting
                            Toast.makeText(
                                this,
                                "geo URIから場所を解析しました",
                                Toast.LENGTH_SHORT
                            ).show()
                            // geo URIからの直接保存と更新はここでは行わず、ユーザーがApplyボタンを押すのを待つ。
                            // もし自動で保存・更新したい場合は、saveCoordinates() を呼び出す。
                            // 保存する
                        } catch (e: NumberFormatException) {
                            Log.e(TAG_INPUT_ACTIVITY, "geo URIの解析に失敗: $path", e)
                            Toast.makeText(
                                this,
                                getString(R.string.failed_to_parse_shared_location),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        // Try to parse from query parameter if it's like geo:0,0?q=lat,lng(label)
                        val query = uri.getQueryParameter("q")
                        if (query != null) {
                            parseCoordinatesFromText(query) // Reuse text parsing logic
                        } else {
                            Toast.makeText(
                                this,
                                getString(R.string.failed_to_parse_shared_location),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private val coordinatePattern =
        Pattern.compile("([-+]?\\d{1,2}(\\.\\d+)?),\\s*([-+]?\\d{1,3}(\\.\\d+)?)")

    private fun parseCoordinatesFromText(text: String) {
        val matcher = coordinatePattern.matcher(text)
        if (matcher.find()) {
            try {
                val latStr = matcher.group(1)
                val lonStr = matcher.group(3)
                if (latStr != null && lonStr != null) {
                    val lat = latStr.toDouble()
                    val lon = lonStr.toDouble()

                    // Validate range before setting
                    if (lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0) {
                        editTextLatitude.setText(lat.toString())
                        editTextLongitude.setText(lon.toString())
                        validateAndEnableApplyButton()
                        Toast.makeText(
                            this,
                            "共有テキストから場所を解析しました",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }
                }
            } catch (e: NumberFormatException) {
                Log.e(TAG_INPUT_ACTIVITY, "テキストからの座標解析エラー: $text", e)
            }
        }
        Toast.makeText(this, getString(R.string.failed_to_parse_shared_location), Toast.LENGTH_LONG)
            .show()
    }

    private fun loadSavedCoordinates() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // メイン画面として起動された場合、特定のウィジェットIDはない。
        // 最後に設定された値、または全ウィジェット共通のデフォルト値を読み込む。
        // ここでは、便宜上「最初のウィジェット」または「アプリ全体のデフォルト」の値を読み込む。
        val targetAppWidgetId = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            appWidgetId
        } else {
            getFirstWidgetId().takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID } ?: 0 // 0はデフォルト用キーの一部として使う
        }

        val latKey = if (targetAppWidgetId != 0) PREF_LATITUDE_PREFIX + targetAppWidgetId else "default_latitude"
        val lonKey = if (targetAppWidgetId != 0) PREF_LONGITUDE_PREFIX + targetAppWidgetId else "default_longitude"

        // SharedPreferencesから文字列として読み込む。
        // 1. targetAppWidgetId に紐づく値を試す
        // 2. 存在しなければ "default_latitude" (または "default_longitude") を試す
        // 3. それも存在しなければ、ハードコードされた DEFAULT_LATITUDE_STRING (または DEFAULT_LONGITUDE_STRING) を使う
        val latString = prefs.getString(latKey, prefs.getString("default_latitude", DEFAULT_LATITUDE_STRING))
        val lonString = prefs.getString(lonKey, prefs.getString("default_longitude", DEFAULT_LONGITUDE_STRING))

        // 読み込んだ文字列をDoubleに変換する。変換失敗時はハードコードされたデフォルト値をDoubleに変換して使用。
        val lat = (latString ?: DEFAULT_LATITUDE_STRING).toDoubleOrNull() ?: DEFAULT_LATITUDE_STRING.toDouble()
        val lon = (lonString ?: DEFAULT_LONGITUDE_STRING).toDoubleOrNull() ?: DEFAULT_LONGITUDE_STRING.toDouble()

        editTextLatitude.setText(lat.toString())
        editTextLongitude.setText(lon.toString())
        validateAndEnableApplyButton()
    }

    private fun getFirstWidgetId(): Int {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, Oyatsu::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        return appWidgetIds.firstOrNull() ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }


    private fun setupValidation() {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateAndEnableApplyButton()
            }
        }
        editTextLatitude.addTextChangedListener(textWatcher)
        editTextLongitude.addTextChangedListener(textWatcher)
        validateAndEnableApplyButton() // Initial check
    }

    private fun validateAndEnableApplyButton() {
        val latStr = editTextLatitude.text.toString()
        val lonStr = editTextLongitude.text.toString()
        var latValid = false
        var lonValid = false

        try {
            val lat = latStr.toDoubleOrNull()
            if (lat != null && lat >= -90.0 && lat <= 90.0) {
                latValid = true
                editTextLatitude.error = null
            } else {
                if (latStr.isNotEmpty()) editTextLatitude.error =
                    getString(R.string.invalid_latitude)
            }
        } catch (e: NumberFormatException) {
            Log.e("TAG", "Number format exception occurred", e)
            if (latStr.isNotEmpty()) editTextLatitude.error = getString(R.string.invalid_latitude)
        }

        try {
            val lon = lonStr.toDoubleOrNull()
            if (lon != null && lon >= -180.0 && lon <= 180.0) {
                lonValid = true
                editTextLongitude.error = null
            } else {
                if (lonStr.isNotEmpty()) editTextLongitude.error =
                    getString(R.string.invalid_longitude)
            }
        } catch (e: NumberFormatException) {
            Log.e("TAG", "Number format exception occurred", e)
            if (lonStr.isNotEmpty()) editTextLongitude.error = getString(R.string.invalid_longitude)
        }

        buttonApply.isEnabled = latValid && lonValid
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }

    private fun validateInputs() {
        val latitudeStr = editTextLatitude.text.toString()
        val longitudeStr = editTextLongitude.text.toString()

        val isLatitudeValid = isValidCoordinate(latitudeStr, -90.0, 90.0)
        val isLongitudeValid = isValidCoordinate(longitudeStr, -180.0, 180.0)

        buttonApply.isEnabled = isLatitudeValid && isLongitudeValid
    }

    private fun isValidCoordinate(coordStr: String, min: Double, max: Double): Boolean {
        return try {
            val coord = coordStr.toDouble()
            coord >= min && coord <= max
        } catch (e: NumberFormatException) {
            false
        }
    }

    private fun saveCoordinates() {
        val latStr = editTextLatitude.text.toString()
        val lonStr = editTextLongitude.text.toString()
        val latitude = latStr.toDoubleOrNull()  // StringからDouble?に変換
        val longitude = lonStr.toDoubleOrNull() // StringからDouble?に変換

        if (latitude == null || longitude == null || !buttonApply.isEnabled) {
            Toast.makeText(this, getString(R.string.invalid_coordinates), Toast.LENGTH_SHORT).show()
            return
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                // ウィジェット設定フローから起動された場合
                putString(
                    PREF_LATITUDE_PREFIX + appWidgetId,
                    latitude.toString()
                ) // DoubleをStringとして保存
                putString(
                    PREF_LONGITUDE_PREFIX + appWidgetId,
                    longitude.toString()
                ) // DoubleをStringとして保存
                Log.d(
                    TAG_INPUT_ACTIVITY,
                    "Saved coordinates for widget ID $appWidgetId: $latitude, $longitude"
                )
            } else {
                // アプリから直接起動された場合：全ウィジェットを更新 + デフォルト値を更新
                putString("default_latitude", latitude.toString()) // DoubleをStringとして保存
                putString("default_longitude", longitude.toString()) // DoubleをStringとして保存
                Log.d(TAG_INPUT_ACTIVITY, "Saved as default coordinates: $latitude, $longitude")

                val appWidgetManager = AppWidgetManager.getInstance(this@LocationInputActivity)
                val componentName = ComponentName(this@LocationInputActivity, Oyatsu::class.java)
                val allWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                allWidgetIds.forEach { id ->
                    putString(PREF_LATITUDE_PREFIX + id, latitude.toString()) // DoubleをStringとして保存
                    putString(
                        PREF_LONGITUDE_PREFIX + id,
                        longitude.toString()
                    ) // DoubleをStringとして保存
                    Log.d(
                        TAG_INPUT_ACTIVITY,
                        "Updated coordinates for existing widget ID $id: $latitude, $longitude"
                    )
                }
            }
            apply() // 即時書き込みではなく非同期書き込みを推奨
            updateClockView()
        }

        Toast.makeText(this, getString(R.string.location_saved), Toast.LENGTH_SHORT).show()

        // 全ウィジェットの更新をトリガー
        val idsToUpdate: IntArray = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            intArrayOf(appWidgetId)
        } else {
            val appWidgetManager = AppWidgetManager.getInstance(this@LocationInputActivity)
            val componentName = ComponentName(this@LocationInputActivity, Oyatsu::class.java)
            appWidgetManager.getAppWidgetIds(componentName)
        }

        if (idsToUpdate.isNotEmpty()) {
            val intentToBroadcast = Intent(this, Oyatsu::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, idsToUpdate)
            }
            sendBroadcast(intentToBroadcast)
            Log.d(
                TAG_INPUT_ACTIVITY,
                "Sent update broadcast for widget IDs: ${idsToUpdate.joinToString()}"
            )
        }

        // ウィジェット設定フローの場合、結果をセットして終了
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultValue)
        }
        // アプリのメイン画面として動作している場合、finish() はユーザーが戻るボタンを押した時のみ。
        // 設定後は画面に留まるのが一般的。ウィジェット設定時のみ finish() する。
        if (intent?.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE || appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
        }
    }

    private fun saveLocationAndFinish() {
        val latitudeStr = editTextLatitude.text.toString()
        val longitudeStr = editTextLongitude.text.toString()

        try {
            val latitude = latitudeStr.toDouble()
            val longitude = longitudeStr.toDouble()

            // Save to SharedPreferences for this specific widget ID
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                putString(PREF_LATITUDE_PREFIX + appWidgetId, latitude.toString())
                putString(PREF_LONGITUDE_PREFIX + appWidgetId, longitude.toString())
                apply()
            }

            // Notify the widget provider that the location has been updated
            // BroadCastIntentを使ってOyatsuウィジェットに更新を通知
            val updateIntent = Intent(this, Oyatsu::class.java).apply {
                action = ACTION_LOCATION_UPDATED
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            sendBroadcast(updateIntent)

            Toast.makeText(this, getString(R.string.location_saved), Toast.LENGTH_SHORT).show()

            // Finish the activity for widget configuration flow
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                setResult(RESULT_OK, resultValue)
                finish()
            } else {
                // If launched as a regular activity, just update the clock view
                updateClockView()
            }

        } catch (e: NumberFormatException) {
            Toast.makeText(this, "有効な数値を入力してください", Toast.LENGTH_SHORT).show()
            Log.e(TAG_INPUT_ACTIVITY, "Invalid number format", e)
        }
    }

    private fun updateClockView() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val latitude = editTextLatitude.text.toString().toDoubleOrNull() ?: DEFAULT_LATITUDE_STRING.toDouble()
        val longitude = editTextLongitude.text.toString().toDoubleOrNull() ?: DEFAULT_LONGITUDE_STRING.toDouble()
        val today = Calendar.getInstance()

        // SunriseSunsetUtilsを使って日の出・日の入り時刻を取得
        val sunriseTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(this, appWidgetId, latitude, longitude, today, true, true)
        val sunsetTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(this, appWidgetId, latitude, longitude, today, false, true)

        // Custom Clock Viewを更新
        japaneseClockView.setSunriseSunsetTimes(sunriseTime, sunsetTime)
    }
}