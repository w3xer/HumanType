package com.example.humantype

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import kotlin.random.Random

/**
 * "Движок" печати. Это клавиатура без собственного вида: ничего не показывает на экране,
 * а только держит соединение с полем ввода и вставляет туда символы.
 * Управляется плавающей панелью (OverlayService).
 */
class TypingService : InputMethodService() {

    companion object {
        @Volatile
        var instance: TypingService? = null

        /** (идёт ли печать, текст статуса) */
        var listener: ((Boolean, String) -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var text = ""
    private var index = 0
    private var speed = 120
    private var running = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        stopTyping("")
        instance = null
        super.onDestroy()
    }

    // Никакой клавиатуры на экране — только плавающая панель.
    override fun onEvaluateInputViewShown(): Boolean = false

    override fun onFinishInput() {
        super.onFinishInput()
        if (running) stopTyping("Поле закрыто, пауза")
    }

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            val ic = currentInputConnection
            if (ic == null) {
                stopTyping("Нажмите на поле")
                return
            }
            if (index >= text.length) {
                index = 0
                stopTyping("Готово")
                return
            }
            val len = Character.charCount(text.codePointAt(index))
            val chunk = text.substring(index, index + len)
            if (chunk == "\n") {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            } else {
                ic.commitText(chunk, 1)
            }
            index += len
            publish("${index * 100 / text.length}%")
            handler.postDelayed(this, nextDelay(chunk))
        }
    }

    /** ▶ / ■ : старт (с места остановки) или пауза. */
    fun toggle() {
        if (running) stopTyping("Пауза ${progress()}") else begin(restart = false)
    }

    /** Печать заново с самого начала. */
    fun restart() {
        stopTyping("")
        begin(restart = true)
    }

    /**
     * Панель закрыта: печать прекращается, а если сейчас выбрана клавиатура HumanType —
     * возвращаем ту, что была выбрана до неё.
     */
    fun onPanelClosed() {
        stopTyping("")
        restoring = true
        restoreKeyboard(0)
    }

    /** Панель открыли снова — незавершённое переключение на прежнюю клавиатуру больше не нужно. */
    fun cancelRestore() {
        restoring = false
        handler.removeCallbacks(restoreStep)
    }

    private var restoring = false
    private var restoreAttempt = 0
    private val restoreStep = Runnable { restoreKeyboard(restoreAttempt + 1) }

    private fun isOurKeyboardSelected(): Boolean =
        (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "")
            .startsWith("$packageName/")

    /**
     * Система не даёт обычному приложению менять клавиатуру напрямую, а клавиатура может
     * переключить себя только на другую. Одного вызова бывает мало (на части телефонов он
     * молча игнорируется), поэтому пробуем по очереди несколько способов и после каждой
     * попытки проверяем, что HumanType действительно больше не выбрана.
     */
    private fun restoreKeyboard(attempt: Int) {
        restoreAttempt = attempt
        if (!restoring) return
        if (!isOurKeyboardSelected()) {
            restoring = false
            return
        }
        if (attempt > 3) {
            restoring = false
            Toast.makeText(this, "Выберите обычную клавиатуру вручную", Toast.LENGTH_LONG).show()
            return
        }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val saved = getSharedPreferences("humantype", MODE_PRIVATE).getString("prev_ime", null)
        // запомненная клавиатура должна всё ещё быть включена
        val previous = saved?.takeIf { id -> imm.enabledInputMethodList.any { it.id == id } }
        runCatching {
            when {
                attempt <= 1 && previous != null && Build.VERSION.SDK_INT >= 28 -> switchInputMethod(previous)
                attempt <= 1 && previous != null -> {
                    @Suppress("DEPRECATION")
                    imm.setInputMethod(window?.window?.attributes?.token, previous)
                }
                attempt <= 2 -> {
                    @Suppress("DEPRECATION")
                    switchToPreviousInputMethod()
                }
                else -> {
                    @Suppress("DEPRECATION")
                    switchToNextInputMethod(false)
                }
            }
        }
        handler.postDelayed(restoreStep, 400)
    }

    private fun begin(restart: Boolean) {
        if (running) return
        val prefs = getSharedPreferences("humantype", MODE_PRIVATE)
        val newText = (prefs.getString("text", "") ?: "").replace("\r\n", "\n")
        speed = prefs.getInt("speed", 20).coerceIn(1, 60)
        if (newText.isEmpty()) {
            publish("Нет текста")
            return
        }
        if (restart || newText != text || index >= newText.length) index = 0
        text = newText
        if (currentInputConnection == null) {
            publish("Нажмите на поле")
            return
        }
        running = true
        handler.post(step)
    }

    private fun stopTyping(message: String) {
        running = false
        handler.removeCallbacks(step)
        if (message.isNotEmpty()) publish(message)
    }

    private fun progress(): String =
        if (text.isEmpty()) "" else "${index * 100 / text.length}%"

    private fun publish(message: String) {
        listener?.invoke(running, message)
    }

    private fun nextDelay(ch: String): Long {
        var d = speed * Random.nextDouble(0.6, 1.4)
        // «Человеческие» паузы на знаках препинания и случайные задумчивости уменьшаются вместе
        // с задержкой: на максимальных 60 мс они полные, на малых значениях (вплоть до 1 мс)
        // не мешают быстрой печати.
        val pauseScale = (speed / 60.0).coerceAtMost(1.0)
        when (ch) {
            " " -> d += speed * 0.5
            ".", ",", "!", "?", "\n" -> d += Random.nextLong(150, 400) * pauseScale
        }
        if (Random.nextInt(40) == 0) d += Random.nextLong(300, 900) * pauseScale
        return Math.round(d).coerceAtLeast(1L)
    }
}
