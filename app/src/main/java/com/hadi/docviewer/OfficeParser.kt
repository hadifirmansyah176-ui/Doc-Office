package com.hadi.docviewer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Parser ringan untuk .docx / .xlsx / .pptx -> HTML (tanpa Apache POI, aman untuk Android 5). */
object OfficeParser {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Baca bagian-bagian zip yang namanya diawali salah satu prefix. Memakai ZipFile (membaca daftar isi zip). */
    private fun unzip(input: InputStream, prefixes: List<String>): Map<String, ByteArray> {
        val tmp = File.createTempFile("office", ".zip")
        try {
            tmp.outputStream().use { o -> input.copyTo(o) }
            val map = LinkedHashMap<String, ByteArray>()
            ZipFile(tmp).use { z ->
                val en = z.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    val name = e.name.trimStart('/')
                    if (prefixes.any { name.startsWith(it) }) {
                        map[name] = z.getInputStream(e).use { it.readBytes() }
                    }
                }
            }
            return map
        } finally {
            tmp.delete()
        }
    }

    private fun parser(data: ByteArray): XmlPullParser =
        Xml.newPullParser().also { it.setInput(ByteArrayInputStream(data), "UTF-8") }

    fun toHtml(input: InputStream, ext: String): String {
        val data = input.readBytes()
        // File berekstensi lama kadang isinya OOXML (zip) -> tetap bisa dibuka
        val isZip = data.size > 2 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()
        val kind = ext.removeSuffix("x")   // doc / xls / ppt
        val body = try {
            if (isZip) when (kind) {
                "doc" -> docx(ByteArrayInputStream(data))
                "xls" -> xlsx(ByteArrayInputStream(data))
                "ppt" -> pptx(ByteArrayInputStream(data))
                else -> "<p>Format tidak didukung.</p>"
            } else when (kind) {
                "doc" -> LegacyOffice.doc(OleReader(data))
                "xls" -> LegacyOffice.xls(OleReader(data))
                "ppt" -> LegacyOffice.ppt(OleReader(data))
                else -> "<p>Format tidak didukung.</p>"
            }
        } catch (e: Exception) {
            "<p>Gagal membaca file: ${esc(e.javaClass.simpleName + " " + (e.message ?: ""))}</p>"
        }
        return wrap(body)
    }

    /** Bungkus isi dokumen dengan kerangka HTML + gaya dasar. */
    fun wrap(body: String): String {
        return "<html><head><meta name='viewport' content='width=device-width, initial-scale=1, minimum-scale=0.5, maximum-scale=5'>" +
                "<style>body{font-family:sans-serif;padding:12px;line-height:1.5}" +
                "table{border-collapse:collapse;margin:8px 0}td{border:1px solid #999;padding:4px 8px;vertical-align:top}" +
                ".slide{border:1px solid #bbb;border-radius:8px;padding:12px;margin:16px 0;background:#fafafa}" +
                "h3{color:#555}</style></head><body>$body</body></html>"
    }

    /** Nama elemen tanpa prefiks namespace (aman untuk parser dengan/tanpa namespace). */
    private fun XmlPullParser.local(): String = (name ?: "").substringAfter(':')

    private fun docx(input: InputStream): String {
        val files = unzip(input, listOf("word/document.xml"))
        val xml = files["word/document.xml"] ?: return "<p>File DOCX tidak valid (word/document.xml tidak ada).</p>"
        val p = parser(xml)
        val sb = StringBuilder()
        var hasText = false
        var skip = 0   // melewati subpohon yang tidak perlu (Fallback duplikat, daftar tab)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (skip > 0) {
                if (ev == XmlPullParser.START_TAG) skip++ else if (ev == XmlPullParser.END_TAG) skip--
            } else when (ev) {
                XmlPullParser.START_TAG -> when (p.local()) {
                    "Fallback", "tabs" -> skip = 1
                    "tbl" -> sb.append("<table>")
                    "tr" -> sb.append("<tr>")
                    "tc" -> sb.append("<td>")
                    "p" -> sb.append("<p>")
                    "tab" -> sb.append("&emsp;")
                    "br" -> sb.append("<br>")
                    "t" -> { val t = p.nextText(); if (t.isNotBlank()) hasText = true; sb.append(esc(t)) }
                }
                XmlPullParser.END_TAG -> when (p.local()) {
                    "tbl" -> sb.append("</table>")
                    "tr" -> sb.append("</tr>")
                    "tc" -> sb.append("</td>")
                    "p" -> sb.append("</p>")
                }
            }
            ev = p.next()
        }
        return if (!hasText) "<p>Dokumen kosong atau tidak berisi teks yang bisa dibaca.</p>" else sb.toString()
    }

    /** "AB12" -> 27 (indeks kolom mulai 0) */
    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return n - 1
    }

    private fun xlsx(input: InputStream): String {
        val files = unzip(input, listOf("xl/sharedStrings.xml", "xl/worksheets/sheet"))
        val shared = ArrayList<String>()
        files["xl/sharedStrings.xml"]?.let {
            val p = parser(it)
            var cur: StringBuilder? = null
            var skip = 0   // lewati rPh (cara baca fonetik)
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
        val sheets = files.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        if (sheets.isEmpty()) return "<p>File XLSX tidak berisi sheet.</p>"
        val sb = StringBuilder()
        var anyCell = false
        sheets.forEachIndexed { idx, name ->
            sb.append("<h3>Sheet ${idx + 1}</h3><div style='overflow-x:auto'><table>")
            val p = parser(files[name]!!)
            var type = ""
            var col = 0
            var rows = 0
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (p.local()) {
                    "row" -> { sb.append("<tr>"); col = 0; rows++ }
                    "c" -> {
                        type = p.getAttributeValue(null, "t") ?: ""
                        val ref = p.getAttributeValue(null, "r")
                        if (ref != null) {
                            val target = colIndex(ref)
                            while (col < target && col < 500) { sb.append("<td></td>"); col++ }
                        }
                    }
                    "v" -> {
                        val v = p.nextText()
                        val text = if (type == "s") shared.getOrElse(v.toIntOrNull() ?: -1) { "" } else v
                        sb.append("<td>").append(esc(text)).append("</td>"); col++; anyCell = true
                    }
                    "t" -> {   // inline string: <is><t>
                        sb.append("<td>").append(esc(p.nextText())).append("</td>"); col++; anyCell = true
                    }
                } else if (ev == XmlPullParser.END_TAG && p.local() == "row") sb.append("</tr>")
                if (rows > 5000) { sb.append("<tr><td>... (dipotong, terlalu banyak baris)</td></tr>"); break }
                ev = p.next()
            }
            sb.append("</table></div>")
        }
        return if (!anyCell) "<p>Spreadsheet kosong atau tidak berisi data yang bisa dibaca.</p>" else sb.toString()
    }

    private fun pptx(input: InputStream): String {
        val files = unzip(input, listOf("ppt/slides/slide"))
        val slides = files.keys.filter { it.endsWith(".xml") }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        if (slides.isEmpty()) return "<p>File PPTX tidak berisi slide.</p>"
        val sb = StringBuilder()
        var hasText = false
        slides.forEachIndexed { i, name ->
            sb.append("<div class='slide'><h3>Slide ${i + 1}</h3>")
            val p = parser(files[name]!!)
            var skip = 0
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (skip > 0) {
                    if (ev == XmlPullParser.START_TAG) skip++ else if (ev == XmlPullParser.END_TAG) skip--
                } else if (ev == XmlPullParser.START_TAG) {
                    when (p.local()) {
                        "Fallback" -> skip = 1
                        "p" -> sb.append("<p>")
                        "t" -> { val t = p.nextText(); if (t.isNotBlank()) hasText = true; sb.append(esc(t)) }
                    }
                } else if (ev == XmlPullParser.END_TAG && p.local() == "p") sb.append("</p>")
                ev = p.next()
            }
            sb.append("</div>")
        }
        return if (!hasText) "<p>Presentasi tidak berisi teks yang bisa dibaca.</p>" else sb.toString()
    }
}
