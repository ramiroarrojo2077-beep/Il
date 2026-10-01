package ar.rama.ai

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ar.rama.ai.motor.Catalogo
import ar.rama.ai.motor.Descargador
import ar.rama.ai.motor.Edicion
import ar.rama.ai.motor.Gguf
import ar.rama.ai.motor.Variante
import ar.rama.ai.motor.Identidad
import ar.rama.ai.motor.Llama
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * La pantalla del modelo Rama: qué es, qué edición conviene para este
 * teléfono, la descarga con su progreso y las opciones para usar o borrar.
 */
class PantallaModelo(
    private val actividad: Activity,
    private val raiz: FrameLayout,
    private val descargas: DescargaEnSegundoPlano,
    private val modeloActivo: () -> File?,
    private val planActivo: () -> ar.rama.ai.motor.PlanDeMemoria?,
    private val ahorroRam: () -> Boolean,
    private val cargando: () -> Boolean,
    private val alUsar: (File) -> Unit,
    private val alQuitar: () -> Unit,
    private val alBorrar: (File) -> Unit,
    private val alImportar: () -> Unit,
    private val alCopiar: (String, String) -> Unit,
    private val alTerminarDescarga: (File) -> Unit,
) {
    private val principal = Handler(Looper.getMainLooper())
    private val red = Executors.newSingleThreadExecutor()
    private val tarjetas = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
    private val resolviendo = HashSet<String>()
    private val errores = HashMap<String, String>()
    private val enCurso = HashSet<String>()
    val vista: View = construir()
    val visible: Boolean get() = vista.visibility == View.VISIBLE

    val ramDelTelefono: Int by lazy {
        try {
            val gestor = actividad.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            gestor.getMemoryInfo(info)
            ceil(info.totalMem / 1_000_000_000.0).toInt()
        } catch (e: Exception) {
            0
        }
    }

    private var ultimaFirma = ""

    private val vigilar = object : Runnable {
        override fun run() {
            revisarDescargas()
            if (visible && firma() != ultimaFirma) refrescar()
            if (visible || enCurso.isNotEmpty()) principal.postDelayed(this, 1000)
        }
    }

    /** Resume el estado de todo lo que se muestra: si no cambió, no hace falta redibujar. */
    private fun firma(): String = Catalogo.EDICIONES.joinToString("|") { e ->
        val estado = when (val s = descargas.estado(e)) {
            is EstadoDescarga.EnCurso -> "c" + (s.bajados / 4_000_000) + s.enPausa
            else -> s.javaClass.simpleName
        }
        e.id + estado + (e.id in resolviendo) + errores[e.id] + descargas.variante(e, ramDelTelefono)?.id
    } + modeloActivo()?.absolutePath + cargando() + ahorroRam()

    init {
        // Si quedó una descarga andando de antes, la seguimos aunque la pantalla esté cerrada.
        for (e in Catalogo.EDICIONES) if (descargas.estado(e) is EstadoDescarga.EnCurso) enCurso.add(e.id)
        if (enCurso.isNotEmpty()) principal.postDelayed(vigilar, 1000)
    }

    private fun construir(): View {
        val capa = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Colores.FONDO)
            visibility = View.GONE
            isClickable = true
        }
        val cabecera = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(8f))
        }
        cabecera.addView(actividad.botonIcono(Trazos.atras(), "Volver", alTocar = { ocultar() }))
        cabecera.addView(TextView(actividad).apply {
            text = "Modelo Rama"
            estilo(20f, Colores.TEXTO, Peso.NEGRITA, 1f)
        }, lp(0, WRAP, 1f) { leftMargin = dp(12f) })
        capa.addView(cabecera)
        val desplazable = ScrollView(actividad).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(16f), dp(4f), dp(16f), dp(28f))
            addView(tarjetas)
        }
        capa.addView(desplazable, lp(MATCH, 0, 1f))
        raiz.addView(capa, FrameLayout.LayoutParams(MATCH, MATCH))
        return capa
    }

    fun mostrar() {
        refrescar()
        vista.visibility = View.VISIBLE
        vista.translationX = vista.width.toFloat().coerceAtLeast(dp(300f).toFloat())
        vista.alpha = 0.6f
        vista.animate().translationX(0f).alpha(1f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
        principal.removeCallbacks(vigilar)
        principal.postDelayed(vigilar, 1000)
    }

    fun ocultar() {
        if (!visible) return
        vista.animate().translationX(vista.width.toFloat()).alpha(0.4f).setDuration(200).withEndAction {
            vista.visibility = View.GONE
        }.start()
    }

    fun refrescar() {
        ultimaFirma = firma()
        tarjetas.removeAllViews()
        tarjetas.addView(heroe(), lp(MATCH, WRAP) { bottomMargin = dp(14f) })
        if (!Llama.disponible) {
            tarjetas.addView(aviso(
                "Este teléfono no puede correr el modelo",
                (Llama.motivoNoDisponible ?: "No se pudo cargar el motor.") + "\n\nRama sigue funcionando con sus habilidades exactas, su base propia y la búsqueda web.",
                Colores.PELIGRO,
            ), lp(MATCH, WRAP) { bottomMargin = dp(14f) })
        }
        val recomendada = Catalogo.recomendada(ramDelTelefono)
        tarjetas.addView(TextView(actividad).apply {
            text = if (ramDelTelefono > 0) "Tu teléfono tiene unos $ramDelTelefono GB de RAM: te recomiendo ${recomendada.nombre}, la más potente que entra. Es el mismo modelo en tres tamaños: bajá uno solo."
            else "No pude leer la memoria del teléfono. Si dudás, empezá por ${Catalogo.LIVIANA.nombre}."
            estilo(13.5f, Colores.TEXTO_2, Peso.NORMAL, 1.35f)
        }, lp(MATCH, WRAP) { bottomMargin = dp(12f) })
        for (edicion in Catalogo.EDICIONES) {
            tarjetas.addView(tarjetaEdicion(descargas.efectiva(edicion, ramDelTelefono), edicion.id == recomendada.id), lp(MATCH, WRAP) { bottomMargin = dp(12f) })
        }
        tarjetas.addView(tarjetaManual(), lp(MATCH, WRAP) { topMargin = dp(4f); bottomMargin = dp(12f) })
        val viejos = descargas.huerfanos()
        if (viejos.isNotEmpty()) tarjetas.addView(tarjetaHuerfanos(viejos), lp(MATCH, WRAP) { bottomMargin = dp(12f) })
        tarjetas.addView(TextView(actividad).apply {
            text = "Rama ${Identidad.VERSION} · modelo de código abierto construido sobre ${Catalogo.BASE} (${Catalogo.LICENCIA}). " +
                "Motor: llama.cpp (MIT). Tipografía: Inter (OFL). Todo corre en tu teléfono: las preguntas no salen del dispositivo, salvo las búsquedas web que vos permitís."
            estilo(11.5f, Colores.TEXTO_3, Peso.NORMAL, 1.4f)
            gravity = Gravity.CENTER
        }, lp(MATCH, WRAP) { topMargin = dp(6f) })
    }

    private fun heroe(): View {
        val caja = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            background = redondeado(Colores.SUPERFICIE, dp(16f).toFloat(), Colores.BORDE_TENUE, dp(1f))
            setPadding(dp(18f), dp(18f), dp(18f), dp(16f))
        }
        val fila = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = FrameLayout(actividad).apply {
            background = redondeado(Colores.SUPERFICIE_ALTA, dp(12f).toFloat(), Colores.BORDE, dp(1f))
            addView(ImageView(actividad).apply { setImageDrawable(Icono(Trazos.rama(), Colores.TEXTO, 2.4f)) }, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dp(10f), dp(10f), dp(10f), dp(10f)) })
            addView(ImageView(actividad).apply { setImageDrawable(Icono(Trazos.ramaBrotes(), Colores.ACENTO_CLARO, 0f, true)) }, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dp(10f), dp(10f), dp(10f), dp(10f)) })
        }
        fila.addView(logo, lp(dp(48f), dp(48f)) { rightMargin = dp(14f) })
        val textos = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(TextView(actividad).apply {
            text = "Rama"
            estilo(22f, Colores.TEXTO, Peso.NEGRITA, 1f)
        })
        textos.addView(TextView(actividad).apply {
            text = "Modelo de IA de código abierto"
            estilo(13f, Colores.TEXTO_2, Peso.MEDIO, 1.1f)
        }, lp(WRAP, WRAP) { topMargin = dp(3f) })
        fila.addView(textos, lp(0, WRAP, 1f))
        caja.addView(fila)
        caja.addView(TextView(actividad).apply {
            text = "Un solo modelo que piensa dentro de tu teléfono, sale a buscar a la web cuando hace falta y te deja elegir cuánto razona antes de contestar."
            estilo(13.5f, Colores.TEXTO_2, Peso.NORMAL, 1.4f)
        }, lp(MATCH, WRAP) { topMargin = dp(12f) })
        val capacidades = LinearLayout(actividad).apply { orientation = LinearLayout.HORIZONTAL }
        for ((texto, trazo) in listOf(
            "Búsqueda web" to Trazos.globo(),
            "4 niveles" to Trazos.destello(),
            "Privado" to Trazos.chip(),
        )) {
            capacidades.addView(TextView(actividad).apply {
                text = texto
                estilo(12f, Colores.TEXTO_2, Peso.MEDIO, 1f)
                relleno(dp(9f), dp(6f))
                background = redondeado(Colores.SUPERFICIE_ALTA, dp(8f).toFloat(), Colores.BORDE_TENUE, dp(1f))
                val icono = Icono(trazo, Colores.TEXTO_3, 2.2f)
                icono.setBounds(0, 0, dp(12f), dp(12f))
                setCompoundDrawables(icono, null, null, null)
                compoundDrawablePadding = dp(5f)
            }, lp(WRAP, WRAP) { rightMargin = dp(6f) })
        }
        val carrusel = android.widget.HorizontalScrollView(actividad).apply {
            isHorizontalScrollBarEnabled = false
            addView(capacidades)
        }
        caja.addView(carrusel, lp(MATCH, WRAP) { topMargin = dp(14f) })
        return caja
    }

    private fun tarjetaEdicion(edicion: Edicion, recomendada: Boolean): View {
        val archivo = descargas.archivoDe(edicion)
        val activo = modeloActivo()?.absolutePath == archivo.absolutePath
        val estado = descargas.estado(edicion)
        val radio = dp(16f).toFloat()
        val caja = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            background = if (activo) redondeado(Colores.SUPERFICIE, radio, Colores.alfa(Colores.ACENTO, 0.7f), dp(1.5f))
            else redondeado(Colores.SUPERFICIE, radio, if (recomendada) Colores.BORDE else Colores.BORDE_TENUE, dp(1f))
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        val fila = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fila.addView(ImageView(actividad).apply {
            setImageDrawable(Icono(Trazos.chip(), if (activo || recomendada) Colores.ACENTO_CLARO else Colores.TEXTO_2, 1.9f))
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            background = redondeado(Colores.SUPERFICIE_ALTA, dp(10f).toFloat(), Colores.BORDE_TENUE, dp(1f))
        }, lp(dp(42f), dp(42f)) { rightMargin = dp(12f) })
        val textos = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(TextView(actividad).apply {
            text = edicion.nombre
            estilo(16.5f, Colores.TEXTO, Peso.NEGRITA, 1f)
        })
        textos.addView(TextView(actividad).apply {
            text = "${edicion.parametros} parámetros · descarga ${peso(edicion.bytesAproximados)} · usa ~${edicion.memoriaTipica.totalLegible} de RAM"
            estilo(12f, Colores.TEXTO_3, Peso.MEDIO, 1.2f)
        }, lp(WRAP, WRAP) { topMargin = dp(3f) })
        fila.addView(textos, lp(0, WRAP, 1f))
        caja.addView(fila)
        val sellos = LinearLayout(actividad).apply { orientation = LinearLayout.HORIZONTAL }
        if (activo) {
            val plan = planActivo()
            val texto = when {
                cargando() -> "Cargando…"
                plan != null && plan.enAhorro -> "En uso · ahorro de RAM · ${plan.enUsoLegible}"
                plan != null -> "En uso · ${plan.contexto} tokens · ${plan.totalLegible}"
                else -> "En uso"
            }
            sellos.addView(actividad.pastilla(texto, Colores.EXITO, Trazos.visto()), lp(WRAP, WRAP) { rightMargin = dp(6f) })
        }
        if (recomendada) sellos.addView(actividad.pastilla("Recomendada para vos", Colores.ACENTO_CLARO, Trazos.destello(), true), lp(WRAP, WRAP) { rightMargin = dp(6f) })
        if (edicion.id == Catalogo.ULTRA.id && !activo) sellos.addView(actividad.pastilla("La más potente", Colores.TEXTO_2, Trazos.fuego(), true), lp(WRAP, WRAP) { rightMargin = dp(6f) })
        if (estado is EstadoDescarga.Terminada && archivo.exists()) {
            cuantizacionDe(archivo)?.let { sellos.addView(actividad.pastilla(it, Colores.TEXTO_2), lp(WRAP, WRAP) { rightMargin = dp(6f) }) }
        }
        if (sellos.childCount > 0) caja.addView(sellos, lp(MATCH, WRAP) { topMargin = dp(12f) })
        caja.addView(TextView(actividad).apply {
            text = edicion.descripcion
            estilo(13.5f, Colores.TEXTO_2, Peso.NORMAL, 1.4f)
        }, lp(MATCH, WRAP) { topMargin = dp(10f) })
        if (edicion.id == Catalogo.ULTRA.id) caja.addView(requisitoDeMemoria(edicion), lp(MATCH, WRAP) { topMargin = dp(10f) })

        val acciones = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
        when {
            edicion.id in resolviendo -> {
                acciones.addView(TextView(actividad).apply {
                    text = "Buscando el archivo en Hugging Face…"
                    estilo(12.5f, Colores.TEXTO_2, Peso.MEDIO, 1.2f)
                })
                acciones.addView(BarraProgreso(actividad).apply { indeterminada = true }, lp(MATCH, dp(6f)) { topMargin = dp(8f) })
            }
            estado is EstadoDescarga.EnCurso -> {
                val texto = if (estado.totales > 0) String.format(Locale("es", "AR"), "%s de %s · %.0f%%", peso(estado.bajados), peso(estado.totales), estado.fraccion * 100)
                else "Empezando… ${peso(estado.bajados)}"
                acciones.addView(TextView(actividad).apply {
                    text = if (estado.enPausa) "$texto · en pausa (esperando red)" else texto
                    estilo(12.5f, Colores.TEXTO_2, Peso.MEDIO, 1.2f)
                })
                acciones.addView(BarraProgreso(actividad).apply {
                    if (estado.totales > 0) fraccion = estado.fraccion else indeterminada = true
                }, lp(MATCH, dp(6f)) { topMargin = dp(8f) })
                acciones.addView(actividad.boton("Cancelar descarga", trazo = Trazos.cerrar()) {
                    descargas.cancelar(edicion)
                    enCurso.remove(edicion.id)
                    refrescar()
                }, lp(MATCH, WRAP) { topMargin = dp(12f) })
            }
            estado is EstadoDescarga.Terminada && archivo.exists() -> {
                if (activo) {
                    acciones.addView(actividad.boton("Quitar de la memoria", trazo = Trazos.cerrar()) {
                        alQuitar()
                        refrescar()
                    }, lp(MATCH, WRAP))
                } else {
                    acciones.addView(actividad.boton("Usar ${edicion.nombre}", Colores.ACENTO, Trazos.visto()) {
                        ocultar()
                        alUsar(archivo)
                    }, lp(MATCH, WRAP))
                }
                acciones.addView(actividad.boton("Borrar del teléfono (${peso(archivo.length())})", trazo = Trazos.papelera()) {
                    alBorrar(archivo)
                    refrescar()
                }, lp(MATCH, WRAP) { topMargin = dp(8f) })
            }
            else -> {
                val error = errores[edicion.id] ?: (estado as? EstadoDescarga.Fallo)?.motivo
                if (error != null) {
                    acciones.addView(TextView(actividad).apply {
                        text = "No se pudo bajar: $error"
                        estilo(12.5f, Colores.PELIGRO, Peso.MEDIO, 1.3f)
                    }, lp(MATCH, WRAP) { bottomMargin = dp(8f) })
                }
                if (edicion.variantes.isNotEmpty()) acciones.addView(selectorDeVariante(edicion), lp(MATCH, WRAP) { bottomMargin = dp(12f) })
                acciones.addView(actividad.boton(if (error != null) "Reintentar" else "Descargar ${edicion.nombre} (${peso(edicion.bytesAproximados)})", if (recomendada) Colores.ACENTO else null, Trazos.descargar()) {
                    descargar(edicion)
                }, lp(MATCH, WRAP))
            }
        }
        caja.addView(acciones, lp(MATCH, WRAP) { topMargin = dp(14f) })
        return caja
    }

    /** Dos pastillas para elegir cómo guardar los pesos (menos RAM o más precisión). */
    private fun selectorDeVariante(edicion: Edicion): View {
        val caja = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
        val elegida = descargas.variante(edicion, ramDelTelefono)
        caja.addView(actividad.rotulo("Memoria"), lp(WRAP, WRAP) { bottomMargin = dp(8f) })
        val fila = LinearLayout(actividad).apply { orientation = LinearLayout.HORIZONTAL }
        edicion.variantes.forEachIndexed { i, v ->
            val sel = v.id == elegida?.id
            val radio = dp(12f).toFloat()
            fila.addView(LinearLayout(actividad).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
                background = if (sel) pulsable(Colores.mezclar(Colores.SUPERFICIE_ALTA, Colores.ACENTO, 0.10f), radio, Colores.ACENTO, dp(1.5f))
                else pulsable(Colores.SUPERFICIE_ALTA, radio, Colores.BORDE, dp(1f))
                addView(TextView(actividad).apply {
                    text = v.nombre
                    estilo(13.5f, if (sel) Colores.TEXTO else Colores.TEXTO_2, Peso.NEGRITA, 1f)
                    gravity = Gravity.CENTER
                })
                val uso = ar.rama.ai.motor.PlanDeMemoria.estimar(v.bytesAproximados, edicion.elementosKvPorToken, 4096)
                addView(TextView(actividad).apply {
                    text = "${v.cuantizacion} · usa ~${uso.totalLegible}"
                    estilo(11.5f, if (sel) Colores.ACENTO_CLARO else Colores.TEXTO_3, Peso.MEDIO, 1f)
                    gravity = Gravity.CENTER
                }, lp(WRAP, WRAP) { topMargin = dp(3f) })
                setOnClickListener {
                    descargas.elegirVariante(edicion, v)
                    refrescar()
                }
            }, lp(0, WRAP, 1f) { if (i < edicion.variantes.size - 1) rightMargin = dp(8f) })
        }
        caja.addView(fila)
        if (elegida != null) caja.addView(TextView(actividad).apply {
            text = elegida.descripcion
            estilo(12.5f, Colores.TEXTO_2, Peso.NORMAL, 1.3f)
        }, lp(MATCH, WRAP) { topMargin = dp(8f) })
        return caja
    }

    /** Cuánta memoria libre pide para abrir: normal y en modo ahorro. */
    private fun requisitoDeMemoria(edicion: Edicion): View {
        val P = ar.rama.ai.motor.PlanDeMemoria
        val completo = P.minimoParaAbrir(edicion.bytesAproximados, edicion.elementosKvPorToken, false)
        val ahorro = P.minimoParaAbrir(edicion.bytesAproximados, edicion.elementosKvPorToken, true)
        val activo = ahorroRam()
        return LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            background = redondeado(Colores.SUPERFICIE_ALTA, dp(12f).toFloat(), Colores.BORDE_TENUE, dp(1f))
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            addView(TextView(actividad).apply {
                text = if (activo) "Pide desde ${P.legible(ahorro)} libres" else "Pide ${P.legible(completo)} libres"
                estilo(13.5f, Colores.TEXTO, Peso.NEGRITA, 1.1f)
            })
            addView(TextView(actividad).apply {
                text = if (activo) "Con Ahorro de RAM: si no entra entera, lee del almacenamiento lo que falta (más lenta). Con ${P.legible(completo)} libres va a velocidad completa."
                else "Activá Ahorro de RAM en Ajustes para abrirla desde ${P.legible(ahorro)} libres (más lenta)."
                estilo(12f, Colores.TEXTO_2, Peso.NORMAL, 1.3f)
            }, lp(MATCH, WRAP) { topMargin = dp(3f) })
        }
    }

    private val cuantizaciones = HashMap<String, String?>()

    /** La cuantización real del archivo bajado, leída de su cabecera (con caché). */
    private fun cuantizacionDe(archivo: File): String? {
        val clave = archivo.absolutePath + ":" + archivo.length() + ":" + archivo.lastModified()
        return cuantizaciones.getOrPut(clave) { Gguf.leer(archivo)?.cuantizacion }
    }

    private fun tarjetaManual(): View {
        val caja = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            background = redondeado(Colores.SUPERFICIE, dp(16f).toFloat(), Colores.BORDE_TENUE, dp(1f))
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }
        caja.addView(actividad.rotulo("¿No baja?"))
        caja.addView(TextView(actividad).apply {
            text = "Copiá el enlace, bajalo desde el navegador y después elegí el archivo .gguf acá. También sirve para probar otro modelo GGUF que tengas."
            estilo(13f, Colores.TEXTO_2, Peso.NORMAL, 1.4f)
        }, lp(MATCH, WRAP) { topMargin = dp(8f) })
        val recomendada = Catalogo.recomendada(ramDelTelefono)
        caja.addView(actividad.boton("Copiar enlace de ${recomendada.nombre}", trazo = Trazos.enlace()) {
            alCopiar(Descargador.enlaceManual(recomendada), "Enlace copiado. Pegalo en el navegador, bajá el .gguf y volvé a elegirlo acá.")
        }, lp(MATCH, WRAP) { topMargin = dp(12f) })
        caja.addView(actividad.boton("Elegir un .gguf del teléfono", trazo = Trazos.documento()) {
            alImportar()
        }, lp(MATCH, WRAP) { topMargin = dp(8f) })
        return caja
    }

    private fun tarjetaHuerfanos(viejos: List<File>): View {
        val bytes = viejos.sumOf { it.length() }
        val caja = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            background = redondeado(Colores.SUPERFICIE, dp(16f).toFloat(), Colores.BORDE_TENUE, dp(1f))
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }
        caja.addView(actividad.rotulo("Modelos viejos"))
        caja.addView(TextView(actividad).apply {
            text = "${viejos.size} archivo(s) de la versión anterior ocupan ${peso(bytes)}. Rama ya no los usa: podés borrarlos."
            estilo(13f, Colores.TEXTO_2, Peso.NORMAL, 1.4f)
        }, lp(MATCH, WRAP) { topMargin = dp(8f) })
        caja.addView(actividad.boton("Borrar modelos viejos", Colores.PELIGRO, Trazos.papelera()) {
            for (f in viejos) {
                if (modeloActivo()?.absolutePath == f.absolutePath) alQuitar()
                f.delete()
            }
            refrescar()
        }, lp(MATCH, WRAP) { topMargin = dp(12f) })
        return caja
    }

    private fun aviso(titulo: String, cuerpo: String, color: Int): View {
        val caja = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            background = redondeado(Colores.alfa(color, 0.08f), dp(14f).toFloat(), Colores.alfa(color, 0.35f), dp(1f))
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        caja.addView(TextView(actividad).apply {
            text = titulo
            estilo(14.5f, color, Peso.NEGRITA, 1.2f)
        })
        caja.addView(TextView(actividad).apply {
            text = cuerpo
            estilo(13f, Colores.TEXTO_2, Peso.NORMAL, 1.4f)
        }, lp(MATCH, WRAP) { topMargin = dp(6f) })
        return caja
    }

    private fun descargar(edicion: Edicion) {
        if (edicion.id in resolviendo) return
        errores.remove(edicion.id)
        resolviendo.add(edicion.id)
        refrescar()
        red.execute {
            val resolucion = try {
                Descargador.resolver(edicion)
            } catch (e: Exception) {
                Descargador.Resolucion(null, listOf(e.message ?: e.javaClass.simpleName))
            }
            principal.post {
                resolviendo.remove(edicion.id)
                val url = resolucion.url
                if (url == null) {
                    errores[edicion.id] = "no encontré el archivo (¿hay internet?).\n" + resolucion.intentos.take(4).joinToString("\n")
                } else if (!descargas.encolar(edicion, url)) {
                    errores[edicion.id] = "el gestor de descargas del sistema no aceptó el pedido"
                } else {
                    enCurso.add(edicion.id)
                    principal.removeCallbacks(vigilar)
                    principal.postDelayed(vigilar, 800)
                }
                if (visible) refrescar()
            }
        }
    }

    /** Detecta descargas que terminaron (aunque la pantalla esté cerrada) y avisa. */
    private fun revisarDescargas() {
        val iterador = enCurso.iterator()
        while (iterador.hasNext()) {
            val id = iterador.next()
            val edicion = Catalogo.porId(id) ?: continue
            when (val estado = descargas.estado(edicion)) {
                is EstadoDescarga.Terminada -> {
                    iterador.remove()
                    alTerminarDescarga(estado.archivo)
                }
                is EstadoDescarga.Fallo -> {
                    iterador.remove()
                    errores[id] = estado.motivo
                }
                is EstadoDescarga.Ninguna -> iterador.remove()
                else -> {}
            }
        }
    }

    fun cerrar() {
        principal.removeCallbacks(vigilar)
        red.shutdownNow()
    }

    private fun peso(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale("es", "AR"), "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format(Locale("es", "AR"), "%.0f MB", bytes / 1_000_000.0)
        else -> String.format(Locale("es", "AR"), "%.0f KB", bytes / 1000.0)
    }

    private fun dp(valor: Float) = actividad.dp(valor)
}
