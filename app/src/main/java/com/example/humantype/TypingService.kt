package com.example.humantype

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import kotlin.random.Random

class TypingService : InputMethodService() {

    private val handler = Handler(Looper.getMainLooper())
    private var text = ""
    private var index = 0
    private var speed = 120
    private var running = false
    private lateinit var status: TextView

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        status = view.findViewById(R.id.status)
        view.findViewById<Button>(R.id.btn_start).setOnClickListener { start() }
        view.findViewById<Button>(R.id.btn_stop).setOnClickListener { stop("Остановлено") }
        view.findViewById<Button>(R.id.btn_switch).setOnClickListener {
            stop("Остановлено")
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        return view
    }

    override fun onFinishInput() {
        super.onFinishInput()
        stop("Остановлено")
    }

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            val ic = currentInputConnection
            if (ic == null) {
                stop("Нет поля ввода")
                return
            }
            if (index >= text.length) {
                stop("Готово")
                return
            }
            val len = Character.charCount(text.codePointAt(index))
            val chunk = text.substring(index, index + len)
            ic.commitText(chunk, 1)
            index += len
            status.text = "Печатаю: $index / ${text.length}"
            handler.postDelayed(this, nextDelay(chunk))
        }
    }

    private fun start() {
        if (running) return
        val prefs = getSharedPreferences("humantype", MODE_PRIVATE)
        text = prefs.getString("text", "") ?: ""
        speed = prefs.getInt("speed", 120)
        if (text.isEmpty()) {
            status.text = "Сначала вставьте текст в приложении"
            return
        }
        index = 0
        running = true
        handler.post(step)
    }

    private fun stop(message: String) {
        running = false
        handler.removeCallbacks(step)
        if (::status.isInitialized) status.text = message
    }

    private fun nextDelay(ch: String): Long {
        var d = speed * Random.nextDouble(0.6, 1.4)
        when (ch) {
            " " -> d += speed * 0.5
            ".", ",", "!", "?", "\n" -> d += Random.nextLong(150, 400)
        }
        if (Random.nextInt(40) == 0) d += Random.nextLong(300, 900)
        return d.toLong()
    }
}
