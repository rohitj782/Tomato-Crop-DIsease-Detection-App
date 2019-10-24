package com.rohitrj.tomatodiseasedetection.GettingStarted

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.rohitrj.tomatodiseasedetection.Home.MainActivity
import com.rohitrj.tomatodiseasedetection.R
import kotlinx.android.synthetic.main.activity_getting_started.*

class GettingStarted : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_getting_started)

        buttonNext.setOnClickListener {
            startActivity(Intent(this,MainActivity::class.java))
            finish()
        }
    }
}
