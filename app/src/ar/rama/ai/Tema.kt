package ar.rama.ai

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * La paleta de Rama: grafito neutro con un solo acento azul. Los colores de
 * estado (éxito, aviso, peligro) están apagados y se usan sólo para informar.
 */
object Colores {
    const val FONDO = 0xFF0F1115.toInt()
    const val SUPERFICIE = 0xFF171A20.toInt()
    const val SUPERFICIE_ALTA = 0xFF1F232B.toInt()
    const val BORDE = 0xFF2C313A.toInt()
    const val BORDE_TENUE = 0xFF22262E.toInt()
    const val TEXTO = 0xFFECEEF1.toInt()
    const val TEXTO_2 = 0xFFA9AFBA.toInt()
    const val TEXTO_3 = 0xFF6F7683.toInt()

    /** El único color de marca: botones principales, selección y enlaces. */
    const val ACENTO = 0xFF4C7EE8.toInt()
    const val ACENTO_CLARO = 0xFF8DB0F4.toInt()

    const val EXITO = 0xFF4FAE86.toInt()
    const val AVISO = 0xFFCFA14E.toInt()
    const val PELIGRO = 0xFFDC6A63.toInt()
    const val TOQUE = 0x24FFFFFF

    fun alfa(color: Int, opacidad: Float): Int =
        Color.argb((opacidad * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /** Mezcla lineal entre dos colores. */
    fun mezclar(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}

enum class Peso(val valor: Int) { NORMAL(400), MEDIO(500), NEGRITA(600), EXTRA(700) }

/** Inter (Google Fonts, licencia OFL), la tipografía de Rama, en cuatro pesos. */
object Fuentes {
    private val cache = HashMap<Peso, Typeface>()

    fun cargar(contexto: Context) {
        if (cache.isNotEmpty()) return
        for (peso in Peso.entries) {
            cache[peso] = try {
                Typeface.Builder(contexto.assets, "fuentes/Inter.ttf")
                    .setFontVariationSettings("'wght' ${peso.valor}")
                    .setWeight(peso.valor)
                    .build() ?: respaldo(peso)
            } catch (e: Throwable) {
                respaldo(peso)
            }
        }
    }

    private fun respaldo(peso: Peso): Typeface =
        if (peso.valor >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

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
    setLineSpacing(0f, 1f + (interlineado - 1f) * 0.75f)
    includeFontPadding = false
    return this
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

/** Un fondo con efecto de toque (ripple) recortado a la misma forma. */
fun pulsable(fondo: Drawable, radio: Float, radios: FloatArray? = null): Drawable =
    RippleDrawable(ColorStateList.valueOf(Colores.TOQUE), fondo, redondeado(Color.WHITE, radio, radios = radios))

fun pulsable(color: Int, radio: Float, borde: Int = 0, anchoBorde: Int = 0): Drawable =
    pulsable(redondeado(color, radio, borde, anchoBorde), radio)

fun esquinas(arriba: Float, abajoDerecha: Float, abajoIzquierda: Float): FloatArray =
    floatArrayOf(arriba, arriba, arriba, arriba, abajoDerecha, abajoDerecha, abajoIzquierda, abajoIzquierda)
