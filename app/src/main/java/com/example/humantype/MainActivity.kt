package com.example.humantype

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var textInput: EditText
    private lateinit var speedLabel: TextView
    private lateinit var seekSpeed: SeekBar
    private lateinit var setupStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textInput = findViewById(R.id.text_input)
        speedLabel = findViewById(R.id.speed_label)
        seekSpeed = findViewById(R.id.seek_speed)
        setupStatus = findViewById(R.id.setup_status)

        val prefs = getSharedPreferences("humantype", MODE_PRIVATE)
        textInput.setText(prefs.getString("text", ""))
        seekSpeed.progress = prefs.getInt("speed", 120).coerceAtLeast(10) - 10
        updateLabel()

        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) = updateLabel()
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btn_panel).setOnClickListener { showPanel() }
        findViewById<Button>(R.id.btn_hide).setOnClickListener {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        updateSetupStatus()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    /** Одна кнопка: сама ведёт по недостающим шагам, а когда всё готово — показывает панель. */
    private fun showPanel() {
        save()
        when {
            !Settings.canDrawOverlays(this) -> {
                Toast.makeText(this, "Разрешите показ поверх других приложений", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
            !isKeyboardEnabled() -> {
                Toast.makeText(this, "Включите переключатель HumanType", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            else -> {
                ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
                moveTaskToBack(true)
            }
        }
    }

    private fun isKeyboardEnabled(): Boolean =
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .enabledInputMethodList.any { it.packageName == packageName }

    private fun isKeyboardSelected(): Boolean =
        (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "")
            .startsWith("$packageName/")

    private fun updateSetupStatus() {
        fun mark(ok: Boolean) = if (ok) "✓" else "✗"
        setupStatus.text = "${mark(Settings.canDrawOverlays(this))} Показ поверх приложений\n" +
            "${mark(isKeyboardEnabled())} Клавиатура HumanType включена\n" +
            "${mark(isKeyboardSelected())} Клавиатура выбрана (это делается кнопкой ⌨ на панели)"
    }

    private fun updateLabel() {
        speedLabel.text = "Задержка между символами: ~${seekSpeed.progress + 10} мс"
    }

    private fun save() {
        getSharedPreferences("humantype", MODE_PRIVATE).edit()
            .putString("text", textInput.text.toString())
            .putInt("speed", seekSpeed.progress + 10)
            .apply()
    }
}
