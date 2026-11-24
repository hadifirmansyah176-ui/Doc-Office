package com.hadi.docviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

/**
 * Satu perubahan teks pada PDF. Semua posisi berupa pecahan 0..1 dari lebar/tinggi halaman.
 * fontFrac & base terisi kalau berasal dari ketukan pada teks asli (ukuran huruf + garis dasar diketahui).
 */
data class PdfEdit(
    val page: Int, val l: Float, val t: Float, val r: Float, val b: Float, val text: String,
    val fontFrac: Float = 0f, val base: Float = -1f
)

/** Satu halaman PDF. Mode edit: ketuk tulisan untuk mengubahnya, atau tekan-tahan lalu seret untuk memilih area bebas. */
class PageView(ctx: Context) : AppCompatImageView(ctx) {
    init { scaleType = android.widget.ImageView.ScaleType.FIT_XY }

    var editMode = false
    var edits: List<PdfEdit> = emptyList()
    var onArea: ((Float, Float, Float, Float) -> Unit)? = null   // area bebas (pecahan halaman)
    var onTap: ((Float, Float) -> Unit)? = null                  // ketukan (pecahan halaman)

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val white = Paint().apply { color = Color.WHITE }
    private val black = Paint().apply { color = Color.BLACK; isAntiAlias = true }
    private val frame = Paint().apply { color = Color.BLUE; style = Paint.Style.STROKE; strokeWidth = 4f }
    private var sx = 0f; private var sy = 0f; private var cx = 0f; private var cy = 0f
    private var dragging = false
    private var moved = false
    private val longPress = Runnable {
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (e in edits) {
            val l = e.l * width; val t = e.t * height; val r = e.r * width; val b = e.b * height
            canvas.drawRect(l, t, r, b, white)
            if (e.fontFrac > 0f && e.base >= 0f) {
                black.textSize = e.fontFrac * height
                canvas.drawText(e.text, l, e.base * height, black)
            } else {
                black.textSize = (b - t) * 0.75f
                canvas.drawText(e.text, l, b - (b - t) * 0.25f, black)
            }
        }
        if (dragging) canvas.drawRect(minOf(sx, cx), minOf(sy, cy), maxOf(sx, cx), maxOf(sy, cy), frame)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!editMode) return false
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                sx = ev.x; sy = ev.y; cx = sx; cy = sy; dragging = false; moved = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(ev.x - sx) > slop || abs(ev.y - sy) > slop)) {
                    moved = true
                    if (!dragging) removeCallbacks(longPress)
                }
                if (dragging) { cx = ev.x; cy = ev.y; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging) {
                    dragging = false
                    val l = (minOf(sx, cx) / width).coerceIn(0f, 1f)
                    val r = (maxOf(sx, cx) / width).coerceIn(0f, 1f)
                    val t = (minOf(sy, cy) / height).coerceIn(0f, 1f)
                    val b = (maxOf(sy, cy) / height).coerceIn(0f, 1f)
                    if (r - l > 0.01f && b - t > 0.005f) onArea?.invoke(l, t, r, b)
                    invalidate()
                } else if (!moved && width > 0 && height > 0) {
                    onTap?.invoke(ev.x / width, ev.y / height)
                }
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); dragging = false; invalidate() }
        }
        return true
    }
}
