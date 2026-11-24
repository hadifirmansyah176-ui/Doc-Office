package com.hadi.docviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.concurrent.thread

class OfficeViewerActivity : BaseActivity() {

    private lateinit var web: WebView
    @Volatile private var model: OfficeEditor.Model? = null
    private val edits = LinkedHashMap<String, String>()                 // id unit -> teks baru
    private val history = ArrayList<Pair<String, String?>>()            // untuk "Batalkan"
    private var editMode = false

    private val saver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode == RESULT_OK && uri != null) writeTo(uri)
    }

    /** Dipanggil dari JavaScript saat bagian teks diketuk di mode edit. */
    inner class Bridge {
        @JavascriptInterface
        fun edit(id: String) {
            runOnUiThread { onUnitTapped(id) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("name") ?: "Dokumen"
        title = name
        web = WebView(this).apply {
            settings.javaScriptEnabled = true          // hanya untuk HTML buatan aplikasi sendiri
            settings.setSupportZoom(true)              // cubit dua jari untuk zoom
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            addJavascriptInterface(Bridge(), "AndroidEdit")
        }
        setContentView(web)
        web.loadDataWithBaseURL(null, "<p style='font-family:sans-serif'>Memuat dokumen...</p>", "text/html", "UTF-8", null)
        val uri = intent.data ?: return finish()
        val ext = name.substringAfterLast('.', "").lowercase()
        thread {
            val html = try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val isZip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
                val m = if (isZip) OfficeEditor.open(bytes, ext.removeSuffix("x")) else null
                if (m != null) {
                    model = m
                    OfficeParser.wrap(m.html + OfficeEditor.EDIT_ASSETS)
                } else {
                    OfficeParser.toHtml(ByteArrayInputStream(bytes), ext)   // format lama: baca saja
                }
            } catch (t: Throwable) {
                "<p style='font-family:sans-serif'>Gagal membuka file:<br>${t.javaClass.simpleName}: ${t.message}</p>"
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }
    }

    // ---------- Menu ----------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Mode Edit Teks").isCheckable = true
        menu.add(0, 2, 1, "Batalkan perubahan terakhir")
        menu.add(0, 3, 2, "Simpan file")
        menu.add(0, BaseActivity.ID_SHARE, 50, "Kirim ke...")
        return super.onCreateOptionsMenu(menu)      // + Buka File, Tentang
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                if (model == null) {
                    item.isChecked = false
                    Toast.makeText(this,
                        "Edit hanya untuk .docx / .xlsx / .pptx. File lama (.doc/.xls/.ppt) simpan dulu ke format baru.",
                        Toast.LENGTH_LONG).show()
                } else {
                    editMode = !editMode
                    item.isChecked = editMode
                    web.evaluateJavascript("setEdit($editMode)", null)
                    if (editMode) Toast.makeText(this, "Ketuk teks yang ingin diubah", Toast.LENGTH_LONG).show()
                }
            }
            2 -> undo()
            3 -> startSave()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- Edit ----------
    private fun onUnitTapped(id: String) {
        val u = model?.units?.get(id) ?: return
        if (!u.editable) {
            Toast.makeText(this, u.reason, Toast.LENGTH_SHORT).show()
            return
        }
        val current = edits[id] ?: u.text
        val input = EditText(this).apply {
            setText(current)
            setSelection(current.length)
            maxLines = 8
        }
        AlertDialog.Builder(this)
            .setTitle(if (u.ref != null) "Ubah sel ${u.ref}" else "Ubah teks")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                applyEdit(id, input.text.toString().replace("\r", "").replace("\n", " "))
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun applyEdit(id: String, text: String) {
        val u = model?.units?.get(id) ?: return
        val prev = edits[id]
        if (text == (prev ?: u.text)) return
        history.add(Pair(id, prev))
        if (text == u.text) edits.remove(id) else edits[id] = text
        showText(id, text, text != u.text)
    }

    private fun showText(id: String, text: String, edited: Boolean) {
        web.evaluateJavascript("setText(${JSONObject.quote(id)}, ${JSONObject.quote(text)}, $edited)", null)
    }

    private fun undo() {
        if (history.isEmpty()) {
            Toast.makeText(this, "Tidak ada yang dibatalkan", Toast.LENGTH_SHORT).show()
            return
        }
        val (id, prev) = history.removeAt(history.lastIndex)
        val u = model?.units?.get(id) ?: return
        if (prev == null) edits.remove(id) else edits[id] = prev
        showText(id, prev ?: u.text, prev != null)
    }

    // ---------- Simpan & kirim ----------
    private fun outName(): String {
        val base = safeName(intent.getStringExtra("name") ?: "dokumen").substringBeforeLast('.')
        val ext = when (model?.kind) { "xls" -> "xlsx"; "ppt" -> "pptx"; else -> "docx" }
        return "edit_$base.$ext"
    }

    private fun startSave() {
        if (model == null) {
            Toast.makeText(this, "Edit hanya untuk .docx / .xlsx / .pptx", Toast.LENGTH_SHORT).show()
            return
        }
        if (edits.isEmpty()) {
            Toast.makeText(this, "Belum ada perubahan", Toast.LENGTH_SHORT).show()
            return
        }
        val name = outName()
        saver.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeFor(name)
            putExtra(Intent.EXTRA_TITLE, name)
        })
    }

    private fun writeTo(uri: Uri) {
        val m = model ?: return
        val snapshot = LinkedHashMap(edits)
        thread {
            val msg = try {
                contentResolver.openOutputStream(uri)!!.use { OfficeEditor.save(m, snapshot, it) }
                "File tersimpan"
            } catch (t: Throwable) {
                "Gagal menyimpan: ${t.message ?: t.javaClass.simpleName}"
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
    }

    /** Kirim ke aplikasi lain. Kalau sudah ada perubahan, yang dikirim versi yang sudah diedit. */
    override fun onShare() {
        val uri = intent.data ?: return
        val m = model
        val snapshot = LinkedHashMap(edits)
        val name = if (m != null && snapshot.isNotEmpty()) outName() else safeName(intent.getStringExtra("name") ?: "dokumen")
        Toast.makeText(this, "Menyiapkan file...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val f = File(shareDir(), name)
                if (m != null && snapshot.isNotEmpty()) {
                    f.outputStream().use { OfficeEditor.save(m, snapshot, it) }
                } else {
                    contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                }
                runOnUiThread { shareFile(f, name) }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "Gagal menyiapkan file: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
