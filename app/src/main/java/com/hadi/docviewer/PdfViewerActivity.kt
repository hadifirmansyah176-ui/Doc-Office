package com.hadi.docviewer

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.OutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

/** Satu potongan teks (satu baris / kolom) hasil pembacaan PDF. Satuan: poin PDF, titik 0,0 di kiri-atas. */
private data class TextLine(
    val text: String, val x: Float, val endX: Float, val base: Float, val size: Float, val asc: Float
)

private class PageText(val lines: List<TextLine>, val w: Float, val h: Float, val rotation: Int)

class PdfViewerActivity : BaseActivity() {

    private val edits = mutableListOf<PdfEdit>()
    private var editMode = false
    private var zoom = 1f

    private lateinit var src: File
    private lateinit var renderer: PdfRenderer
    private lateinit var pfd: ParcelFileDescriptor
    private lateinit var list: RecyclerView
    private lateinit var hsv: HorizontalScrollView
    private lateinit var zoomLayout: ZoomLayout

    // Pembacaan teks PDF (untuk fitur "ketuk tulisan untuk mengubah")
    private val pdLock = Any()
    private var pdDoc: PDDocument? = null
    private val pageTexts = HashMap<Int, PageText>()

    private fun screenW() = resources.displayMetrics.widthPixels

    private val saver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@registerForActivityResult
        thread {
            val msg = try {
                contentResolver.openOutputStream(uri)!!.use { saveEdits(it) }
                "PDF tersimpan"
            } catch (t: Throwable) {
                "Gagal menyimpan: ${t.message ?: t.javaClass.simpleName}"
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = intent.getStringExtra("name") ?: "PDF"
        val uri = intent.data ?: return finish()
        PDFBoxResourceLoader.init(applicationContext)
        try {
            // PdfRenderer butuh file yang bisa di-seek, jadi salin dulu ke cache
            src = File(cacheDir, "current.pdf")
            contentResolver.openInputStream(uri)!!.use { i -> src.outputStream().use { o -> i.copyTo(o) } }
            pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka PDF (terproteksi password / rusak?)", Toast.LENGTH_LONG).show()
            return finish()
        }

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PdfViewerActivity)
            setBackgroundColor(Color.DKGRAY)
            adapter = PageAdapter()
        }
        // RecyclerView di dalam HorizontalScrollView: geser atas-bawah untuk halaman, kiri-kanan saat diperbesar
        hsv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(list, FrameLayout.LayoutParams(screenW(), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        zoomLayout = ZoomLayout(this).apply {
            setBackgroundColor(Color.DKGRAY)
            addView(hsv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            onZoomEnd = { f, x, y -> commitZoom(f, x, y) }
        }
        setContentView(zoomLayout)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { renderer.close(); pfd.close() } catch (_: Exception) {}
        try { synchronized(pdLock) { pdDoc?.close(); pdDoc = null } } catch (_: Exception) {}
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::list.isInitialized) return
        list.layoutParams.width = (screenW() * zoom).toInt()
        list.requestLayout()
        list.adapter?.notifyDataSetChanged()
    }

    // ---------- Zoom ----------
    /** Terapkan zoom baru: lebar daftar halaman diubah lalu halaman dirender ulang, posisi jari dipertahankan. */
    private fun commitZoom(factor: Float, fx: Float, fy: Float) {
        val newZoom = (zoom * factor).coerceIn(1f, 4f)
        val r = newZoom / zoom
        if (abs(r - 1f) < 0.02f) return

        val lm = list.layoutManager as LinearLayoutManager
        val probeX = (hsv.scrollX + fx).coerceIn(0f, (list.width - 1).toFloat())
        val child = list.findChildViewUnder(probeX, fy)
        val pos = child?.let { list.getChildAdapterPosition(it) }?.takeIf { it >= 0 }
            ?: lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val top = child?.top ?: (lm.findViewByPosition(pos)?.top ?: 0)
        val newTop = (fy - (fy - top) * r).toInt()
        val newScrollX = ((hsv.scrollX + fx) * r - fx).toInt().coerceAtLeast(0)

        zoom = newZoom
        zoomLayout.currentZoom = zoom
        list.layoutParams.width = (screenW() * zoom).toInt()
        list.requestLayout()
        list.adapter?.notifyDataSetChanged()
        lm.scrollToPositionWithOffset(pos, newTop)
        hsv.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                hsv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                hsv.scrollTo(newScrollX, 0)
            }
        })
    }

    // ---------- Menu ----------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Mode Edit Teks").isCheckable = true
        menu.add(0, 2, 1, "Batalkan perubahan terakhir")
        menu.add(0, 3, 2, "Simpan PDF")
        menu.add(0, BaseActivity.ID_SHARE, 3, "Kirim ke...")
        return super.onCreateOptionsMenu(menu)      // + Buka File, Tentang
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                editMode = !editMode
                item.isChecked = editMode
                list.adapter?.notifyDataSetChanged()
                if (editMode) Toast.makeText(this,
                    "Ketuk tulisan yang ingin diubah.\nUntuk area bebas: tekan-tahan lalu seret.",
                    Toast.LENGTH_LONG).show()
            }
            2 -> if (edits.isNotEmpty()) { edits.removeAt(edits.lastIndex); list.adapter?.notifyDataSetChanged() }
            3 -> if (edits.isEmpty()) Toast.makeText(this, "Belum ada perubahan", Toast.LENGTH_SHORT).show()
                 else saver.launch("edit_${title}")
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- Edit: area bebas (tekan-tahan lalu seret) ----------
    private fun askText(page: Int, l: Float, t: Float, r: Float, b: Float) {
        val input = EditText(this).apply { hint = "Teks pengganti" }
        AlertDialog.Builder(this)
            .setTitle("Ganti teks di area terpilih")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val s = input.text.toString()
                if (s.isNotEmpty()) { edits.add(PdfEdit(page, l, t, r, b, s)); list.adapter?.notifyItemChanged(page) }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------- Edit: ketuk tulisan ----------
    private fun sanitize(s: String) = s.map { if (it.code in 32..255) it else '?' }.joinToString("")

    private fun textWidthPt(s: String, size: Float): Float = try {
        PDType1Font.HELVETICA.getStringWidth(sanitize(s)) / 1000f * size
    } catch (_: Exception) { s.length * size * 0.5f }

    /** Baca teks satu halaman (di thread latar). Hasilnya disimpan agar ketukan berikutnya cepat. */
    private fun loadPageText(pageIdx: Int): PageText = synchronized(pdLock) {
        val cached = pageTexts[pageIdx]
        if (cached != null) return@synchronized cached

        PDFBoxResourceLoader.init(applicationContext)
        val doc = pdDoc ?: PDDocument.load(src).also { pdDoc = it }
        val page = doc.getPage(pageIdx)
        val box = page.cropBox

        val words = ArrayList<TextLine>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                if (textPositions.isEmpty() || text.isBlank()) return
                val first = textPositions.first()
                val last = textPositions.last()
                val size = textPositions.maxOf { it.fontSizeInPt }.let { if (it > 0f) it else 10f }
                val asc = maxOf(textPositions.maxOf { it.heightDir }, size * 0.7f)
                words.add(TextLine(text.trim(), first.xDirAdj, last.xDirAdj + last.widthDirAdj, first.yDirAdj, size, asc))
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = pageIdx + 1
        stripper.endPage = pageIdx + 1
        stripper.getText(doc)

        // Gabungkan kata-kata yang sebaris dan berdekatan menjadi satu potongan
        val segs = ArrayList<TextLine>()
        for (w in words) {
            val prev = segs.lastOrNull()
            if (prev != null && abs(prev.base - w.base) < prev.size * 0.4f &&
                w.x >= prev.x && w.x - prev.endX < prev.size * 1.2f) {
                segs[segs.lastIndex] = TextLine(
                    prev.text + " " + w.text, prev.x, maxOf(prev.endX, w.endX),
                    prev.base, maxOf(prev.size, w.size), maxOf(prev.asc, w.asc)
                )
            } else segs.add(w)
        }
        val pt = PageText(segs, box.width, box.height, page.rotation)
        pageTexts[pageIdx] = pt
        pt
    }

    private fun onTapPage(page: Int, fx: Float, fy: Float) {
        // 1) Mengetuk tulisan yang sudah pernah diubah -> ubah lagi
        val idx = edits.indexOfLast { it.page == page && it.fontFrac > 0f && fx in it.l..it.r && fy in it.t..it.b }
        if (idx >= 0) {
            val e = edits[idx]
            val cached = pageTexts[page]
            showLineDialog(page, e.text, e.l, e.t, e.r, e.b, e.fontFrac, e.base, idx, cached?.w ?: 0f, cached?.h ?: 0f)
            return
        }
        // 2) Mengetuk teks asli PDF
        if (!pageTexts.containsKey(page)) Toast.makeText(this, "Membaca teks halaman...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val pt = loadPageText(page)
                if (pt.rotation != 0) {
                    runOnUiThread { Toast.makeText(this,
                        "Halaman terputar belum didukung. Gunakan tekan-tahan lalu seret.", Toast.LENGTH_LONG).show() }
                    return@thread
                }
                val px = fx * pt.w
                val py = fy * pt.h
                val hit = pt.lines
                    .filter { px >= it.x - 4 && px <= it.endX + 4 && py >= it.base - it.asc - 4 && py <= it.base + it.size * 0.35f + 4 }
                    .minByOrNull { abs(py - (it.base - it.asc / 2)) }
                runOnUiThread {
                    if (hit == null) {
                        Toast.makeText(this,
                            "Tidak ada teks di titik ini (PDF hasil scan?). Coba tekan-tahan lalu seret untuk memilih area.",
                            Toast.LENGTH_LONG).show()
                    } else {
                        val l = hit.x / pt.w
                        val t = ((hit.base - hit.asc * 1.1f) / pt.h).coerceAtLeast(0f)
                        val r = (hit.endX + 1f) / pt.w
                        val b = ((hit.base + hit.size * 0.3f) / pt.h).coerceAtMost(1f)
                        showLineDialog(page, hit.text, l, t, r, b, hit.size / pt.h, hit.base / pt.h, -1, pt.w, pt.h)
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this,
                    "Gagal membaca teks: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    /**
     * @param replaceIdx indeks edit yang diganti (-1 = edit baru)
     * pw/ph (poin) dipakai untuk memperlebar kotak penutup bila teks baru lebih panjang.
     */
    private fun showLineDialog(
        page: Int, initial: String, l: Float, t: Float, r: Float, b: Float,
        fontFrac: Float, base: Float, replaceIdx: Int, pw: Float = 0f, ph: Float = 0f
    ) {
        val input = EditText(this).apply { setText(initial); setSelection(initial.length) }
        AlertDialog.Builder(this)
            .setTitle("Ubah tulisan")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val s = input.text.toString()
                // Perluas kotak putih kalau teks baru lebih panjang dari yang lama
                var newR = r
                if (pw > 0f && ph > 0f) newR = maxOf(r, l + textWidthPt(s, fontFrac * ph) / pw + 0.003f)
                val e = PdfEdit(page, l, t, newR, b, s, fontFrac, base)
                if (replaceIdx >= 0 && replaceIdx < edits.size) edits[replaceIdx] = e else edits.add(e)
                list.adapter?.notifyItemChanged(page)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------- Simpan ----------
    /** Tutup tulisan lama dengan kotak putih, lalu tulis teks baru (Helvetica, hanya karakter Latin). */
    private fun saveEdits(out: OutputStream) {
        PDFBoxResourceLoader.init(applicationContext)
        PDDocument.load(src).use { doc ->
            for ((pg, group) in edits.groupBy { it.page }) {
                val page = doc.getPage(pg)
                if (page.rotation != 0) throw IllegalStateException("Halaman ${pg + 1} terputar, belum didukung")
                val cb = page.cropBox            // sama dengan area yang dirender di layar
                val w = cb.width
                val h = cb.height
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (e in group) {
                        val x = cb.lowerLeftX + e.l * w
                        val yBottom = cb.lowerLeftY + (1f - e.b) * h
                        val boxW = (e.r - e.l) * w
                        val boxH = (e.b - e.t) * h
                        cs.setNonStrokingColor(255, 255, 255)
                        cs.addRect(x, yBottom, boxW, boxH)
                        cs.fill()
                        val txt = sanitize(e.text)
                        if (txt.isNotEmpty()) {
                            val size: Float
                            val y: Float
                            if (e.fontFrac > 0f && e.base >= 0f) {
                                size = e.fontFrac * h
                                y = cb.lowerLeftY + (1f - e.base) * h
                            } else {
                                size = boxH * 0.75f
                                y = yBottom + boxH * 0.25f
                            }
                            cs.setNonStrokingColor(0, 0, 0)
                            cs.beginText()
                            cs.setFont(PDType1Font.HELVETICA, size)
                            cs.newLineAtOffset(x, y)
                            cs.showText(txt)
                            cs.endText()
                        }
                    }
                }
            }
            doc.save(out)
        }
    }

    // ---------- Kirim ----------
    /** Kirim PDF ke aplikasi lain. Kalau sudah ada perubahan teks, yang dikirim adalah versi yang sudah diedit. */
    override fun onShare() {
        val base = safeName(intent.getStringExtra("name") ?: "dokumen.pdf")
        val name = if (edits.isEmpty()) base else "edit_" + (if (base.endsWith(".pdf", true)) base else "$base.pdf")
        Toast.makeText(this, "Menyiapkan file...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val f = File(shareDir(), name)
                if (edits.isEmpty()) src.copyTo(f, overwrite = true)
                else f.outputStream().use { saveEdits(it) }
                runOnUiThread { shareFile(f, name) }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "Gagal menyiapkan file: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ---------- Daftar halaman ----------
    inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        inner class VH(val v: PageView) : RecyclerView.ViewHolder(v)

        override fun getItemCount() = renderer.pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = PageView(parent.context)
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = 12 }
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val viewW = (screenW() * zoom).toInt()
            renderer.openPage(position).use { page ->
                val ratio = page.height.toFloat() / page.width
                val viewH = (viewW * ratio).toInt()
                // Batasi ukuran bitmap agar memori aman; di atas batas ini gambar hanya diskalakan
                var rw = minOf(viewW, 2000)
                var bmp: Bitmap
                try {
                    bmp = Bitmap.createBitmap(rw, (rw * ratio).toInt(), Bitmap.Config.ARGB_8888)
                } catch (oom: OutOfMemoryError) {
                    rw = screenW()
                    bmp = Bitmap.createBitmap(rw, (rw * ratio).toInt(), Bitmap.Config.ARGB_8888)
                }
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                h.v.layoutParams.height = viewH
                h.v.setImageBitmap(bmp)
            }
            h.v.editMode = editMode
            h.v.edits = edits.filter { it.page == position }
            h.v.onArea = { l, t, r, b -> askText(position, l, t, r, b) }
            h.v.onTap = { fx, fy -> onTapPage(position, fx, fy) }
        }

        override fun onViewRecycled(h: VH) {
            h.v.setImageDrawable(null)      // lepas bitmap supaya memori cepat bebas
        }
    }
}
