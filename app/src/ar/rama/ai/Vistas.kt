package ar.rama.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ar.rama.ai.motor.NivelPensar

// ---------------------------------------------------------------- piezas sueltas

fun Context.botonIcono(
    trazo: Path,
    descripcion: String,
    lado: Float = 42f,
    tamanioIcono: Float = 20f,
    color: Int = Colores.TEXTO_2,
    fondo: Int = Colores.SUPERFICIE,
    borde: Int = Colores.BORDE,
    relleno: Boolean = false,
    alTocar: () -> Unit,
): ImageView = ImageView(this).apply {
    setImageDrawable(Icono(trazo, color, 2f, relleno))
    val margen = dp((lado - tamanioIcono) / 2f)
    setPadding(margen, margen, margen, margen)
    background = pulsable(fondo, dp(lado / 2f).toFloat(), borde, if (borde == 0) 0 else dp(1f))
    contentDescription = descripcion
    setOnClickListener { alTocar() }
    layoutParams = LinearLayout.LayoutParams(dp(lado), dp(lado))
}

/** Botón de texto. Con [colores] va relleno con ese degradé; si no, es un botón sobrio. */
fun Context.boton(
    texto: String,
    colores: IntArray? = null,
    trazo: Path? = null,
    iconoRelleno: Boolean = false,
    alTocar: () -> Unit,
): TextView = TextView(this).apply {
    this.text = texto
    estilo(14.5f, Colores.TEXTO, Peso.NEGRITA, 1f)
    gravity = Gravity.CENTER
    relleno(dp(18f), dp(12f))
    val radio = dp(16f).toFloat()
    background = if (colores != null) pulsable(degradado(colores, radio, GradientDrawable.Orientation.LEFT_RIGHT), radio)
    else pulsable(Colores.SUPERFICIE_ALTA, radio, Colores.BORDE, dp(1f))
    if (trazo != null) {
        val icono = Icono(trazo, Colores.TEXTO, 2.2f, iconoRelleno)
        icono.setBounds(0, 0, dp(17f), dp(17f))
        setCompoundDrawables(icono, null, null, null)
        compoundDrawablePadding = dp(8f)
    }
    setOnClickListener { alTocar() }
}

/** Etiqueta en mayúsculas, chiquita y espaciada. */
fun Context.rotulo(texto: String, color: Int = Colores.TEXTO_3): TextView = TextView(this).apply {
    this.text = texto.uppercase()
    estilo(11f, color, Peso.EXTRA, 1f)
    letterSpacing = 0.12f
}

/** Pastilla de color con texto: estados, sellos, capacidades. */
fun Context.pastilla(texto: String, color: Int, trazo: Path? = null, rellenoIcono: Boolean = false): TextView = TextView(this).apply {
    this.text = texto
    estilo(11.5f, color, Peso.NEGRITA, 1f)
    relleno(dp(10f), dp(5f))
    background = redondeado(Colores.alfa(color, 0.14f), dp(999f).toFloat(), Colores.alfa(color, 0.45f), dp(1f))
    if (trazo != null) {
        val icono = Icono(trazo, color, 2.3f, rellenoIcono)
        icono.setBounds(0, 0, dp(12f), dp(12f))
        setCompoundDrawables(icono, null, null, null)
        compoundDrawablePadding = dp(5f)
    }
}

/** El avatar de Rama: círculo con el degradé de la marca y la rama en blanco. */
fun Context.avatarRama(lado: Float): View = FrameLayout(this).apply {
    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, Colores.MARCA).apply { shape = GradientDrawable.OVAL }
    val margen = dp(lado * 0.2f)
    addView(ImageView(context).apply { setImageDrawable(Icono(Trazos.rama(), Colores.TEXTO, 2.4f)) }, FrameLayout.LayoutParams(-1, -1).apply {
        setMargins(margen, margen, margen, margen)
    })
    addView(ImageView(context).apply { setImageDrawable(Icono(Trazos.ramaBrotes(), Colores.TEXTO, 0f, true)) }, FrameLayout.LayoutParams(-1, -1).apply {
        setMargins(margen, margen, margen, margen)
    })
    layoutParams = LinearLayout.LayoutParams(dp(lado), dp(lado))
}

fun Context.separador(color: Int = Colores.BORDE_TENUE): View = View(this).apply {
    setBackgroundColor(color)
    layoutParams = LinearLayout.LayoutParams(-1, dp(1f))
}

fun lp(ancho: Int, alto: Int, peso: Float = 0f, bloque: LinearLayout.LayoutParams.() -> Unit = {}): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ancho, alto, peso).apply(bloque)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ---------------------------------------------------------------- vistas animadas

/** Tres puntos que laten con los colores de la marca mientras Rama prepara la respuesta. */
class PuntosPensando(contexto: Context, private val colores: IntArray = Colores.MARCA) : View(contexto) {
    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fase = 0f
    private val animador = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            fase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animador.start()
    }

    override fun onDetachedFromWindow() {
        animador.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(lienzo: Canvas) {
        val radio = height / 5f
        val paso = width / 3f
        for (i in 0 until 3) {
            val t = ((fase - i * 0.18f) % 1f + 1f) % 1f
            val pulso = if (t < 0.5f) t * 2f else (1f - t) * 2f
            pincel.color = colores[i % colores.size]
            pincel.alpha = (90 + 165 * pulso).toInt()
            lienzo.drawCircle(paso * i + paso / 2f, height / 2f - pulso * radio * 0.5f, radio * (0.75f + 0.35f * pulso), pincel)
        }
    }
}

/** Barra de progreso redondeada con relleno degradé. */
class BarraProgreso(contexto: Context, private val colores: IntArray = Colores.MARCA) : View(contexto) {
    private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colores.SUPERFICIE_ALTA }
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caja = RectF()
    var fraccion: Float = 0f
        set(valor) {
            field = valor.coerceIn(0f, 1f)
            invalidate()
        }
    var indeterminada = false
        set(valor) {
            field = valor
            if (valor) animador.start() else animador.cancel()
            invalidate()
        }
    private var fase = 0f
    private val animador = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1300
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            fase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animador.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(lienzo: Canvas) {
        val r = height / 2f
        caja.set(0f, 0f, width.toFloat(), height.toFloat())
        lienzo.drawRoundRect(caja, r, r, fondo)
        relleno.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, colores, null, Shader.TileMode.CLAMP)
        if (indeterminada) {
            val largo = width * 0.35f
            val inicio = (width + largo) * fase - largo
            caja.set(maxOf(0f, inicio), 0f, minOf(width.toFloat(), inicio + largo), height.toFloat())
        } else {
            caja.set(0f, 0f, maxOf(height.toFloat(), width * fraccion), height.toFloat())
        }
        if (caja.width() > 0) lienzo.drawRoundRect(caja, r, r, relleno)
    }
}

/**
 * Los cuatro niveles de pensamiento en una fila de pastillas. La elegida va
 * rellena con el degradé de su nivel; las otras, en su color sobre oscuro.
 */
class SelectorNivel(contexto: Context, private val alElegir: (NivelPensar) -> Unit) : LinearLayout(contexto) {
    private val pastillas = LinkedHashMap<NivelPensar, TextView>()
    var nivel: NivelPensar = NivelPensar.PREDETERMINADO
        private set

    init {
        orientation = HORIZONTAL
        for (n in NivelPensar.entries) {
            val vista = TextView(contexto).apply {
                text = n.nombre
                gravity = Gravity.CENTER
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                relleno(dp(6f), dp(9f))
                contentDescription = "Pensar ${n.nombre}: ${n.descripcion}"
                setOnClickListener { elegir(n, true) }
            }
            pastillas[n] = vista
            addView(vista, lp(0, WRAP, 1f) { if (n != NivelPensar.MAX) rightMargin = dp(7f) })
        }
        pintar()
    }

    fun elegir(n: NivelPensar, avisar: Boolean) {
        val cambio = n != nivel
        nivel = n
        pintar()
        if (avisar && cambio) {
            pastillas[n]?.let { rebote(it) }
            alElegir(n)
        } else if (avisar) {
            alElegir(n)
        }
    }

    private fun pintar() {
        for ((n, vista) in pastillas) {
            val elegido = n == nivel
            val color = Colores.principalDeNivel(n)
            val radio = dp(999f).toFloat()
            vista.estilo(13f, if (elegido) Colores.FONDO else color, if (elegido) Peso.EXTRA else Peso.NEGRITA, 1f)
            vista.background = if (elegido) {
                pulsable(degradado(Colores.deNivel(n), radio, GradientDrawable.Orientation.LEFT_RIGHT), radio)
            } else {
                pulsable(Colores.alfa(color, 0.10f), radio, Colores.alfa(color, 0.40f), dp(1f))
            }
            val (trazo, relleno) = Trazos.nivel(n)
            val icono = Icono(trazo, if (elegido) Colores.FONDO else color, 2.2f, relleno)
            icono.setBounds(0, 0, dp(14f), dp(14f))
            vista.setCompoundDrawables(icono, null, null, null)
            vista.compoundDrawablePadding = dp(5f)
        }
    }
}

/** Un pequeño salto al tocar algo, para que se sienta vivo. */
fun rebote(vista: View) {
    vista.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction {
        vista.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }.start()
}

// ---------------------------------------------------------------- hoja inferior

/**
 * Una hoja que sube desde abajo sobre un velo oscuro. Sirve para ajustes,
 * confirmaciones y cualquier diálogo, con el estilo de la app.
 */
class HojaInferior(private val raiz: FrameLayout) {
    private val contexto = raiz.context
    private var velo: FrameLayout? = null

    val abierta: Boolean get() = velo != null

    fun mostrar(titulo: String, contenido: View) {
        cerrar(animar = false)
        val capa = FrameLayout(contexto).apply {
            setBackgroundColor(Colores.alfa(0xFF05030D.toInt(), 0.72f))
            isClickable = true
            setOnClickListener { cerrar() }
            alpha = 0f
        }
        val tarjeta = LinearLayout(contexto).apply {
            orientation = LinearLayout.VERTICAL
            background = bordeDegradado(Colores.MARCA, Colores.SUPERFICIE, dp(26f).toFloat(), dp(1.5f))
            setPadding(dp(20f), dp(12f), dp(20f), dp(20f))
            isClickable = true
            elevation = dp(12f).toFloat()
        }
        tarjeta.addView(View(contexto).apply {
            background = redondeado(Colores.BORDE, dp(3f).toFloat())
        }, lp(dp(40f), dp(5f)) { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14f) })
        tarjeta.addView(TextView(contexto).apply {
            text = titulo
            estilo(19f, Colores.TEXTO, Peso.EXTRA, 1.1f)
        }, lp(MATCH, WRAP) { bottomMargin = dp(12f) })
        val desplazable = ScrollView(contexto).apply {
            isVerticalScrollBarEnabled = false
            addView(contenido)
        }
        tarjeta.addView(desplazable, lp(MATCH, WRAP))
        val alto = (contexto.resources.displayMetrics.heightPixels * 0.82f).toInt()
        capa.addView(tarjeta, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            setMargins(dp(8f), 0, dp(8f), dp(8f))
        })
        tarjeta.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            if (bottom - top > alto) v.layoutParams = (v.layoutParams as FrameLayout.LayoutParams).apply { height = alto }
        }
        raiz.addView(capa, FrameLayout.LayoutParams(MATCH, MATCH))
        velo = capa
        capa.animate().alpha(1f).setDuration(160).start()
        tarjeta.translationY = dp(60f).toFloat()
        tarjeta.animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    fun cerrar(animar: Boolean = true) {
        val capa = velo ?: return
        velo = null
        if (!animar) {
            raiz.removeView(capa)
            return
        }
        capa.animate().alpha(0f).setDuration(140).withEndAction { raiz.removeView(capa) }.start()
    }

    private fun dp(valor: Float) = contexto.dp(valor)
}

/** Evita que un toque "atraviese" una capa y llegue a lo de abajo. */
fun View.bloquearToques() {
    setOnTouchListener { _, evento -> evento.action != MotionEvent.ACTION_CANCEL }
}

/** Interruptor encendido/apagado dibujado a mano, con el color que se le pida. */
class Interruptor(
    contexto: Context,
    private var activo: Boolean,
    private val color: Int,
    private val alCambiar: (Boolean) -> Unit,
) : View(contexto) {
    private val pista = Paint(Paint.ANTI_ALIAS_FLAG)
    private val perilla = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colores.TEXTO }
    private val caja = RectF()
    private var posicion = if (activo) 1f else 0f

    init {
        isClickable = true
        setOnClickListener { alternar() }
        contentDescription = if (activo) "Activado" else "Desactivado"
    }

    fun alternar() {
        activo = !activo
        contentDescription = if (activo) "Activado" else "Desactivado"
        ValueAnimator.ofFloat(posicion, if (activo) 1f else 0f).apply {
            duration = 180
            addUpdateListener {
                posicion = it.animatedValue as Float
                invalidate()
            }
        }.start()
        alCambiar(activo)
    }

    override fun onDraw(lienzo: Canvas) {
        val alto = height.toFloat()
        val r = alto / 2f
        caja.set(0f, 0f, width.toFloat(), alto)
        pista.shader = null
        pista.color = Colores.mezclar(Colores.SUPERFICIE_ALTA, color, posicion)
        lienzo.drawRoundRect(caja, r, r, pista)
        val margen = alto * 0.14f
        val x = r + (width - 2 * r) * posicion
        perilla.alpha = (190 + 65 * posicion).toInt()
        lienzo.drawCircle(x, r, r - margen, perilla)
    }
}
