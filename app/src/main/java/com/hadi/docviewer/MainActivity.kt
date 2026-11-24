package com.hadi.docviewer

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        box.addView(TextView(this).apply {
            text = "Doc Viewer\nPDF • Word • Excel • PowerPoint"
            textSize = 20f
            gravity = Gravity.CENTER
        })
        box.addView(Button(this).apply {
            text = "Buka File"
            setOnClickListener { openPicker() }
        })
        setContentView(box)

        // Dibuka dari aplikasi lain (file manager, WhatsApp, dll)
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { route(it) }
    }
}
