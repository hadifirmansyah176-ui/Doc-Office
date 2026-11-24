package com.hadi.docviewer

import android.os.Bundle
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
        val uri = intent.data ?: return finish()
        val ext = name.substringAfterLast('.', "").lowercase()
        thread {
            val html = try {
                contentResolver.openInputStream(uri)!!.use { OfficeParser.toHtml(it, ext) }
            } catch (e: Exception) {
                "<p>Gagal membuka file: ${e.message}</p>"
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }
    }
}
