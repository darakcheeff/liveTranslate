package com.livetranslate.phone.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class WaveformVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = 0xFF4285F4.toInt()
        strokeWidth = 6f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private var rmsLevel: Float = 0f
    private val bars = FloatArray(16)

    fun updateRms(rms: Float) {
        this.rmsLevel = rms.coerceIn(0f, 3000f)
        for (i in 0 until bars.size - 1) {
            bars[i] = bars[i + 1]
        }
        bars[bars.size - 1] = rmsLevel / 3000f
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f
        val step = w / (bars.size + 1)

        for (i in bars.indices) {
            val barHeight = (bars[i] * (h * 0.8f)).coerceAtLeast(8f)
            val x = (i + 1) * step
            canvas.drawLine(x, centerY - barHeight / 2, x, centerY + barHeight / 2, paint)
        }
    }
}
