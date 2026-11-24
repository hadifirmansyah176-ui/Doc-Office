package com.hadi.docviewer

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Induk semua layar: menyediakan menu "Buka File" dan "Tentang". */
open class BaseActivity : AppCompatActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) route(uri)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, ID_OPEN, 100, "Buka File")
        menu.add(0, ID_ABOUT, 101, "Tentang")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        ID_OPEN -> { openPicker(); true }
        ID_ABOUT -> { showAbout(); true }
        else -> super.onOptionsItemSelected(item)
    }

    fun openPicker() {
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

    fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Tentang")
            .setMessage(
                "Aplikasi ini hanya untuk hiburan pribadi tidak dijual belikan.\n\n" +
                "Versi saat ini : V1.0\n" +
                "By.Anonim 11/2026"
            )
            .setPositiveButton("OK", null)
            .show()
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

    fun route(uri: Uri) {
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

    companion object {
        const val ID_OPEN = 100
        const val ID_ABOUT = 101
    }
}
