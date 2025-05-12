package lab.rreedd.oyatsu

import android.appwidget.AppWidgetManager
import android.content.ComponentName
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
import java.util.regex.Pattern

private const val TAG_INPUT_ACTIVITY = "LocationInputActivity"
private const val PREFS_NAME = "lab.rreedd.oyatsu.OyatsuWidgetPrefs"
private const val PREF_LATITUDE_PREFIX = "latitude_"
private const val PREF_LONGITUDE_PREFIX = "longitude_"
const val DEFAULT_LATITUDE_STRING = "35.6895" // Tokyo Station
const val DEFAULT_LONGITUDE_STRING = "139.6917" // Tokyo Station

class LocationInputActivity : AppCompatActivity() {

    private lateinit var editTextLatitude: EditText
    private lateinit var editTextLongitude: EditText
    private lateinit var buttonApply: Button

    // このActivityがメインになったため、特定のウィジェットIDを意識する必要は薄れるが、
    // 既存のウィジェットがある場合にそれらを更新するロジックは有用なので残す。
    // appWidgetId は、ウィジェット設定フローから起動された場合にのみ有効な値を持つ。
    // 直接アプリとして起動された場合は INVALID_APPWIDGET_ID となる。
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_input)

        editTextLatitude = findViewById(R.id.editTextLatitude)
        editTextLongitude = findViewById(R.id.editTextLongitude)
        buttonApply = findViewById(R.id.buttonApply)

        // ランチャーからの起動や、ウィジェット設定フローからの起動をハンドル
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // If no widget ID, this activity might have been launched for general settings
        // or by a share intent not tied to a specific widget.
        // For simplicity, we'll assume if it's not INVALID_APPWIDGET_ID, it's for THAT widget.
        // If opened by share, and appWidgetId is INVALID, we might prompt user or apply to a default/first widget.
        // For now, if shared, it will apply to the 'active' widget or potentially just store as a general default.

        loadSavedCoordinates()
        setupValidation()

        buttonApply.setOnClickListener {
            saveCoordinates()
        }

        handleIntent(intent)
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
                            Toast.makeText(this, "geo URIから場所を解析しました", Toast.LENGTH_SHORT).show()
                            // geo URIからの直接保存と更新はここでは行わず、ユーザーがApplyボタンを押すのを待つ。
                            // もし自動で保存・更新したい場合は、saveCoordinates() を呼び出す。
                            // 保存する
                        } catch (e: NumberFormatException) {
                            Log.e(TAG_INPUT_ACTIVITY, "geo URIの解析に失敗: $path", e)
                            Toast.makeText(this, getString(R.string.failed_to_parse_shared_location), Toast.LENGTH_LONG).show()
                        }
                    } else {
                         // Try to parse from query parameter if it's like geo:0,0?q=lat,lng(label)
                        val query = uri.getQueryParameter("q")
                        if (query != null) {
                            parseCoordinatesFromText(query) // Reuse text parsing logic
                        } else {
                            Toast.makeText(this, getString(R.string.failed_to_parse_shared_location), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }


    private val coordinatePattern = Pattern.compile("([-+]?\\d{1,2}(\\.\\d+)?),\\s*([-+]?\\d{1,3}(\\.\\d+)?)")

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
                        Toast.makeText(this, "共有テキストから場所を解析しました", Toast.LENGTH_SHORT).show()
                        return
                    }
                }
            } catch (e: NumberFormatException) {
                Log.e(TAG_INPUT_ACTIVITY, "テキストからの座標解析エラー: $text", e)
            }
        }
        Toast.makeText(this, getString(R.string.failed_to_parse_shared_location), Toast.LENGTH_LONG).show()
    }


    private fun loadSavedCoordinates() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // メイン画面として起動された場合、特定のウィジェットIDはない。
        // 最後に設定された値、または全ウィジェット共通のデフォルト値を読み込む。
        // ここでは、便宜上「最初のウィジェット」または「アプリ全体のデフォルト」の値を読み込む。
        val targetAppWidgetId = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            appWidgetId
        } else {
            getFirstWidgetId().takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID } ?: 0 // 0はデフォルト用キーの一部として使うなど工夫が必要
        }

        val latKey = if (targetAppWidgetId != 0) PREF_LATITUDE_PREFIX + targetAppWidgetId else "default_latitude"
        val lonKey = if (targetAppWidgetId != 0) PREF_LONGITUDE_PREFIX + targetAppWidgetId else "default_longitude"


        // ウィジェットID がない（アプリ直接起動）の場合でも、何らかのデフォルト値を表示したい
        // 最後に保存された値を表示するか、固定のデフォルト値を表示する
        val lat = prefs.getFloat(latKey, prefs.getFloat("default_latitude", DEFAULT_LATITUDE_STRING.toFloat()))
        val lon = prefs.getFloat(lonKey, prefs.getFloat("default_longitude", DEFAULT_LONGITUDE_STRING.toFloat()))

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
                if (latStr.isNotEmpty()) editTextLatitude.error = getString(R.string.invalid_latitude)
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
                if (lonStr.isNotEmpty()) editTextLongitude.error = getString(R.string.invalid_longitude)
            }
        } catch (e: NumberFormatException) {
            Log.e("TAG", "Number format exception occurred", e)
            if (lonStr.isNotEmpty()) editTextLongitude.error = getString(R.string.invalid_longitude)
        }

        buttonApply.isEnabled = latValid && lonValid
    }


    private fun saveCoordinates() {
        val latStr = editTextLatitude.text.toString()
        val lonStr = editTextLongitude.text.toString()
        val latitude = latStr.toDoubleOrNull()
        val longitude = lonStr.toDoubleOrNull()
        // if (latitude == null || longitude == null) {
        //     Toast.makeText(this, "Invalid input.", Toast.LENGTH_SHORT).show()
        //     return
        // }

        // if (!(latitude >= -90.0 && latitude <= 90.0)) {
        //     Toast.makeText(this, getString(R.string.invalid_latitude), Toast.LENGTH_LONG).show()
        //     return
        // }
        // if (!(longitude >= -180.0 && longitude <= 180.0)) {
        //     Toast.makeText(this, getString(R.string.invalid_longitude), Toast.LENGTH_LONG).show()
        //     return
        // }
        if (latitude == null || longitude == null || !buttonApply.isEnabled) { // buttonApply.isEnabled もチェック
            Toast.makeText(this, "入力が無効です。", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            // アプリとして起動された場合、特定のウィジェットIDがない。
            // この場合、既存の全ウィジェットの設定を更新するか、
            // または「デフォルト設定」として保存し、新規ウィジェット作成時に使用する。
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                // ウィジェット設定フローから起動された場合
                putFloat(PREF_LATITUDE_PREFIX + appWidgetId, latitude.toFloat())
                putFloat(PREF_LONGITUDE_PREFIX + appWidgetId, longitude.toFloat())
                Log.d(TAG_INPUT_ACTIVITY, "Saved coordinates for widget ID $appWidgetId: $latitude, $longitude")
            } else {
                // アプリから直接起動された場合：全ウィジェットを更新 + デフォルト値を更新
                putFloat("default_latitude", latitude.toFloat())
                putFloat("default_longitude", longitude.toFloat())
                Log.d(TAG_INPUT_ACTIVITY, "Saved as default coordinates: $latitude, $longitude")

                val appWidgetManager = AppWidgetManager.getInstance(this@LocationInputActivity)
                val componentName = ComponentName(this@LocationInputActivity, Oyatsu::class.java)
                val allWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                allWidgetIds.forEach { id ->
                    putFloat(PREF_LATITUDE_PREFIX + id, latitude.toFloat())
                    putFloat(PREF_LONGITUDE_PREFIX + id, longitude.toFloat())
                    Log.d(TAG_INPUT_ACTIVITY, "Updated coordinates for existing widget ID $id: $latitude, $longitude")
                }
            }
            apply() // 即時書き込み
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
            val intentToBroadcast = Intent(this, Oyatsu::class.java).apply { // intentToBroadcast という新しい名前でIntentを作成
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, idsToUpdate)
            }
            // applyブロックが完了した後で、設定済みの intentToBroadcast を使ってブロードキャストする
            sendBroadcast(intentToBroadcast)
            Log.d(TAG_INPUT_ACTIVITY, "Sent update broadcast for widget IDs: ${idsToUpdate.joinToString()}")
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
}
