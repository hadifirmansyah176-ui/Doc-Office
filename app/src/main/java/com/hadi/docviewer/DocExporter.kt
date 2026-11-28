package com.hadi.docviewer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Mengubah halaman HTML dokumen menjadi potongan-potongan gambar seukuran halaman A4
 * (dipakai untuk Save As PDF / JPEG / JPG / PNG).
 * Memakai WebView tersendiri yang tidak terlihat supaya tampilan utama tidak terganggu.
 * Batas halaman dipilih di antara paragraf / baris tabel agar tulisan tidak terpotong di tengah.
 */
class DocExporter(private val act: Activity, private val html: String) {

    class Plan(val cuts: List<Double>, val wCss: Double, val hCss: Double) {
        val pages: Int get() = cuts.size - 1
    }

    private var web: WebView? = null
    private var started = false

    private val planJs = """
(function(){
  var de=document.documentElement;
  var W=de.clientWidth;
  var H=Math.max(de.scrollHeight,document.body.scrollHeight);
  var els=document.querySelectorAll('p,tr,h3,img,.slide');
  var b=[];
  for(var i=0;i<els.length;i++){
    var el=els[i];
    if(el.tagName!=='TR'){
      var q=el.parentNode,inCell=false;
      while(q&&q!==document){if(q.tagName==='TD'){inCell=true;break;}q=q.parentNode;}
      if(inCell)continue;
    }
    var r=el.getBoundingClientRect();
    b.push(r.bottom+window.pageYOffset);
  }
  return JSON.stringify({w:W,h:H,b:b});
})()
"""

    /** Muat dokumen di WebView tersembunyi lalu hitung pembagian halaman. Callback jalan di thread utama. */
    fun prepare(onReady: (Plan) -> Unit, onError: (String) -> Unit) {
        try {
            try { WebView.enableSlowWholeDocumentDraw() } catch (_: Throwable) {}
            val w = WebView(act)
            web = w
            w.settings.useWideViewPort = true
            w.settings.loadWithOverviewMode = true
            w.alpha = 0.01f
            val dm = act.resources.displayMetrics
            act.addContentView(w, FrameLayout.LayoutParams(dm.widthPixels, dm.heightPixels))
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (started) return
                    started = true
                    // beri waktu sebentar supaya tata letak selesai
                    w.postDelayed({
                        w.evaluateJavascript(planJs) { raw ->
                            try {
                                val s = JSONTokener(raw).nextValue() as String
                                onReady(buildPlan(JSONObject(s)))
                            } catch (t: Throwable) {
                                onError(t.message ?: t.javaClass.simpleName)
                            }
                        }
                    }, 400)
                }
            }
            w.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        } catch (t: Throwable) {
            onError(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun buildPlan(o: JSONObject): Plan {
        val w = o.getDouble("w")
        val h = o.getDouble("h")
        val arr = o.getJSONArray("b")
        val bottoms = ArrayList<Double>()
        for (i in 0 until arr.length()) bottoms.add(arr.getDouble(i))
        bottoms.sort()
        val pageH = w * 842.0 / 595.0
        val cuts = arrayListOf(0.0)
        var start = 0.0
        var guard = 0
        while (start < h - 1 && guard++ < 2000) {
            val limit = start + pageH
            if (limit >= h) { cuts.add(h); break }
            var cut = limit
            for (x in bottoms) if (x > start + pageH * 0.4 && x <= limit) cut = x   // terurut naik -> yang terakhir = terbesar
            cuts.add(cut)
            start = cut
        }
        if (cuts.size < 2) cuts.add(maxOf(h, 1.0))
        return Plan(cuts, w, h)
    }

    /** Gambar tiap halaman satu per satu (thread utama). [onPage] harus selesai memakai bitmap sebelum kembali. */
    fun render(plan: Plan, onPage: (Int, Bitmap) -> Unit, onDone: (String?) -> Unit) {
        val w = web ?: return onDone("Penampil belum siap")
        try {
            val vw = w.width
            w.measure(
                View.MeasureSpec.makeMeasureSpec(vw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val fullH = w.measuredHeight
            if (fullH <= 0) return onDone("Gagal mengukur dokumen")
            w.layout(0, 0, vw, fullH)
            val s = fullH / plan.hCss
            val wPx = Math.round(plan.wCss * s).toInt().coerceAtLeast(1)
            var i = 0
            fun step() {
                if (i >= plan.pages) { onDone(null); return }
                try {
                    val y0 = plan.cuts[i] * s
                    val y1 = plan.cuts[i + 1] * s
                    val h = Math.round(y1 - y0).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(wPx, h, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    c.drawColor(Color.WHITE)
                    c.translate(0f, (-y0).toFloat())
                    w.draw(c)
                    onPage(i, bmp)
                    bmp.recycle()
                    i++
                    w.post { step() }
                } catch (t: Throwable) {
                    onDone(t.message ?: t.javaClass.simpleName)
                }
            }
            step()
        } catch (t: Throwable) {
            onDone(t.message ?: t.javaClass.simpleName)
        }
    }

    fun dispose() {
        val w = web ?: return
        web = null
        try {
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        } catch (_: Throwable) {}
    }
}
