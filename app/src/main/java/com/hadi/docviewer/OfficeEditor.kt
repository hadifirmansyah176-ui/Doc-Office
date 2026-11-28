package com.hadi.docviewer

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Tampilan + penyuntingan untuk .docx / .xlsx / .pptx.
 * Semua perubahan dilakukan langsung pada XML di dalam file asli (gambar, gaya, dan lainnya tidak disentuh),
 * lalu isi zip ditulis ulang. Setiap operasi mengembalikan byte file baru.
 *  - Word  : edit teks, format paragraf (font, ukuran, warna, rata), sisip gambar & tabel
 *  - Excel : edit teks / nilai per sel
 *  - PowerPoint : edit teks per paragraf
 */
object OfficeEditor {

    /** Satu bagian yang bisa diketuk: paragraf (docx/pptx) atau sel (xlsx). */
    class EditUnit(
        val part: String,            // nama entri zip yang memuat unit ini
        val text: String,            // teks asli yang tampil
        val nodes: List<Int>?,       // docx/pptx: nomor node teks milik paragraf
        val ref: String?,            // xlsx: alamat sel, mis. "B2"
        val editable: Boolean,
        val reason: String = "",
        val numeric: Boolean = false // xlsx: sel angka
    )

    class Model(val kind: String, val html: String, val units: Map<String, EditUnit>, val zipBytes: ByteArray)

    /** Format paragraf (Word). Nilai null = tidak diubah. align: left | center | right | both */
    class ParaFmt(
        val font: String? = null,
        val sizePt: Float? = null,
        val colorHex: String? = null,
        val align: String? = null
    )

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val UTF8 = Charsets.UTF_8

    private val REF_RE = Regex("^[A-Z]{1,3}[0-9]{1,7}$")
    private val CELL_RE = Regex("<c\\s[^>]*?/>|<c\\s[^>]*?>.*?</c>", RegexOption.DOT_MATCHES_ALL)
    private val REF_ATTR = Regex("\\sr=\"([A-Z]+[0-9]+)\"")
    private val STYLE_ATTR = Regex("\\ss=\"(\\d+)\"")
    private val NUM_RE = Regex("^-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?$")
    private val PARA_TOKEN = Regex("<w:p(?=[\\s/>])[^>]*?(/?)>|</w:p>")
    private val RUN_RE = Regex("<w:r(?=[\\s>])[^>]*?>.*?</w:r>", RegexOption.DOT_MATCHES_ALL)
    private val RPR_RE = Regex("^\\s*<w:rPr>(.*?)</w:rPr>", RegexOption.DOT_MATCHES_ALL)
    private val RPR_EMPTY_RE = Regex("^\\s*<w:rPr/>")
    private val PPR_RE = Regex("^\\s*<w:pPr>(.*?)</w:pPr>", RegexOption.DOT_MATCHES_ALL)
    private val PPR_EMPTY_RE = Regex("^\\s*<w:pPr/>")
    private val ANY_START = Regex("<([A-Za-z0-9]+):([A-Za-z0-9]+)(?=[\\s/>])")

    // urutan anak <w:rPr> menurut skema OOXML (Word cerewet soal urutan)
    private val RPR_ORDER = listOf(
        "rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline",
        "shadow", "emboss", "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing",
        "w", "kern", "position", "sz", "szCs", "highlight", "u", "effect", "bdr", "shd", "fitText",
        "vertAlign", "rtl", "cs", "em", "lang", "eastAsianLayout", "specVanish", "oMath"
    )
    // anak <w:pPr> yang harus berada SETELAH <w:jc>
    private val PPR_AFTER_JC = setOf(
        "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle",
        "rPr", "sectPr", "pPrChange"
    )

    /** Gaya + skrip untuk mode edit, pilih paragraf, dan sebagainya (ditempel di akhir HTML). */
    const val EDIT_ASSETS = """
<style>
p[data-e]{min-height:1.2em;margin:4px 0}
td{min-width:36px;height:22px}
td p{margin:2px 0}
img{max-width:100%}
.sel{background:#cfe3ff}
body.edit [data-e]{outline:1px dashed #7a9cc6;cursor:pointer}
</style>
<script>
window.__edit=false;
function setEdit(on){window.__edit=on;document.body.className=on?'edit':'';}
function setSel(id,on){
  var els=document.querySelectorAll('[data-e]');
  for(var i=0;i<els.length;i++){
    if(els[i].getAttribute('data-e')===id){
      if(on){els[i].classList.add('sel');}else{els[i].classList.remove('sel');}
      break;
    }
  }
}
function selectIds(arr){
  var m={};for(var i=0;i<arr.length;i++){m[arr[i]]=1;}
  var els=document.querySelectorAll('[data-e]');
  for(var j=0;j<els.length;j++){if(m[els[j].getAttribute('data-e')]){els[j].classList.add('sel');}}
}
function selectAll(on){
  var els=document.querySelectorAll('p[data-e]');
  for(var i=0;i<els.length;i++){if(on){els[i].classList.add('sel');}else{els[i].classList.remove('sel');}}
}
document.addEventListener('click',function(e){
  if(!window.__edit)return;
  var el=e.target;
  while(el&&el!==document&&!(el.getAttribute&&el.getAttribute('data-e'))){el=el.parentNode;}
  if(el&&el.getAttribute){AndroidEdit.edit(el.getAttribute('data-e'));}
});
</script>
"""

    // ================================================================ util
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Aman untuk isi elemen XML: escape & < > dan buang karakter kontrol yang dilarang XML. */
    private fun xmlEsc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch.code < 0x20 && ch != '\t' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun parser(data: ByteArray): XmlPullParser =
        Xml.newPullParser().also { it.setInput(ByteArrayInputStream(data), "UTF-8") }

    private fun XmlPullParser.local(): String = (name ?: "").substringAfter(':')

    /** Cocokkan namespace: lewat URI, prefiks, atau nama berprefiks (tahan terhadap mode parser yang berbeda). */
    private fun XmlPullParser.isNs(uri: String, pfx: String): Boolean =
        namespace == uri || this.prefix == pfx || (name ?: "").startsWith("$pfx:")

    /** Nilai atribut berdasarkan nama lokal (mis. "val" untuk w:val). */
    private fun XmlPullParser.attr(local: String): String? {
        for (i in 0 until attributeCount) {
            if (getAttributeName(i).substringAfter(':') == local) return getAttributeValue(i)
        }
        return null
    }

    private fun readEntries(bytes: ByteArray, prefixes: List<String>): Map<String, ByteArray> {
        val tmp = File.createTempFile("office", ".zip")
        try {
            tmp.writeBytes(bytes)
            val map = LinkedHashMap<String, ByteArray>()
            ZipFile(tmp).use { z ->
                val en = z.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    val name = e.name.trimStart('/')
                    if (!e.isDirectory && prefixes.any { name.startsWith(it) }) {
                        map[name] = z.getInputStream(e).use { it.readBytes() }
                    }
                }
            }
            return map
        } finally {
            tmp.delete()
        }
    }

    private fun zipNames(bytes: ByteArray): Set<String> {
        val tmp = File.createTempFile("office", ".zip")
        try {
            tmp.writeBytes(bytes)
            val set = HashSet<String>()
            ZipFile(tmp).use { z ->
                val en = z.entries()
                while (en.hasMoreElements()) set.add(en.nextElement().name.trimStart('/'))
            }
            return set
        } finally {
            tmp.delete()
        }
    }

    /**
     * Tulis ulang zip. [modify] dipanggil untuk tiap entri (kembalikan byte baru, atau null = biarkan).
     * [extra] = entri baru yang ditambahkan di akhir.
     */
    private fun rewrite(
        bytes: ByteArray,
        modify: (String, ByteArray) -> ByteArray?,
        extra: Map<String, ByteArray> = emptyMap()
    ): ByteArray {
        val tmp = File.createTempFile("office", ".zip")
        try {
            tmp.writeBytes(bytes)
            val bos = ByteArrayOutputStream()
            val seen = HashSet<String>()
            ZipFile(tmp).use { zf ->
                ZipOutputStream(bos).use { zo ->
                    val en = zf.entries()
                    while (en.hasMoreElements()) {
                        val e = en.nextElement()
                        val name = e.name.trimStart('/')
                        seen.add(name)
                        if (e.isDirectory) {
                            zo.putNextEntry(ZipEntry(e.name)); zo.closeEntry(); continue
                        }
                        val data = zf.getInputStream(e).use { it.readBytes() }
                        val nd = modify(name, data) ?: data
                        zo.putNextEntry(ZipEntry(e.name))
                        zo.write(nd)
                        zo.closeEntry()
                    }
                    for ((n, d) in extra) {
                        if (n in seen) continue
                        zo.putNextEntry(ZipEntry(n)); zo.write(d); zo.closeEntry()
                    }
                }
            }
            return bos.toByteArray()
        } finally {
            tmp.delete()
        }
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return n - 1
    }

    // ================================================================ buka
    /** Kembalikan null kalau file tidak bisa dibaca (pemanggil lalu memakai tampilan baca-saja). */
    fun open(bytes: ByteArray, kind: String): Model? = try {
        when (kind) {
            "doc" -> openDocx(bytes)
            "xls" -> openXlsx(bytes)
            "ppt" -> openPptx(bytes)
            else -> null
        }
    } catch (t: Throwable) {
        null
    }

    // ---------------------------------------------------------------- Word
    private class DPara(val id: String) {
        val nodes = ArrayList<Int>()
        val text = StringBuilder()
        val html = StringBuilder()
        var align: String? = null
        var style: String? = null
        var bullet = false
    }

    private class RunSt {
        var b = false
        var i = false
        var u = false
        var strike = false
        var sz = 0
        var color: String? = null
        var font: String? = null
    }

    private fun flagOn(v: String?) = v == null || (v != "0" && v != "false" && v != "off" && v != "none")

    private fun cssAlign(v: String): String = when (v) {
        "center" -> "center"
        "right", "end" -> "right"
        "both", "distribute", "justify" -> "justify"
        else -> "left"
    }

    private fun headingCss(style: String?): String? {
        if (style == null) return null
        if (style.equals("Title", true)) return "font-size:26pt;font-weight:bold;"
        val m = Regex("(?i)^heading([1-3])$").find(style) ?: return null
        return when (m.groupValues[1]) {
            "1" -> "font-size:20pt;font-weight:bold;"
            "2" -> "font-size:16pt;font-weight:bold;"
            else -> "font-size:14pt;font-weight:bold;"
        }
    }

    private fun fontCss(raw: String): String {
        val clean = raw.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim()
        if (clean.isEmpty()) return "sans-serif"
        val l = clean.lowercase()
        val generic = when {
            l.contains("courier") || l.contains("consolas") || l.contains("mono") -> "monospace"
            l.contains("times") || l.contains("georgia") || l.contains("cambria") || l.contains("garamond") ||
                l.contains("serif") && !l.contains("sans") -> "serif"
            else -> "sans-serif"
        }
        return "'$clean',$generic"
    }

    private fun spanFor(r: RunSt?, text: String): String {
        if (r == null) return text
        val css = StringBuilder()
        if (r.b) css.append("font-weight:bold;")
        if (r.i) css.append("font-style:italic;")
        if (r.u || r.strike) {
            css.append("text-decoration:")
            if (r.u) css.append("underline ")
            if (r.strike) css.append("line-through")
            css.append(';')
        }
        if (r.sz > 0) css.append("font-size:").append(r.sz / 2.0).append("pt;")
        r.color?.let { css.append("color:#").append(it).append(';') }
        r.font?.let { css.append("font-family:").append(fontCss(it)).append(';') }
        return if (css.isEmpty()) text else "<span style=\"$css\">$text</span>"
    }

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    /**
     * Baca document.xml Word -> HTML + daftar paragraf. Nomor node teks (w:t) dan nomor paragraf (w:p)
     * dihitung untuk SEMUA elemen, termasuk yang di dalam Fallback (tidak ditampilkan), supaya cocok persis
     * dengan penghitungan berbasis teks saat mengubah file.
     */
    private fun scanDocx(
        xml: ByteArray, part: String, rels: Map<String, String>, media: Map<String, ByteArray>,
        units: MutableMap<String, EditUnit>, sb: StringBuilder
    ): Boolean {
        val p = parser(xml)
        val stack = ArrayList<DPara>()
        var nodeCount = 0
        var paraCount = 0
        var hasContent = false
        var skip = 0
        var inPPr = false
        var inRPr = false
        var run: RunSt? = null
        var imgTotal = 0L

        fun out(): StringBuilder = if (stack.isEmpty()) sb else stack[stack.lastIndex].html

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (skip > 0) {
                if (ev == XmlPullParser.START_TAG) {
                    if (p.isNs(W_NS, "w")) {
                        val ln = p.local()
                        if (ln == "t") nodeCount++ else if (ln == "p") paraCount++
                    }
                    skip++
                } else if (ev == XmlPullParser.END_TAG) skip--
            } else if (ev == XmlPullParser.START_TAG) {
                val ln = p.local()
                val mine = p.isNs(W_NS, "w")
                val r = run
                if (r != null && inRPr && mine) {
                    when (ln) {
                        "b" -> r.b = flagOn(p.attr("val"))
                        "i" -> r.i = flagOn(p.attr("val"))
                        "u" -> r.u = flagOn(p.attr("val"))
                        "strike" -> r.strike = flagOn(p.attr("val"))
                        "sz" -> r.sz = p.attr("val")?.toIntOrNull() ?: 0
                        "color" -> r.color = p.attr("val")?.takeIf { Regex("^[0-9A-Fa-f]{6}$").matches(it) }
                        "rFonts" -> r.font = p.attr("ascii") ?: p.attr("hAnsi")
                    }
                }
                when {
                    ln == "Fallback" || ln == "pPrChange" || ln == "rPrChange" -> skip = 1
                    !mine -> {
                        if (ln == "blip") {
                            val rid = p.attr("embed")
                            val path = if (rid != null) rels[rid] else null
                            val data = if (path != null) media[path] else null
                            val target = out()
                            if (data != null && path != null && imgTotal + data.size <= 4_000_000L) {
                                imgTotal += data.size
                                target.append("<img src=\"data:").append(mimeOf(path)).append(";base64,")
                                    .append(Base64.encodeToString(data, Base64.NO_WRAP)).append("\">")
                            } else target.append("<i>[Gambar]</i>")
                            hasContent = true
                        }
                    }
                    ln == "tabs" -> skip = 1
                    ln == "tbl" -> out().append("<table>")
                    ln == "tr" -> out().append("<tr>")
                    ln == "tc" -> out().append("<td>")
                    ln == "p" -> { stack.add(DPara("d$paraCount")); paraCount++ }
                    ln == "pPr" -> inPPr = true
                    ln == "pStyle" && inPPr -> stack.lastOrNull()?.style = p.attr("val")
                    ln == "jc" && inPPr && !inRPr -> stack.lastOrNull()?.align = p.attr("val")
                    ln == "numPr" && inPPr -> stack.lastOrNull()?.bullet = true
                    ln == "r" -> run = RunSt()
                    ln == "rPr" -> if (run != null) inRPr = true
                    ln == "tab" -> out().append("&emsp;")
                    ln == "br" -> out().append("<br>")
                    ln == "t" -> {
                        val t = p.nextText()
                        val idx = nodeCount++
                        if (t.isNotBlank()) hasContent = true
                        val para = stack.lastOrNull()
                        if (para != null) { para.nodes.add(idx); para.text.append(t) }
                        out().append(spanFor(run, esc(t)))
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                val ln = p.local()
                if (p.isNs(W_NS, "w")) {
                    when (ln) {
                        "pPr" -> inPPr = false
                        "rPr" -> inRPr = false
                        "r" -> run = null
                        "tbl" -> out().append("</table>")
                        "tr" -> out().append("</tr>")
                        "tc" -> out().append("</td>")
                        "p" -> if (stack.isNotEmpty()) {
                            val para = stack.removeAt(stack.lastIndex)
                            val css = StringBuilder()
                            para.align?.let { css.append("text-align:").append(cssAlign(it)).append(';') }
                            headingCss(para.style)?.let { css.append(it) }
                            val target = out()
                            target.append("<p data-e=\"").append(para.id).append('"')
                            if (css.isNotEmpty()) target.append(" style=\"").append(css).append('"')
                            target.append('>')
                            if (para.bullet) target.append("&bull; ")
                            target.append(para.html).append("</p>")
                            units[para.id] = EditUnit(part, para.text.toString(), para.nodes.toList(), null, true)
                        }
                    }
                }
            }
            ev = p.next()
        }
        return hasContent
    }

    private fun openDocx(bytes: ByteArray): Model? {
        val part = "word/document.xml"
        val relsName = "word/_rels/document.xml.rels"
        val files = readEntries(bytes, listOf(part, relsName, "word/media/"))
        val xml = files[part] ?: return null

        val rels = HashMap<String, String>()
        files[relsName]?.let { r ->
            val s = String(r, UTF8)
            Regex("<Relationship\\s[^>]*?/?>").findAll(s).forEach { m ->
                val tag = m.value
                val id = Regex("\\sId=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
                val target = Regex("\\sTarget=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
                if (id != null && target != null && !tag.contains("TargetMode=\"External\"")) {
                    rels[id] = if (target.startsWith("/")) target.trimStart('/') else "word/$target"
                }
            }
        }
        val units = LinkedHashMap<String, EditUnit>()
        val sb = StringBuilder()
        val has = scanDocx(xml, part, rels, files, units, sb)
        val html = if (has) sb.toString() else sb.toString() + "<p><i>Dokumen kosong. Ketuk paragraf lalu pakai menu Insert.</i></p>"
        return Model("doc", html, units, bytes)
    }

    // ---------------------------------------------------------------- PowerPoint
    private class Para(val id: String) {
        val nodes = ArrayList<Int>()
        val text = StringBuilder()
    }

    private fun scanParas(
        xml: ByteArray, pfx: String, ns: String, idPrefix: String, part: String,
        units: MutableMap<String, EditUnit>, sb: StringBuilder
    ): Boolean {
        val p = parser(xml)
        val stack = ArrayList<Para>()
        var nodeCount = 0
        var paraCount = 0
        var hasText = false
        var skip = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (skip > 0) {
                if (ev == XmlPullParser.START_TAG) {
                    if (p.local() == "t" && p.isNs(ns, pfx)) nodeCount++
                    skip++
                } else if (ev == XmlPullParser.END_TAG) skip--
            } else if (ev == XmlPullParser.START_TAG) {
                val ln = p.local()
                val mine = p.isNs(ns, pfx)
                when {
                    ln == "Fallback" -> skip = 1
                    mine && ln == "p" -> {
                        val para = Para("$idPrefix$paraCount")
                        paraCount++
                        stack.add(para)
                        sb.append("<p data-e=\"").append(para.id).append("\">")
                    }
                    mine && ln == "br" -> sb.append("<br>")
                    mine && ln == "t" -> {
                        val t = p.nextText()
                        val idx = nodeCount++
                        if (t.isNotBlank()) hasText = true
                        stack.lastOrNull()?.let { it.nodes.add(idx); it.text.append(t) }
                        sb.append(esc(t))
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if (p.isNs(ns, pfx) && p.local() == "p") {
                    sb.append("</p>")
                    if (stack.isNotEmpty()) {
                        val para = stack.removeAt(stack.lastIndex)
                        units[para.id] = EditUnit(
                            part, para.text.toString(), para.nodes.toList(), null,
                            para.nodes.isNotEmpty(), "Paragraf ini tidak punya teks yang bisa diedit"
                        )
                    }
                }
            }
            ev = p.next()
        }
        return hasText
    }

    private fun openPptx(bytes: ByteArray): Model? {
        val files = readEntries(bytes, listOf("ppt/slides/slide"))
        val slides = files.keys.filter { it.endsWith(".xml") }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        if (slides.isEmpty()) return null
        val units = LinkedHashMap<String, EditUnit>()
        val sb = StringBuilder()
        var has = false
        slides.forEachIndexed { i, part ->
            sb.append("<div class='slide'><h3>Slide ${i + 1}</h3>")
            if (scanParas(files[part]!!, "a", A_NS, "s${i}_", part, units, sb)) has = true
            sb.append("</div>")
        }
        val html = if (has) sb.toString() else "<p>Presentasi tidak berisi teks yang bisa dibaca.</p>"
        return Model("ppt", html, units, bytes)
    }

    // ---------------------------------------------------------------- Excel
    private fun openXlsx(bytes: ByteArray): Model? {
        val files = readEntries(bytes, listOf("xl/sharedStrings.xml", "xl/worksheets/sheet"))
        val shared = ArrayList<String>()
        files["xl/sharedStrings.xml"]?.let {
            val p = parser(it)
            var cur: StringBuilder? = null
            var skip = 0
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (skip > 0) {
                    if (ev == XmlPullParser.START_TAG) skip++ else if (ev == XmlPullParser.END_TAG) skip--
                } else if (ev == XmlPullParser.START_TAG) {
                    when (p.local()) {
                        "si" -> cur = StringBuilder()
                        "rPh" -> skip = 1
                        "t" -> cur?.append(p.nextText())
                    }
                } else if (ev == XmlPullParser.END_TAG && p.local() == "si") {
                    shared.add(cur?.toString() ?: ""); cur = null
                }
                ev = p.next()
            }
        }
        val sheetParts = files.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        if (sheetParts.isEmpty()) return null

        val units = LinkedHashMap<String, EditUnit>()
        val sb = StringBuilder()
        var anyCell = false
        sheetParts.forEachIndexed { idx, part ->
            sb.append("<h3>Sheet ${idx + 1}</h3><div style='overflow-x:auto'><table>")
            val p = parser(files[part]!!)
            var type = ""
            var ref: String? = null
            var hasF = false
            var col = 0
            var rows = 0
            var isBuf: StringBuilder? = null
            var skip = 0

            fun cell(text: String) {
                val r = ref
                if (r != null) {
                    val id = "$idx:$r"
                    units[id] = EditUnit(
                        part, text, null, r, !hasF, "Sel ini berisi rumus, tidak bisa diedit",
                        numeric = (type == "" || type == "n")
                    )
                    sb.append("<td data-e=\"").append(id).append("\">")
                } else sb.append("<td>")
                sb.append(esc(text)).append("</td>")
                col++
                anyCell = true
            }

            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (skip > 0) {
                    if (ev == XmlPullParser.START_TAG) skip++ else if (ev == XmlPullParser.END_TAG) skip--
                } else if (ev == XmlPullParser.START_TAG) {
                    when (p.local()) {
                        "rPh" -> skip = 1
                        "row" -> { sb.append("<tr>"); col = 0; rows++ }
                        "c" -> {
                            type = p.getAttributeValue(null, "t") ?: ""
                            val raw = p.getAttributeValue(null, "r")
                            ref = raw?.takeIf { REF_RE.matches(it) }
                            hasF = false
                            if (raw != null) {
                                val target = colIndex(raw)
                                while (col < target && col < 500) { sb.append("<td></td>"); col++ }
                            }
                        }
                        "f" -> hasF = true
                        "is" -> isBuf = StringBuilder()
                        "t" -> {
                            val tx = p.nextText()
                            val ib = isBuf
                            if (ib != null) ib.append(tx) else cell(tx)
                        }
                        "v" -> {
                            val v = p.nextText()
                            cell(
                                when (type) {
                                    "s" -> shared.getOrElse(v.toIntOrNull() ?: -1) { "" }
                                    "b" -> if (v == "1") "TRUE" else "FALSE"
                                    else -> v
                                }
                            )
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.local()) {
                        "row" -> sb.append("</tr>")
                        "is" -> { cell(isBuf?.toString() ?: ""); isBuf = null }
                    }
                }
                if (rows > 5000) { sb.append("<tr><td>... (dipotong, terlalu banyak baris)</td></tr>"); break }
                ev = p.next()
            }
            sb.append("</table></div>")
        }
        val html = if (anyCell) sb.toString() else "<p>Spreadsheet kosong atau tidak berisi data yang bisa dibaca.</p>"
        return Model("xls", html, units, bytes)
    }

    // ================================================================ ubah teks
    /** Ganti teks satu paragraf / sel. Hasil: byte file baru. */
    fun applyText(model: Model, id: String, text: String): ByteArray {
        val u = model.units[id] ?: return model.zipBytes
        val clean = text.replace("\r", "").replace("\n", " ")
        val nodes = u.nodes
        if (model.kind == "doc" && nodes != null && nodes.isEmpty()) {
            // paragraf kosong: buat run baru berisi teks
            val idx = id.removePrefix("d").toIntOrNull() ?: return model.zipBytes
            return editDocument(model) { xml -> insertRun(xml, idx, clean) }
        }
        return applyEdits(model, mapOf(id to clean))
    }

    private fun applyEdits(model: Model, edits: Map<String, String>): ByteArray {
        val byPart = HashMap<String, MutableMap<String, String>>()
        for ((id, text) in edits) {
            val u = model.units[id] ?: continue
            if (!u.editable) continue
            byPart.getOrPut(u.part) { HashMap() }[id] = text
        }
        return rewrite(model.zipBytes, { name, data ->
            val pe = byPart[name]
            when {
                pe != null -> patchPart(model, pe, String(data, UTF8)).toByteArray(UTF8)
                model.kind == "xls" && name == "xl/workbook.xml" && byPart.isNotEmpty() ->
                    forceRecalc(String(data, UTF8)).toByteArray(UTF8)
                else -> null
            }
        })
    }

    private fun patchPart(model: Model, pe: Map<String, String>, xml: String): String = when (model.kind) {
        "xls" -> {
            val refText = HashMap<String, Pair<String, Boolean>>()
            for ((id, text) in pe) {
                val u = model.units[id] ?: continue
                val r = u.ref ?: continue
                refText[r] = Pair(text, u.numeric)
            }
            patchSheet(xml, refText)
        }
        else -> {
            // paragraf: teks baru masuk ke node pertama, node lain dikosongkan (gaya run pertama yang dipakai)
            val nodeMap = HashMap<Int, String>()
            for ((id, text) in pe) {
                val nodes = model.units[id]?.nodes ?: continue
                nodes.forEachIndexed { i, n -> nodeMap[n] = if (i == 0) text else "" }
            }
            patchTextNodes(xml, if (model.kind == "doc") "w" else "a", nodeMap)
        }
    }

    private fun patchTextNodes(xml: String, pfx: String, nodeMap: Map<Int, String>): String {
        val re = Regex("<$pfx:t(?:\\s[^>]*?)?(?:/>|>([^<]*)</$pfx:t>)")
        var idx = 0
        return re.replace(xml) { m ->
            val i = idx++
            val nt = nodeMap[i]
            if (nt == null) m.value else "<$pfx:t xml:space=\"preserve\">${xmlEsc(nt)}</$pfx:t>"
        }
    }

    private fun patchSheet(xml: String, refText: Map<String, Pair<String, Boolean>>): String =
        CELL_RE.replace(xml) { m ->
            val v = m.value
            val ref = REF_ATTR.find(v)?.groupValues?.get(1)
            val e = if (ref != null) refText[ref] else null
            if (ref == null || e == null) v
            else buildCell(ref, STYLE_ATTR.find(v)?.groupValues?.get(1), e.first, e.second)
        }

    private fun buildCell(ref: String, style: String?, text: String, wasNumeric: Boolean): String {
        val s = if (style != null) " s=\"$style\"" else ""
        val t = text.trim()
        return when {
            text.isEmpty() -> "<c r=\"$ref\"$s/>"
            wasNumeric && NUM_RE.matches(t) -> "<c r=\"$ref\"$s><v>$t</v></c>"
            else -> "<c r=\"$ref\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEsc(text)}</t></is></c>"
        }
    }

    /** Minta Excel menghitung ulang semua rumus saat file dibuka (nilai lama di sel rumus bisa usang). */
    private fun forceRecalc(xml: String): String =
        if (xml.contains("<calcPr") && !xml.contains("fullCalcOnLoad"))
            xml.replaceFirst("<calcPr", "<calcPr fullCalcOnLoad=\"1\"")
        else xml

    // ================================================================ Word: paragraf & sisip
    private class Span(val start: Int, val startEnd: Int, val endTagStart: Int, val end: Int) {
        val selfClosing get() = endTagStart < 0
        val valid get() = start >= 0
    }

    /** Posisi tiap paragraf (w:p) di dalam teks XML, berurutan menurut kemunculan tag pembuka. */
    private fun paragraphSpans(xml: String): List<Span> {
        class Open(val idx: Int, val start: Int, val startEnd: Int)
        val spans = ArrayList<Span?>()
        val stack = ArrayList<Open>()
        for (m in PARA_TOKEN.findAll(xml)) {
            if (m.value.startsWith("</")) {
                if (stack.isNotEmpty()) {
                    val o = stack.removeAt(stack.lastIndex)
                    spans[o.idx] = Span(o.start, o.startEnd, m.range.first, m.range.last + 1)
                }
            } else if (m.groupValues[1] == "/") {
                spans.add(Span(m.range.first, m.range.last + 1, -1, m.range.last + 1))
            } else {
                spans.add(null)
                stack.add(Open(spans.size - 1, m.range.first, m.range.last + 1))
            }
        }
        return spans.map { it ?: Span(-1, -1, -1, -1) }
    }

    private fun editDocument(
        model: Model,
        extra: Map<String, ByteArray> = emptyMap(),
        others: (String, ByteArray) -> ByteArray? = { _, _ -> null },
        edit: (String) -> String
    ): ByteArray = rewrite(model.zipBytes, { name, data ->
        if (name == "word/document.xml") edit(String(data, UTF8)).toByteArray(UTF8) else others(name, data)
    }, extra)

    private fun insertRun(xml: String, idx: Int, text: String): String {
        if (text.isEmpty()) return xml
        val sp = paragraphSpans(xml).getOrNull(idx) ?: return xml
        if (!sp.valid) return xml
        val run = "<w:r><w:t xml:space=\"preserve\">${xmlEsc(text)}</w:t></w:r>"
        return if (sp.selfClosing) {
            val open = xml.substring(sp.start, sp.startEnd).removeSuffix("/>").trimEnd() + ">"
            xml.substring(0, sp.start) + open + run + "</w:p>" + xml.substring(sp.end)
        } else {
            xml.substring(0, sp.endTagStart) + run + xml.substring(sp.endTagStart)
        }
    }

    // ---------------------------------------------------------------- format paragraf
    private fun removeTag(c: String, name: String): String =
        Regex("<w:$name(?=[\\s/>])[^>]*?/>").replace(c, "")

    /** Sisipkan elemen run-properties sesuai urutan skema. */
    private fun insertOrdered(c: String, tag: String, elem: String): String {
        val order = RPR_ORDER.indexOf(tag)
        for (m in ANY_START.findAll(c)) {
            val pre = m.groupValues[1]
            val nm = m.groupValues[2]
            val idx = if (pre == "w") RPR_ORDER.indexOf(nm).let { if (it < 0) 500 else it } else 1000
            if (idx > order) return c.substring(0, m.range.first) + elem + c.substring(m.range.first)
        }
        return c + elem
    }

    private fun applyRunProps(content: String, fmt: ParaFmt): String {
        var c = content
        val font = fmt.font?.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }
        if (!font.isNullOrBlank()) {
            c = removeTag(c, "rFonts")
            c = insertOrdered(c, "rFonts",
                "<w:rFonts w:ascii=\"$font\" w:hAnsi=\"$font\" w:eastAsia=\"$font\" w:cs=\"$font\"/>")
        }
        if (fmt.colorHex != null && Regex("^[0-9A-Fa-f]{6}$").matches(fmt.colorHex)) {
            c = removeTag(c, "color")
            c = insertOrdered(c, "color", "<w:color w:val=\"${fmt.colorHex.uppercase()}\"/>")
        }
        if (fmt.sizePt != null) {
            val half = (fmt.sizePt * 2).roundToInt().coerceIn(2, 1638)
            c = removeTag(c, "sz")
            c = removeTag(c, "szCs")
            c = insertOrdered(c, "sz", "<w:sz w:val=\"$half\"/>")
            c = insertOrdered(c, "szCs", "<w:szCs w:val=\"$half\"/>")
        }
        return c
    }

    private fun fmtRun(run: String, fmt: ParaFmt): String {
        val gt = run.indexOf('>')
        val startTag = run.substring(0, gt + 1)
        val rest = run.substring(gt + 1, run.length - "</w:r>".length)
        val m = RPR_RE.find(rest)
        val m2 = if (m == null) RPR_EMPTY_RE.find(rest) else null
        val newRest = when {
            m != null -> rest.replaceRange(m.range, "<w:rPr>" + applyRunProps(m.groupValues[1], fmt) + "</w:rPr>")
            m2 != null -> rest.replaceRange(m2.range, "<w:rPr>" + applyRunProps("", fmt) + "</w:rPr>")
            else -> "<w:rPr>" + applyRunProps("", fmt) + "</w:rPr>" + rest
        }
        return startTag + newRest + "</w:r>"
    }

    private fun insertJc(pPrContent: String, jc: String): String {
        for (m in ANY_START.findAll(pPrContent)) {
            if (m.groupValues[1] == "w" && m.groupValues[2] in PPR_AFTER_JC) {
                return pPrContent.substring(0, m.range.first) + jc + pPrContent.substring(m.range.first)
            }
        }
        return pPrContent + jc
    }

    private fun setAlign(inner: String, align: String): String {
        val jc = "<w:jc w:val=\"$align\"/>"
        val m = PPR_RE.find(inner)
        if (m != null) {
            val c = insertJc(removeTag(m.groupValues[1], "jc"), jc)
            return inner.replaceRange(m.range, "<w:pPr>$c</w:pPr>")
        }
        val m2 = PPR_EMPTY_RE.find(inner)
        if (m2 != null) return inner.replaceRange(m2.range, "<w:pPr>$jc</w:pPr>")
        return "<w:pPr>$jc</w:pPr>$inner"
    }

    private fun fmtInner(inner0: String, fmt: ParaFmt): String {
        var inner = inner0
        if (fmt.align != null) inner = setAlign(inner, fmt.align)
        if (fmt.font != null || fmt.sizePt != null || fmt.colorHex != null) {
            inner = RUN_RE.replace(inner) { m -> fmtRun(m.value, fmt) }
        }
        return inner
    }

    /** Terapkan format (font / ukuran / warna / rata) ke paragraf-paragraf terpilih (id "d12", dst). */
    fun formatParagraphs(model: Model, ids: Collection<String>, fmt: ParaFmt): ByteArray {
        val wanted = ids.mapNotNull { it.removePrefix("d").toIntOrNull() }.toSortedSet()
        return editDocument(model) { xml ->
            val spans = paragraphSpans(xml)
            // hanya paragraf terluar (kalau ada paragraf di dalam paragraf, yang terluar sudah mencakup)
            val kept = ArrayList<Span>()
            var lastEnd = -1
            for (i in wanted) {
                val sp = spans.getOrNull(i) ?: continue
                if (!sp.valid || sp.start < lastEnd) continue
                kept.add(sp)
                lastEnd = sp.end
            }
            val sb = StringBuilder(xml)
            for (sp in kept.reversed()) {
                if (sp.selfClosing) {
                    if (fmt.align != null) {
                        val open = xml.substring(sp.start, sp.startEnd).removeSuffix("/>").trimEnd() + ">"
                        sb.replace(sp.start, sp.end, open + "<w:pPr><w:jc w:val=\"${fmt.align}\"/></w:pPr></w:p>")
                    }
                } else {
                    sb.replace(sp.startEnd, sp.endTagStart, fmtInner(xml.substring(sp.startEnd, sp.endTagStart), fmt))
                }
            }
            sb.toString()
        }
    }

    // ---------------------------------------------------------------- sisip tabel & gambar
    private fun bodyEndPos(xml: String): Int {
        val sect = xml.lastIndexOf("<w:sectPr")
        val lastP = xml.lastIndexOf("</w:p>")
        if (sect != -1 && sect > lastP) return sect
        val body = xml.lastIndexOf("</w:body>")
        return if (body != -1) body else xml.length
    }

    private fun insertAfter(xml: String, afterIndex: Int?, block: String): String {
        var pos = bodyEndPos(xml)
        if (afterIndex != null) {
            val sp = paragraphSpans(xml).getOrNull(afterIndex)
            if (sp != null && sp.valid) pos = sp.end
        }
        return xml.substring(0, pos) + block + xml.substring(pos)
    }

    private fun tableXml(rows: Int, cols: Int): String {
        val cw = 9000 / cols
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
        for (b in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("<w:").append(b).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>")
        }
        sb.append("</w:tblBorders><w:tblLook w:val=\"04A0\"/></w:tblPr><w:tblGrid>")
        repeat(cols) { sb.append("<w:gridCol w:w=\"").append(cw).append("\"/>") }
        sb.append("</w:tblGrid>")
        repeat(rows) {
            sb.append("<w:tr>")
            repeat(cols) {
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(cw).append("\" w:type=\"dxa\"/></w:tcPr><w:p/></w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl><w:p/>")   // Word butuh paragraf setelah tabel
        return sb.toString()
    }

    /** Sisipkan tabel [rows] x [cols] setelah paragraf [afterId] (atau di akhir dokumen). */
    fun insertTable(model: Model, afterId: String?, rows: Int, cols: Int): ByteArray {
        val r = rows.coerceIn(1, 50)
        val c = cols.coerceIn(1, 10)
        val idx = afterId?.removePrefix("d")?.toIntOrNull()
        return editDocument(model) { xml -> insertAfter(xml, idx, tableXml(r, c)) }
    }

    private fun pictureXml(rid: String, cx: Long, cy: Long, id: Int, name: String): String =
        "<w:p><w:r><w:drawing>" +
        "<wp:inline xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
        "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>" +
        "<wp:docPr id=\"$id\" name=\"$name\"/>" +
        "<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"$A_NS\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
        "<a:graphic xmlns:a=\"$A_NS\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
        "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
        "<pic:nvPicPr><pic:cNvPr id=\"0\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr>" +
        "<pic:blipFill><a:blip xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:embed=\"$rid\"/>" +
        "<a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
        "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
        "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
        "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"

    /**
     * Sisipkan gambar (png / jpeg) setelah paragraf [afterId] (atau di akhir dokumen).
     * [cx], [cy] = ukuran dalam EMU. Gambar disimpan di word/media dan didaftarkan di relasi + content types.
     */
    fun insertPicture(model: Model, afterId: String?, img: ByteArray, ext: String, cx: Long, cy: Long): ByteArray {
        val relsName = "word/_rels/document.xml.rels"
        val names = zipNames(model.zipBytes)
        var n = 1
        while (names.contains("word/media/image$n.$ext")) n++
        val mediaName = "word/media/image$n.$ext"

        val relsXml = readEntries(model.zipBytes, listOf(relsName))[relsName]?.let { String(it, UTF8) }
        val usedIds = if (relsXml == null) emptySet() else
            Regex("Id=\"([^\"]+)\"").findAll(relsXml).map { it.groupValues[1] }.toSet()
        var rid = "rIdImg$n"
        var k = n
        while (rid in usedIds) { k++; rid = "rIdImg$k" }

        val relTag = "<Relationship Id=\"$rid\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image$n.$ext\"/>"
        val mime = if (ext == "png") "image/png" else "image/jpeg"
        val idx = afterId?.removePrefix("d")?.toIntOrNull()

        val extra = HashMap<String, ByteArray>()
        extra[mediaName] = img
        if (relsXml == null) {
            extra[relsName] = ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$relTag</Relationships>").toByteArray(UTF8)
        }
        return editDocument(model, extra, { name, data ->
            when {
                name == relsName && relsXml != null -> relsXml.replace("</Relationships>", "$relTag</Relationships>").toByteArray(UTF8)
                name == "[Content_Types].xml" -> {
                    val ct = String(data, UTF8)
                    val tm = Regex("<Types[^>]*>").find(ct)
                    if (Regex("Extension=\"$ext\"", RegexOption.IGNORE_CASE).containsMatchIn(ct) || tm == null) null
                    else {
                        val at = tm.range.last + 1
                        (ct.substring(0, at) + "<Default Extension=\"$ext\" ContentType=\"$mime\"/>" + ct.substring(at))
                            .toByteArray(UTF8)
                    }
                }
                else -> null
            }
        }) { xml ->
            val nextId = (Regex("<wp:docPr[^>]*?\\sid=\"(\\d+)\"").findAll(xml)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0) + 1
            insertAfter(xml, idx, pictureXml(rid, cx, cy, nextId, "Picture $n"))
        }
    }
}
