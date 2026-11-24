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
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

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

    private fun screenW() = resources.displayMetrics.widthPixels

    private val saver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@registerForActivityResult
        thread {
            val msg = try {
                saveEdits(uri)
                "PDF tersimpan"
            } catch (e: Exception) {
                "Gagal menyimpan: ${e.message}"
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = intent.getStringExtra("name") ?: "PDF"
        val uri = intent.data ?: return finish()
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
        return super.onCreateOptionsMenu(menu)      // + Buka File, Tentang
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                editMode = !editMode
                item.isChecked = editMode
                list.adapter?.notifyDataSetChanged()
                if (editMode) Toast.makeText(this,
                    "Tekan-tahan lalu seret untuk memilih area teks yang akan diganti", Toast.LENGTH_LONG).show()
            }
            2 -> if (edits.isNotEmpty()) { edits.removeAt(edits.lastIndex); list.adapter?.notifyDataSetChanged() }
            3 -> if (edits.isEmpty()) Toast.makeText(this, "Belum ada perubahan", Toast.LENGTH_SHORT).show()
                 else saver.launch("edit_${title}")
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- Edit ----------
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

    /** Tutup area lama dengan kotak putih, lalu tulis teks baru (Helvetica, hanya karakter Latin). */
    private fun saveEdits(out: android.net.Uri) {
        PDFBoxResourceLoader.init(applicationContext)
        PDDocument.load(src).use { doc ->
            for ((pg, group) in edits.groupBy { it.page }) {
                val page = doc.getPage(pg)
                val mb = page.mediaBox
                val w = mb.width; val h = mb.height
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (e in group) {
                        val x = mb.lowerLeftX + e.l * w
                        val yBottom = mb.lowerLeftY + (1f - e.b) * h
                        val boxW = (e.r - e.l) * w
                        val boxH = (e.b - e.t) * h
                        cs.setNonStrokingColor(255, 255, 255)
                        cs.addRect(x, yBottom, boxW, boxH)
                        cs.fill()
                        val size = boxH * 0.75f
                        cs.setNonStrokingColor(0, 0, 0)
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA, size)
                        cs.newLineAtOffset(x, yBottom + boxH * 0.25f)
                        cs.showText(e.text.map { if (it.code in 32..255) it else '?' }.joinToString(""))
                        cs.endText()
                    }
                }
            }
            contentResolver.openOutputStream(out)!!.use { doc.save(it) }
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
        }

        override fun onViewRecycled(h: VH) {
            h.v.setImageDrawable(null)      // lepas bitmap supaya memori cepat bebas
        }
    }
}
