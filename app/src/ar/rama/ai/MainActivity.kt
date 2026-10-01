package ar.rama.ai

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ar.rama.ai.motor.AjustesRama
import ar.rama.ai.motor.Asistente
import ar.rama.ai.motor.Catalogo
import ar.rama.ai.motor.Conversaciones
import ar.rama.ai.motor.Descargador
import ar.rama.ai.motor.Estilo
import ar.rama.ai.motor.Estilos
import ar.rama.ai.motor.Gguf
import ar.rama.ai.motor.Identidad
import ar.rama.ai.motor.Llama
import ar.rama.ai.motor.Memoria
import ar.rama.ai.motor.AlmacenArchivo
import ar.rama.ai.motor.Mensaje
import ar.rama.ai.motor.MotorRama
import ar.rama.ai.motor.NivelPensar
import ar.rama.ai.motor.OyenteRama
import ar.rama.ai.motor.PasoRama
import ar.rama.ai.motor.PlanDeMemoria
import ar.rama.ai.motor.Rama
import ar.rama.ai.motor.RespuestaRama
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.ceil

class MainActivity : Activity() {

    // ------------------------------------------------------------ estado

    private var asistente: Asistente? = null
    private var motor: MotorRama? = null
    private var cargandoModelo = false
    private var modeloEnPausa = false
    private var generando = false
    /** true entre onStart y onStop: si no, la respuesta termina con una notificación. */
    private var enPrimerPlano = false

    @Volatile
    private var detener = false

    private var nivel = NivelPensar.PREDETERMINADO
    private var estilo: Estilo = Estilos.PREDETERMINADO
    private var buscarWeb = true
    private var mostrarRazonamiento = true
    /** Abrir modelos grandes aunque sus pesos no entren enteros en RAM (más lento). */
    private var ahorroRam = true

    private lateinit var conversaciones: Conversaciones
    private lateinit var descargas: DescargaEnSegundoPlano
    private var chatActual = ""
    private val historial = ArrayList<Mensaje>()
    private var ultimoAdjunto: Adjunto? = null

    private val trabajador = Executors.newSingleThreadExecutor { tarea ->
        Thread(tarea, "rama-motor").apply { isDaemon = true }
    }
    private val principal = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------ vistas

    private lateinit var raiz: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var lista: LinearLayout
    private lateinit var entrada: EditText
    private lateinit var botonEnviar: ImageView
    private lateinit var botonWeb: ImageView
    private lateinit var subtitulo: TextView
    private lateinit var puntoEstado: View
    private lateinit var chipModelo: TextView
    private lateinit var selector: SelectorNivel
    private lateinit var avisoModelo: View
    private lateinit var tostada: TextView
    private lateinit var hoja: HojaInferior
    private lateinit var pantallaChats: PantallaChats
    private lateinit var pantallaModelo: PantallaModelo
    private var bienvenida: View? = null
    private val ocultarTostada = Runnable { tostada.animate().alpha(0f).setDuration(250).start() }

    // ------------------------------------------------------------ ciclo de vida

    override fun onCreate(estadoGuardado: Bundle?) {
        super.onCreate(estadoGuardado)
        instalarReporteDeErrores()
        Fuentes.cargar(this)
        Markdown.sangria = dp(15f)
        window.statusBarColor = Colores.FONDO
        window.navigationBarColor = Colores.FONDO

        val prefs = preferencias()
        nivel = NivelPensar.porId(prefs.getString("nivel", null))
        estilo = Estilos.porId(prefs.getString("estilo", null))
        buscarWeb = prefs.getBoolean("web", true)
        mostrarRazonamiento = prefs.getBoolean("razonamiento", true)
        ahorroRam = prefs.getBoolean("ahorro-ram", true)
        conversaciones = Conversaciones(File(filesDir, "chats"))
        descargas = DescargaEnSegundoPlano(this)
        chatActual = prefs.getString("chat", null) ?: Conversaciones.nuevoId()

        raiz = FrameLayout(this).apply {
            setBackgroundColor(Colores.FONDO)
            fitsSystemWindows = true
        }
        raiz.addView(construirPantalla(), FrameLayout.LayoutParams(MATCH, MATCH))
        hoja = HojaInferior(raiz)
        pantallaChats = PantallaChats(
            this, raiz, conversaciones, hoja,
            chatActual = { chatActual },
            alAbrir = { abrirChat(it) },
            alNuevo = { nuevoChat() },
            alBorrarActual = { empezarDeCero() },
        )
        pantallaModelo = PantallaModelo(
            this, raiz, descargas,
            modeloActivo = { motor?.archivo },
            planActivo = { motor?.plan },
            ahorroRam = { ahorroRam },
            cargando = { cargandoModelo },
            alUsar = { cargarModelo(it) },
            alQuitar = { quitarModelo(borrarPreferencia = true) },
            alBorrar = { archivo ->
                if (motor?.archivo?.absolutePath == archivo.absolutePath) quitarModelo(borrarPreferencia = true)
                archivo.delete()
                avisar("Modelo borrado del teléfono.")
            },
            alImportar = { pedirModelo() },
            alCopiar = { texto, aviso ->
                copiar(texto, aviso)
            },
            alTerminarDescarga = { alTerminarDescarga(it) },
        )
        setContentView(raiz)
        ServicioRama.alDetener = { principal.post { if (generando) detenerRespuesta() } }
        Avisos.crearCanales(this)
        cargarCerebro()
    }

    override fun onPause() {
        super.onPause()
        guardarChat()
    }

    override fun onDestroy() {
        pantallaModelo.cerrar()
        ServicioRama.alDetener = null
        ServicioRama.terminar(this)
        // Si está escribiendo, primero se corta la respuesta y después se cierra el
        // modelo en su propio hilo: cerrarlo mientras genera rompería el motor nativo.
        detener = true
        asistente?.cancelar()
        val cerrar = motor
        motor = null
        trabajador.execute {
            asistente?.motor = null
            cerrar?.cerrar()
        }
        trabajador.shutdown()
        super.onDestroy()
    }

    @Deprecated("Sigue funcionando en API 34 sin opt-in a la navegación predictiva")
    override fun onBackPressed() {
        when {
            hoja.abierta -> hoja.cerrar()
            pantallaModelo.visible -> pantallaModelo.ocultar()
            pantallaChats.visible -> pantallaChats.ocultar()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onTrimMemory(nivelMemoria: Int) {
        super.onTrimMemory(nivelMemoria)
        // Un modelo grande (Rama Ultra) se suelta antes: apenas Android avisa que
        // la memoria escasea con la app en segundo plano o que empieza a faltar
        // mientras está abierta. Si no, Android cierra la app entera.
        val grande = (motor?.plan?.total ?: 0L) > MODELO_GRANDE
        val aprieta = nivelMemoria >= TRIM_MODERADO || nivelMemoria == TRIM_CRITICO ||
            (grande && (nivelMemoria >= TRIM_FONDO || nivelMemoria == TRIM_BAJO))
        if (aprieta) soltarModelo()
    }

    override fun onStop() {
        super.onStop()
        enPrimerPlano = false
        // Si está respondiendo, sigue en segundo plano: la notificación muestra en qué anda.
        if (generando) Avisos.actualizar(this, preguntaEnCurso, etapaEnCurso)
        armarSueltaPorInactividad()
    }

    /**
     * Con la app en segundo plano, un modelo grande se suelta a los 2 minutos:
     * son varios GB que el resto del teléfono puede usar mientras tanto.
     */
    private fun armarSueltaPorInactividad() {
        principal.removeCallbacks(soltarPorInactividad)
        if ((motor?.plan?.total ?: 0L) > MODELO_GRANDE) principal.postDelayed(soltarPorInactividad, INACTIVIDAD)
    }

    override fun onStart() {
        super.onStart()
        enPrimerPlano = true
        Avisos.limpiarLista(this)
        principal.removeCallbacks(soltarPorInactividad)
        // Si se soltó por inactividad, se vuelve a abrir en cuanto la app vuelve al frente.
        if (soltadoPorInactividad && modeloEnPausa && motor == null && !cargandoModelo) {
            soltadoPorInactividad = false
            preferencias().getString("modelo", null)?.let { File(it) }?.takeIf { it.exists() }?.let { cargarModelo(it, silencioso = true) }
        }
    }

    private var soltadoPorInactividad = false
    private val soltarPorInactividad = Runnable {
        if (soltarModelo()) soltadoPorInactividad = true
    }

    /** Cierra el modelo para liberar memoria; se recarga solo en la próxima pregunta. */
    private fun soltarModelo(): Boolean {
        if (generando || motor == null || cargandoModelo) return false
        val soltar = motor
        motor = null
        modeloEnPausa = true
        trabajador.execute {
            asistente?.motor = null
            soltar?.cerrar()
        }
        principal.post { pintarEstadoModelo() }
        return true
    }

    private fun preferencias(): SharedPreferences = getSharedPreferences("rama", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ arranque

    private fun cargarCerebro() {
        subtitulo.text = "despertando…"
        principal.postDelayed({
            if (asistente == null) {
                subtitulo.text = "no pude arrancar"
                burbujaRama("Algo me está trabando el arranque: pasaron 8 segundos y todavía no cargué mi base de conocimiento. Probá cerrar y volver a abrir.")
            }
        }, 8000)
        enSegundoPlano("cargando mi base de conocimiento") {
            val conocimiento = assets.open("conocimiento.json").bufferedReader().use { it.readText() }
            val datos = try {
                assets.open("datos.json").bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                null
            }
            val memoria = Memoria(AlmacenArchivo(File(filesDir, "aprendido.json")))
            val cerebro = Rama(conocimiento, memoria, null, datos)
            val ayudante = Asistente(cerebro)
            principal.post {
                ayudante.motor = motor
                asistente = ayudante
                val guardado = conversaciones.cargar(chatActual)
                if (guardado.isNotEmpty()) abrirChat(chatActual) else mostrarBienvenida()
                pintarEstadoModelo()
                mostrarErrorAnterior()
                restaurarModelo()
                atenderArchivoCompartido(intent)
            }
        }
    }

    override fun onNewIntent(nuevo: Intent?) {
        super.onNewIntent(nuevo)
        if (nuevo != null) atenderArchivoCompartido(nuevo)
    }

    // ------------------------------------------------------------ construcción de la pantalla

    private fun construirPantalla(): View {
        val columna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        columna.addView(construirEncabezado())
        selector = SelectorNivel(this) { elegido -> elegirNivel(elegido) }
        selector.elegir(nivel, avisar = false)
        columna.addView(selector, lp(MATCH, WRAP) { setMargins(dp(14f), dp(2f), dp(14f), dp(6f)) })

        val contenedor = FrameLayout(this)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            setPadding(dp(14f), dp(6f), dp(14f), dp(14f))
        }
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(lista, FrameLayout.LayoutParams(MATCH, WRAP))
        contenedor.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        tostada = TextView(this).apply {
            estilo(12.5f, Colores.TEXTO, Peso.MEDIO, 1.25f)
            gravity = Gravity.CENTER
            relleno(dp(14f), dp(10f))
            background = redondeado(Colores.SUPERFICIE_ALTA, dp(10f).toFloat(), Colores.BORDE, dp(1f))
            alpha = 0f
            elevation = dp(6f).toFloat()
            maxWidth = (resources.displayMetrics.widthPixels * 0.88f).toInt()
        }
        contenedor.addView(tostada, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(10f)
        })
        columna.addView(contenedor, lp(MATCH, 0, 1f))

        avisoModelo = construirAvisoModelo()
        columna.addView(avisoModelo, lp(MATCH, WRAP) { setMargins(dp(12f), 0, dp(12f), dp(8f)) })
        columna.addView(construirBarraEntrada())
        return columna
    }

    private fun construirEncabezado(): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(10f), dp(12f), dp(10f))
        }
        fila.addView(botonIcono(Trazos.menu(), "Chats guardados", color = Colores.TEXTO) { pantallaChats.mostrar() })
        val marca = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, dp(8f), 0)
        }
        marca.addView(TextView(this).apply {
            text = "Rama"
            estilo(19f, Colores.TEXTO, Peso.NEGRITA, 1f)
        })
        val estado = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        puntoEstado = View(this)
        estado.addView(puntoEstado, lp(dp(7f), dp(7f)) { rightMargin = dp(6f) })
        subtitulo = TextView(this).apply {
            estilo(12f, Colores.TEXTO_3, Peso.MEDIO, 1f)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        estado.addView(subtitulo)
        marca.addView(estado, lp(WRAP, WRAP) { topMargin = dp(3f) })
        fila.addView(marca, lp(0, WRAP, 1f))

        chipModelo = TextView(this).apply {
            estilo(12.5f, Colores.TEXTO_2, Peso.MEDIO, 1f)
            relleno(dp(11f), dp(8f))
            setSingleLine()
            maxWidth = dp(130f)
            ellipsize = TextUtils.TruncateAt.END
            contentDescription = "Modelo Rama"
            setOnClickListener { pantallaModelo.mostrar() }
        }
        fila.addView(chipModelo, lp(WRAP, WRAP) { rightMargin = dp(8f) })
        fila.addView(botonIcono(Trazos.ajustes(), "Ajustes", color = Colores.TEXTO) { mostrarAjustes() })
        return fila
    }

    private fun construirAvisoModelo(): View {
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val radio = dp(14f).toFloat()
            background = pulsable(Colores.SUPERFICIE, radio, Colores.BORDE, dp(1f))
            setPadding(dp(12f), dp(11f), dp(12f), dp(11f))
            setOnClickListener { pantallaModelo.mostrar() }
        }
        tarjeta.addView(ImageView(this).apply {
            setImageDrawable(Icono(Trazos.descargar(), Colores.TEXTO, 2.2f))
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            background = redondeado(Colores.ACENTO, dp(10f).toFloat())
        }, lp(dp(36f), dp(36f)) { rightMargin = dp(12f) })
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(TextView(this).apply {
            text = "Descargá el modelo Rama"
            estilo(14f, Colores.TEXTO, Peso.NEGRITA, 1.1f)
        })
        textos.addView(TextView(this).apply {
            text = "Una sola vez y después funciona sin internet. Tocá acá."
            estilo(12f, Colores.TEXTO_2, Peso.NORMAL, 1.2f)
        }, lp(WRAP, WRAP) { topMargin = dp(2f) })
        tarjeta.addView(textos, lp(0, WRAP, 1f))
        return tarjeta
    }

    private fun construirBarraEntrada(): View {
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, dp(12f), dp(12f))
        }
        val capsula = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            background = redondeado(Colores.SUPERFICIE, dp(24f).toFloat(), Colores.BORDE, dp(1f))
            setPadding(dp(5f), dp(5f), dp(5f), dp(5f))
        }
        capsula.addView(botonIcono(Trazos.mas(), "Adjuntar foto, PDF o video", 40f, 19f, Colores.TEXTO_2, Colores.SUPERFICIE_ALTA, 0) { pedirArchivo() })
        entrada = EditText(this).apply {
            hint = "Preguntale a Rama…"
            estilo(15.5f, Colores.TEXTO, Peso.NORMAL, 1.25f)
            setHintTextColor(Colores.TEXTO_3)
            background = null
            setPadding(dp(10f), dp(10f), dp(8f), dp(10f))
            maxLines = 5
            setHorizontallyScrolling(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { vista, accion, evento ->
                val enter = evento != null && evento.keyCode == KeyEvent.KEYCODE_ENTER && evento.action == KeyEvent.ACTION_DOWN
                if (accion == EditorInfo.IME_ACTION_SEND || accion == EditorInfo.IME_ACTION_DONE || enter) {
                    enviar(vista.text.toString())
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = pintarBotonEnviar()
            })
        }
        capsula.addView(entrada, lp(0, WRAP, 1f))
        botonWeb = botonIcono(Trazos.globo(), "Búsqueda web", 40f, 19f) { alternarWeb() }
        capsula.addView(botonWeb, lp(dp(40f), dp(40f)) { rightMargin = dp(5f) })
        botonEnviar = botonIcono(Trazos.enviar(), "Enviar", 40f, 19f) {
            if (generando) detenerRespuesta() else enviar(entrada.text.toString())
        }
        capsula.addView(botonEnviar, lp(dp(40f), dp(40f)))
        barra.addView(capsula, lp(MATCH, WRAP))
        pintarBotonWeb()
        pintarBotonEnviar()
        return barra
    }

    private fun pintarBotonEnviar() {
        if (!::botonEnviar.isInitialized || !::entrada.isInitialized) return
        val radio = dp(20f).toFloat()
        if (generando) {
            botonEnviar.setImageDrawable(Icono(Trazos.detener(), Colores.TEXTO, 2f, true))
            botonEnviar.background = pulsable(Colores.PELIGRO, radio)
            botonEnviar.contentDescription = "Detener"
            return
        }
        val hayTexto = entrada.text.isNotBlank()
        botonEnviar.setImageDrawable(Icono(Trazos.enviar(), if (hayTexto) Colores.TEXTO else Colores.TEXTO_3, 2.4f))
        botonEnviar.background = if (hayTexto) pulsable(Colores.ACENTO, radio)
        else pulsable(Colores.SUPERFICIE_ALTA, radio)
        botonEnviar.contentDescription = "Enviar"
    }

    private fun pintarBotonWeb() {
        val radio = dp(20f).toFloat()
        botonWeb.setImageDrawable(Icono(Trazos.globo(), if (buscarWeb) Colores.ACENTO_CLARO else Colores.TEXTO_3, 2f))
        botonWeb.background = if (buscarWeb) pulsable(Colores.alfa(Colores.ACENTO, 0.14f), radio, Colores.alfa(Colores.ACENTO, 0.45f), dp(1f))
        else pulsable(Colores.SUPERFICIE_ALTA, radio)
        botonWeb.contentDescription = if (buscarWeb) "Búsqueda web activada" else "Búsqueda web apagada"
    }

    private fun pintarEstadoModelo() {
        val m = motor
        val (texto, color) = when {
            cargandoModelo -> "cargando el modelo…" to Colores.ACENTO_CLARO
            m != null && m.plan.enAhorro -> "${m.nombre} · ahorro de RAM (${m.plan.enUsoLegible})" to Colores.AVISO
            m != null -> "${m.nombre} · lista · ${m.plan.totalLegible} de RAM" to Colores.EXITO
            modeloEnPausa -> "modelo en pausa (se recarga solo)" to Colores.TEXTO_3
            !Llama.disponible -> "sin motor en este teléfono" to Colores.PELIGRO
            else -> "sin modelo · tocá Modelo" to Colores.AVISO
        }
        subtitulo.text = texto
        puntoEstado.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val radio = dp(10f).toFloat()
        chipModelo.text = when {
            cargandoModelo -> "Cargando…"
            m != null -> (m.edicion?.nombre?.removePrefix("Rama ") ?: "Propio")
            else -> "Modelo"
        }
        val dibujo = Icono(if (m != null) Trazos.chip() else Trazos.descargar(), Colores.TEXTO_2, 2.2f)
        dibujo.setBounds(0, 0, dp(14f), dp(14f))
        chipModelo.setCompoundDrawables(dibujo, null, null, null)
        chipModelo.compoundDrawablePadding = dp(6f)
        chipModelo.background = pulsable(Colores.SUPERFICIE, radio, Colores.BORDE, dp(1f))
        avisoModelo.visibility = if (m == null && !cargandoModelo && !modeloEnPausa && Llama.disponible) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------ ajustes

    private fun elegirNivel(elegido: NivelPensar) {
        nivel = elegido
        preferencias().edit().putString("nivel", elegido.id).apply()
        val detalle = if (elegido.piensa) " · piensa hasta ~${elegido.presupuesto} tokens" else ""
        avisar("${elegido.nombre}: ${elegido.descripcion}$detalle")
    }

    private fun alternarWeb() {
        buscarWeb = !buscarWeb
        preferencias().edit().putBoolean("web", buscarWeb).apply()
        pintarBotonWeb()
        rebote(botonWeb)
        avisar(if (buscarWeb) "Búsqueda web activada: busco cuando la pregunta lo necesita." else "Búsqueda web apagada: respondo sólo con lo que tengo en el teléfono.")
    }

    private fun mostrarAjustes() {
        val contenido = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        contenido.addView(rotulo("Estilo de respuesta"))
        val estilos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val descripcion = TextView(this).apply { estilo(12.5f, Colores.TEXTO_2, Peso.NORMAL, 1.35f) }
        val fichas = ArrayList<Pair<Estilo, TextView>>()
        fun pintarEstilos() {
            for ((e, vista) in fichas) {
                val elegido = e.id == estilo.id
                val radio = dp(10f).toFloat()
                vista.estilo(13f, if (elegido) Colores.TEXTO else Colores.TEXTO_2, if (elegido) Peso.NEGRITA else Peso.MEDIO, 1f)
                vista.background = if (elegido) pulsable(Colores.mezclar(Colores.SUPERFICIE_ALTA, Colores.ACENTO, 0.14f), radio, Colores.ACENTO, dp(1f))
                else pulsable(Colores.SUPERFICIE_ALTA, radio, Colores.BORDE, dp(1f))
            }
            descripcion.text = estilo.descripcion
        }
        var fila: LinearLayout? = null
        Estilos.TODOS.forEachIndexed { i, e ->
            if (i % 3 == 0) {
                fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                estilos.addView(fila, lp(MATCH, WRAP) { topMargin = dp(8f) })
            }
            val ficha = TextView(this).apply {
                text = e.nombre
                gravity = Gravity.CENTER
                relleno(dp(12f), dp(9f))
                setOnClickListener {
                    if (estilo.id != e.id) {
                        estilo = e
                        preferencias().edit().putString("estilo", e.id).apply()
                        precalentar()
                    }
                    pintarEstilos()
                    rebote(this)
                }
            }
            fichas.add(e to ficha)
            fila!!.addView(ficha, lp(WRAP, WRAP) { rightMargin = dp(8f) })
        }
        pintarEstilos()
        contenido.addView(estilos)
        contenido.addView(descripcion, lp(MATCH, WRAP) { topMargin = dp(8f); bottomMargin = dp(18f) })

        contenido.addView(rotulo("Opciones"), lp(WRAP, WRAP) { bottomMargin = dp(6f) })
        contenido.addView(filaInterruptor("Buscar en la web", "Cuando la pregunta necesita datos actuales o verificables.", buscarWeb, Colores.ACENTO) {
            buscarWeb = it
            preferencias().edit().putBoolean("web", it).apply()
            pintarBotonWeb()
        })
        contenido.addView(filaInterruptor(
            "Ahorro de RAM",
            "Deja abrir Rama Ultra con poca memoria libre: lee del almacenamiento la parte de los pesos que no entra. Responde más lento, pero no hace falta tener 5 GB libres.",
            ahorroRam, Colores.ACENTO,
        ) {
            ahorroRam = it
            preferencias().edit().putBoolean("ahorro-ram", it).apply()
            if (pantallaModelo.visible) pantallaModelo.refrescar()
        })
        contenido.addView(filaInterruptor("Mostrar el razonamiento", "Ver en vivo cómo piensa, qué busca y qué lee antes de responder.", mostrarRazonamiento, Colores.ACENTO) {
            mostrarRazonamiento = it
            preferencias().edit().putBoolean("razonamiento", it).apply()
        })

        contenido.addView(rotulo("Niveles de pensamiento"), lp(WRAP, WRAP) { topMargin = dp(18f); bottomMargin = dp(6f) })
        for (n in NivelPensar.entries) {
            val filaNivel = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7f), 0, dp(7f))
            }
            val (trazo, relleno) = Trazos.nivel(n)
            filaNivel.addView(ImageView(this).apply {
                setImageDrawable(Icono(trazo, Colores.ACENTO_CLARO, 2.2f, relleno))
                setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
                background = redondeado(Colores.SUPERFICIE_ALTA, dp(9f).toFloat(), Colores.BORDE_TENUE, dp(1f))
            }, lp(dp(32f), dp(32f)) { rightMargin = dp(12f) })
            val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textos.addView(TextView(this).apply {
                text = n.nombre + if (n.piensa) "  ·  hasta ${n.presupuesto} tokens de razonamiento" else "  ·  sin razonamiento"
                estilo(13.5f, Colores.TEXTO, Peso.NEGRITA, 1.1f)
            })
            textos.addView(TextView(this).apply {
                text = n.descripcion + if (n.paginasALeer > 0) " Lee hasta ${n.paginasALeer} página(s) al buscar." else ""
                estilo(12.5f, Colores.TEXTO_2, Peso.NORMAL, 1.3f)
            })
            filaNivel.addView(textos, lp(0, WRAP, 1f))
            contenido.addView(filaNivel)
        }

        contenido.addView(TextView(this).apply {
            text = "Rama ${Identidad.VERSION} · modelo de código abierto sobre ${Catalogo.BASE} (${Catalogo.LICENCIA}) · llama.cpp · Inter"
            estilo(11.5f, Colores.TEXTO_3, Peso.NORMAL, 1.3f)
            gravity = Gravity.CENTER
        }, lp(MATCH, WRAP) { topMargin = dp(18f) })
        hoja.mostrar("Ajustes", contenido)
    }

    private fun filaInterruptor(titulo: String, detalle: String, activo: Boolean, color: Int, alCambiar: (Boolean) -> Unit): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, dp(8f))
        }
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(TextView(this).apply {
            text = titulo
            estilo(14.5f, Colores.TEXTO, Peso.NEGRITA, 1.1f)
        })
        textos.addView(TextView(this).apply {
            text = detalle
            estilo(12.5f, Colores.TEXTO_2, Peso.NORMAL, 1.3f)
        }, lp(MATCH, WRAP) { topMargin = dp(2f) })
        fila.addView(textos, lp(0, WRAP, 1f) { rightMargin = dp(12f) })
        val interruptor = Interruptor(this, activo, color, alCambiar)
        fila.addView(interruptor, lp(dp(50f), dp(30f)))
        fila.setOnClickListener { interruptor.alternar() }
        return fila
    }

    // ------------------------------------------------------------ chat

    private fun mostrarBienvenida() {
        if (bienvenida != null) return
        val portada = construirBienvenida()
        bienvenida = portada
        lista.addView(portada, 0)
    }

    private fun quitarBienvenida() {
        bienvenida?.let { lista.removeView(it) }
        bienvenida = null
    }

    private fun construirBienvenida(): View {
        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4f), dp(36f), dp(4f), dp(8f))
        }
        columna.addView(avatarRama(52f), lp(dp(52f), dp(52f)))
        columna.addView(TextView(this).apply {
            text = "Hola, soy Rama"
            estilo(24f, Colores.TEXTO, Peso.NEGRITA, 1.05f)
            gravity = Gravity.CENTER
        }, lp(WRAP, WRAP) { topMargin = dp(18f) })
        columna.addView(TextView(this).apply {
            text = "Tu IA de código abierto. Pienso adentro de tu teléfono, busco en la web cuando hace falta y vos elegís cuánto razono."
            estilo(14.5f, Colores.TEXTO_2, Peso.NORMAL, 1.4f)
            gravity = Gravity.CENTER
        }, lp(MATCH, WRAP) { setMargins(dp(16f), dp(8f), dp(16f), dp(26f)) })

        val sugerencias = listOf(
            "¿Qué pasó hoy en Argentina?" to Trazos.globo(),
            "¿A cuánto está el dólar hoy?" to Trazos.calculadora(),
            "Explicame la relatividad como si tuviera 12 años" to Trazos.lamparita(),
            "Ayudame a escribir un mensaje para mi jefe" to Trazos.lapiz(),
        )
        for (fila in sugerencias.chunked(2)) {
            val renglon = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fila.forEachIndexed { i, (texto, trazo) ->
                renglon.addView(tarjetaSugerencia(texto, trazo), lp(0, MATCH, 1f) { if (i == 0) rightMargin = dp(10f) })
            }
            columna.addView(renglon, lp(MATCH, WRAP) { bottomMargin = dp(10f) })
        }
        return columna
    }

    private fun tarjetaSugerencia(texto: String, trazo: android.graphics.Path): View {
        val radio = dp(14f).toFloat()
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pulsable(Colores.SUPERFICIE, radio, Colores.BORDE_TENUE, dp(1f))
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            setOnClickListener {
                rebote(this)
                enviar(texto)
            }
        }
        tarjeta.addView(ImageView(this).apply {
            setImageDrawable(Icono(trazo, Colores.TEXTO_2, 2f))
        }, lp(dp(18f), dp(18f)) { bottomMargin = dp(12f) })
        tarjeta.addView(TextView(this).apply {
            text = texto
            estilo(13.5f, Colores.TEXTO, Peso.MEDIO, 1.3f)
        })
        return tarjeta
    }

    private fun burbujaUsuario(texto: String) {
        val burbuja = TextView(this).apply {
            text = texto
            estilo(15.5f, Colores.TEXTO, Peso.NORMAL, 1.35f)
            setPadding(dp(15f), dp(11f), dp(15f), dp(11f))
            val grande = dp(18f).toFloat()
            background = redondeado(Colores.SUPERFICIE_ALTA, 0f, radios = esquinas(grande, dp(6f).toFloat(), grande))
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            setOnLongClickListener {
                copiar(texto)
                true
            }
        }
        lista.addView(burbuja, lp(WRAP, WRAP) {
            gravity = Gravity.END
            topMargin = dp(14f)
            bottomMargin = dp(4f)
        })
        alFinal()
    }

    private fun nuevaRespuesta(nivelMostrado: NivelPensar?): VistaRespuesta {
        val respuesta = VistaRespuesta(this, nivelMostrado) { copiar(it) }
        lista.addView(respuesta.vista, lp(MATCH, WRAP) {
            topMargin = dp(16f)
            bottomMargin = dp(4f)
            rightMargin = dp(18f)
        })
        alFinal()
        return respuesta
    }

    /** Mensaje de Rama que no pasa por el modelo (avisos, adjuntos, errores). */
    private fun burbujaRama(texto: String): VistaRespuesta {
        val r = nuevaRespuesta(null)
        r.mostrarTexto(Markdown.formatear(texto), false)
        return r
    }

    private fun avisar(texto: String) {
        tostada.text = texto
        tostada.animate().cancel()
        tostada.alpha = 0f
        tostada.translationY = dp(8f).toFloat()
        tostada.animate().alpha(1f).translationY(0f).setDuration(200).start()
        principal.removeCallbacks(ocultarTostada)
        principal.postDelayed(ocultarTostada, (2200 + texto.length * 25L).coerceAtMost(6000))
    }

    private fun alFinal() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun enviar(texto: String) {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return
        if (generando) {
            avisar("Esperá que termine, o tocá el botón rojo para detener.")
            return
        }
        val ayudante = asistente
        if (ayudante == null) {
            avisar("Dame un segundo, todavía me estoy despertando.")
            return
        }
        if (!asegurarModelo(limpio)) return
        entrada.setText("")
        quitarBienvenida()
        burbujaUsuario(limpio)

        val nivelUsado = nivel
        val vista = nuevaRespuesta(nivelUsado)
        val tarjeta = if (mostrarRazonamiento) vista.agregarTarjeta(nivelUsado) { alFinal() } else null
        vista.mostrarPuntos()
        generando = true
        detener = false
        pintarBotonEnviar()
        pedirPermisoDeAvisos()
        preguntaEnCurso = limpio
        etapaEnCurso = "Pensando…"
        ServicioRama.empezar(this, limpio)
        val turnos = historial.toList()
        historial.add(Mensaje("user", limpio))
        val ajustes = AjustesRama(nivelUsado, estilo, buscarWeb)
        val adjunto = ultimoAdjunto

        val escrito = StringBuilder()
        var pintarPendiente = false
        val oyente = object : OyenteRama {
            override fun paso(paso: PasoRama) {
                principal.post {
                    tarjeta?.agregarPaso(paso)
                    contarEtapa(paso.titulo)
                }
            }

            override fun pensamiento(fragmento: String) {
                principal.post { tarjeta?.agregarPensamiento(fragmento) }
            }

            override fun texto(fragmento: String) {
                principal.post {
                    tarjeta?.terminarPensar()
                    if (escrito.isEmpty()) contarEtapa("Escribiendo la respuesta…")
                    escrito.append(fragmento)
                    if (!pintarPendiente) {
                        pintarPendiente = true
                        principal.postDelayed({
                            pintarPendiente = false
                            if (generando) {
                                vista.mostrarTexto(Markdown.formatear(escrito.toString()), false)
                                alFinal()
                            }
                        }, 45)
                    }
                }
            }

            override fun seguir(): Boolean = !detener
        }

        trabajador.execute {
            val resultado: RespuestaRama? = try {
                ayudante.responder(limpio, turnos, adjunto, ajustes, oyente)
            } catch (e: Throwable) {
                principal.post {
                    burbujaRama("Me tropecé escribiendo la respuesta:\n\n`${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}`")
                }
                null
            }
            principal.post { terminarRespuesta(limpio, vista, tarjeta, resultado) }
        }
    }

    private var preguntaEnCurso = ""
    private var etapaEnCurso = ""
    private var ultimaEtapa = 0L
    private var precalentarAlTerminar = false

    /** Lo que se ve en la notificación mientras responde en segundo plano. */
    private fun contarEtapa(etapa: String) {
        etapaEnCurso = etapa
        if (enPrimerPlano) return
        val ahora = System.currentTimeMillis()
        if (ahora - ultimaEtapa < 1000) return
        ultimaEtapa = ahora
        Avisos.actualizar(this, preguntaEnCurso, etapa)
    }

    private fun pedirPermisoDeAvisos() {
        if (android.os.Build.VERSION.SDK_INT < 33 || Avisos.permitidas(this)) return
        val prefs = preferencias()
        if (prefs.getBoolean("pidio-avisos", false)) return
        prefs.edit().putBoolean("pidio-avisos", true).apply()
        try {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), PEDIDO_AVISOS)
        } catch (e: Exception) {
        }
    }

    /**
     * Procesa el comienzo fijo de todos los pedidos (las instrucciones de Rama)
     * con el modelo ocioso, así la primera respuesta arranca sin esa espera.
     */
    private fun precalentar() {
        if (generando) {
            precalentarAlTerminar = true
            return
        }
        val estiloActual = estilo
        trabajador.execute { asistente?.precalentar(estiloActual) }
    }

    private fun terminarRespuesta(pregunta: String, vista: VistaRespuesta, tarjeta: TarjetaPensar?, respuesta: RespuestaRama?) {
        generando = false
        ServicioRama.terminar(this)
        pintarBotonEnviar()
        if (!enPrimerPlano) {
            if (respuesta != null && !respuesta.detenida) Avisos.lista(this, pregunta, respuesta.texto)
            armarSueltaPorInactividad()
        }
        // Si cambió el estilo mientras respondía, se prepara el nuevo comienzo ahora.
        if (precalentarAlTerminar) {
            precalentarAlTerminar = false
            precalentar()
        }
        tarjeta?.cerrar(respuesta)
        if (respuesta == null) {
            vista.mostrarTexto("(no pude responder)", false)
            historial.removeLastOrNull()
            return
        }
        val texto = respuesta.texto
        vista.mostrarTexto(Markdown.formatear(texto, respuesta.fuentes) { abrirEnlace(it) }, respuesta.fuentes.isNotEmpty())
        vista.agregarFuentes(respuesta.fuentes) { abrirEnlace(it) }
        vista.agregarPie(respuesta.respaldo) { avisar(it) }
        if (respuesta.detenida && texto == "(detenido)") {
            historial.removeLastOrNull()
        } else {
            historial.add(Mensaje("assistant", texto))
        }
        guardarChat()
        alFinal()
    }

    private fun detenerRespuesta() {
        detener = true
        asistente?.cancelar()
        avisar("Deteniendo…")
    }

    private fun guardarChat() {
        if (historial.isEmpty()) return
        try {
            conversaciones.guardar(chatActual, historial.toList(), null)
            preferencias().edit().putString("chat", chatActual).apply()
        } catch (e: Exception) {
            avisar("No pude guardar el chat: ${e.message}")
        }
    }

    private fun nuevoChat() {
        if (generando) detenerRespuesta()
        guardarChat()
        empezarDeCero()
    }

    private fun empezarDeCero() {
        chatActual = Conversaciones.nuevoId()
        historial.clear()
        ultimoAdjunto = null
        lista.removeAllViews()
        bienvenida = null
        preferencias().edit().putString("chat", chatActual).apply()
        mostrarBienvenida()
    }

    private fun abrirChat(id: String) {
        if (generando) detenerRespuesta()
        if (id != chatActual) guardarChat()
        val mensajes = conversaciones.cargar(id)
        chatActual = id
        historial.clear()
        historial.addAll(mensajes)
        ultimoAdjunto = null
        lista.removeAllViews()
        bienvenida = null
        preferencias().edit().putString("chat", id).apply()
        for (m in mensajes) {
            if (m.rol == "user") burbujaUsuario(m.contenido) else burbujaRama(m.contenido)
        }
        if (mensajes.isEmpty()) mostrarBienvenida()
        alFinal()
    }

    private fun copiar(texto: String, aviso: String = "Copiado") {
        try {
            val portapapeles = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            portapapeles.setPrimaryClip(ClipData.newPlainText("Rama", texto))
            avisar(aviso)
        } catch (e: Exception) {
            avisar("No pude copiar")
        }
    }

    private fun abrirEnlace(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            avisar("No pude abrir el enlace.")
        }
    }

    // ------------------------------------------------------------ modelo

    private fun ramTotalGb(): Int = try {
        val gestor = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        gestor.getMemoryInfo(info)
        ceil(info.totalMem / 1_000_000_000.0).toInt()
    } catch (e: Exception) {
        0
    }

    private fun memoriaLibre(): Long = try {
        val gestor = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        gestor.getMemoryInfo(info)
        info.availMem
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    private fun restaurarModelo() {
        val guardado = preferencias().getString("modelo", null)?.let { File(it) }
        if (guardado != null && guardado.exists()) {
            cargarModelo(guardado, silencioso = true)
            return
        }
        // Si hay una edición descargada que nunca se cargó, la usamos.
        val recomendada = Catalogo.recomendada(ramTotalGb())
        val candidata = (listOf(recomendada) + Catalogo.EDICIONES)
            .map { descargas.archivoDe(it) }
            .firstOrNull { it.exists() && descargas.estado(Catalogo.deArchivo(it.name)!!) is EstadoDescarga.Terminada }
        if (candidata != null) cargarModelo(candidata, silencioso = true)
        pintarEstadoModelo()
    }

    private fun cargarModelo(archivo: File, silencioso: Boolean = false) {
        if (cargandoModelo) return
        if (!Llama.disponible) {
            burbujaRama("Este teléfono no puede correr el modelo: ${Llama.motivoNoDisponible ?: "falta el motor"}")
            return
        }
        cargandoModelo = true
        val anterior = motor
        motor = null
        pintarEstadoModelo()
        trabajador.execute {
            val gguf = Gguf.leer(archivo)
            // La memoria del modelo que está abierto se libera al cerrarlo.
            val libre = memoriaLibre() + (anterior?.plan?.total ?: 0L)
            val plan = MotorRama.planear(archivo, gguf, ramTotalGb(), libre, ahorroRam)
            if (!plan.alcanza) {
                principal.post {
                    cargandoModelo = false
                    motor = anterior
                    burbujaRama(faltaMemoria(archivo, plan))
                    pintarEstadoModelo()
                }
                return@execute
            }
            asistente?.motor = null
            anterior?.cerrar()
            val abierto = try {
                MotorRama.abrir(archivo, plan, gguf)
            } catch (e: Throwable) {
                null
            }
            asistente?.motor = abierto
            principal.post {
                cargandoModelo = false
                motor = abierto
                modeloEnPausa = false
                if (abierto == null) {
                    burbujaRama("No pude cargar «${archivo.name}». Puede que el archivo esté incompleto, que no sea un GGUF o que al teléfono le falte memoria para esta edición.")
                } else {
                    preferencias().edit().putString("modelo", archivo.absolutePath).apply()
                    precalentar()
                    if (plan.enAhorro) {
                        burbujaRama(
                            "Abrí **${abierto.nombre}** en **modo ahorro de RAM**: reservo ${PlanDeMemoria.legible(plan.fijo)} fijos y de los " +
                                "${PlanDeMemoria.legible(plan.bytesPesos)} de pesos dejo en memoria unos ${PlanDeMemoria.legible(plan.pesosResidentes)}; " +
                                "el resto lo leo del almacenamiento a medida que hace falta.\n\n" +
                                "Así entra en tu teléfono, pero cada palabra tarda bastante más y pienso menos para no demorar. " +
                                "Si cerrás otras aplicaciones y la volvés a abrir desde **Modelo**, va más rápido.",
                        )
                    } else if (!silencioso) {
                        avisar("${abierto.nombre} lista · ${plan.contexto} tokens de contexto · usa ~${plan.totalLegible} de RAM")
                    }
                }
                pintarEstadoModelo()
                if (pantallaModelo.visible) pantallaModelo.refrescar()
            }
        }
    }

    private fun faltaMemoria(archivo: File, plan: PlanDeMemoria): String {
        val edicion = Catalogo.deArchivo(archivo.name)
        val ram = ramTotalGb()
        // Primero, la misma edición con pesos más livianos; si no, una edición más chica.
        val variante = edicion?.variantes?.filter { it.bytesAproximados < plan.bytesPesos - 200_000_000L }?.minByOrNull { it.bytesAproximados }
        val menor = Catalogo.EDICIONES.map { descargas.efectiva(it, ram) }
            .lastOrNull { it.memoriaTipica.total < plan.total && it.id != edicion?.id }
        val puedeAhorrar = MotorRama.seLeeDelArchivo(Gguf.leer(archivo), archivo)
        val minimoAhorro = PlanDeMemoria.minimoParaAbrir(plan.bytesPesos, (edicion?.elementosKvPorToken ?: 0L), true)
        val sugerencia = when {
            puedeAhorrar && !ahorroRam -> ", o activá **Ahorro de RAM** en Ajustes: con eso abre desde unos ${PlanDeMemoria.legible(minimoAhorro)} libres (más lenta)."
            variante != null -> ", o borrala y bajá la variante **${variante.nombre}** (${variante.cuantizacion}), que ocupa unos " +
                PlanDeMemoria.estimar(variante.bytesAproximados, edicion?.elementosKvPorToken ?: 0L, 4096).totalLegible + "."
            menor != null -> ", o usá **${menor.nombre}**, que ocupa unos ${menor.memoriaTipica.totalLegible}."
            else -> "."
        }
        val necesita = if (puedeAhorrar && ahorroRam) "aun en modo ahorro necesita unos ${PlanDeMemoria.legible(minimoAhorro)}" else "necesita unos ${plan.totalLegible}"
        return "No me alcanza la memoria para abrir **${edicion?.nombre ?: archivo.name}**: $necesita " +
            "(pesos ${PlanDeMemoria.legible(plan.bytesPesos)}, memoria de la charla ${PlanDeMemoria.legible(plan.bytesCache)} y cálculo ${PlanDeMemoria.legible(plan.bytesComputo)}) " +
            "y ahora hay ${PlanDeMemoria.legible(plan.libre)} libres.\n\n" +
            "Cerrá otras aplicaciones (sobre todo juegos, cámara y navegador) y probá de nuevo" + sugerencia
    }

    private fun quitarModelo(borrarPreferencia: Boolean) {
        val anterior = motor
        motor = null
        modeloEnPausa = false
        if (borrarPreferencia) preferencias().edit().remove("modelo").apply()
        trabajador.execute {
            asistente?.motor = null
            anterior?.cerrar()
        }
        pintarEstadoModelo()
    }

    /** Si Android nos hizo soltar el modelo por memoria, lo recarga antes de responder. */
    private fun asegurarModelo(pregunta: String): Boolean {
        if (motor != null || !modeloEnPausa) return true
        val guardado = preferencias().getString("modelo", null)?.let { File(it) }
        if (guardado == null || !guardado.exists()) {
            modeloEnPausa = false
            return true
        }
        avisar("Recargando el modelo, que había soltado para liberar memoria…")
        cargandoModelo = true
        pintarEstadoModelo()
        trabajador.execute {
            val gguf = Gguf.leer(guardado)
            val plan = MotorRama.planear(guardado, gguf, ramTotalGb(), memoriaLibre(), ahorroRam)
            val abierto = if (!plan.alcanza) null else try {
                MotorRama.abrir(guardado, plan, gguf)
            } catch (e: Throwable) {
                null
            }
            asistente?.motor = abierto
            principal.post {
                cargandoModelo = false
                motor = abierto
                pintarEstadoModelo()
                when {
                    abierto != null -> {
                        modeloEnPausa = false
                        enviar(pregunta)
                    }
                    !plan.alcanza -> burbujaRama(faltaMemoria(guardado, plan))
                    else -> burbujaRama("No pude recargar el modelo.")
                }
            }
        }
        return false
    }

    private fun alTerminarDescarga(archivo: File) {
        if (!Descargador.esGguf(archivo)) {
            archivo.delete()
            burbujaRama("La descarga terminó pero el archivo no es un modelo válido. Probá bajarlo de nuevo desde **Modelo**.")
            return
        }
        val edicion = Catalogo.deArchivo(archivo.name)
        if (motor == null && !cargandoModelo) {
            burbujaRama("¡Listo! Terminó la descarga de **${edicion?.nombre ?: archivo.name}**. La cargo y ya podés preguntarme lo que quieras.")
            cargarModelo(archivo)
        } else {
            avisar("Terminó la descarga de ${edicion?.nombre ?: archivo.name}.")
        }
    }

    private fun pedirModelo() {
        val intencion = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intencion, PEDIDO_MODELO)
        } catch (e: Exception) {
            avisar("No encontré una app para elegir archivos.")
        }
    }

    private fun importarModelo(uri: Uri) {
        pantallaModelo.ocultar()
        val aviso = burbujaRama("Copiando el modelo al almacenamiento de la app…")
        enSegundoPlano("importando el modelo") {
            val carpeta = File(filesDir, "modelos").apply { mkdirs() }
            val destino = File(carpeta, "importado.gguf")
            val copiados = contentResolver.openInputStream(uri)?.use { entradaArchivo ->
                FileOutputStream(destino).use { salida -> entradaArchivo.copyTo(salida, 1 shl 20) }
            } ?: throw IOException("no pude abrir el archivo elegido")
            principal.post {
                if (!Descargador.esGguf(destino)) {
                    destino.delete()
                    aviso.mostrarTexto("Ese archivo no es un modelo GGUF. Fijate que la extensión sea **.gguf**.", false)
                } else {
                    aviso.mostrarTexto("Copiado (${AnalizadorAdjuntos.pesoLegible(copiados)}). Lo cargo…", false)
                    cargarModelo(destino)
                }
            }
        }
    }

    // ------------------------------------------------------------ adjuntos

    private fun pedirArchivo() {
        val intencion = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "application/pdf"))
        }
        try {
            startActivityForResult(intencion, PEDIDO_ARCHIVO)
        } catch (e: Exception) {
            avisar("No encontré una app para elegir archivos en este teléfono.")
        }
    }

    @Deprecated("startActivityForResult sigue siendo la vía simple en una Activity sin AndroidX")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(codigo, resultado, datos)
        val uri = datos?.data
        if (resultado != RESULT_OK || uri == null) return
        when (codigo) {
            PEDIDO_ARCHIVO -> procesarAdjunto(uri)
            PEDIDO_MODELO -> importarModelo(uri)
        }
    }

    private fun atenderArchivoCompartido(intencion: Intent?) {
        if (intencion?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = intencion.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        intencion.removeExtra(Intent.EXTRA_STREAM)
        procesarAdjunto(uri)
    }

    private fun procesarAdjunto(uri: Uri) {
        quitarBienvenida()
        val cargando = TextView(this).apply {
            text = "Leyendo el archivo…"
            estilo(13.5f, Colores.TEXTO_2, Peso.MEDIO, 1f)
            relleno(dp(14f), dp(11f))
            background = redondeado(Colores.SUPERFICIE, dp(12f).toFloat(), Colores.BORDE, dp(1f))
        }
        lista.addView(cargando, lp(WRAP, WRAP) {
            gravity = Gravity.END
            topMargin = dp(10f)
        })
        alFinal()
        enSegundoPlano("leyendo el archivo") {
            val adjunto = try {
                AnalizadorAdjuntos.analizar(this, uri)
            } catch (e: Exception) {
                null
            }
            principal.post {
                lista.removeView(cargando)
                if (adjunto == null) {
                    burbujaRama("No pude leer ese archivo. Puede que la app que lo comparte no me dé acceso.")
                    return@post
                }
                ultimoAdjunto = adjunto
                tarjetaAdjunto(adjunto)
                val r = burbujaRama(adjunto.resumen)
                if (adjunto.datos.isNotEmpty()) r.pie.addView(fichaDatos(adjunto.datos), lp(MATCH, WRAP) { topMargin = dp(8f) })
                historial.add(Mensaje("assistant", adjunto.resumen))
                guardarChat()
                avisar("Preguntame lo que quieras sobre el archivo.")
            }
        }
    }

    private fun tarjetaAdjunto(adjunto: Adjunto) {
        val grande = dp(16f).toFloat()
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = redondeado(Colores.SUPERFICIE_ALTA, grande, Colores.BORDE, dp(1f))
            setPadding(dp(6f), dp(6f), dp(6f), dp(10f))
        }
        adjunto.miniatura?.let { miniatura ->
            val ancho = (resources.displayMetrics.widthPixels * 0.62f).toInt()
            val alto = (ancho * miniatura.height / miniatura.width.coerceAtLeast(1)).coerceIn(dp(90f), dp(300f))
            tarjeta.addView(ImageView(this).apply {
                setImageBitmap(miniatura)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(vista: View, contorno: Outline) =
                        contorno.setRoundRect(0, 0, vista.width, vista.height, dp(11f).toFloat())
                }
            }, lp(ancho, alto))
        }
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(10f), dp(8f), 0)
        }
        fila.addView(ImageView(this).apply { setImageDrawable(Icono(Trazos.documento(), Colores.TEXTO_2, 2f)) }, lp(dp(16f), dp(16f)) { rightMargin = dp(8f) })
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(TextView(this).apply {
            text = adjunto.nombre
            estilo(13.5f, Colores.TEXTO, Peso.NEGRITA, 1.2f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        textos.addView(TextView(this).apply {
            text = "${adjunto.tipo} · ${AnalizadorAdjuntos.pesoLegible(adjunto.tamanioBytes)}"
            estilo(11.5f, Colores.TEXTO_3, Peso.MEDIO, 1f)
        })
        fila.addView(textos, lp(0, WRAP, 1f))
        tarjeta.addView(fila)
        lista.addView(tarjeta, lp(WRAP, WRAP) {
            gravity = Gravity.END
            topMargin = dp(12f)
            bottomMargin = dp(4f)
        })
        alFinal()
    }

    private fun fichaDatos(datos: List<Pair<String, String>>): View {
        val ficha = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = redondeado(Colores.SUPERFICIE, dp(12f).toFloat(), Colores.BORDE_TENUE, dp(1f))
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        }
        for ((clave, valor) in datos) {
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3f), 0, dp(3f))
            }
            fila.addView(TextView(this).apply {
                text = clave
                estilo(12f, Colores.TEXTO_3, Peso.MEDIO, 1.2f)
            }, lp(0, WRAP, 1f))
            fila.addView(TextView(this).apply {
                text = valor
                estilo(12f, Colores.TEXTO, Peso.MEDIO, 1.2f)
                gravity = Gravity.END
            }, lp(0, WRAP, 1.3f))
            ficha.addView(fila)
        }
        return ficha
    }

    // ------------------------------------------------------------ errores y tareas

    private fun enSegundoPlano(queHacia: String, tarea: () -> Unit) {
        trabajador.execute {
            try {
                tarea()
            } catch (e: Throwable) {
                principal.post {
                    subtitulo.text = "algo falló"
                    burbujaRama("Me tropecé $queHacia:\n\n`${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}`")
                }
            }
        }
    }

    private fun instalarReporteDeErrores() {
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, error ->
            try {
                File(filesDir, ARCHIVO_ERROR).writeText("${Date()}\nhilo: ${hilo.name}\n\n${error.stackTraceToString()}")
            } catch (e: Throwable) {
            }
            anterior?.uncaughtException(hilo, error)
        }
    }

    private fun mostrarErrorAnterior() {
        val archivo = File(filesDir, ARCHIVO_ERROR)
        if (!archivo.exists()) return
        try {
            val detalle = archivo.readText().take(2500)
            burbujaRama("La vez pasada me cerré de golpe por un error. Te lo dejo tal cual, para que se pueda arreglar:\n\n```\n$detalle\n```")
        } catch (e: Exception) {
        } finally {
            archivo.delete()
        }
    }

    companion object {
        private const val ARCHIVO_ERROR = "ultimo-error.txt"
        private const val PEDIDO_ARCHIVO = 1001
        private const val PEDIDO_MODELO = 1002
        private const val PEDIDO_AVISOS = 1003
        /** Arriba de esto (≈ Rama Ultra) el modelo se suelta apenas escasea la memoria. */
        private const val MODELO_GRANDE = 3_500_000_000L
        /** Cuánto espera en segundo plano antes de soltar un modelo grande. */
        private const val INACTIVIDAD = 2 * 60 * 1000L
        // Niveles de onTrimMemory (varios quedaron obsoletos como constantes en API 34).
        private const val TRIM_BAJO = 10
        private const val TRIM_CRITICO = 15
        private const val TRIM_FONDO = 40
        private const val TRIM_MODERADO = 60
    }
}
