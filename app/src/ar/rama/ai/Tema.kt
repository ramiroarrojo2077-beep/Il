package ar.rama.ai

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import ar.rama.ai.motor.NivelPensar

/** La paleta de Rama 4: noche violeta con acentos de colores vivos. */
object Colores {
    const val FONDO = 0xFF0D0A1C.toInt()
    const val SUPERFICIE = 0xFF1A1437.toInt()
    const val SUPERFICIE_ALTA = 0xFF241C4A.toInt()
    const val BORDE = 0xFF34295F.toInt()
    const val BORDE_TENUE = 0xFF231B45.toInt()
    const val TEXTO = 0xFFF6F3FF.toInt()
    const val TEXTO_2 = 0xFFC2B9EA.toInt()
    const val TEXTO_3 = 0xFF8A7FBC.toInt()

    const val VIOLETA = 0xFF8B5CF6.toInt()
    const val LILA = 0xFFB39DFF.toInt()
    const val FUCSIA = 0xFFEC4899.toInt()
    const val CORAL = 0xFFFB7185.toInt()
    const val NARANJA = 0xFFF59E0B.toInt()
    const val FUEGO = 0xFFF97316.toInt()
    const val ROJO = 0xFFEF4444.toInt()
    const val AMARILLO = 0xFFFACC15.toInt()
    const val MENTA = 0xFF34D399.toInt()
    const val CIAN = 0xFF22D3EE.toInt()
    const val CELESTE = 0xFF38BDF8.toInt()
    const val INDIGO = 0xFF818CF8.toInt()
    const val ERROR = 0xFFF87171.toInt()
    const val TOQUE = 0x40FFFFFF

    /** El degradé de la marca: violeta → fucsia → naranja. */
    val MARCA = intArrayOf(VIOLETA, FUCSIA, NARANJA)
    val FRIO = intArrayOf(CIAN, VIOLETA)

    fun alfa(color: Int, opacidad: Float): Int =
        Color.argb((opacidad * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /** Mezcla lineal entre dos colores. */
    fun mezclar(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    fun deNivel(nivel: NivelPensar): IntArray = when (nivel) {
        NivelPensar.BAJO -> intArrayOf(MENTA, CIAN)
        NivelPensar.NORMAL -> intArrayOf(CELESTE, INDIGO)
        NivelPensar.ALTO -> intArrayOf(LILA, FUCSIA)
        NivelPensar.MAX -> intArrayOf(AMARILLO, FUEGO, ROJO)
    }

    fun principalDeNivel(nivel: NivelPensar): Int = when (nivel) {
        NivelPensar.BAJO -> MENTA
        NivelPensar.NORMAL -> CELESTE
        NivelPensar.ALTO -> LILA
        NivelPensar.MAX -> FUEGO
    }
}

enum class Peso(val valor: Int) { NORMAL(450), MEDIO(600), NEGRITA(750), EXTRA(900) }

/** Nunito (Google Fonts, licencia OFL), la tipografía de Rama 4, en sus cuatro pesos. */
object Fuentes {
    private val cache = HashMap<Peso, Typeface>()

    fun cargar(contexto: Context) {
        if (cache.isNotEmpty()) return
        for (peso in Peso.entries) {
            cache[peso] = try {
                Typeface.Builder(contexto.assets, "fuentes/Nunito.ttf")
                    .setFontVariationSettings("'wght' ${peso.valor}")
                    .setWeight(peso.valor.coerceIn(100, 1000))
                    .build() ?: respaldo(peso)
            } catch (e: Throwable) {
                respaldo(peso)
            }
        }
    }

    private fun respaldo(peso: Peso): Typeface =
        if (peso.valor >= 700) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

    fun de(peso: Peso): Typeface = cache[peso] ?: respaldo(peso)
}

/** Span que cambia la tipografía (TypefaceSpan(Typeface) recién existe en API 28). */
class SpanFuente(private val fuente: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(pintura: TextPaint) {
        pintura.typeface = fuente
    }

    override fun updateMeasureState(pintura: TextPaint) {
        pintura.typeface = fuente
    }
}

fun Context.dp(valor: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, valor, resources.displayMetrics).toInt()

fun View.dp(valor: Float): Int = context.dp(valor)

fun <T : TextView> T.estilo(
    tamanio: Float,
    color: Int = Colores.TEXTO,
    peso: Peso = Peso.NORMAL,
    interlineado: Float = 1.25f,
): T {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, tamanio)
    setTextColor(color)
    typeface = Fuentes.de(peso)
    // Nunito ya trae renglones altos: se aplica menos de la mitad del aire pedido.
    setLineSpacing(0f, 1f + (interlineado - 1f) * 0.45f)
    includeFontPadding = false
    return this
}

/** Pinta el texto con un degradé horizontal (se recalcula al medir). */
fun TextView.textoDegradado(colores: IntArray) {
    val vista = this
    addOnLayoutChangeListener { _, izquierda, _, derecha, _, _, _, _, _ ->
        val ancho = (derecha - izquierda).toFloat().coerceAtLeast(1f)
        val medida = paint.measureText(text.toString()).coerceAtMost(ancho).coerceAtLeast(1f)
        vista.paint.shader = LinearGradient(0f, 0f, medida, 0f, colores, null, Shader.TileMode.CLAMP)
        vista.invalidate()
    }
}

fun View.relleno(horizontal: Int, vertical: Int) = setPadding(horizontal, vertical, horizontal, vertical)

// ---------------------------------------------------------------- fondos

fun redondeado(
    color: Int,
    radio: Float,
    borde: Int = 0,
    anchoBorde: Int = 0,
    radios: FloatArray? = null,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(color)
    if (radios != null) cornerRadii = radios else cornerRadius = radio
    if (anchoBorde > 0) setStroke(anchoBorde, borde)
}

fun degradado(
    colores: IntArray,
    radio: Float,
    orientacion: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR,
    radios: FloatArray? = null,
): GradientDrawable = GradientDrawable(orientacion, colores).apply {
    shape = GradientDrawable.RECTANGLE
    if (radios != null) cornerRadii = radios else cornerRadius = radio
}

/** Un fondo con efecto de toque (ripple) recortado a la misma forma. */
fun pulsable(fondo: Drawable, radio: Float, radios: FloatArray? = null): Drawable =
    RippleDrawable(ColorStateList.valueOf(Colores.TOQUE), fondo, redondeado(Color.WHITE, radio, radios = radios))

fun pulsable(color: Int, radio: Float, borde: Int = 0, anchoBorde: Int = 0): Drawable =
    pulsable(redondeado(color, radio, borde, anchoBorde), radio)

/** Borde con degradé: una capa de color degradado y encima el relleno, achicado [grosor]. */
fun bordeDegradado(colores: IntArray, relleno: Int, radio: Float, grosor: Int): Drawable {
    val capas = LayerDrawable(
        arrayOf(
            degradado(colores, radio, GradientDrawable.Orientation.LEFT_RIGHT),
            redondeado(relleno, (radio - grosor).coerceAtLeast(0f)),
        ),
    )
    capas.setLayerInset(1, grosor, grosor, grosor, grosor)
    return capas
}

fun esquinas(arriba: Float, abajoDerecha: Float, abajoIzquierda: Float): FloatArray =
    floatArrayOf(arriba, arriba, arriba, arriba, abajoDerecha, abajoDerecha, abajoIzquierda, abajoIzquierda)

/**
 * El fondo de la app: noche violeta con manchas de luz de colores, como una
 * aurora. Son degradés radiales muy transparentes, así que no molestan al leer.
 */
class FondoAurora(private val intensidad: Float = 1f) : Drawable() {
    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
    private data class Mancha(val x: Float, val y: Float, val radio: Float, val color: Int, val opacidad: Float)

    private val manchas = listOf(
        Mancha(0.05f, 0.02f, 0.85f, Colores.VIOLETA, 0.34f),
        Mancha(1.00f, 0.22f, 0.70f, Colores.FUCSIA, 0.20f),
        Mancha(0.00f, 0.92f, 0.80f, Colores.CIAN, 0.14f),
        Mancha(0.95f, 1.02f, 0.65f, Colores.NARANJA, 0.13f),
    )
    private val sombreados = ArrayList<Pair<Mancha, RadialGradient>>()

    override fun onBoundsChange(limites: Rect) {
        super.onBoundsChange(limites)
        sombreados.clear()
        val ancho = limites.width().toFloat()
        val alto = limites.height().toFloat()
        if (ancho <= 0 || alto <= 0) return
        for (m in manchas) {
            val radio = m.radio * maxOf(ancho, alto * 0.6f)
            val sombra = RadialGradient(
                limites.left + m.x * ancho, limites.top + m.y * alto, radio,
                intArrayOf(Colores.alfa(m.color, m.opacidad * intensidad), Colores.alfa(m.color, 0f)),
                null, Shader.TileMode.CLAMP,
            )
            sombreados.add(m to sombra)
        }
    }

    override fun draw(lienzo: Canvas) {
        lienzo.drawColor(Colores.FONDO)
        val b = bounds
        for ((m, sombra) in sombreados) {
            pintura.shader = sombra
            val radio = m.radio * maxOf(b.width().toFloat(), b.height() * 0.6f)
            lienzo.drawCircle(b.left + m.x * b.width(), b.top + m.y * b.height(), radio, pintura)
        }
    }

    override fun setAlpha(alfa: Int) {
        pintura.alpha = alfa
    }

    override fun setColorFilter(filtro: ColorFilter?) {
        pintura.colorFilter = filtro
    }

    @Deprecated("Lo exige Drawable")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
