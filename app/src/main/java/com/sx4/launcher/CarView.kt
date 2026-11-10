package com.sx4.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Fondo con tus fotos reales (sx4_foto_01, sx4_foto_02, ...), acercadas.
 * Al deslizar el dedo cambia de una foto a la otra con fundido y se queda
 * en la foto mas cercana. De noche se encienden los faros.
 */
class CarView(ctx: Context) : View(ctx) {

    private class L(val x: Float, val y: Float, val r: Float)

    private val frames = ArrayList<Bitmap>()
    private val zoom = 1.30f

    // centro del coche en cada foto (fraccion del ancho/alto de la imagen)
    private val anchors = arrayOf(floatArrayOf(0.490f, 0.478f), floatArrayOf(0.528f, 0.478f))

    // posicion de faros por foto (fraccion del ancho/alto de la imagen)
    private val lights: List<List<L>> = listOf(
        listOf(L(0.435f, 0.475f, 0.075f), L(0.274f, 0.478f, 0.060f), L(0.420f, 0.600f, 0.035f)),
        listOf(L(0.652f, 0.478f, 0.075f), L(0.668f, 0.600f, 0.035f))
    )

    var autoRotate = false
        set(v) { field = v; postInvalidateOnAnimation() }

    private var nightTarget = 0f
    private var nightAmt = 0f
    private var pos = 0f
    private var spin = 0f
    private var dragging = false
    private var lastX = 0f
    private var lastT = 0L
    private var lastFrame = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrim = Paint()
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val screen = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    private val dst = RectF()
    private var shL: Shader? = null
    private var shR: Shader? = null
    private var shB: Shader? = null

    init {
        for (i in 1..120) {
            val id = resources.getIdentifier("sx4_foto_%02d".format(i), "drawable", context.packageName)
            if (id == 0) break
            val o = BitmapFactory.Options()
            o.inPreferredConfig = Bitmap.Config.ARGB_8888
            val b = BitmapFactory.decodeResource(resources, id, o)
            if (b != null) frames.add(b)
        }
    }

    fun setNight(n: Boolean) {
        nightTarget = if (n) 1f else 0f
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val dark = 0xB0000000.toInt()
        shL = LinearGradient(0f, 0f, w * 0.34f, 0f, dark, 0x00000000, Shader.TileMode.CLAMP)
        shR = LinearGradient(w.toFloat(), 0f, w * 0.64f, 0f, dark, 0x00000000, Shader.TileMode.CLAMP)
        shB = LinearGradient(0f, h.toFloat(), 0f, h * 0.76f, dark, 0x00000000, Shader.TileMode.CLAMP)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastX = e.x
                lastT = e.eventTime
                spin = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
                postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val dx = e.x - lastX
                val n = max(1, frames.size)
                val ppf = max(px(10f), width * 0.6f / n)
                val df = -dx / ppf
                pos += df
                val dt = max(1L, e.eventTime - lastT) / 1000f
                spin = spin * 0.5f + (df / dt) * 0.5f
                lastX = e.x
                lastT = e.eventTime
                postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                spin = (spin * 0.35f).coerceIn(-6f, 6f)
                postInvalidateOnAnimation()
            }
        }
        return true
    }

    /** Coloca la foto acercada y centrada en el coche. */
    private fun layout(idx: Int, w: Float, h: Float) {
        val b = frames[idx]
        val bw = b.width.toFloat()
        val bh = b.height.toFloat()
        val sc = max(w / bw, h / bh) * zoom
        val dw = bw * sc
        val dh = bh * sc
        val an = anchors.getOrNull(idx) ?: floatArrayOf(0.5f, 0.5f)
        var left = w * 0.5f - an[0] * dw
        var top = h * 0.49f - an[1] * dh
        left = left.coerceIn(w - dw, 0f)
        top = top.coerceIn(h - dh, 0f)
        dst.set(left, top, left + dw, top + dh)
    }

    private fun drawFrame(c: Canvas, idx: Int, alpha: Int, w: Float, h: Float) {
        layout(idx, w, h)
        paint.alpha = alpha
        val a = nightAmt
        val k = 0.82f
        val cm = ColorMatrix(floatArrayOf(
            k - 0.52f * a, 0f, 0f, 0f, 0f,
            0f, k - 0.48f * a, 0f, 0f, 0f,
            0f, 0f, k - 0.32f * a, 0f, 10f * a,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        c.drawBitmap(frames[idx], null, dst, paint)

        if (nightAmt > 0.01f) {
            val list = lights.getOrNull(idx) ?: return
            val ga = (alpha * nightAmt).toInt().coerceIn(0, 255)
            glow.xfermode = screen
            for (l in list) {
                val cx = dst.left + l.x * dst.width()
                val cy = dst.top + l.y * dst.height()
                val rad = l.r * dst.width()
                glow.shader = RadialGradient(
                    cx, cy, rad,
                    intArrayOf(Color.argb(ga, 255, 246, 220), Color.argb(ga * 4 / 10, 255, 240, 200), Color.argb(0, 255, 240, 200)),
                    floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP
                )
                c.drawCircle(cx, cy, rad, glow)
            }
            glow.shader = null
            glow.xfermode = null
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame).coerceIn(1L, 60L) / 1000f
        lastFrame = now
        var animating = false

        if (nightAmt != nightTarget) {
            val d = nightTarget - nightAmt
            nightAmt += if (d > 0) min(d, dt * 1.6f) else -min(-d, dt * 1.6f)
            animating = true
        }

        txt.textAlign = Paint.Align.CENTER
        if (frames.isEmpty()) {
            txt.color = Color.WHITE
            txt.textSize = px(20f)
            c.drawText("Faltan las fotos sx4_foto_01.jpg y sx4_foto_02.jpg", w / 2f, h / 2f, txt)
            return
        }

        val n = frames.size
        if (!dragging) {
            if (abs(spin) > 0.02f) {
                pos += spin * dt
                spin *= 0.92f.pow(dt * 60f)
                animating = true
            } else {
                spin = 0f
            }
            if (autoRotate && abs(spin) <= 0.02f) {
                pos += 0.30f * dt
                animating = true
            } else if (!autoRotate && abs(spin) < 0.8f) {
                // se acomoda en la foto mas cercana
                val target = pos.roundToInt().toFloat()
                val diff = target - pos
                if (abs(diff) > 0.002f) {
                    pos += diff * min(1f, dt * 8f)
                    animating = true
                } else {
                    pos = target
                }
            }
        } else {
            animating = true
        }

        val p = ((pos % n) + n) % n
        val i0 = floor(p).toInt() % n
        val fr = p - floor(p)
        val i1 = (i0 + 1) % n
        drawFrame(c, i0, 255, w, h)
        if (n > 1 && fr > 0.01f) drawFrame(c, i1, (fr * 255).toInt(), w, h)

        // sombras en los bordes para que los paneles flotantes se lean bien
        scrim.shader = shL
        c.drawRect(0f, 0f, w * 0.34f, h, scrim)
        scrim.shader = shR
        c.drawRect(w * 0.64f, 0f, w, h, scrim)
        scrim.shader = shB
        c.drawRect(0f, h * 0.76f, w, h, scrim)

        txt.color = 0x99FFFFFF.toInt()
        txt.textSize = px(15f)
        c.drawText("Desliza para cambiar de posicion", w / 2f, px(30f), txt)

        if (animating) postInvalidateOnAnimation()
    }
}
