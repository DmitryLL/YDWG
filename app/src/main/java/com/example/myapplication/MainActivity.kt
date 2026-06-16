package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.service.NmeaUiState
import com.example.myapplication.ui.MainViewModel
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var btnSettings: ImageButton
    private lateinit var cardSettings: MaterialCardView
    private lateinit var scrollContent: View
    private lateinit var etHost: TextInputEditText
    private lateinit var etPort: TextInputEditText
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvHeading: TextView
    private lateinit var tvWindSpeed: TextView
    private lateinit var tvWindDir: TextView
    private lateinit var tvWindDirLabel: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvDepth: TextView
    private lateinit var tvWaterTemp: TextView
    private lateinit var tvLastUpdate: TextView
    private lateinit var tvPacketCount: TextView
    private lateinit var tvRawSentences: TextView
    private lateinit var switchTheme: SwitchMaterial

    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val prefs by lazy { getSharedPreferences("ydwg_settings", MODE_PRIVATE) }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // Применяем тему ДО super.onCreate() — без мерцания
        applyThemeFromPrefs()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        btnSettings    = findViewById(R.id.btnSettings)
        cardSettings   = findViewById(R.id.cardSettings)
        scrollContent  = findViewById(R.id.scrollContent)
        etHost         = findViewById(R.id.etHost)
        etPort         = findViewById(R.id.etPort)
        btnConnect     = findViewById(R.id.btnConnect)
        tvStatus       = findViewById(R.id.tvStatus)
        tvHeading      = findViewById(R.id.tvHeading)
        tvWindSpeed    = findViewById(R.id.tvWindSpeed)
        tvWindDir      = findViewById(R.id.tvWindDir)
        tvWindDirLabel = findViewById(R.id.tvWindDirLabel)
        tvSpeed        = findViewById(R.id.tvSpeed)
        tvDepth        = findViewById(R.id.tvDepth)
        tvWaterTemp    = findViewById(R.id.tvWaterTemp)
        tvLastUpdate   = findViewById(R.id.tvLastUpdate)
        tvPacketCount  = findViewById(R.id.tvPacketCount)
        tvRawSentences = findViewById(R.id.tvRawSentences)
        switchTheme    = findViewById(R.id.switchTheme)

        // Восстанавливаем состояние переключателя темы
        switchTheme.isChecked = prefs.getBoolean("dark_mode", false)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        viewModel.bindService()
        requestNotificationPermissionIfNeeded()

        // Иконка ⚙ — переключить между настройками и датчиками
        btnSettings.setOnClickListener {
            setSettingsOpen(cardSettings.visibility == View.GONE)
        }

        // Подключить / Отключить
        btnConnect.setOnClickListener {
            if (viewModel.uiState.value.isConnected) {
                viewModel.disconnect()
            } else {
                val host = etHost.text?.toString()?.trim()?.ifEmpty { "192.168.4.1" } ?: "192.168.4.1"
                val port = etPort.text?.toString()?.toIntOrNull() ?: 1457
                viewModel.connect(host, port)
                setSettingsOpen(false) // закрыть настройки, показать датчики
            }
        }

        // Переключатель тёмной темы
        switchTheme.setOnCheckedChangeListener { _, isDark ->
            prefs.edit().putBoolean("dark_mode", isDark).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (isDark) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        lifecycleScope.launch {
            viewModel.uiState.collect { updateUi(it) }
        }
    }

    private fun applyThemeFromPrefs() {
        val isDark = getSharedPreferences("ydwg_settings", MODE_PRIVATE)
            .getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    /**
     * Открыть настройки (датчики скрыты, иконка красная) либо
     * закрыть настройки (видны датчики, иконка синяя).
     */
    private fun setSettingsOpen(open: Boolean) {
        cardSettings.visibility = if (open) View.VISIBLE else View.GONE
        scrollContent.visibility = if (open) View.GONE else View.VISIBLE

        val tint = if (open) {
            ContextCompat.getColor(this, android.R.color.holo_red_dark)
        } else {
            // Вернуть цвет темы (colorPrimary)
            val tv = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
            tv.data
        }
        ImageViewCompat.setImageTintList(btnSettings, ColorStateList.valueOf(tint))
    }

    private fun updateUi(state: NmeaUiState) {
        if (state.isConnected) {
            tvStatus.text = "●  Подключено"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            btnConnect.text = getString(R.string.btn_disconnect)
        } else {
            tvStatus.text = "○  Отключено"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            btnConnect.text = getString(R.string.btn_connect)
        }

        tvHeading.text      = state.headingTrue?.let { "%.1f°".format(it) } ?: "---"
        tvWindSpeed.text    = state.windSpeedKnots?.let { "%.1f".format(it) } ?: "---"
        tvWindDir.text      = state.windDirectionDeg?.let { "%.0f°".format(it) } ?: "---"
        // Если курс известен — направление приведено к северу, иначе оно относительно носа лодки
        tvWindDirLabel.text = if (state.headingTrue != null) "направление (от N)" else "направление (от носа)"
        tvSpeed.text        = state.speedKnots?.let { "%.1f".format(it) } ?: "---"
        tvDepth.text        = state.depthMeters?.let { "%.1f".format(it) } ?: "---"
        tvWaterTemp.text    = state.waterTempC?.let { "%.1f".format(it) } ?: "---"
        tvLastUpdate.text   = if (state.lastUpdateMs > 0) "Обновлено ${sdf.format(Date(state.lastUpdateMs))}" else ""
        tvPacketCount.text  = "${state.rawPacketCount} пакетов"
        tvRawSentences.text = state.lastRawSentences.joinToString("\n").ifEmpty { "Ожидание..." }

        state.errorMessage?.let {
            tvStatus.text = "✕  $it"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.unbindService()
    }
}
