package com.hadi.docviewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

class OfficeViewerActivity : BaseActivity() {

    companion object {
        private val FONTS = arrayOf(
            "Calibri", "Arial", "Times New Roman", "Courier New", "Verdana",
            "Tahoma", "Georgia", "Cambria", "Comic Sans MS", "Segoe UI"
        )
        private val SIZES = arrayOf("8", "9", "10", "11", "12", "14", "16", "18", "20", "24", "28", "32", "36", "48", "72")
        private val COLORS = listOf(
            Pair("Hitam", "000000"), Pair("Merah", "FF0000"), Pair("Oranye", "FF8C00"), Pair("Kuning", "FFC000"),
            Pair("Hijau", "00A651"), Pair("Biru", "0070C0"), Pair("Biru tua", "002060"), Pair("Ungu", "7030A0"),
            Pair("Abu-abu", "7F7F7F"), Pair("Cokelat", "8B4513"), Pair("Merah muda", "FF69B4"), Pair("Putih", "FFFFFF")
        )
    }

    private class Prepared(val bytes: ByteArray, val ext: String, val cx: Long, val cy: Long)

    private lateinit var web: WebView
    private lateinit var ribbon: LinearLayout
    private lateinit var ribbonRow: LinearLayout
    private lateinit var tabHome: Button
    private lateinit var tabInsert: Button

    @Volatile private var model: OfficeEditor.Model? = null
    private var origBytes: ByteArray? = null
    private var currentHtml = ""
    private val undoStack = ArrayList<ByteArray>()
    private var dirty = false
    private var busy = false
    private var editMode = false
    private val selected = LinkedHashSet<String>()
    private var savedScroll = 0
    private lateinit var fileName: String

    // hasil pemilihan lokasi simpan (file / folder)
    private var pendingUri: ((Uri) -> Unit)? = null
    private var pendingCancel: (() -> Unit)? = null
    private val uriLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val act = pendingUri
        val cancel = pendingCancel
        pendingUri = null
        pendingCancel = null
        val uri = res.data?.data
        if (res.resultCode == RESULT_OK && uri != null && act != null) act(uri) else cancel?.invoke()
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) insertPicture(uri)
    }

    /** Dipanggil dari JavaScript saat bagian teks diketuk di mode edit. */
    inner class Bridge {
        @JavascriptInterface
        fun edit(id: String) {
            runOnUiThread { onUnitTapped(id) }
        }
    }

    // ================================================================ siklus hidup
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileName = intent.getStringExtra("name") ?: "Dokumen"
        title = fileName

        web = WebView(this).apply {
            settings.javaScriptEnabled = true          // hanya untuk HTML buatan aplikasi sendiri
            settings.setSupportZoom(true)              // cubit dua jari untuk zoom
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            addJavascriptInterface(Bridge(), "AndroidEdit")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { onPageReady() }
            }
        }
        ribbon = buildRibbon()
        ribbon.visibility = View.GONE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(ribbon, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        web.loadDataWithBaseURL(null, "<p style='font-family:sans-serif'>Memuat dokumen...</p>", "text/html", "UTF-8", null)

        val uri = intent.data ?: return finish()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        thread {
            var html = ""
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                origBytes = bytes
                val isZip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
                val m = if (isZip) OfficeEditor.open(bytes, ext.removeSuffix("x")) else null
                if (m != null) {
                    model = m
                    html = OfficeParser.wrap(m.html + OfficeEditor.EDIT_ASSETS)
                } else {
                    html = OfficeParser.toHtml(ByteArrayInputStream(bytes), ext)   // format lama: baca saja
                }
            } catch (t: Throwable) {
                html = "<p style='font-family:sans-serif'>Gagal membuka file:<br>${t.javaClass.simpleName}: ${t.message}</p>"
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                currentHtml = html
                web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }
    }

    private fun toast(s: String, long: Boolean = false) =
        Toast.makeText(this, s, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun toastUi(s: String) = runOnUiThread { toast(s, true) }

    // ================================================================ menu atas (titik tiga)
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Mode Edit Teks").isCheckable = true
        menu.add(0, 2, 1, "Batalkan perubahan terakhir")
        menu.add(0, 3, 2, "Save As...")
        menu.add(0, BaseActivity.ID_SHARE, 50, "Kirim ke...")
        return super.onCreateOptionsMenu(menu)      // + Buka File, Tentang
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> toggleEditMode(item)
            2 -> undo()
            3 -> saveAsDialog()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun toggleEditMode(item: MenuItem) {
        val m = model
        if (m == null) {
            item.isChecked = false
            toast("Edit hanya untuk .docx / .xlsx / .pptx. File lama (.doc/.xls/.ppt) simpan dulu ke format baru.", true)
            return
        }
        editMode = !editMode
        item.isChecked = editMode
        if (!editMode) {
            selected.clear()
            web.evaluateJavascript("selectAll(false)", null)
        }
        web.evaluateJavascript("setEdit($editMode)", null)
        ribbon.visibility = if (editMode && m.kind == "doc") View.VISIBLE else View.GONE
        if (editMode) {
            if (m.kind == "doc") {
                showTab(0)
                toast("Ketuk paragraf untuk memilih, lalu pakai menu Home / Insert di bawah", true)
            } else toast("Ketuk teks yang ingin diubah", true)
        }
    }

    // ================================================================ ribbon Home / Insert
    private fun buildRibbon(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFEFEFEF.toInt())
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabHome = rb("Home") { showTab(0) }
        tabInsert = rb("Insert") { showTab(1) }
        tabs.addView(tabHome)
        tabs.addView(tabInsert)
        ribbonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(ribbonRow)
        }
        box.addView(tabs)
        box.addView(scroll)
        return box
    }

    private fun rb(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        setPadding(28, 8, 28, 8)
        setOnClickListener { onClick() }
    }

    private fun showTab(i: Int) {
        tabHome.alpha = if (i == 0) 1f else 0.5f
        tabInsert.alpha = if (i == 1) 1f else 0.5f
        ribbonRow.removeAllViews()
        if (i == 0) {
            ribbonRow.addView(rb("Font") { chooseFont() })
            ribbonRow.addView(rb("Font Size") { chooseSize() })
            ribbonRow.addView(rb("Align Left") { applyAlign("left") })
            ribbonRow.addView(rb("Center") { applyAlign("center") })
            ribbonRow.addView(rb("Align Right") { applyAlign("right") })
            ribbonRow.addView(rb("Justify") { applyAlign("both") })
            ribbonRow.addView(rb("Font Color") { chooseColor() })
            ribbonRow.addView(rb("Edit Text") { editSelectedText() })
            ribbonRow.addView(rb("Select All") { selectAllParas() })
        } else {
            ribbonRow.addView(rb("Picture") { imagePicker.launch(arrayOf("image/*")) })
            ribbonRow.addView(rb("Table") { insertTableDialog() })
        }
    }

    // ================================================================ pilih paragraf
    private fun onUnitTapped(id: String) {
        val m = model ?: return
        if (!m.units.containsKey(id)) return
        if (m.kind == "doc") {
            if (!selected.add(id)) selected.remove(id)
            web.evaluateJavascript("setSel(${JSONObject.quote(id)}, ${selected.contains(id)})", null)
        } else {
            editTextDialog(id)
        }
    }

    private fun selectAllParas() {
        val m = model ?: return
        val all = m.units.keys.filter { it.startsWith("d") }
        if (selected.size == all.size) {
            selected.clear()
            web.evaluateJavascript("selectAll(false)", null)
        } else {
            selected.clear()
            selected.addAll(all)
            web.evaluateJavascript("selectAll(true)", null)
        }
    }

    private fun requireSelection(): List<String>? {
        if (selected.isEmpty()) {
            toast("Pilih paragraf dulu: ketuk teks yang ingin diubah")
            return null
        }
        return selected.toList()
    }

    private fun afterIdForInsert(): String? =
        selected.maxByOrNull { it.removePrefix("d").toIntOrNull() ?: -1 }

    // ================================================================ Home
    private fun chooseFont() {
        val ids = requireSelection() ?: return
        AlertDialog.Builder(this).setTitle("Font")
            .setItems(FONTS) { _, i -> applyFmt(ids, OfficeEditor.ParaFmt(font = FONTS[i])) }
            .show()
    }

    private fun chooseSize() {
        val ids = requireSelection() ?: return
        AlertDialog.Builder(this).setTitle("Font Size (pt)")
            .setItems(SIZES) { _, i -> applyFmt(ids, OfficeEditor.ParaFmt(sizePt = SIZES[i].toFloat())) }
            .show()
    }

    private fun applyAlign(a: String) {
        val ids = requireSelection() ?: return
        applyFmt(ids, OfficeEditor.ParaFmt(align = a))
    }

    private fun chooseColor() {
        val ids = requireSelection() ?: return
        val grid = GridLayout(this).apply {
            columnCount = 4
            setPadding(24, 24, 24, 24)
        }
        lateinit var dlg: AlertDialog
        for ((_, hex) in COLORS) {
            val v = View(this).apply {
                setBackgroundColor(Color.parseColor("#$hex"))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 120
                    height = 120
                    setMargins(12, 12, 12, 12)
                }
                setOnClickListener {
                    dlg.dismiss()
                    applyFmt(ids, OfficeEditor.ParaFmt(colorHex = hex))
                }
            }
            grid.addView(v)
        }
        dlg = AlertDialog.Builder(this).setTitle("Font Color").setView(grid)
            .setNegativeButton("Batal", null).create()
        dlg.show()
    }

    private fun applyFmt(ids: List<String>, fmt: OfficeEditor.ParaFmt) {
        mutate(true) { m -> OfficeEditor.formatParagraphs(m, ids, fmt) }
    }

    private fun editSelectedText() {
        if (selected.size != 1) {
            toast("Pilih satu paragraf saja untuk mengubah teks")
            return
        }
        editTextDialog(selected.first())
    }

    private fun editTextDialog(id: String) {
        val u = model?.units?.get(id) ?: return
        if (!u.editable) {
            toast(u.reason)
            return
        }
        val input = EditText(this).apply {
            setText(u.text)
            setSelection(u.text.length)
            maxLines = 8
        }
        AlertDialog.Builder(this)
            .setTitle(if (u.ref != null) "Ubah sel ${u.ref}" else "Ubah teks")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val t = input.text.toString()
                if (t != u.text) mutate(true) { m -> OfficeEditor.applyText(m, id, t) }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ================================================================ Insert
    private fun insertTableDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val rows = EditText(this).apply {
            hint = "Rows (baris, 1-50)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("3")
        }
        val cols = EditText(this).apply {
            hint = "Columns (kolom, 1-10)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("3")
        }
        box.addView(rows)
        box.addView(cols)
        AlertDialog.Builder(this).setTitle("Insert Table").setView(box)
            .setPositiveButton("Insert") { _, _ ->
                val r = rows.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 3
                val c = cols.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 3
                val after = afterIdForInsert()
                mutate(false) { m -> OfficeEditor.insertTable(m, after, r, c) }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun insertPicture(uri: Uri) {
        val after = afterIdForInsert()
        mutate(false) { m ->
            val img = prepareImage(uri)
            OfficeEditor.insertPicture(m, after, img.bytes, img.ext, img.cx, img.cy)
        }
    }

    /** Baca gambar; ubah ke PNG/JPEG kalau formatnya lain, kecilkan kalau terlalu besar; hitung ukuran di dokumen. */
    private fun prepareImage(uri: Uri): Prepared {
        val raw = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
        var w = o.outWidth
        var h = o.outHeight
        if (w <= 0 || h <= 0) throw IOException("Bukan file gambar yang valid")
        val mime = o.outMimeType ?: contentResolver.getType(uri) ?: ""
        val ok = mime == "image/png" || mime == "image/jpeg"
        var bytes = raw
        var ext = if (mime == "image/png") "png" else "jpeg"
        if (!ok || maxOf(w, h) > 2400) {
            var sample = 1
            while (maxOf(w, h) / sample > 2400) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IOException("Gambar tidak bisa dibaca")
            val bos = ByteArrayOutputStream()
            if (mime == "image/png" || (!ok && bmp.hasAlpha())) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                ext = "png"
            } else {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                ext = "jpeg"
            }
            bytes = bos.toByteArray()
            w = bmp.width
            h = bmp.height
            bmp.recycle()
        }
        val maxCx = 5_029_200L                       // 5,5 inci
        val cx = minOf(w * 9525L, maxCx)             // 1 piksel = 9525 EMU (96 dpi)
        val cy = maxOf(1L, cx * h / w)
        return Prepared(bytes, ext, cx, cy)
    }

    // ================================================================ terapkan perubahan
    /** Jalankan operasi pada dokumen (thread latar), lalu muat ulang tampilan dengan hasilnya. */
    private fun mutate(keepSelection: Boolean, op: (OfficeEditor.Model) -> ByteArray) {
        val m = model ?: return
        if (busy) {
            toast("Sedang memproses, tunggu sebentar")
            return
        }
        busy = true
        savedScroll = web.scrollY
        val before = m.zipBytes
        thread {
            try {
                val nb = op(m)
                val nm = OfficeEditor.open(nb, m.kind) ?: throw IOException("Hasil perubahan tidak bisa dibaca")
                runOnUiThread {
                    busy = false
                    undoStack.add(before)
                    if (undoStack.size > 10) undoStack.removeAt(0)
                    dirty = true
                    model = nm
                    if (!keepSelection) selected.clear()
                    showModel()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    busy = false
                    toast("Gagal: ${t.message ?: t.javaClass.simpleName}", true)
                }
            }
        }
    }

    private fun showModel() {
        val m = model ?: return
        currentHtml = OfficeParser.wrap(m.html + OfficeEditor.EDIT_ASSETS)
        web.loadDataWithBaseURL(null, currentHtml, "text/html", "UTF-8", null)
    }

    private fun onPageReady() {
        web.evaluateJavascript("setEdit($editMode)", null)
        if (selected.isNotEmpty()) {
            web.evaluateJavascript("selectIds(${JSONArray(selected.toList())})", null)
        }
        if (savedScroll > 0) {
            val y = savedScroll
            savedScroll = 0
            web.postDelayed({ web.scrollTo(0, y) }, 200)
        }
    }

    private fun undo() {
        if (busy) return
        val kind = model?.kind
        if (kind == null || undoStack.isEmpty()) {
            toast("Tidak ada yang dibatalkan")
            return
        }
        val prev = undoStack.removeAt(undoStack.lastIndex)
        busy = true
        savedScroll = web.scrollY
        thread {
            val nm = OfficeEditor.open(prev, kind)
            runOnUiThread {
                busy = false
                if (nm == null) {
                    toast("Gagal membatalkan")
                } else {
                    model = nm
                    selected.clear()
                    dirty = undoStack.isNotEmpty()
                    showModel()
                }
            }
        }
    }

    // ================================================================ Save As
    private fun baseName() = safeName(fileName).substringBeforeLast('.')

    private fun nativeExt(m: OfficeEditor.Model) = when (m.kind) {
        "xls" -> "xlsx"
        "ppt" -> "pptx"
        else -> "docx"
    }

    private fun createIntent(mime: String, name: String) = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mime
        putExtra(Intent.EXTRA_TITLE, name)
    }

    private fun askUri(intent: Intent, onCancel: () -> Unit = {}, action: (Uri) -> Unit) {
        pendingUri = action
        pendingCancel = onCancel
        uriLauncher.launch(intent)
    }

    private fun progress(msg: String): AlertDialog =
        AlertDialog.Builder(this).setMessage(msg).setCancelable(false).create().also { it.show() }

    private fun saveAsDialog() {
        val m = model
        val labels = ArrayList<String>()
        val codes = ArrayList<String>()
        if (m != null) {
            labels.add(when (m.kind) {
                "xls" -> "Excel (.xlsx)"
                "ppt" -> "PowerPoint (.pptx)"
                else -> "Word (.docx)"
            })
            codes.add("native")
        }
        labels.addAll(listOf("PDF (.pdf)", "JPEG (.jpeg)", "JPG (.jpg)", "PNG (.png)"))
        codes.addAll(listOf("pdf", "jpeg", "jpg", "png"))
        AlertDialog.Builder(this).setTitle("Save As")
            .setItems(labels.toTypedArray()) { _, i ->
                if (codes[i] == "native") saveNative() else startExport(codes[i])
            }
            .show()
    }

    private fun saveNative() {
        val m = model ?: return
        val name = "${baseName()}.${nativeExt(m)}"
        val bytes = m.zipBytes
        askUri(createIntent(mimeFor(name), name)) { uri ->
            thread {
                try {
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                    toastUi("File tersimpan")
                } catch (t: Throwable) {
                    toastUi("Gagal menyimpan: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    /** PDF / JPEG / JPG / PNG: dokumen digambar per halaman A4 lalu disimpan. */
    private fun startExport(fmt: String) {
        if (currentHtml.isEmpty()) {
            toast("Dokumen belum siap")
            return
        }
        val dlg = progress("Menyiapkan halaman...")
        val ex = DocExporter(this, currentHtml)
        ex.prepare({ plan ->
            dlg.dismiss()
            chooseExportTarget(ex, plan, fmt)
        }, { err ->
            dlg.dismiss()
            ex.dispose()
            toast("Gagal menyiapkan: $err", true)
        })
    }

    private fun chooseExportTarget(ex: DocExporter, plan: DocExporter.Plan, fmt: String) {
        val mime = when (fmt) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            else -> "image/jpeg"
        }
        if (fmt == "pdf" || plan.pages == 1) {
            askUri(createIntent(mime, "${baseName()}.$fmt"), { ex.dispose() }) { uri ->
                runExport(ex, plan, fmt, mime, uri, null)
            }
        } else {
            toast("Dokumen ${plan.pages} halaman. Pilih folder; tiap halaman jadi satu gambar.", true)
            askUri(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), { ex.dispose() }) { tree ->
                runExport(ex, plan, fmt, mime, null, tree)
            }
        }
    }

    private fun runExport(ex: DocExporter, plan: DocExporter.Plan, fmt: String, mime: String, fileUri: Uri?, treeUri: Uri?) {
        val dlg = progress("Membuat file...")
        val pdf = if (fmt == "pdf") PdfDocument() else null
        val base = baseName()
        ex.render(plan, { i, bmp ->
            if (pdf != null) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
                val sc = 595f / bmp.width
                page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, 595f, bmp.height * sc), null)
                pdf.finishPage(page)
            } else {
                val cf = if (fmt == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val os = if (treeUri != null) {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                    val doc = DocumentsContract.createDocument(contentResolver, parent, mime, "${base}_${i + 1}.$fmt")
                        ?: throw IOException("Gagal membuat file di folder")
                    contentResolver.openOutputStream(doc)!!
                } else contentResolver.openOutputStream(fileUri!!)!!
                os.use { bmp.compress(cf, 92, it) }
            }
        }, { err ->
            if (err != null) {
                dlg.dismiss(); ex.dispose(); pdf?.close()
                toast("Gagal membuat file: $err", true)
            } else if (pdf != null) {
                thread {
                    val msg = try {
                        contentResolver.openOutputStream(fileUri!!)!!.use { pdf.writeTo(it) }
                        "PDF tersimpan"
                    } catch (t: Throwable) {
                        "Gagal menyimpan PDF: ${t.message ?: t.javaClass.simpleName}"
                    }
                    pdf.close()
                    runOnUiThread { dlg.dismiss(); ex.dispose(); toast(msg, true) }
                }
            } else {
                dlg.dismiss(); ex.dispose()
                toast(if (treeUri != null) "Gambar tersimpan di folder yang dipilih" else "Gambar tersimpan", true)
            }
        })
    }

    // ================================================================ Kirim ke
    /** Kirim ke aplikasi lain. Kalau sudah ada perubahan, yang dikirim versi yang sudah diedit. */
    override fun onShare() {
        val uri = intent.data ?: return
        val m = model
        val edited = m != null && dirty
        val name = if (edited) "${baseName()}_edit.${nativeExt(m!!)}" else safeName(fileName)
        toast("Menyiapkan file...")
        thread {
            try {
                val f = File(shareDir(), name)
                if (edited) f.writeBytes(m!!.zipBytes)
                else contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                runOnUiThread { shareFile(f, name) }
            } catch (t: Throwable) {
                toastUi("Gagal menyiapkan file: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }
}
