package com.hadi.docviewer

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout

/**
 * Menangkap cubitan dua jari (pinch). Selama cubitan, anak (child) hanya diskalakan secara visual;
 * saat jari dilepas, [onZoomEnd] dipanggil agar isi dirender ulang dengan ukuran baru (tetap tajam).
 */
class ZoomLayout(ctx: Context) : FrameLayout(ctx) {
    var currentZoom = 1f
    var minZoom = 1f
    var maxZoom = 4f
    var onZoomEnd: ((factor: Float, fx: Float, fy: Float) -> Unit)? = null

    private var gestureScale = 1f

    private val detector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { gestureScale = 1f; return true }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            gestureScale = (gestureScale * d.scaleFactor)
                .coerceIn(minZoom / currentZoom, maxZoom / currentZoom)
            getChildAt(0)?.let {
                it.pivotX = d.focusX; it.pivotY = d.focusY
                it.scaleX = gestureScale; it.scaleY = gestureScale
            }
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            getChildAt(0)?.let { it.scaleX = 1f; it.scaleY = 1f }
            onZoomEnd?.invoke(gestureScale, d.focusX, d.focusY)
        }
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return ev.pointerCount > 1 || detector.isInProgress
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return true
    }
}
