package com.hadi.docviewer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Parser ringan untuk .docx / .xlsx / .pptx -> HTML (tanpa Apache POI, aman untuk Android 5). */
object OfficeParser {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unzip(input: InputStream, prefixes: List<String>): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (prefixes.any { e!!.name.startsWith(it) }) map[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        return map
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
            "<p>Gagal membaca file: ${esc(e.message ?: e.javaClass.simpleName)}</p>"
        }
        return "<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                "<style>body{font-family:sans-serif;padding:12px;line-height:1.5}" +
                "table{border-collapse:collapse;margin:8px 0}td{border:1px solid #999;padding:4px 8px;vertical-align:top}" +
                ".slide{border:1px solid #bbb;border-radius:8px;padding:12px;margin:16px 0;background:#fafafa}" +
                "h3{color:#555}</style></head><body>$body</body></html>"
    }

    private fun docx(input: InputStream): String {
        val files = unzip(input, listOf("word/document.xml"))
        val xml = files["word/document.xml"] ?: return "<p>File DOCX tidak valid.</p>"
        val p = parser(xml)
        val sb = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:tbl" -> sb.append("<table>")
                    "w:tr" -> sb.append("<tr>")
                    "w:tc" -> sb.append("<td>")
                    "w:p" -> sb.append("<p>")
                    "w:tab" -> sb.append("&emsp;")
                    "w:br" -> sb.append("<br>")
                    "w:t" -> sb.append(esc(p.nextText()))
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:tbl" -> sb.append("</table>")
                    "w:tr" -> sb.append("</tr>")
                    "w:tc" -> sb.append("</td>")
                    "w:p" -> sb.append("</p>")
                }
            }
            ev = p.next()
        }
        return sb.toString()
    }

    private fun xlsx(input: InputStream): String {
        val files = unzip(input, listOf("xl/sharedStrings.xml", "xl/worksheets/sheet"))
        val shared = ArrayList<String>()
        files["xl/sharedStrings.xml"]?.let {
            val p = parser(it)
            var cur: StringBuilder? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    if (p.name == "si") cur = StringBuilder()
                    else if (p.name == "t") cur?.append(p.nextText())
                } else if (ev == XmlPullParser.END_TAG && p.name == "si") {
                    shared.add(cur.toString()); cur = null
                }
                ev = p.next()
            }
        }
        val sheets = files.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val sb = StringBuilder()
        sheets.forEachIndexed { idx, name ->
            sb.append("<h3>Sheet ${idx + 1}</h3><div style='overflow-x:auto'><table>")
            val p = parser(files[name]!!)
            var type = ""
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (p.name) {
                    "row" -> sb.append("<tr>")
                    "c" -> type = p.getAttributeValue(null, "t") ?: ""
                    "v" -> {
                        val v = p.nextText()
                        val text = if (type == "s") shared.getOrElse(v.toIntOrNull() ?: -1) { "" } else v
                        sb.append("<td>").append(esc(text)).append("</td>")
                    }
                    "is" -> {} // inline string
                    "t" -> sb.append("<td>").append(esc(p.nextText())).append("</td>")
                } else if (ev == XmlPullParser.END_TAG && p.name == "row") sb.append("</tr>")
                ev = p.next()
            }
            sb.append("</table></div>")
        }
        return sb.toString()
    }

    private fun pptx(input: InputStream): String {
        val files = unzip(input, listOf("ppt/slides/slide"))
        val slides = files.keys.filter { it.endsWith(".xml") }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val sb = StringBuilder()
        slides.forEachIndexed { i, name ->
            sb.append("<div class='slide'><h3>Slide ${i + 1}</h3>")
            val p = parser(files[name]!!)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "a:p") sb.append("<p>")
                else if (ev == XmlPullParser.START_TAG && p.name == "a:t") sb.append(esc(p.nextText()))
                else if (ev == XmlPullParser.END_TAG && p.name == "a:p") sb.append("</p>")
                ev = p.next()
            }
            sb.append("</div>")
        }
        return sb.toString()
    }
}
