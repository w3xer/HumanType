package com.example.humantype

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Плавающая панель поверх всех приложений: ▶/■, смена клавиатуры, настройки, закрыть, перетаскивание. */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.humantype.STOP_PANEL"
    }

    private lateinit var wm: WindowManager
    private var panel: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var playBtn: TextView
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())

    /** Невидимое поле ввода: на время показа окна выбора клавиатуры панель «владеет» редактором. */
    private var hiddenInput: EditText? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (panel == null) {
            TypingService.instance?.cancelRestore()
            rememberPreviousKeyboard()
            showPanel()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hiddenInput = null
        TypingService.listener = null
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        // Панель закрыта (✕ или «Убрать панель»): печать прекращается,
        // а выбранная клавиатура возвращается на прежнюю.
        TypingService.instance?.onPanelClosed()
        super.onDestroy()
    }

    // ---------- панель ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cell(label: String, sizeSp: Float = 22f) = TextView(this).apply {
        text = label
        textSize = sizeSp
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
    }

    private fun showPanel() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("humantype", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6101015"))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val drag = cell("✥")
        playBtn = cell("▶", 26f).apply { setTextColor(Color.parseColor("#4C8DFF")) }
        statusView = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#BBBBBB"))
            text = "готов"
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(24))
        }
        val keyboard = cell("⌨")
        val settings = cell("⚙")
        val close = cell("✕")

        // Порядок сверху вниз: ▶, статус, ⌨, ⚙, ✕ (виден только в свёрнутом виде), ✥ (внизу)
        listOf(playBtn, statusView, keyboard, settings, close, drag).forEach { root.addView(it) }

        // Скрытое поле для showPicker(): 1×1 пиксель, не видно и не перехватывает касания.
        hiddenInput = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 1)
            background = null
            alpha = 0f
            isFocusable = true
            isFocusableInTouchMode = true
            showSoftInputOnFocus = false
        }.also { root.addView(it) }

        // В свёрнутом виде остаются только ✕ и ✥. Печать при этом продолжается:
        // она живёт в TypingService и от панели не зависит. Останавливается она только
        // когда панель закрыта совсем (см. onDestroy).
        val hideWhenCollapsed = listOf(playBtn, statusView, keyboard, settings)
        // высота, которую даёт скрытие: ▶ + статус + ⌨ + ⚙ минус ✕
        val collapseShift = dp(52 + 24 + 52 + 52 - 52)
        // Панель всегда открывается полной, как бы её ни закрывали в прошлый раз.
        var collapsed = false

        fun applyCollapsed() {
            hideWhenCollapsed.forEach { it.visibility = if (collapsed) View.GONE else View.VISIBLE }
            close.visibility = if (collapsed) View.VISIBLE else View.GONE
        }
        applyCollapsed()

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // NOT_FOCUSABLE: нажатие на панель не уводит фокус из поля ввода
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("panel_x", dp(8))
            y = prefs.getInt("panel_y", dp(200))
        }

        // ✥: перетащить; короткое нажатие без движения — свернуть/развернуть панель
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        drag.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // ✥ закреплена внизу панели, поэтому сдвигаем окно так, чтобы она не прыгала
                        collapsed = !collapsed
                        params.y = if (collapsed) params.y + collapseShift
                        else (params.y - collapseShift).coerceAtLeast(0)
                        applyCollapsed()
                        wm.updateViewLayout(root, params)
                    }
                    // Позиция сохраняется как для полной панели, чтобы свёрнутый сдвиг
                    // не накапливался при следующем открытии.
                    prefs.edit()
                        .putInt("panel_x", params.x)
                        .putInt("panel_y", if (collapsed) params.y - collapseShift else params.y)
                        .apply()
                    true
                }
                else -> false
            }
        }

        playBtn.setOnClickListener { onPlay(restart = false) }
        playBtn.setOnLongClickListener { onPlay(restart = true); true }
        keyboard.setOnClickListener { showPicker() }
        settings.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        close.setOnClickListener { stopSelf() }

        TypingService.listener = { running, message ->
            playBtn.text = if (running) "■" else "▶"
            playBtn.setTextColor(Color.parseColor(if (running) "#FF5C5C" else "#4C8DFF"))
            statusView.text = message
        }

        wm.addView(root, params)
        panel = root
    }

    private fun onPlay(restart: Boolean) {
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val engine = TypingService.instance
        if (!current.startsWith("$packageName/") || engine == null) {
            statusView.text = "выберите HumanType"
            showPicker()
            return
        }
        if (restart) engine.restart() else engine.toggle()
    }

    /**
     * Запоминаем клавиатуру, которая была выбрана до HumanType, чтобы вернуть её,
     * когда панель закроется.
     */
    private fun rememberPreviousKeyboard() {
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        if (current.isNotEmpty() && !current.startsWith("$packageName/")) {
            getSharedPreferences("humantype", MODE_PRIVATE).edit().putString("prev_ime", current).apply()
        }
    }

    /**
     * Система показывает окно выбора клавиатуры, только если вызов пришёл от приложения,
     * у которого сейчас есть активное поле ввода. Пока открыто наше приложение, оно есть;
     * вне приложения — нет, и вызов молча игнорировался.
     *
     * Поэтому на короткое время окно панели становится фокусным и получает скрытое поле ввода,
     * затем открывается окно выбора, и панель снова перестаёт брать фокус на себя.
     */
    private fun showPicker() {
        rememberPreviousKeyboard()
        val view = panel
        val input = hiddenInput
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (view == null || input == null) {
            runCatching { imm.showInputMethodPicker() }
            return
        }

        handler.removeCallbacksAndMessages(null)
        runCatching {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            wm.updateViewLayout(view, params)
            input.requestFocus()
        }
        // даём системе время передать фокус окну панели и принять поле ввода
        handler.postDelayed({
            runCatching { imm.showInputMethodPicker() }
            handler.postDelayed({ restoreNotFocusable() }, 400)
        }, 250)
    }

    private fun restoreNotFocusable() {
        val view = panel ?: return
        runCatching {
            hiddenInput?.clearFocus()
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            wm.updateViewLayout(view, params)
        }
    }
}
