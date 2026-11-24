package com.hadi.docviewer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Tampilan + penyuntingan teks untuk .docx / .xlsx / .pptx.
 * Cara kerja: teks diubah langsung di dalam XML file aslinya (format, gambar, dan lainnya tidak disentuh),
 * lalu seluruh isi zip ditulis ulang. Word/PowerPoint diedit per paragraf, Excel per sel.
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

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"

    private val REF_RE = Regex("^[A-Z]{1,3}[0-9]{1,7}$")
    private val CELL_RE = Regex("<c\\s[^>]*?/>|<c\\s[^>]*?>.*?</c>", RegexOption.DOT_MATCHES_ALL)
    private val REF_ATTR = Regex("\\sr=\"([A-Z]+[0-9]+)\"")
    private val STYLE_ATTR = Regex("\\ss=\"(\\d+)\"")
    private val NUM_RE = Regex("^-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?$")

    /** Dipakai di HTML: gaya + skrip yang mengirim ketukan ke aplikasi saat mode edit aktif. */
    const val EDIT_ASSETS = """
<style>
.edited{background:#fff3b0}
body.edit [data-e]{outline:1px dashed #7a9cc6;cursor:pointer}
</style>
<script>
window.__edit=false;
function setEdit(on){window.__edit=on;document.body.className=on?'edit':'';}
function setText(id,t,edited){
  var els=document.querySelectorAll('[data-e]');
  for(var i=0;i<els.length;i++){
    if(els[i].getAttribute('data-e')===id){
      els[i].textContent=t;
      if(edited){els[i].classList.add('edited');}else{els[i].classList.remove('edited');}
      break;
    }
  }
}
document.addEventListener('click',function(e){
  if(!window.__edit)return;
  var el=e.target;
  while(el&&el!==document&&!(el.getAttribute&&el.getAttribute('data-e'))){el=el.parentNode;}
  if(el&&el.getAttribute){AndroidEdit.edit(el.getAttribute('data-e'));}
});
</script>
"""

    // ---------------------------------------------------------------- util
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

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return n - 1
    }

    // ---------------------------------------------------------------- buka
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

    private class Para(val id: String) {
        val nodes = ArrayList<Int>()
        val text = StringBuilder()
    }

    /**
     * Baca paragraf dari XML Word (pfx "w") atau PowerPoint (pfx "a").
     * Nomor node teks dihitung untuk SEMUA elemen t (termasuk di dalam Fallback yang tidak ditampilkan),
     * supaya cocok persis dengan penghitungan saat menyimpan.
     */
    private fun scanParas(
        xml: ByteArray, pfx: String, ns: String, tables: Boolean, idPrefix: String, part: String,
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
                    mine && ln == "tabs" -> skip = 1
                    tables && mine && ln == "tbl" -> sb.append("<table>")
                    tables && mine && ln == "tr" -> sb.append("<tr>")
                    tables && mine && ln == "tc" -> sb.append("<td>")
                    mine && ln == "p" -> {
                        val para = Para("$idPrefix$paraCount")
                        paraCount++
                        stack.add(para)
                        sb.append("<p data-e=\"").append(para.id).append("\">")
                    }
                    mine && ln == "tab" -> sb.append("&emsp;")
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
                val ln = p.local()
                val mine = p.isNs(ns, pfx)
                when {
                    mine && ln == "p" -> {
                        sb.append("</p>")
                        if (stack.isNotEmpty()) {
                            val para = stack.removeAt(stack.lastIndex)
                            units[para.id] = EditUnit(
                                part, para.text.toString(), para.nodes.toList(), null,
                                para.nodes.isNotEmpty(), "Paragraf ini tidak punya teks yang bisa diedit"
                            )
                        }
                    }
                    tables && mine && ln == "tbl" -> sb.append("</table>")
                    tables && mine && ln == "tr" -> sb.append("</tr>")
                    tables && mine && ln == "tc" -> sb.append("</td>")
                }
            }
            ev = p.next()
        }
        return hasText
    }

    private fun openDocx(bytes: ByteArray): Model? {
        val part = "word/document.xml"
        val xml = readEntries(bytes, listOf(part))[part] ?: return null
        val units = LinkedHashMap<String, EditUnit>()
        val sb = StringBuilder()
        val has = scanParas(xml, "w", W_NS, true, "d", part, units, sb)
        val html = if (has) sb.toString() else "<p>Dokumen kosong atau tidak berisi teks yang bisa dibaca.</p>"
        return Model("doc", html, units, bytes)
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
            if (scanParas(files[part]!!, "a", A_NS, false, "s${i}_", part, units, sb)) has = true
            sb.append("</div>")
        }
        val html = if (has) sb.toString() else "<p>Presentasi tidak berisi teks yang bisa dibaca.</p>"
        return Model("ppt", html, units, bytes)
    }

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

    // ---------------------------------------------------------------- simpan
    /** Tulis ulang file dengan teks hasil edit. [edits] = id unit -> teks baru. */
    fun save(model: Model, edits: Map<String, String>, out: OutputStream) {
        val byPart = HashMap<String, MutableMap<String, String>>()
        for ((id, text) in edits) {
            val u = model.units[id] ?: continue
            if (!u.editable) continue
            byPart.getOrPut(u.part) { HashMap() }[id] = text
        }
        val tmp = File.createTempFile("office", ".zip")
        try {
            tmp.writeBytes(model.zipBytes)
            ZipFile(tmp).use { zf ->
                ZipOutputStream(out).use { zo ->
                    val en = zf.entries()
                    while (en.hasMoreElements()) {
                        val e = en.nextElement()
                        if (e.isDirectory) {
                            zo.putNextEntry(ZipEntry(e.name)); zo.closeEntry(); continue
                        }
                        val name = e.name.trimStart('/')
                        var data = zf.getInputStream(e).use { it.readBytes() }
                        val pe = byPart[name]
                        if (pe != null) {
                            data = patchPart(model, pe, String(data, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                        } else if (model.kind == "xls" && name == "xl/workbook.xml" && byPart.isNotEmpty()) {
                            data = forceRecalc(String(data, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                        }
                        zo.putNextEntry(ZipEntry(e.name))
                        zo.write(data)
                        zo.closeEntry()
                    }
                }
            }
        } finally {
            tmp.delete()
        }
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
}
