package com.example.humantype

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager

/**
 * Невидимая активность, единственная задача которой — открыть системное окно выбора клавиатуры.
 *
 * Системе можно показывать это окно только приложению, у которого есть фокус окна.
 * Из плавающей панели (она NOT_FOCUSABLE) вызов иногда молча игнорировался —
 * поэтому окно выбора открывается отсюда, когда фокус гарантированно есть.
 */
class PickerActivity : Activity() {

    private var shown = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || shown) return
        shown = true
        runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 150)
    }
}
