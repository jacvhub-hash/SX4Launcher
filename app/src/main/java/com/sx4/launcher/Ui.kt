package com.sx4.launcher

/** Escala de la interfaz: el diseno esta hecho para 1280x720 pixeles. */
object Ui {
    var s = 1f
}

fun px(v: Float): Float = v * Ui.s
fun pxi(v: Int): Int = (v * Ui.s).toInt()
