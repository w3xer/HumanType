package com.example.humantype

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private companion object {
        const val MIN_DELAY_MS = 1
        const val MAX_DELAY_MS = 60
        const val COLLAPSED_LINES = 5
        const val EXPANDED_MIN_LINES = 8
        const val LONG_TEXT_CHARS = 300
        const val PASTE_JUMP_CHARS = 200
        const val MAX_IMPORT_CHARS = 500_000
        const val CLEAR_CONFIRM_MS = 3000L
    }

    private lateinit var textInput: EditText
    private lateinit var btnMore: TextView
    private lateinit var btnClear: Button
    private lateinit var speedLabel: TextView
    private lateinit var seekSpeed: SeekBar

    /** Пользователь нажал «Показать ещё» — длинный текст показан целиком. */
    private var expanded = false

    /** Первое нажатие «Удалить текст» сделано, ждём подтверждения вторым нажатием. */
    private var clearArmed = false
    private val handler = Handler(Looper.getMainLooper())
    private val disarmClear = Runnable { disarmClear() }

    private val pickTxt = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importText(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textInput = findViewById(R.id.text_input)
        btnMore = findViewById(R.id.btn_more)
        btnClear = findViewById(R.id.btn_clear)
        speedLabel = findViewById(R.id.speed_label)
        seekSpeed = findViewById(R.id.seek_speed)

        val prefs = getSharedPreferences("humantype", MODE_PRIVATE)
        textInput.setText(prefs.getString("text", ""))
        seekSpeed.max = MAX_DELAY_MS - MIN_DELAY_MS
        seekSpeed.progress = prefs.getInt("speed", 20).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS) - MIN_DELAY_MS
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

        findViewById<Button>(R.id.btn_import).setOnClickListener { pickTxt.launch("text/*") }
        btnClear.setOnClickListener { onClearClicked() }

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
    }

    override fun onPause() {
        super.onPause()
        disarmClear()
        save()
    }

    // ---------- импорт и удаление текста ----------

    /** Читает .txt (UTF-8) и подставляет его в поле ввода вместо текущего текста. */
    private fun importText(uri: Uri) {
        val result = runCatching {
            val sb = StringBuilder()
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buf = CharArray(8192)
                while (sb.length < MAX_IMPORT_CHARS) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
            } ?: error("no stream")
            sb
        }
        val sb = result.getOrNull()
        if (sb == null) {
            Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_LONG).show()
            return
        }
        var text = sb.toString().removePrefix("﻿")
        if (text.length > MAX_IMPORT_CHARS) {
            text = text.substring(0, MAX_IMPORT_CHARS)
            Toast.makeText(this, "Файл слишком большой, загружено $MAX_IMPORT_CHARS символов", Toast.LENGTH_LONG).show()
        } else if (text.isEmpty()) {
            Toast.makeText(this, "Файл пустой", Toast.LENGTH_SHORT).show()
            return
        }
        disarmClear()
        textInput.setText(text)
        expanded = false
        applyCollapse()
        save()
    }

    /** Удаление в два нажатия: первое просит подтверждение, второе стирает текст. */
    private fun onClearClicked() {
        if (textInput.text.isNullOrEmpty()) return
        if (!clearArmed) {
            clearArmed = true
            btnClear.text = "Нажмите ещё раз"
            handler.postDelayed(disarmClear, CLEAR_CONFIRM_MS)
        } else {
            disarmClear()
            textInput.setText("")
            expanded = false
            applyCollapse()
            save()
        }
    }

    private fun disarmClear() {
        handler.removeCallbacks(disarmClear)
        clearArmed = false
        btnClear.text = "Удалить текст"
    }

    // ---------- поле ввода ----------

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
            }
        }
    }

    private fun isKeyboardEnabled(): Boolean =
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .enabledInputMethodList.any { it.packageName == packageName }

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
