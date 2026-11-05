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

/**
 * Coche 3D con el dedo: 8 vistas de tu SX4 (car_01..car_08, recortadas y alineadas)
 * sobre un escenario. Arrastra para girar; con inercia y giro automatico opcional.
 */
class CarView(ctx: Context) : View(ctx) {

    private val frames = ArrayList<Bitmap>()
    private val fw = 840f     // tamano de cada vista (px del PNG)
    private val fh = 330f
    private val baseY = 322f  // linea del suelo dentro de cada vista

    // luces por vista: [tipo, x, y, radio] ... tipo 0=faro, 1=cola, 2=niebla
    private val lightData: Array<FloatArray> = arrayOf(
        floatArrayOf(0f, 293f, 179f, 22f, 0f, 551f, 174f, 24f, 2f, 285f, 258f, 9f, 2f, 553f, 258f, 9f),
        floatArrayOf(0f, 364f, 177f, 28f, 0f, 179f, 179f, 14f, 2f, 342f, 260f, 9f),
        floatArrayOf(0f, 110f, 180f, 15f, 1f, 767f, 133f, 14f),
        floatArrayOf(1f, 464f, 134f, 21f, 1f, 664f, 143f, 11f),
        floatArrayOf(1f, 279f, 141f, 16f, 1f, 559f, 141f, 18f, 1f, 290f, 225f, 8f, 1f, 550f, 225f, 8f),
        floatArrayOf(1f, 400f, 129f, 19f, 1f, 194f, 138f, 11f),
        floatArrayOf(1f, 93f, 132f, 10f, 0f, 737f, 180f, 13f),
        floatArrayOf(0f, 461f, 176f, 30f, 0f, 649f, 180f, 14f, 2f, 478f, 260f, 9f)
    )

    var autoRotate = false
        set(v) { field = v; postInvalidateOnAnimation() }

    private var nightTarget = 0f
    private var nightAmt = 0f
    private var pos = 1f          // arranca en la vista 3/4 frontal
    private var spin = 0f
    private var dragging = false
    private var lastX = 0f
    private var lastT = 0L
    private var lastFrame = 0L

    private val bg = Paint()
    private val carPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint()
    private val scrim = Paint()
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val screen = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    private val dstIn = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    private val rf = RectF()
    private var shL: Shader? = null
    private var shR: Shader? = null
    private var shB: Shader? = null

    init {
        for (i in 1..120) {
            val id = resources.getIdentifier("sx4_v%02d".format(i), "drawable", context.packageName)
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
        shL = LinearGradient(0f, 0f, w * 0.30f, 0f, dark, 0x00000000, Shader.TileMode.CLAMP)
        shR = LinearGradient(w.toFloat(), 0f, w * 0.68f, 0f, dark, 0x00000000, Shader.TileMode.CLAMP)
        shB = LinearGradient(0f, h.toFloat(), 0f, h * 0.80f, dark, 0x00000000, Shader.TileMode.CLAMP)
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
                val ppf = max(px(10f), width * 0.8f / n)
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
                spin = spin.coerceIn(-10f, 10f)
                postInvalidateOnAnimation()
            }
        }
        return true
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = Color.red(a) + (Color.red(b) - Color.red(a)) * t
        val g = Color.green(a) + (Color.green(b) - Color.green(a)) * t
        val bl = Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t
        return Color.rgb(r.toInt(), g.toInt(), bl.toInt())
    }

    private fun drawLights(c: Canvas, idx: Int, alpha: Int) {
        val d = lightData.getOrNull(idx) ?: return
        val a = nightAmt
        val ga = (alpha * a).toInt().coerceIn(0, 255)
        if (ga <= 2) return
        lightPaint.xfermode = screen
        var i = 0
        while (i + 3 < d.size) {
            val type = d[i].toInt()
            val x = d[i + 1]
            val y = d[i + 2]
            val r = d[i + 3]
            val rad: Float
            val c0: Int
            val c1: Int
            val c2: Int
            if (type == 1) {
                rad = r * 2.6f
                c0 = Color.argb(ga, 255, 60, 40); c1 = Color.argb(ga * 5 / 10, 255, 30, 20); c2 = Color.argb(0, 255, 30, 20)
            } else if (type == 2) {
                rad = r * 3.2f
                c0 = Color.argb(ga, 255, 250, 235); c1 = Color.argb(ga * 4 / 10, 255, 245, 215); c2 = Color.argb(0, 255, 245, 215)
            } else {
                rad = r * 2.5f
                c0 = Color.argb(ga, 255, 248, 225); c1 = Color.argb(ga * 5 / 10, 255, 240, 200); c2 = Color.argb(0, 255, 240, 200)
            }
            lightPaint.shader = RadialGradient(x, y, rad, intArrayOf(c0, c1, c2),
                floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, rad, lightPaint)
            i += 4
        }
        lightPaint.shader = null
        lightPaint.xfermode = null
    }

    /** Dibuja las vistas (con fundido corto entre una y otra) en coordenadas locales de la vista. */
    private fun drawFrames(c: Canvas, i0: Int, i1: Int, a1: Float, withLights: Boolean) {
        val a = nightAmt
        val cm = ColorMatrix(floatArrayOf(
            1f - 0.55f * a, 0f, 0f, 0f, 0f,
            0f, 1f - 0.52f * a, 0f, 0f, 0f,
            0f, 0f, 1f - 0.38f * a, 0f, 8f * a,
            0f, 0f, 0f, 1f, 0f
        ))
        carPaint.colorFilter = ColorMatrixColorFilter(cm)
        val alpha0 = ((1f - a1) * 255).toInt()
        val alpha1 = (a1 * 255).toInt()
        if (alpha0 > 0) {
            carPaint.alpha = alpha0
            c.drawBitmap(frames[i0], 0f, 0f, carPaint)
            if (withLights) drawLights(c, i0, alpha0)
        }
        if (alpha1 > 0 && i1 != i0) {
            carPaint.alpha = alpha1
            c.drawBitmap(frames[i1], 0f, 0f, carPaint)
            if (withLights) drawLights(c, i1, alpha1)
        }
        carPaint.alpha = 255
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = Ui.s
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame).coerceIn(1L, 60L) / 1000f
        lastFrame = now
        var animating = false

        if (nightAmt != nightTarget) {
            val d = nightTarget - nightAmt
            nightAmt += if (d > 0) min(d, dt * 1.6f) else -min(-d, dt * 1.6f)
            animating = true
        }
        val a = nightAmt

        // ---- escenario
        val groundY = h * 0.66f
        bg.shader = LinearGradient(0f, 0f, 0f, h,
            mix(Color.parseColor("#3A4048"), Color.parseColor("#0B1220"), a),
            mix(Color.parseColor("#14171B"), Color.parseColor("#000000"), a), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, bg)
        // luz de fondo (foco)
        bg.shader = RadialGradient(w * 0.5f, groundY - 140f * s, 520f * s,
            mix(Color.argb(95, 205, 220, 240), Color.argb(80, 70, 130, 255), a), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, bg)
        // piso brillante
        bg.shader = LinearGradient(0f, groundY - 30f * s, 0f, h,
            mix(Color.parseColor("#2C323A"), Color.parseColor("#0A0F18"), a),
            mix(Color.parseColor("#0E1013"), Color.parseColor("#000000"), a), Shader.TileMode.CLAMP)
        c.drawRect(0f, groundY - 30f * s, w, h, bg)
        bg.shader = null

        if (frames.isEmpty()) {
            txt.textAlign = Paint.Align.CENTER
            txt.color = Color.WHITE
            txt.textSize = px(20f)
            c.drawText("Faltan las imagenes sx4_v01.png ... sx4_v08.png", w / 2f, h / 2f, txt)
            return
        }

        val n = frames.size
        if (!dragging) {
            if (abs(spin) > 0.02f) {
                pos += spin * dt
                spin *= 0.94f.pow(dt * 60f)
                animating = true
            } else {
                spin = 0f
            }
            if (autoRotate && abs(spin) <= 0.02f) {
                pos += max(0.5f, n / 14f) * dt
                animating = true
            }
        } else {
            animating = true
        }

        val p = ((pos % n) + n) % n
        val i0 = floor(p).toInt() % n
        val fr = p - floor(p)
        val i1 = (i0 + 1) % n
        val a1 = smooth((fr - 0.30f) / 0.40f)

        val sc = 0.80f * s
        val left = w * 0.5f - fw * sc / 2f
        val top = groundY - baseY * sc

        // sombra de contacto
        c.save()
        c.translate(w * 0.5f, groundY)
        c.scale(1f, 0.10f)
        bg.shader = RadialGradient(0f, 0f, 340f * s,
            intArrayOf(Color.argb(190, 0, 0, 0), Color.argb(90, 0, 0, 0), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(0f, 0f, 340f * s, bg)
        bg.shader = null
        c.restore()

        // reflejo en el piso
        val reflH = 120f * s
        rf.set(left, groundY, left + fw * sc, groundY + reflH)
        val layer = c.saveLayer(rf, null)
        c.save()
        c.translate(left, groundY)
        c.scale(sc, -sc)
        c.translate(0f, -baseY)
        carPaint.alpha = 255
        drawFrames(c, i0, i1, a1, false)
        c.restore()
        fadePaint.shader = LinearGradient(0f, groundY, 0f, groundY + reflH,
            Color.argb(80, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        fadePaint.xfermode = dstIn
        c.drawRect(rf, fadePaint)
        fadePaint.xfermode = null
        fadePaint.shader = null
        c.restoreToCount(layer)

        // coche
        c.save()
        c.translate(left, top)
        c.scale(sc, sc)
        drawFrames(c, i0, i1, a1, true)
        c.restore()

        // sombras en los bordes para que los paneles flotantes se lean bien
        scrim.shader = shL
        c.drawRect(0f, 0f, w * 0.30f, h, scrim)
        scrim.shader = shR
        c.drawRect(w * 0.68f, 0f, w, h, scrim)
        scrim.shader = shB
        c.drawRect(0f, h * 0.80f, w, h, scrim)

        txt.textAlign = Paint.Align.CENTER
        txt.color = 0x99FFFFFF.toInt()
        txt.textSize = px(15f)
        c.drawText("Desliza para girar", w / 2f, px(30f), txt)

        if (animating) postInvalidateOnAnimation()
    }
}
