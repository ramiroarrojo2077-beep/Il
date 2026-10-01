package ar.rama.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ar.rama.ai.motor.NivelPensar
import ar.rama.ai.motor.PasoRama
import ar.rama.ai.motor.Respaldo
import ar.rama.ai.motor.Resultado
import ar.rama.ai.motor.RespuestaRama
import ar.rama.ai.motor.TipoPaso
import java.util.Locale

/**
 * La tarjeta "Pensando…" que acompaña a cada respuesta: muestra en vivo los
 * pasos (habilidades, búsqueda, lectura) y el razonamiento del modelo, con el
 * nivel elegido. Al terminar se pliega y queda tocable para releerla.
 */
class TarjetaPensar(
    private val contexto: Context,
    private val nivel: NivelPensar,
    private val alCrecer: () -> Unit,
) {
    private val color = Colores.TEXTO_2
    private val principal = Handler(Looper.getMainLooper())
    private val inicio = SystemClock.elapsedRealtime()
    private var finPensar = 0L
    private var cerrada = false
    private var abierta = true
    private val textoPensado = StringBuilder()
    private var pintarPendiente = false
    private var cantidadPasos = 0

    val vista: LinearLayout = LinearLayout(contexto).apply {
        orientation = LinearLayout.VERTICAL
        background = redondeado(Colores.SUPERFICIE, dp(12f).toFloat(), Colores.BORDE_TENUE, dp(1f))
        setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
    }
    private val titulo = TextView(contexto).apply {
        text = if (nivel.piensa) "Pensando…" else "Preparando…"
        estilo(13f, color, Peso.MEDIO, 1f)
    }
    private val tiempo = TextView(contexto).apply { estilo(12f, Colores.TEXTO_3, Peso.MEDIO, 1f) }
    private val flecha = ImageView(contexto).apply {
        setImageDrawable(Icono(Trazos.chevron(), Colores.TEXTO_3, 2.2f))
        rotation = 180f
    }
    private val cuerpo = LinearLayout(contexto).apply { orientation = LinearLayout.VERTICAL }
    private val pasos = LinearLayout(contexto).apply { orientation = LinearLayout.VERTICAL }
    private val razonamiento = TextView(contexto).apply {
        estilo(12.5f, Colores.TEXTO_2, Peso.NORMAL, 1.35f)
        setTypeface(typeface, android.graphics.Typeface.ITALIC)
        visibility = View.GONE
    }
    private val reloj = object : Runnable {
        override fun run() {
            if (cerrada) return
            tiempo.text = segundos((if (finPensar > 0) finPensar else SystemClock.elapsedRealtime()) - inicio)
            if (finPensar == 0L) principal.postDelayed(this, 100)
        }
    }

    init {
        val cabecera = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val (trazo, relleno) = Trazos.nivel(nivel)
        cabecera.addView(ImageView(contexto).apply { setImageDrawable(Icono(trazo, Colores.ACENTO_CLARO, 2f, relleno)) },
            lp(contexto.dp(16f), contexto.dp(16f)) { rightMargin = contexto.dp(8f) })
        cabecera.addView(titulo, lp(0, WRAP, 1f))
        cabecera.addView(tiempo, lp(WRAP, WRAP) { rightMargin = contexto.dp(8f) })
        cabecera.addView(flecha, lp(contexto.dp(15f), contexto.dp(15f)))
        vista.addView(cabecera)
        cuerpo.addView(pasos)
        cuerpo.addView(razonamiento, lp(MATCH, WRAP) { topMargin = contexto.dp(6f) })
        vista.addView(cuerpo, lp(MATCH, WRAP) { topMargin = contexto.dp(8f) })
        vista.setOnClickListener { alternar() }
        principal.post(reloj)
    }

    fun agregarPaso(paso: PasoRama) {
        if (paso.tipo == TipoPaso.MOTOR && !nivel.piensa) titulo.text = "Escribiendo…"
        cantidadPasos++
        val (trazo, tono) = iconoDe(paso.tipo)
        val fila = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, contexto.dp(4f), 0, contexto.dp(4f))
        }
        fila.addView(ImageView(contexto).apply { setImageDrawable(Icono(trazo, tono, 2.1f)) },
            lp(contexto.dp(14f), contexto.dp(14f)) { rightMargin = contexto.dp(8f); topMargin = contexto.dp(2f) })
        val textos = LinearLayout(contexto).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(TextView(contexto).apply {
            text = paso.titulo
            estilo(12.5f, Colores.TEXTO_2, Peso.MEDIO, 1.1f)
        })
        textos.addView(TextView(contexto).apply {
            text = paso.detalle
            estilo(12f, Colores.TEXTO_2, Peso.NORMAL, 1.3f)
            maxLines = 7
            ellipsize = TextUtils.TruncateAt.END
        })
        fila.addView(textos, lp(0, WRAP, 1f))
        pasos.addView(fila)
        alCrecer()
    }

    fun agregarPensamiento(fragmento: String) {
        textoPensado.append(fragmento)
        if (pintarPendiente) return
        pintarPendiente = true
        principal.postDelayed({
            pintarPendiente = false
            if (cerrada) return@postDelayed
            razonamiento.visibility = View.VISIBLE
            val limpio = textoPensado.toString().trim()
            razonamiento.text = if (limpio.length > 650) "…" + limpio.takeLast(650).substringAfter(' ') else limpio
            alCrecer()
        }, 70)
    }

    /** Llegó el primer pedazo de respuesta: el razonamiento terminó. */
    fun terminarPensar() {
        if (finPensar != 0L) return
        finPensar = SystemClock.elapsedRealtime()
        titulo.text = if (nivel.piensa && textoPensado.isNotBlank()) "Pensó ${segundos(finPensar - inicio)}" else "Listo"
        tiempo.text = ""
        plegar(true)
    }

    fun cerrar(respuesta: RespuestaRama?) {
        if (finPensar == 0L) finPensar = SystemClock.elapsedRealtime()
        cerrada = true
        principal.removeCallbacks(reloj)
        val partes = ArrayList<String>()
        if (respuesta != null && respuesta.pensamiento.isNotBlank()) {
            partes.add("Pensó " + segundos(if (respuesta.milisPensando > 0) respuesta.milisPensando else finPensar - inicio))
            if (respuesta.tokensPensados > 0) partes.add("${respuesta.tokensPensados} tokens")
        }
        if (cantidadPasos > 0) partes.add(if (cantidadPasos == 1) "1 paso" else "$cantidadPasos pasos")
        titulo.text = if (partes.isEmpty()) "Nivel ${nivel.nombre}" else partes.joinToString(" · ")
        tiempo.text = nivel.nombre
        tiempo.setTextColor(Colores.TEXTO_3)
        val completo = respuesta?.pensamiento?.trim().orEmpty().ifEmpty { textoPensado.toString().trim() }
        if (completo.isNotEmpty()) {
            razonamiento.visibility = View.VISIBLE
            razonamiento.text = completo
        }
        plegar(true)
    }

    private fun alternar() = plegar(abierta)

    private fun plegar(plegada: Boolean) {
        abierta = !plegada
        cuerpo.visibility = if (plegada) View.GONE else View.VISIBLE
        flecha.animate().rotation(if (plegada) 0f else 180f).setDuration(180).start()
        if (!plegada) alCrecer()
    }

    private fun segundos(milis: Long): String = String.format(Locale("es", "AR"), "%.1f s", milis / 1000f)

    private fun iconoDe(tipo: TipoPaso) = when (tipo) {
        TipoPaso.HABILIDAD -> Trazos.calculadora() to Colores.TEXTO_3
        TipoPaso.BASE -> Trazos.libro() to Colores.TEXTO_3
        TipoPaso.WEB -> Trazos.globo() to Colores.TEXTO_3
        TipoPaso.LECTURA -> Trazos.ojo() to Colores.TEXTO_3
        TipoPaso.HERRAMIENTA -> Trazos.destello() to Colores.TEXTO_3
        TipoPaso.MOTOR -> Trazos.chip() to Colores.TEXTO_3
        TipoPaso.PRESUPUESTO -> Trazos.reloj() to Colores.AVISO
        TipoPaso.ADJUNTO -> Trazos.archivo() to Colores.TEXTO_3
        TipoPaso.AVISO -> Trazos.advertencia() to Colores.PELIGRO
    }
}

/** Una respuesta de Rama en el chat: cabecera, razonamiento opcional, texto y pie. */
class VistaRespuesta(
    private val contexto: Context,
    nivel: NivelPensar?,
    private val alCopiar: (String) -> Unit,
) {
    val vista = LinearLayout(contexto).apply { orientation = LinearLayout.VERTICAL }
    // La respuesta va sin caja, directo sobre el fondo, alineada con la cabecera.
    private val tarjetaTexto = LinearLayout(contexto).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(contexto.dp(2f), 0, contexto.dp(2f), 0)
    }
    val cuerpo = TextView(contexto).apply {
        estilo(15.5f, Colores.TEXTO, Peso.NORMAL, 1.5f)
        setTextIsSelectable(false)
        setOnLongClickListener {
            alCopiar(text.toString())
            true
        }
    }
    val pie = LinearLayout(contexto).apply {
        orientation = LinearLayout.VERTICAL
    }
    var tarjeta: TarjetaPensar? = null
        private set
    private var puntos: PuntosPensando? = null

    init {
        val cabecera = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cabecera.addView(contexto.avatarRama(22f), lp(contexto.dp(22f), contexto.dp(22f)) { rightMargin = contexto.dp(8f) })
        cabecera.addView(TextView(contexto).apply {
            text = "Rama"
            estilo(13.5f, Colores.TEXTO, Peso.NEGRITA, 1f)
        })
        if (nivel != null) {
            val (trazo, relleno) = Trazos.nivel(nivel)
            cabecera.addView(TextView(contexto).apply {
                text = "· " + nivel.nombre
                estilo(12.5f, Colores.TEXTO_3, Peso.MEDIO, 1f)
            }, lp(WRAP, WRAP) { leftMargin = contexto.dp(6f) })
        }
        vista.addView(cabecera, lp(WRAP, WRAP) { bottomMargin = contexto.dp(8f) })
        tarjetaTexto.addView(cuerpo)
        vista.addView(tarjetaTexto, lp(MATCH, WRAP))
        vista.addView(pie, lp(MATCH, WRAP))
    }

    fun agregarTarjeta(nivel: NivelPensar, alCrecer: () -> Unit): TarjetaPensar {
        val nueva = TarjetaPensar(contexto, nivel, alCrecer)
        vista.addView(nueva.vista, 1, lp(MATCH, WRAP) { bottomMargin = contexto.dp(8f) })
        tarjeta = nueva
        return nueva
    }

    fun mostrarPuntos() {
        if (puntos != null) return
        val p = PuntosPensando(contexto)
        puntos = p
        tarjetaTexto.addView(p, lp(contexto.dp(40f), contexto.dp(18f)))
    }

    fun quitarPuntos() {
        puntos?.let { tarjetaTexto.removeView(it) }
        puntos = null
    }

    fun mostrarTexto(texto: CharSequence, conEnlaces: Boolean) {
        quitarPuntos()
        cuerpo.text = texto
        if (conEnlaces) cuerpo.movementMethod = LinkMovementMethod.getInstance()
    }

    /** Sello de respaldo + botón copiar. */
    fun agregarPie(respaldo: Respaldo, alExplicar: (String) -> Unit) {
        val fila = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val color = when (respaldo) {
            Respaldo.CALCULO -> Colores.TEXTO_3
            Respaldo.WEB -> Colores.TEXTO_3
            Respaldo.BASE -> Colores.TEXTO_3
            Respaldo.SOLO_MODELO -> Colores.AVISO
        }
        val trazo = when (respaldo) {
            Respaldo.CALCULO -> Trazos.calculadora()
            Respaldo.WEB -> Trazos.globo()
            Respaldo.BASE -> Trazos.libro()
            Respaldo.SOLO_MODELO -> Trazos.advertencia()
        }
        val sello = contexto.pastilla(respaldo.etiqueta, color, trazo).apply {
            setOnClickListener { alExplicar(respaldo.explicacion) }
        }
        fila.addView(sello)
        fila.addView(View(contexto), lp(0, 1, 1f))
        fila.addView(contexto.botonIcono(Trazos.copiar(), "Copiar respuesta", 32f, 15f, Colores.TEXTO_3, 0, 0) {
            alCopiar(cuerpo.text.toString())
        })
        pie.addView(fila, lp(MATCH, WRAP) { topMargin = contexto.dp(8f) })
    }

    /** Las fuentes consultadas, como fichas de colores que se deslizan de costado. */
    fun agregarFuentes(fuentes: List<Resultado>, alAbrir: (String) -> Unit) {
        if (fuentes.isEmpty()) return
        pie.addView(contexto.rotulo("Fuentes"), lp(WRAP, WRAP) { topMargin = contexto.dp(12f); bottomMargin = contexto.dp(6f) })
        val carrusel = HorizontalScrollView(contexto).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val fila = LinearLayout(contexto).apply { orientation = LinearLayout.HORIZONTAL }
        val tono = Colores.TEXTO_2
        fuentes.forEachIndexed { i, fuente ->
            val ficha = LinearLayout(contexto).apply {
                orientation = LinearLayout.VERTICAL
                background = pulsable(Colores.SUPERFICIE, contexto.dp(12f).toFloat(), Colores.BORDE_TENUE, contexto.dp(1f))
                setPadding(contexto.dp(11f), contexto.dp(9f), contexto.dp(11f), contexto.dp(9f))
                setOnClickListener { alAbrir(fuente.url) }
            }
            val arriba = LinearLayout(contexto).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            arriba.addView(TextView(contexto).apply {
                text = (i + 1).toString()
                estilo(11f, Colores.TEXTO_2, Peso.NEGRITA, 1f)
                gravity = Gravity.CENTER
                background = redondeado(Colores.SUPERFICIE_ALTA, contexto.dp(9f).toFloat())
            }, lp(contexto.dp(18f), contexto.dp(18f)) { rightMargin = contexto.dp(7f) })
            arriba.addView(TextView(contexto).apply {
                text = ar.rama.ai.motor.Html.dominio(fuente.url)
                estilo(11.5f, tono, Peso.MEDIO, 1f)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            })
            ficha.addView(arriba)
            ficha.addView(TextView(contexto).apply {
                text = fuente.titulo
                estilo(12.5f, Colores.TEXTO, Peso.MEDIO, 1.25f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, lp(MATCH, WRAP) { topMargin = contexto.dp(5f) })
            fila.addView(ficha, lp(contexto.dp(190f), WRAP) { rightMargin = contexto.dp(8f) })
        }
        carrusel.addView(fila)
        pie.addView(carrusel, lp(MATCH, WRAP))
    }
}
