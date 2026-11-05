package com.sx4.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Tacometro de velocidad 0-220 km/h, estilo deportivo, con aguja animada. */
class GaugeView(ctx: Context) : View(ctx) {

    var accent: Int = Color.parseColor("#FFC9D3DD")
        set(v) { field = v; invalidate() }
    var status: String = "Buscando GPS..."
        set(v) { field = v; invalidate() }

    private var target = 0f
    private var shown = 0f
    private var valid = false
    private var forced: Float? = null
    private val ui = Handler(Looper.getMainLooper())

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val needle = Path()
    private val startAngle = 135f
    private val sweepAngle = 270f
    private val maxSpeed = 220f
    private val orange = Color.parseColor("#FFFF8A1F")
    private val red = Color.parseColor("#FFFF2D2D")
    private val italic = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)

    fun setSpeed(kmh: Float) {
        target = kmh.coerceIn(0f, maxSpeed)
        valid = true
        postInvalidateOnAnimation()
    }

    fun setValid(v: Boolean) {
        valid = v
        postInvalidateOnAnimation()
    }

    /** Barrido de arranque, como un tablero real. */
    fun intro() {
        forced = maxSpeed
        invalidate()
        ui.postDelayed({
            forced = 0f
            invalidate()
            ui.postDelayed({ forced = null; invalidate() }, 900)
        }, 1000)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val r = s / 2f - px(8f)

        val tgt = forced ?: if (valid) target else 0f
        shown += (tgt - shown) * 0.10f
        val moving = abs(tgt - shown) > 0.15f
        if (!moving) shown = tgt

        // cara oscura translucida
        p.style = Paint.Style.FILL
        p.color = 0x88050A10.toInt()
        c.drawCircle(cx, cy, r, p)

        // anillo exterior
        p.style = Paint.Style.STROKE
        p.strokeWidth = px(2f)
        p.color = 0x99FFFFFF.toInt()
        c.drawCircle(cx, cy, r, p)

        val ringW = px(11f)
        rect.set(cx - r + px(12f), cy - r + px(12f), cx + r - px(12f), cy + r - px(12f))

        // pista y zona roja (180-220)
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = ringW
        p.style = Paint.Style.STROKE
        p.color = 0x33FFFFFF
        c.drawArc(rect, startAngle, sweepAngle, false, p)
        p.color = 0x99FF2D2D.toInt()
        c.drawArc(rect, startAngle + sweepAngle * 180f / maxSpeed, sweepAngle * 40f / maxSpeed, false, p)

        // progreso: color de acento -> naranja -> rojo
        val seg1 = minOf(shown, 120f)
        p.color = accent
        if (seg1 > 0.5f) c.drawArc(rect, startAngle, sweepAngle * seg1 / maxSpeed, false, p)
        if (shown > 120f) {
            val e = minOf(shown, 180f)
            p.color = orange
            c.drawArc(rect, startAngle + sweepAngle * 120f / maxSpeed, sweepAngle * (e - 120f) / maxSpeed, false, p)
        }
        if (shown > 180f) {
            p.color = red
            c.drawArc(rect, startAngle + sweepAngle * 180f / maxSpeed, sweepAngle * (shown - 180f) / maxSpeed, false, p)
        }

        // marcas y numeros
        val r1 = r - px(30f)
        var v = 0
        while (v <= 220) {
            val ang = Math.toRadians((startAngle + sweepAngle * v / maxSpeed).toDouble())
            val major = v % 20 == 0
            val len = px(if (major) 18f else 9f)
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.BUTT
            p.strokeWidth = px(if (major) 4f else 2f)
            p.color = if (v >= 180) red else if (v <= shown) accent else 0x88FFFFFF.toInt()
            val ca = cos(ang).toFloat()
            val sa = sin(ang).toFloat()
            c.drawLine(cx + ca * r1, cy + sa * r1, cx + ca * (r1 - len), cy + sa * (r1 - len), p)
            if (major) {
                p.style = Paint.Style.FILL
                p.typeface = italic
                p.color = if (v >= 180) red else 0xEEFFFFFF.toInt()
                p.textSize = px(15f)
                p.textAlign = Paint.Align.CENTER
                val rl = r1 - px(34f)
                c.drawText(v.toString(), cx + ca * rl, cy + sa * rl + px(5f), p)
                p.typeface = Typeface.DEFAULT
            }
            v += 10
        }

        // aguja afilada con resplandor
        val needleAngle = startAngle + sweepAngle * shown / maxSpeed
        val len = r - px(22f)
        c.save()
        c.rotate(needleAngle, cx, cy)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0x28FF2D2D
        p.strokeWidth = px(16f)
        c.drawLine(cx, cy, cx + len, cy, p)
        p.color = 0x55FF2D2D
        p.strokeWidth = px(9f)
        c.drawLine(cx, cy, cx + len, cy, p)
        needle.reset()
        needle.moveTo(cx - px(26f), cy - px(6f))
        needle.lineTo(cx + len, cy)
        needle.lineTo(cx - px(26f), cy + px(6f))
        needle.close()
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#FFFF2D2D")
        c.drawPath(needle, p)
        c.restore()

        // eje
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#FF101317")
        c.drawCircle(cx, cy, px(17f), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = px(3f)
        p.color = 0xFFC9D3DD.toInt()
        c.drawCircle(cx, cy, px(17f), p)

        // lectura digital
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = italic
        p.color = if (shown >= 180f) red else if (shown >= 120f) orange else Color.WHITE
        p.textSize = px(58f)
        val txt = if (!valid && forced == null) "--" else shown.toInt().toString()
        c.drawText(txt, cx, cy + r * 0.56f, p)
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = px(15f)
        p.color = 0xCCFFFFFF.toInt()
        c.drawText("KM/H", cx, cy + r * 0.56f + px(22f), p)
        p.typeface = Typeface.DEFAULT
        p.textSize = px(12f)
        p.color = 0x88FFFFFF.toInt()
        c.drawText(status, cx, cy + r * 0.56f + px(40f), p)

        if (moving) postInvalidateOnAnimation()
    }
}
