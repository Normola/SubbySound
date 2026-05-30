package com.subbysound

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class SpectrogramView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onSelectionChanged: ((lowHz: Float, highHz: Float) -> Unit)? = null
    var onSelectionCleared: (() -> Unit)? = null

    private var sampleRate = AudioConfig.SAMPLE_RATE
    private val fftSize = AudioConfig.FFT_SIZE

    // Color LUT: maps [0,255] to ARGB
    private val colorLut = buildColorLut()

    // Ring-buffer based scrolling spectrogram
    private var spectBitmap: Bitmap? = null
    private var pixelBuf: IntArray = IntArray(0)
    private var bmpWidth = 0
    private var bmpHeight = 0
    private var writeCol = 0  // Next column to overwrite

    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Axis labels
    private val labelPaint = Paint().apply {
        color = Color.WHITE; textSize = 26f; isAntiAlias = true; isFakeBoldText = false
    }
    private val tickPaint = Paint().apply {
        color = Color.argb(140, 200, 200, 255); strokeWidth = 1f
    }

    // Selection
    private var selAnchorY = -1f
    private var selCurrentY = -1f
    var hasSelection = false
        private set
    private val selFillPaint = Paint().apply {
        color = Color.argb(70, 255, 220, 40); style = Paint.Style.FILL
    }
    private val selStrokePaint = Paint().apply {
        color = Color.argb(210, 255, 220, 40); strokeWidth = 2.5f; style = Paint.Style.STROKE
    }

    private val axisW = 76f

    // Pre-allocated objects reused across onDraw calls (avoids DrawAllocation lint error)
    private val axisBgPaint = Paint().apply { color = Color.BLACK }
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val selRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        bmpWidth = (w - axisW).toInt().coerceAtLeast(1)
        bmpHeight = h.coerceAtLeast(1)
        spectBitmap?.recycle()
        spectBitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        pixelBuf = IntArray(bmpWidth * bmpHeight)
        writeCol = 0
    }

    fun setSampleRate(rate: Int) { sampleRate = rate }

    fun addFrame(magnitudesDb: FloatArray) {
        val bh = bmpHeight; val bw = bmpWidth
        if (bw == 0 || bh == 0) return
        val maxHz = sampleRate / 2f
        val freqRes = sampleRate.toFloat() / fftSize

        // Write one new column into ring buffer
        for (y in 0 until bh) {
            val hz = (1f - y.toFloat() / bh) * maxHz
            val bin = (hz / freqRes).toInt().coerceIn(0, magnitudesDb.size - 1)
            val t = ((magnitudesDb[bin] + 80f) / 80f).coerceIn(0f, 1f)
            pixelBuf[y * bw + writeCol] = colorLut[(t * 255).toInt()]
        }
        writeCol = (writeCol + 1) % bw

        // Push updated pixels to bitmap
        spectBitmap?.setPixels(pixelBuf, 0, bw, 0, 0, bw, bh)
        postInvalidate()
    }

    fun clearSpectrogram() {
        pixelBuf.fill(Color.BLACK)
        spectBitmap?.setPixels(pixelBuf, 0, bmpWidth, 0, 0, bmpWidth, bmpHeight)
        writeCol = 0
        val hadSelection = hasSelection
        hasSelection = false
        if (hadSelection) onSelectionCleared?.invoke()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()

        // Draw axis background
        canvas.drawRect(0f, 0f, axisW, h, axisBgPaint)

        val bmp = spectBitmap
        if (bmp != null && bmpWidth > 0) {
            val leftPart = bmpWidth - writeCol
            val rightPart = writeCol
            val spectX = axisW
            val pixPerCol = (w - axisW) / bmpWidth

            if (leftPart > 0) {
                srcRect.set(writeCol, 0, bmpWidth, bmpHeight)
                dstRect.set(spectX, 0f, spectX + leftPart * pixPerCol, h)
                canvas.drawBitmap(bmp, srcRect, dstRect, drawPaint)
            }
            if (rightPart > 0) {
                srcRect.set(0, 0, writeCol, bmpHeight)
                dstRect.set(spectX + leftPart * pixPerCol, 0f, w, h)
                canvas.drawBitmap(bmp, srcRect, dstRect, drawPaint)
            }
        }

        drawFreqAxis(canvas, h)
        if (hasSelection) drawSelection(canvas, h)
    }

    private fun drawFreqAxis(canvas: Canvas, h: Float) {
        val maxHz = sampleRate / 2f
        val step = when {
            maxHz >= 15000 -> 5000f
            maxHz >= 5000  -> 2000f
            else           -> 1000f
        }
        var hz = 0f
        while (hz <= maxHz) {
            val y = h - hz / maxHz * h
            canvas.drawLine(axisW - 6f, y, axisW, y, tickPaint)
            val lbl = if (hz >= 1000f) "${(hz / 1000).toInt()}k" else "${hz.toInt()}"
            canvas.drawText(lbl, 2f, y + 9f, labelPaint)
            hz += step
        }
    }

    private fun drawSelection(canvas: Canvas, h: Float) {
        val top = minOf(selAnchorY, selCurrentY)
        val bot = maxOf(selAnchorY, selCurrentY)
        selRect.set(axisW, top, width.toFloat(), bot)
        canvas.drawRect(selRect, selFillPaint)
        canvas.drawRect(selRect, selStrokePaint)

        val loHz = yToHz(bot, h)
        val hiHz = yToHz(top, h)
        val loLbl = formatHz(loHz); val hiLbl = formatHz(hiHz)
        val ty = (top - 8f).coerceAtLeast(labelPaint.textSize + 2f)
        canvas.drawText("$loLbl – $hiLbl", axisW + 6f, ty, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val h = height.toFloat()
        val y = event.y.coerceIn(0f, h)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                selAnchorY = y; selCurrentY = y; hasSelection = false; invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                selCurrentY = y
                if (abs(selCurrentY - selAnchorY) > 12f) {
                    hasSelection = true
                    notify(h)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!hasSelection) {
                    performClick()
                }
                invalidate()
            }
        }
        return true
    }

    private fun notify(h: Float) {
        val loHz = yToHz(maxOf(selAnchorY, selCurrentY), h)
        val hiHz = yToHz(minOf(selAnchorY, selCurrentY), h)
        onSelectionChanged?.invoke(loHz, hiHz)
    }

    override fun performClick(): Boolean {
        selAnchorY = -1f
        selCurrentY = -1f
        hasSelection = false
        onSelectionCleared?.invoke()
        invalidate()
        super.performClick()
        return true
    }

    fun clearSelection() {
        hasSelection = false; selAnchorY = -1f; selCurrentY = -1f
        onSelectionCleared?.invoke(); invalidate()
    }

    fun getSelectionHz(): Pair<Float, Float>? {
        if (!hasSelection) return null
        val h = height.toFloat()
        return Pair(yToHz(maxOf(selAnchorY, selCurrentY), h), yToHz(minOf(selAnchorY, selCurrentY), h))
    }

    private fun yToHz(y: Float, h: Float) = ((1f - y / h) * (sampleRate / 2f)).coerceIn(0f, sampleRate / 2f)
    private fun formatHz(hz: Float) = if (hz >= 1000f) "%.1f kHz".format(hz / 1000) else "%.0f Hz".format(hz)

    private fun buildColorLut(): IntArray {
        data class Stop(val t: Float, val r: Float, val g: Float, val b: Float)
        val stops = listOf(
            Stop(0.00f,   0f,   0f,  20f),
            Stop(0.15f,   0f,   0f, 160f),
            Stop(0.30f,   0f, 120f, 255f),
            Stop(0.50f,   0f, 255f, 210f),
            Stop(0.65f, 200f, 255f,   0f),
            Stop(0.80f, 255f, 100f,   0f),
            Stop(0.92f, 255f,   0f,   0f),
            Stop(1.00f, 255f, 255f, 255f)
        )
        return IntArray(256) { idx ->
            val t = idx / 255f
            var s = 0
            while (s < stops.size - 2 && t > stops[s + 1].t) s++
            val s0 = stops[s]; val s1 = stops[s + 1]
            val f = if (s1.t > s0.t) (t - s0.t) / (s1.t - s0.t) else 0f
            Color.argb(255,
                (s0.r + f * (s1.r - s0.r)).toInt().coerceIn(0, 255),
                (s0.g + f * (s1.g - s0.g)).toInt().coerceIn(0, 255),
                (s0.b + f * (s1.b - s0.b)).toInt().coerceIn(0, 255))
        }
    }
}
