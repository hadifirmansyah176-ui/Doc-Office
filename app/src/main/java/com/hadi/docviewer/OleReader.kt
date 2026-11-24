package com.hadi.docviewer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pembaca container OLE2 / Compound File (dipakai .doc, .xls, .ppt lama). */
class OleReader(private val d: ByteArray) {
    private class Entry(val name: String, val type: Int, val start: Int, val size: Int)

    private val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
    private var secSize = 512
    private var miniSize = 64
    private var miniCutoff = 4096
    private var fat = IntArray(0)
    private var miniFat = IntArray(0)
    private var miniStream = ByteArray(0)
    private val entries = ArrayList<Entry>()

    init {
        require(d.size > 512 && d[0] == 0xD0.toByte() && d[1] == 0xCF.toByte()) {
            "Bukan file Office lama yang valid"
        }
        secSize = 1 shl bb.getShort(0x1E).toInt()
        miniSize = 1 shl bb.getShort(0x20).toInt()
        miniCutoff = bb.getInt(0x38)

        // --- FAT ---
        val nFat = bb.getInt(0x2C)
        val fatSecs = ArrayList<Int>()
        for (i in 0 until 109) {
            val s = bb.getInt(0x4C + i * 4)
            if (s >= 0 && fatSecs.size < nFat) fatSecs.add(s)
        }
        var difat = bb.getInt(0x44)
        var guard = 0
        while (difat >= 0 && fatSecs.size < nFat && guard++ < 10000) {
            val off = (difat + 1) * secSize
            if (off + secSize > d.size) break
            for (i in 0 until secSize / 4 - 1) {
                val s = bb.getInt(off + i * 4)
                if (s >= 0 && fatSecs.size < nFat) fatSecs.add(s)
            }
            difat = bb.getInt(off + secSize - 4)
        }
        val per = secSize / 4
        fat = IntArray(fatSecs.size * per)
        fatSecs.forEachIndexed { k, s ->
            val off = (s + 1) * secSize
            if (off + secSize <= d.size) for (i in 0 until per) fat[k * per + i] = bb.getInt(off + i * 4)
        }

        // --- Direktori ---
        val dir = fatStream(bb.getInt(0x30))
        val db = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until dir.size / 128) {
            val base = i * 128
            val nameLen = db.getShort(base + 0x40).toInt() and 0xFFFF
            val name = if (nameLen >= 2) String(dir, base, nameLen - 2, Charsets.UTF_16LE) else ""
            entries.add(Entry(name, dir[base + 0x42].toInt(), db.getInt(base + 0x74), db.getInt(base + 0x78)))
        }

        // --- Mini stream ---
        entries.firstOrNull { it.type == 5 }?.let { miniStream = fatStream(it.start) }
        val mf = fatStream(bb.getInt(0x3C))
        val mb = ByteBuffer.wrap(mf).order(ByteOrder.LITTLE_ENDIAN)
        miniFat = IntArray(mf.size / 4) { mb.getInt(it * 4) }
    }

    private fun chain(start: Int, table: IntArray): List<Int> {
        val r = ArrayList<Int>()
        var s = start
        var g = 0
        while (s >= 0 && s < table.size && g++ < 1_000_000) { r.add(s); s = table[s] }
        return r
    }

    private fun fatStream(start: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (s in chain(start, fat)) {
            val o = (s + 1) * secSize
            if (o + secSize > d.size) break
            out.write(d, o, secSize)
        }
        return out.toByteArray()
    }

    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) } ?: return null
        if (e.size < 0) return null
        val raw: ByteArray = if (e.size < miniCutoff) {
            val out = ByteArrayOutputStream()
            for (s in chain(e.start, miniFat)) {
                val o = s * miniSize
                if (o + miniSize > miniStream.size) break
                out.write(miniStream, o, miniSize)
            }
            out.toByteArray()
        } else fatStream(e.start)
        return raw.copyOf(minOf(e.size, raw.size))
    }
}
