package ar.rama.ai

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Un ícono dibujado con un trazo en una grilla de 24×24. Puede ir de un color
 * o con degradé, con línea o relleno.
 */
class Icono(
    private val ruta: Path,
    private val color: Int,
    private val grosor: Float = 2f,
    private val relleno: Boolean = false,
    private val degradado: IntArray? = null,
) : Drawable() {
    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@Icono.color
        style = if (relleno) Paint.Style.FILL else Paint.Style.STROKE
        strokeWidth = grosor
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        if (degradado != null) shader = LinearGradient(2f, 2f, 22f, 22f, degradado, null, Shader.TileMode.CLAMP)
    }

    override fun draw(lienzo: Canvas) {
        val lado = minOf(bounds.width(), bounds.height()).toFloat()
        if (lado <= 0f) return
        lienzo.save()
        lienzo.translate(bounds.left + (bounds.width() - lado) / 2f, bounds.top + (bounds.height() - lado) / 2f)
        lienzo.scale(lado / GRILLA, lado / GRILLA)
        lienzo.drawPath(ruta, pincel)
        lienzo.restore()
    }

    override fun setAlpha(alfa: Int) {
        pincel.alpha = alfa
    }

    override fun setColorFilter(filtro: ColorFilter?) {
        pincel.colorFilter = filtro
    }

    @Deprecated("Lo exige Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val GRILLA = 24f
    }
}

/** Los trazos de todos los íconos de la app. */
object Trazos {
    private fun p(bloque: Path.() -> Unit): Path = Path().apply(bloque)

    private fun Path.circulo(x: Float, y: Float, r: Float) = addCircle(x, y, r, Path.Direction.CW)

    private fun Path.linea(x1: Float, y1: Float, x2: Float, y2: Float) {
        moveTo(x1, y1)
        lineTo(x2, y2)
    }

    private fun Path.caja(l: Float, t: Float, r: Float, b: Float, radio: Float) =
        addRoundRect(RectF(l, t, r, b), radio, radio, Path.Direction.CW)

    /** La marca: una rama con dos brotes. */
    fun rama() = p {
        linea(12f, 21f, 12f, 8.5f)
        linea(12f, 15f, 7f, 11f)
        linea(12f, 12.5f, 17f, 8.5f)
    }

    fun ramaBrotes() = p {
        circulo(12f, 6.5f, 2.4f)
        circulo(6f, 10.2f, 2.1f)
        circulo(18f, 7.7f, 2.1f)
    }

    fun menu() = p {
        linea(4f, 7f, 20f, 7f)
        linea(4f, 12f, 16f, 12f)
        linea(4f, 17f, 12f, 17f)
    }

    fun chats() = p {
        moveTo(20f, 11.5f)
        cubicTo(20f, 15.6f, 16.4f, 19f, 12f, 19f)
        cubicTo(10.8f, 19f, 9.7f, 18.8f, 8.7f, 18.4f)
        lineTo(4f, 20f)
        lineTo(5.3f, 16.2f)
        cubicTo(4.5f, 14.8f, 4f, 13.2f, 4f, 11.5f)
        cubicTo(4f, 7.4f, 7.6f, 4f, 12f, 4f)
        cubicTo(16.4f, 4f, 20f, 7.4f, 20f, 11.5f)
        close()
    }

    fun mas() = p {
        linea(12f, 5f, 12f, 19f)
        linea(5f, 12f, 19f, 12f)
    }

    fun enviar() = p {
        linea(12f, 19f, 12f, 5.5f)
        moveTo(6f, 11f)
        lineTo(12f, 5f)
        lineTo(18f, 11f)
    }

    fun detener() = p { caja(6.5f, 6.5f, 17.5f, 17.5f, 2.5f) }

    fun cerrar() = p {
        linea(6f, 6f, 18f, 18f)
        linea(18f, 6f, 6f, 18f)
    }

    fun atras() = p {
        moveTo(14.5f, 5.5f)
        lineTo(8f, 12f)
        lineTo(14.5f, 18.5f)
    }

    fun globo() = p {
        circulo(12f, 12f, 8.5f)
        linea(3.5f, 12f, 20.5f, 12f)
        moveTo(12f, 3.5f)
        cubicTo(14.6f, 6f, 15.6f, 9f, 15.6f, 12f)
        cubicTo(15.6f, 15f, 14.6f, 18f, 12f, 20.5f)
        cubicTo(9.4f, 18f, 8.4f, 15f, 8.4f, 12f)
        cubicTo(8.4f, 9f, 9.4f, 6f, 12f, 3.5f)
    }

    /** Estrella de cuatro puntas (relleno). */
    fun destello() = p {
        moveTo(12f, 2.5f)
        cubicTo(12.8f, 7.6f, 14.4f, 9.2f, 21.5f, 12f)
        cubicTo(14.4f, 14.8f, 12.8f, 16.4f, 12f, 21.5f)
        cubicTo(11.2f, 16.4f, 9.6f, 14.8f, 2.5f, 12f)
        cubicTo(9.6f, 9.2f, 11.2f, 7.6f, 12f, 2.5f)
        close()
    }

    /** Rayo (relleno): nivel Bajo. */
    fun rayo() = p {
        moveTo(13.5f, 2.5f)
        lineTo(5f, 13.5f)
        lineTo(11f, 13.5f)
        lineTo(10f, 21.5f)
        lineTo(19f, 10f)
        lineTo(13f, 10f)
        close()
    }

    /** Lamparita (línea): nivel Alto. */
    fun lamparita() = p {
        moveTo(9f, 17f)
        lineTo(9f, 15.2f)
        cubicTo(7f, 13.9f, 5.8f, 11.9f, 5.8f, 9.6f)
        cubicTo(5.8f, 6.1f, 8.6f, 3.3f, 12f, 3.3f)
        cubicTo(15.4f, 3.3f, 18.2f, 6.1f, 18.2f, 9.6f)
        cubicTo(18.2f, 11.9f, 17f, 13.9f, 15f, 15.2f)
        lineTo(15f, 17f)
        close()
        linea(9.5f, 20.5f, 14.5f, 20.5f)
    }

    /** Llama (relleno): nivel Max. */
    fun fuego() = p {
        moveTo(12f, 2.5f)
        cubicTo(13f, 6f, 17.5f, 8.2f, 17.5f, 13.8f)
        cubicTo(17.5f, 18.2f, 15f, 21.5f, 12f, 21.5f)
        cubicTo(9f, 21.5f, 6.5f, 18.6f, 6.5f, 15.2f)
        cubicTo(6.5f, 12.4f, 8f, 11f, 9f, 9.5f)
        cubicTo(9.4f, 11.2f, 10.2f, 12.3f, 11.2f, 12.6f)
        cubicTo(10.6f, 9.4f, 11f, 5.8f, 12f, 2.5f)
        close()
    }

    fun chevron() = p {
        moveTo(6f, 9.5f)
        lineTo(12f, 15.5f)
        lineTo(18f, 9.5f)
    }

    fun papelera() = p {
        linea(4.5f, 7f, 19.5f, 7f)
        moveTo(9f, 7f)
        lineTo(9.8f, 4.5f)
        lineTo(14.2f, 4.5f)
        lineTo(15f, 7f)
        moveTo(6.5f, 7f)
        lineTo(7.4f, 19f)
        cubicTo(7.5f, 19.9f, 8.1f, 20.5f, 9f, 20.5f)
        lineTo(15f, 20.5f)
        cubicTo(15.9f, 20.5f, 16.5f, 19.9f, 16.6f, 19f)
        lineTo(17.5f, 7f)
        linea(10.2f, 10.5f, 10.4f, 17f)
        linea(13.8f, 10.5f, 13.6f, 17f)
    }

    fun lapiz() = p {
        moveTo(4.5f, 19.5f)
        lineTo(5.3f, 15.6f)
        lineTo(15.8f, 5.1f)
        cubicTo(16.6f, 4.3f, 17.9f, 4.3f, 18.7f, 5.1f)
        lineTo(18.9f, 5.3f)
        cubicTo(19.7f, 6.1f, 19.7f, 7.4f, 18.9f, 8.2f)
        lineTo(8.4f, 18.7f)
        close()
        linea(14f, 7f, 17f, 10f)
    }

    fun copiar() = p {
        caja(8.5f, 8.5f, 19.5f, 19.5f, 2.5f)
        moveTo(15.5f, 8.5f)
        lineTo(15.5f, 6.5f)
        cubicTo(15.5f, 5.4f, 14.6f, 4.5f, 13.5f, 4.5f)
        lineTo(6.5f, 4.5f)
        cubicTo(5.4f, 4.5f, 4.5f, 5.4f, 4.5f, 6.5f)
        lineTo(4.5f, 13.5f)
        cubicTo(4.5f, 14.6f, 5.4f, 15.5f, 6.5f, 15.5f)
        lineTo(8.5f, 15.5f)
    }

    fun visto() = p {
        moveTo(5f, 12.5f)
        lineTo(9.8f, 17f)
        lineTo(19f, 7f)
    }

    fun advertencia() = p {
        moveTo(12f, 4f)
        lineTo(21f, 19.5f)
        lineTo(3f, 19.5f)
        close()
        linea(12f, 10f, 12f, 14f)
        linea(12f, 16.8f, 12f, 17f)
    }

    fun documento() = p {
        moveTo(14f, 3.5f)
        lineTo(7f, 3.5f)
        cubicTo(5.9f, 3.5f, 5f, 4.4f, 5f, 5.5f)
        lineTo(5f, 18.5f)
        cubicTo(5f, 19.6f, 5.9f, 20.5f, 7f, 20.5f)
        lineTo(17f, 20.5f)
        cubicTo(18.1f, 20.5f, 19f, 19.6f, 19f, 18.5f)
        lineTo(19f, 8.5f)
        close()
        moveTo(14f, 3.5f)
        lineTo(14f, 8.5f)
        lineTo(19f, 8.5f)
        linea(8.5f, 13f, 15.5f, 13f)
        linea(8.5f, 16.5f, 13f, 16.5f)
    }

    /** Un chip de procesador: el modelo. */
    fun chip() = p {
        caja(6.5f, 6.5f, 17.5f, 17.5f, 2.5f)
        caja(9.5f, 9.5f, 14.5f, 14.5f, 1f)
        for (x in listOf(9.5f, 12f, 14.5f)) {
            linea(x, 3.5f, x, 6.5f)
            linea(x, 17.5f, x, 20.5f)
        }
        for (y in listOf(9.5f, 12f, 14.5f)) {
            linea(3.5f, y, 6.5f, y)
            linea(17.5f, y, 20.5f, y)
        }
    }

    fun ajustes() = p {
        linea(4f, 7f, 20f, 7f)
        linea(4f, 17f, 20f, 17f)
        circulo(9f, 7f, 2.3f)
        circulo(15f, 17f, 2.3f)
    }

    fun descargar() = p {
        linea(12f, 4f, 12f, 15f)
        moveTo(7f, 10.5f)
        lineTo(12f, 15.5f)
        lineTo(17f, 10.5f)
        linea(5f, 20f, 19f, 20f)
    }

    fun lupa() = p {
        circulo(10.5f, 10.5f, 6f)
        linea(15f, 15f, 20f, 20f)
    }

    fun libro() = p {
        moveTo(12f, 6.5f)
        cubicTo(10f, 5f, 7f, 4.5f, 4f, 5f)
        lineTo(4f, 18.5f)
        cubicTo(7f, 18f, 10f, 18.5f, 12f, 20f)
        cubicTo(14f, 18.5f, 17f, 18f, 20f, 18.5f)
        lineTo(20f, 5f)
        cubicTo(17f, 4.5f, 14f, 5f, 12f, 6.5f)
        close()
        linea(12f, 6.5f, 12f, 20f)
    }

    fun calculadora() = p {
        caja(5.5f, 3.5f, 18.5f, 20.5f, 2.5f)
        caja(8f, 6f, 16f, 9.5f, 1f)
        for (y in listOf(13f, 17f)) for (x in listOf(9f, 12f, 15f)) {
            linea(x, y, x + 0.01f, y)
        }
    }

    fun ojo() = p {
        moveTo(2.5f, 12f)
        cubicTo(4.8f, 7.5f, 8.2f, 5.5f, 12f, 5.5f)
        cubicTo(15.8f, 5.5f, 19.2f, 7.5f, 21.5f, 12f)
        cubicTo(19.2f, 16.5f, 15.8f, 18.5f, 12f, 18.5f)
        cubicTo(8.2f, 18.5f, 4.8f, 16.5f, 2.5f, 12f)
        close()
        circulo(12f, 12f, 3f)
    }

    fun reloj() = p {
        circulo(12f, 12f, 8.5f)
        moveTo(12f, 7.5f)
        lineTo(12f, 12f)
        lineTo(15f, 14f)
    }

    fun enlace() = p {
        moveTo(10f, 14f)
        lineTo(14f, 10f)
        moveTo(11f, 7f)
        lineTo(12.5f, 5.5f)
        cubicTo(14.4f, 3.6f, 17.6f, 3.6f, 19.5f, 5.5f)
        cubicTo(21.4f, 7.4f, 21.4f, 10.6f, 19.5f, 12.5f)
        lineTo(17f, 15f)
        moveTo(13f, 17f)
        lineTo(11.5f, 18.5f)
        cubicTo(9.6f, 20.4f, 6.4f, 20.4f, 4.5f, 18.5f)
        cubicTo(2.6f, 16.6f, 2.6f, 13.4f, 4.5f, 11.5f)
        lineTo(7f, 9f)
    }

    fun archivo() = p {
        moveTo(20f, 11.2f)
        lineTo(12.2f, 19f)
        cubicTo(10.2f, 21f, 7f, 21f, 5f, 19f)
        cubicTo(3f, 17f, 3f, 13.8f, 5f, 11.8f)
        lineTo(12.8f, 4f)
        cubicTo(14.1f, 2.7f, 16.3f, 2.7f, 17.6f, 4f)
        cubicTo(18.9f, 5.3f, 18.9f, 7.5f, 17.6f, 8.8f)
        lineTo(9.8f, 16.6f)
        cubicTo(9.1f, 17.3f, 8f, 17.3f, 7.4f, 16.6f)
        cubicTo(6.7f, 15.9f, 6.7f, 14.8f, 7.4f, 14.2f)
        lineTo(14.6f, 7f)
    }

    fun nivel(nivel: ar.rama.ai.motor.NivelPensar): Pair<Path, Boolean> = when (nivel) {
        ar.rama.ai.motor.NivelPensar.BAJO -> rayo() to true
        ar.rama.ai.motor.NivelPensar.NORMAL -> destello() to true
        ar.rama.ai.motor.NivelPensar.ALTO -> lamparita() to false
        ar.rama.ai.motor.NivelPensar.MAX -> fuego() to true
    }
}
