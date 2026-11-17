package com.example.humantype

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/** Плавающая панель поверх всех приложений: ▶/■, смена клавиатуры, настройки, закрыть, перетаскивание. */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.humantype.STOP_PANEL"
        private const val CHANNEL_ID = "humantype_panel"
    }

    private lateinit var wm: WindowManager
    private var panel: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var playBtn: TextView
    private lateinit var statusView: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (panel == null) showPanel()
        return START_STICKY
    }

    override fun onDestroy() {
        TypingService.listener = null
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        super.onDestroy()
    }

    // ---------- уведомление (нужно, чтобы система не убивала панель) ----------

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Панель HumanType", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val close = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("HumanType")
            .setContentText("Панель печати активна")
            .setContentIntent(open)
            .addAction(0, "Закрыть панель", close)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
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

        listOf(drag, playBtn, statusView, keyboard, settings, close).forEach { root.addView(it) }

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

        // перетаскивание за ✥
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        drag.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("panel_x", params.x).putInt("panel_y", params.y).apply()
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

    private fun showPicker() {
        runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }
}
