package com.hadi.docviewer

import android.os.Bundle
import android.view.Menu
import android.widget.Toast
import java.io.File
import android.webkit.WebView
import kotlin.concurrent.thread

class OfficeViewerActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("name") ?: "Dokumen"
        title = name
        val web = WebView(this).apply {
            settings.setSupportZoom(true)              // cubit dua jari untuk zoom
            settings.builtInZoomControls = true
            settings.displayZoomControls = false       // sembunyikan tombol +/- bawaan
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
        }
        setContentView(web)
        web.loadDataWithBaseURL(null, "<p style='font-family:sans-serif'>Memuat dokumen...</p>", "text/html", "UTF-8", null)
        val uri = intent.data ?: return finish()
        val ext = name.substringAfterLast('.', "").lowercase()
        thread {
            val html = try {
                contentResolver.openInputStream(uri)!!.use { OfficeParser.toHtml(it, ext) }
            } catch (t: Throwable) {
                "<p style='font-family:sans-serif'>Gagal membuka file:<br>${t.javaClass.simpleName}: ${t.message}</p>"
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, BaseActivity.ID_SHARE, 50, "Kirim ke...")
        return super.onCreateOptionsMenu(menu)      // + Buka File, Tentang
    }

    override fun onShare() {
        val uri = intent.data ?: return
        val name = safeName(intent.getStringExtra("name") ?: "dokumen")
        Toast.makeText(this, "Menyiapkan file...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val f = File(shareDir(), name)
                contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                runOnUiThread { shareFile(f, name) }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "Gagal menyiapkan file: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
