package com.muhan.socbatteryinfo

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.muhan.socbatteryinfo.util.Prefs

class WelcomeActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Prefs.isWelcomeShown(this)) {
            setContentView(R.layout.activity_splash)
            handler.postDelayed({ goToMain() }, 300)
            return
        }

        setContentView(R.layout.activity_welcome)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            Prefs.setWelcomeShown(this)
            goToMain()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
