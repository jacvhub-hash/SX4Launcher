package com.sx4.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Ecualizador. Las barras SOLO se mueven con datos FFT reales del audio
 * (android.media.audiofx.Visualizer). Sin audio, las barras bajan a cero.
 */
class EqView(ctx: Context) : View(ctx) {

    private val n = 28
    private val target = FloatArray(n)
    private val level = FloatArray(n)
    private val peak = FloatArray(n)
    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: Shader? = null

    var accent: Int = Color.parseColor("#FFC9D3DD")
        set(v) { field = v; shader = null; invalidate() }

    /** Se llama (en otro hilo) con la intensidad de graves 0..1. */
    var onBass: ((Float) -> Unit)? = null

    /** Datos FFT del Visualizer: [0]=DC, [1]=Nyquist, luego pares re/im. */
    fun feed(fft: ByteArray, samplingRateMilliHz: Int) {
        val size = fft.size
        val bins = size / 2
        val sr = max(8000f, samplingRateMilliHz / 1000f)
        for (i in 0 until n) {
            val f0 = 40f * (12000f / 40f).pow(i.toFloat() / n)
            val f1 = 40f * (12000f / 40f).pow((i + 1).toFloat() / n)
            var k0 = Math.round(f0 * size / sr)
            var k1 = Math.round(f1 * size / sr)
            if (k0 < 1) k0 = 1
            if (k1 <= k0) k1 = k0 + 1
            if (k1 > bins - 1) k1 = bins - 1
            if (k0 > k1) k0 = k1
            var m = 0f
            for (k in k0..k1) {
                val re = fft[2 * k].toInt().toFloat()
                val im = fft[2 * k + 1].toInt().toFloat()
                m = max(m, sqrt(re * re + im * im))
            }
            val tilt = 1f + i * 0.035f
            val v = min(1f, sqrt(m * tilt / 90f))
            if (v > target[i]) target[i] = v
        }
        var bass = 0f
        for (i in 0 until 4) bass += target[i]
        onBass?.invoke(bass / 4f)
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        shader = null
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (shader == null) {
            shader = LinearGradient(0f, h, 0f, 0f,
                intArrayOf(accent, 0xFFFFFFFF.toInt()), floatArrayOf(0.2f, 1f), Shader.TileMode.CLAMP)
        }
        paint.shader = shader
        val gap = px(4f)
        val bw = (w - gap * (n - 1)) / n
        var active = false
        for (i in 0 until n) {
            level[i] += (target[i] - level[i]) * (if (target[i] > level[i]) 0.6f else 0.18f)
            target[i] *= 0.90f
            if (level[i] > peak[i]) peak[i] = level[i] else peak[i] = max(0f, peak[i] - 0.012f)
            if (level[i] > 0.004f || target[i] > 0.004f || peak[i] > 0.004f) active = true
            val bh = max(px(4f), level[i] * h)
            val x = i * (bw + gap)
            rect.set(x, h - bh, x + bw, h)
            c.drawRoundRect(rect, bw / 2f, bw / 2f, paint)
            val py = h - max(px(8f), peak[i] * h) - px(5f)
            paint.shader = null
            paint.color = 0xAAFFFFFF.toInt()
            c.drawRect(x, py, x + bw, py + px(3f), paint)
            paint.shader = shader
        }
        if (active) postInvalidateOnAnimation()
    }
}
