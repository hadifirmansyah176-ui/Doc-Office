package com.hadi.docviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) route(uri)
    }

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
            setOnClickListener {
                picker.launch(arrayOf(
                    "application/pdf",
                    "application/msword",
                    "application/vnd.ms-excel",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/octet-stream"
                ))
            }
        })
        setContentView(box)

        // Dibuka dari aplikasi lain (file manager, WhatsApp, dll)
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { route(it) }
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: ""
        try {
            contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) name = it.getString(i)
                }
            }
        } catch (_: Exception) {}
        return name
    }

    private fun route(uri: Uri) {
        val name = displayName(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val target = when (ext) {
            "pdf" -> PdfViewerActivity::class.java
            "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> OfficeViewerActivity::class.java
            else -> {
                Toast.makeText(this, "Format tidak didukung: $name", Toast.LENGTH_LONG).show()
                return
            }
        }
        startActivity(Intent(this, target).apply {
            data = uri
            putExtra("name", name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
