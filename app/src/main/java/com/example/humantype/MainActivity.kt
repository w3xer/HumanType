package com.example.humantype

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var textInput: EditText
    private lateinit var speedLabel: TextView
    private lateinit var seekSpeed: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textInput = findViewById(R.id.text_input)
        speedLabel = findViewById(R.id.speed_label)
        seekSpeed = findViewById(R.id.seek_speed)

        val prefs = getSharedPreferences("humantype", MODE_PRIVATE)
        textInput.setText(prefs.getString("text", ""))
        seekSpeed.progress = prefs.getInt("speed", 120) - 30
        updateLabel()

        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) = updateLabel()
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            save()
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_pick).setOnClickListener {
            save()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun updateLabel() {
        speedLabel.text = "Задержка между символами: ~${seekSpeed.progress + 30} мс"
    }

    private fun save() {
        getSharedPreferences("humantype", MODE_PRIVATE).edit()
            .putString("text", textInput.text.toString())
            .putInt("speed", seekSpeed.progress + 30)
            .apply()
    }
}
