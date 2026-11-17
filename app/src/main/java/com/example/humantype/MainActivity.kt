package com.example.humantype

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private companion object {
        const val MIN_DELAY_MS = 1
        const val COLLAPSED_LINES = 5
        const val EXPANDED_MIN_LINES = 8
        const val LONG_TEXT_CHARS = 300
        const val PASTE_JUMP_CHARS = 200
    }

    private lateinit var textInput: EditText
    private lateinit var btnMore: TextView
    private lateinit var speedLabel: TextView
    private lateinit var seekSpeed: SeekBar
    private lateinit var setupStatus: TextView

    /** Пользователь нажал «Показать ещё» — длинный текст показан целиком. */
    private var expanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textInput = findViewById(R.id.text_input)
        btnMore = findViewById(R.id.btn_more)
        speedLabel = findViewById(R.id.speed_label)
        seekSpeed = findViewById(R.id.seek_speed)
        setupStatus = findViewById(R.id.setup_status)

        val prefs = getSharedPreferences("humantype", MODE_PRIVATE)
        textInput.setText(prefs.getString("text", ""))
        seekSpeed.progress = prefs.getInt("speed", 120).coerceAtLeast(MIN_DELAY_MS) - MIN_DELAY_MS
        updateLabel()
        applyCollapse()

        // Вставили большой кусок — сразу сворачиваем, чтобы не листать до кнопок.
        // Пока текст набирают руками, ничего не меняется.
        textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (count - before > PASTE_JUMP_CHARS) {
                    expanded = false
                    applyCollapse()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnMore.setOnClickListener {
            expanded = !expanded
            applyCollapse()
        }

        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) = updateLabel()
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btn_panel).setOnClickListener { showPanel() }
        findViewById<Button>(R.id.btn_hide).setOnClickListener {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        }
    }

    override fun onResume() {
        super.onResume()
        // При возвращении в приложение длинный текст снова свёрнут.
        expanded = false
        applyCollapse()
        updateSetupStatus()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun isLong(s: CharSequence): Boolean =
        s.length > LONG_TEXT_CHARS || s.count { it == '\n' } >= COLLAPSED_LINES

    /** Длинный текст прячется за «Показать ещё», короткий показывается как обычно. */
    private fun applyCollapse() {
        if (!isLong(textInput.text)) {
            textInput.minLines = EXPANDED_MIN_LINES
            textInput.maxLines = Int.MAX_VALUE
            btnMore.visibility = View.GONE
            return
        }
        btnMore.visibility = View.VISIBLE
        if (expanded) {
            textInput.minLines = EXPANDED_MIN_LINES
            textInput.maxLines = Int.MAX_VALUE
            btnMore.text = "Свернуть"
        } else {
            textInput.maxLines = COLLAPSED_LINES
            textInput.minLines = COLLAPSED_LINES
            textInput.scrollTo(0, 0)
            btnMore.text = "Показать ещё"
        }
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
                startService(Intent(this, OverlayService::class.java))
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
        speedLabel.text = "Задержка между символами: ~${seekSpeed.progress + MIN_DELAY_MS} мс"
    }

    private fun save() {
        getSharedPreferences("humantype", MODE_PRIVATE).edit()
            .putString("text", textInput.text.toString())
            .putInt("speed", seekSpeed.progress + MIN_DELAY_MS)
            .apply()
    }
}
