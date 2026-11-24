package com.hadi.docviewer

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.TreeMap

/** Pembaca teks untuk format biner lama: .doc (Word 97-2003), .xls (BIFF8), .ppt (PowerPoint 97-2003). */
object LegacyOffice {

    private val CP1252 = charset("windows-1252")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun buf(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    // =====================================================================
    // DOC
    // =====================================================================
    fun doc(ole: OleReader): String {
        val wd = ole.stream("WordDocument") ?: return "<p>File DOC tidak valid.</p>"
        val w = buf(wd)
        val flags = w.getShort(0x0A).toInt()
        if ((flags and 0x0100) != 0) return "<p>File DOC berpassword / terenkripsi, belum didukung.</p>"
        val table = ole.stream(if ((flags and 0x0200) != 0) "1Table" else "0Table")
            ?: return "<p>File DOC tidak valid (tabel tidak ditemukan).</p>"
        val ccpText = w.getInt(0x4C)
        val fcClx = w.getInt(0x1A2)
        val lcbClx = w.getInt(0x1A6)
        if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > table.size)
            return "<p>Struktur DOC tidak dikenali (mungkin versi sangat lama).</p>"

        val t = buf(table)
        val text = StringBuilder()
        var pos = fcClx
        val end = fcClx + lcbClx
        while (pos < end) {
            val kind = table[pos].toInt() and 0xFF
            if (kind == 1) {
                pos += 3 + (t.getShort(pos + 1).toInt() and 0xFFFF)
            } else if (kind == 2) {
                val lcb = t.getInt(pos + 1)
                val plc = pos + 5
                val n = (lcb - 4) / 12
                for (i in 0 until n) {
                    val cpS = t.getInt(plc + 4 * i)
                    if (cpS >= ccpText) break
                    val cpE = minOf(t.getInt(plc + 4 * (i + 1)), ccpText)
                    val len = cpE - cpS
                    if (len <= 0) continue
                    val fc = t.getInt(plc + 4 * (n + 1) + 8 * i + 2)
                    val real = fc and 0x3FFFFFFF
                    if ((fc and 0x40000000) != 0) {
                        val off = real / 2
                        if (off >= 0 && off + len <= wd.size) text.append(String(wd, off, len, CP1252))
                    } else {
                        if (real >= 0 && real + len * 2 <= wd.size) text.append(String(wd, real, len * 2, Charsets.UTF_16LE))
                    }
                }
                break
            } else break
        }
        if (text.isEmpty()) return "<p>Dokumen kosong atau teks tidak terbaca.</p>"
        return docTextToHtml(text)
    }

    private fun docTextToHtml(text: CharSequence): String {
        val sb = StringBuilder("<p>")
        val field = ArrayList<Boolean>()   // true = sedang di dalam kode field (disembunyikan)
        var prev = ' '
        for (ch in text) {
            when (ch.code) {
                0x13 -> field.add(true)
                0x14 -> if (field.isNotEmpty()) field[field.lastIndex] = false
                0x15 -> if (field.isNotEmpty()) field.removeAt(field.lastIndex)
                else -> if (field.none { it }) when (ch.code) {
                    0x0D -> sb.append("</p><p>")
                    0x07 -> sb.append(if (prev.code == 0x07) "<br>" else " &nbsp;|&nbsp; ")
                    0x0B -> sb.append("<br>")
                    0x09 -> sb.append("&emsp;")
                    0x0C -> sb.append("<hr>")
                    '<'.code -> sb.append("&lt;")
                    '>'.code -> sb.append("&gt;")
                    '&'.code -> sb.append("&amp;")
                    else -> if (ch.code >= 32) sb.append(ch)
                }
            }
            prev = ch
        }
        return sb.append("</p>").toString()
    }

    // =====================================================================
    // XLS (BIFF8)
    // =====================================================================
    private class Segs(val segs: List<ByteArray>) {
        var si = 0
        var pi = 0
        fun u8(): Int {
            while (si < segs.size && pi >= segs[si].size) { si++; pi = 0 }
            if (si >= segs.size) throw EOFException()
            return segs[si][pi++].toInt() and 0xFF
        }
        fun u16() = u8() or (u8() shl 8)
        fun u32() = u16() or (u16() shl 16)
        fun skip(n: Int) { var k = n; while (k > 0) { u8(); k-- } }
    }

    private fun parseSst(segs: List<ByteArray>, out: MutableList<String>) {
        val r = Segs(segs)
        try {
            r.u32()                       // total
            val unique = r.u32()
            for (n in 0 until unique) {
                var remaining = r.u16()
                val grbit = r.u8()
                val runs = if ((grbit and 0x08) != 0) r.u16() else 0
                val ext = if ((grbit and 0x04) != 0) r.u32() else 0
                var compressed = (grbit and 1) == 0
                val sb = StringBuilder()
                while (remaining > 0) {
                    if (r.pi >= r.segs[r.si].size) {          // batas CONTINUE: ada byte flag baru
                        r.si++; r.pi = 0
                        if (r.si >= r.segs.size) break
                        compressed = (r.u8() and 1) == 0
                    }
                    sb.append(if (compressed) r.u8().toChar() else r.u16().toChar())
                    remaining--
                }
                r.skip(runs * 4 + ext)
                out.add(sb.toString())
            }
        } catch (_: EOFException) { /* pakai string yang sudah terbaca */ }
    }

    private val numFmt = DecimalFormat("0.##########", DecimalFormatSymbols(Locale.US))
    private fun num(d: Double): String =
        if (d.isNaN() || d.isInfinite()) d.toString() else numFmt.format(d)

    private fun rk(v: Int): Double {
        var d = if ((v and 2) != 0) (v shr 2).toDouble()
                else Double.fromBits((v.toLong() and 0xFFFFFFFCL) shl 32)
        if ((v and 1) != 0) d /= 100
        return d
    }

    fun xls(ole: OleReader): String {
        val wb = ole.stream("Workbook") ?: return if (ole.stream("Book") != null)
            "<p>File XLS versi sangat lama (Excel 5/95) belum didukung.</p>"
        else "<p>File XLS tidak valid.</p>"
        val b = buf(wb)
        val names = ArrayList<String>()
        val sst = ArrayList<String>()
        val sheets = ArrayList<TreeMap<Int, TreeMap<Int, String>>>()
        var cur: TreeMap<Int, TreeMap<Int, String>>? = null
        var pendR = -1
        var pendC = -1

        fun u16(o: Int) = b.getShort(o).toInt() and 0xFFFF
        fun put(r: Int, c: Int, s: String) { cur?.getOrPut(r) { TreeMap() }?.put(c, s) }
        fun str(o: Int, cch: Int, grbit: Int): String =
            if ((grbit and 1) == 0) String(wb, o, cch, CP1252) else String(wb, o, cch * 2, Charsets.UTF_16LE)

        var pos = 0
        while (pos + 4 <= wb.size) {
            val type = u16(pos)
            val len = u16(pos + 2)
            val d = pos + 4
            if (d + len > wb.size) break
            when (type) {
                0x002F -> return "<p>File XLS berpassword / terenkripsi, belum didukung.</p>"
                0x0085 -> if (wb[d + 5].toInt() == 0) {          // hanya worksheet biasa
                    val cch = wb[d + 6].toInt() and 0xFF
                    names.add(str(d + 8, cch, wb[d + 7].toInt()))
                }
                0x00FC -> {                                        // SST + CONTINUE
                    val segs = ArrayList<ByteArray>()
                    segs.add(wb.copyOfRange(d, d + len))
                    var q = d + len
                    while (q + 4 <= wb.size && u16(q) == 0x003C) {
                        val l2 = u16(q + 2)
                        if (q + 4 + l2 > wb.size) break
                        segs.add(wb.copyOfRange(q + 4, q + 4 + l2))
                        q += 4 + l2
                    }
                    parseSst(segs, sst)
                    pos = q
                    continue
                }
                0x0809 -> if (u16(d + 2) == 0x0010) {              // BOF worksheet
                    cur = TreeMap(); sheets.add(cur!!)
                }
                0x00FD -> put(u16(d), u16(d + 2), sst.getOrElse(b.getInt(d + 6)) { "" })
                0x0203 -> put(u16(d), u16(d + 2), num(b.getDouble(d + 6)))
                0x027E -> put(u16(d), u16(d + 2), num(rk(b.getInt(d + 6))))
                0x00BD -> {
                    val r = u16(d); val c1 = u16(d + 2)
                    for (i in 0 until (len - 6) / 6) put(r, c1 + i, num(rk(b.getInt(d + 6 + 6 * i))))
                }
                0x0204 -> {
                    val cch = u16(d + 6)
                    put(u16(d), u16(d + 2), str(d + 9, cch, wb[d + 8].toInt()))
                }
                0x0205 -> put(u16(d), u16(d + 2),
                    if (wb[d + 7].toInt() != 0) "#ERR" else if (wb[d + 6].toInt() != 0) "TRUE" else "FALSE")
                0x0006 -> {
                    val r = u16(d); val c = u16(d + 2)
                    if (wb[d + 12] == 0xFF.toByte() && wb[d + 13] == 0xFF.toByte()) {
                        when (wb[d + 6].toInt()) {
                            0 -> { pendR = r; pendC = c }
                            1 -> put(r, c, if (wb[d + 8].toInt() != 0) "TRUE" else "FALSE")
                            2 -> put(r, c, "#ERR")
                        }
                    } else put(r, c, num(b.getDouble(d + 6)))
                }
                0x0207 -> if (pendR >= 0) {
                    val cch = u16(d)
                    put(pendR, pendC, str(d + 3, cch, wb[d + 2].toInt()))
                    pendR = -1
                }
            }
            pos = d + len
        }

        if (sheets.isEmpty()) return "<p>Tidak ada data yang bisa dibaca di file XLS ini.</p>"
        val sb = StringBuilder()
        sheets.forEachIndexed { i, rows ->
            sb.append("<h3>").append(esc(names.getOrElse(i) { "Sheet ${i + 1}" })).append("</h3>")
            sb.append("<div style='overflow-x:auto'><table>")
            val maxCol = minOf(255, rows.values.maxOfOrNull { if (it.isEmpty()) 0 else it.lastKey() } ?: 0)
            var shown = 0
            for ((_, cols) in rows) {
                if (shown++ >= 5000) { sb.append("<tr><td>... (dipotong, terlalu banyak baris)</td></tr>"); break }
                sb.append("<tr>")
                for (c in 0..maxCol) sb.append("<td>").append(esc(cols[c] ?: "")).append("</td>")
                sb.append("</tr>")
            }
            sb.append("</table></div>")
        }
        return sb.toString()
    }

    // =====================================================================
    // PPT
    // =====================================================================
    private class PptWalker(val s: ByteArray) {
        val b = buf(s)
        val slides = ArrayList<LinkedHashSet<String>>()
        var cur1 = -1   // indeks slide menurut SlideListWithText
        var cur2 = -1   // indeks slide menurut urutan Slide container

        fun add(idx: Int, text: String) {
            if (idx < 0) return
            while (slides.size <= idx) slides.add(LinkedHashSet())
            for (p in text.split('\r', '\u000B')) {
                val t = p.trim()
                if (t.isNotEmpty()) slides[idx].add(t)
            }
        }

        fun walk(start: Int, end: Int, mode: Int) {
            var p = start
            while (p + 8 <= end) {
                val verInst = b.getShort(p).toInt() and 0xFFFF
                val type = b.getShort(p + 2).toInt() and 0xFFFF
                val len = b.getInt(p + 4)
                if (len < 0 || p + 8 + len > end) break
                val body = p + 8
                if ((verInst and 0xF) == 0xF) {
                    when (type) {
                        0x0FF0 -> if ((verInst shr 4) == 0) walk(body, body + len, 1)   // daftar teks slide
                        0x03EE -> { cur2++; walk(body, body + len, 2) }                   // Slide container
                        else -> walk(body, body + len, mode)
                    }
                } else {
                    val idx = if (mode == 1) cur1 else if (mode == 2) cur2 else -1
                    when (type) {
                        0x03F3 -> if (mode == 1) { cur1++; add(cur1, "") }
                        0x0FA0 -> add(idx, String(s, body, len / 2 * 2, Charsets.UTF_16LE))
                        0x0FA8 -> add(idx, String(s, body, len, CP1252))
                    }
                }
                p = body + len
            }
        }
    }

    fun ppt(ole: OleReader): String {
        val s = ole.stream("PowerPoint Document") ?: return "<p>File PPT tidak valid.</p>"
        val w = PptWalker(s)
        w.walk(0, s.size, 0)
        if (w.slides.isEmpty()) return "<p>Tidak ada teks yang bisa dibaca di file PPT ini.</p>"
        val sb = StringBuilder()
        w.slides.forEachIndexed { i, set ->
            sb.append("<div class='slide'><h3>Slide ${i + 1}</h3>")
            for (p in set) sb.append("<p>").append(esc(p)).append("</p>")
            sb.append("</div>")
        }
        return sb.toString()
    }
}
