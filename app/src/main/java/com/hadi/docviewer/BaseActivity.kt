package com.hadi.docviewer

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

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
        ID_SHARE -> { onShare(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Dioverride oleh layar yang punya file untuk dikirim (PDF / Office). */
    open fun onShare() {}

    /** Folder cache tempat salinan file yang akan dikirim (dibersihkan setiap kali). */
    fun shareDir(): File {
        val dir = File(cacheDir, "shared")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        return dir
    }

    fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "dokumen" }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "*/*"
    }

    /** Buka daftar "Kirim ke" (WhatsApp, Telegram, Email, dll) untuk file ini. Panggil dari thread utama. */
    fun shareFile(file: File, displayName: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(displayName)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, displayName)
                clipData = ClipData.newRawUri(displayName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Kirim ke").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mengirim: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun openPicker() {
        // Semua file bisa dipilih; jenisnya dicek dari ekstensi di route()
        picker.launch(arrayOf("*/*"))
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
        const val ID_SHARE = 102
    }
}
