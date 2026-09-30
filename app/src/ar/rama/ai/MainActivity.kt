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

    @Volatile
    private var detener = false

    private var nivel = NivelPensar.PREDETERMINADO
    private var estilo: Estilo = Estilos.PREDETERMINADO
    private var buscarWeb = true
    private var mostrarRazonamiento = true

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
        conversaciones = Conversaciones(File(filesDir, "chats"))
        descargas = DescargaEnSegundoPlano(this)
        chatActual = prefs.getString("chat", null) ?: Conversaciones.nuevoId()

        raiz = FrameLayout(this).apply {
            background = FondoAurora()
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
        cargarCerebro()
    }

    override fun onPause() {
        super.onPause()
        guardarChat()
    }

    override fun onDestroy() {
        pantallaModelo.cerrar()
        motor?.cerrar()
        trabajador.shutdownNow()
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
        if (aprieta && !generando && motor != null && !cargandoModelo) {
            val soltar = motor
            motor = null
            modeloEnPausa = true
            trabajador.execute {
                asistente?.motor = null
                soltar?.cerrar()
            }
            principal.post { pintarEstadoModelo() }
        }
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
            estilo(12.5f, Colores.TEXTO, Peso.NEGRITA, 1.25f)
            gravity = Gravity.CENTER
            relleno(dp(14f), dp(9f))
            background = bordeDegradado(Colores.MARCA, Colores.SUPERFICIE_ALTA, dp(999f).toFloat(), dp(1.2f))
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
            estilo(24f, Colores.TEXTO, Peso.EXTRA, 1f)
            textoDegradado(Colores.MARCA)
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
            estilo(12.5f, Colores.TEXTO, Peso.NEGRITA, 1f)
            relleno(dp(12f), dp(9f))
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
            val radio = dp(18f).toFloat()
            background = pulsable(bordeDegradado(Colores.MARCA, Colores.alfa(Colores.SUPERFICIE, 0.96f), radio, dp(1.5f)), radio)
            setPadding(dp(12f), dp(11f), dp(12f), dp(11f))
            setOnClickListener { pantallaModelo.mostrar() }
        }
        tarjeta.addView(ImageView(this).apply {
            setImageDrawable(Icono(Trazos.descargar(), Colores.TEXTO, 2.2f))
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            background = degradado(Colores.MARCA, dp(12f).toFloat())
        }, lp(dp(36f), dp(36f)) { rightMargin = dp(12f) })
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(TextView(this).apply {
            text = "Descargá el modelo Rama"
            estilo(14f, Colores.TEXTO, Peso.EXTRA, 1.1f)
        })
        textos.addView(TextView(this).apply {
            text = "Una sola vez y después funciona sin internet. Tocá acá."
            estilo(12f, Colores.TEXTO_2, Peso.MEDIO, 1.2f)
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
            background = redondeado(Colores.alfa(Colores.SUPERFICIE, 0.96f), dp(26f).toFloat(), Colores.BORDE, dp(1f))
            setPadding(dp(5f), dp(5f), dp(5f), dp(5f))
            elevation = dp(4f).toFloat()
        }
        capsula.addView(botonIcono(Trazos.mas(), "Adjuntar foto, PDF o video", 40f, 19f, Colores.TEXTO_2, Colores.SUPERFICIE_ALTA, 0) { pedirArchivo() })
        entrada = EditText(this).apply {
            hint = "Preguntale a Rama…"
            estilo(15.5f, Colores.TEXTO, Peso.MEDIO, 1.25f)
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
            botonEnviar.background = pulsable(degradado(intArrayOf(Colores.CORAL, Colores.ROJO), radio), radio)
            botonEnviar.contentDescription = "Detener"
            return
        }
        val hayTexto = entrada.text.isNotBlank()
        botonEnviar.setImageDrawable(Icono(Trazos.enviar(), if (hayTexto) Colores.TEXTO else Colores.TEXTO_3, 2.4f))
        botonEnviar.background = if (hayTexto) pulsable(degradado(Colores.MARCA, radio), radio)
        else pulsable(Colores.SUPERFICIE_ALTA, radio)
        botonEnviar.contentDescription = "Enviar"
    }

    private fun pintarBotonWeb() {
        val radio = dp(20f).toFloat()
        botonWeb.setImageDrawable(Icono(Trazos.globo(), if (buscarWeb) Colores.CELESTE else Colores.TEXTO_3, 2f))
        botonWeb.background = if (buscarWeb) pulsable(Colores.alfa(Colores.CELESTE, 0.14f), radio, Colores.alfa(Colores.CELESTE, 0.55f), dp(1f))
        else pulsable(Colores.SUPERFICIE_ALTA, radio)
        botonWeb.contentDescription = if (buscarWeb) "Búsqueda web activada" else "Búsqueda web apagada"
    }

    private fun pintarEstadoModelo() {
        val m = motor
        val (texto, color) = when {
            cargandoModelo -> "cargando el modelo…" to Colores.LILA
            m != null -> "${m.nombre} · lista · ${m.plan.totalLegible} de RAM" to Colores.MENTA
            modeloEnPausa -> "modelo en pausa (se recarga solo)" to Colores.LILA
            !Llama.disponible -> "sin motor en este teléfono" to Colores.ERROR
            else -> "sin modelo · tocá Modelo" to Colores.NARANJA
        }
        subtitulo.text = texto
        puntoEstado.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val radio = dp(999f).toFloat()
        chipModelo.text = when {
            cargandoModelo -> "Cargando…"
            m != null -> (m.edicion?.nombre?.removePrefix("Rama ") ?: "Propio")
            else -> "Modelo"
        }
        val (icono, tono) = if (m != null) Trazos.chip() to Colores.MENTA else Trazos.descargar() to Colores.NARANJA
        val dibujo = Icono(icono, tono, 2.2f)
        dibujo.setBounds(0, 0, dp(14f), dp(14f))
        chipModelo.setCompoundDrawables(dibujo, null, null, null)
        chipModelo.compoundDrawablePadding = dp(6f)
        chipModelo.background = pulsable(Colores.alfa(tono, 0.12f), radio, Colores.alfa(tono, 0.5f), dp(1f))
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

        contenido.addView(rotulo("Estilo de respuesta", Colores.LILA))
        val estilos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val descripcion = TextView(this).apply { estilo(12.5f, Colores.TEXTO_2, Peso.NORMAL, 1.35f) }
        val fichas = ArrayList<Pair<Estilo, TextView>>()
        fun pintarEstilos() {
            for ((e, vista) in fichas) {
                val elegido = e.id == estilo.id
                val radio = dp(999f).toFloat()
                vista.estilo(13f, if (elegido) Colores.TEXTO else Colores.TEXTO_2, if (elegido) Peso.EXTRA else Peso.NEGRITA, 1f)
                vista.background = if (elegido) pulsable(degradado(Colores.MARCA, radio, GradientDrawable.Orientation.LEFT_RIGHT), radio)
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
                    estilo = e
                    preferencias().edit().putString("estilo", e.id).apply()
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

        contenido.addView(rotulo("Opciones", Colores.CELESTE), lp(WRAP, WRAP) { bottomMargin = dp(6f) })
        contenido.addView(filaInterruptor("Buscar en la web", "Cuando la pregunta necesita datos actuales o verificables.", buscarWeb, Colores.CELESTE) {
            buscarWeb = it
            preferencias().edit().putBoolean("web", it).apply()
            pintarBotonWeb()
        })
        contenido.addView(filaInterruptor("Mostrar el razonamiento", "Ver en vivo cómo piensa, qué busca y qué lee antes de responder.", mostrarRazonamiento, Colores.LILA) {
            mostrarRazonamiento = it
            preferencias().edit().putBoolean("razonamiento", it).apply()
        })

        contenido.addView(rotulo("Niveles de pensamiento", Colores.NARANJA), lp(WRAP, WRAP) { topMargin = dp(18f); bottomMargin = dp(6f) })
        for (n in NivelPensar.entries) {
            val filaNivel = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7f), 0, dp(7f))
            }
            val (trazo, relleno) = Trazos.nivel(n)
            filaNivel.addView(ImageView(this).apply {
                setImageDrawable(Icono(trazo, Colores.FONDO, 2.2f, relleno))
                setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
                background = degradado(Colores.deNivel(n), dp(11f).toFloat())
            }, lp(dp(32f), dp(32f)) { rightMargin = dp(12f) })
            val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textos.addView(TextView(this).apply {
                text = n.nombre + if (n.piensa) "  ·  hasta ${n.presupuesto} tokens de razonamiento" else "  ·  sin razonamiento"
                estilo(13.5f, Colores.principalDeNivel(n), Peso.EXTRA, 1.1f)
            })
            textos.addView(TextView(this).apply {
                text = n.descripcion + if (n.paginasALeer > 0) " Lee hasta ${n.paginasALeer} página(s) al buscar." else ""
                estilo(12.5f, Colores.TEXTO_2, Peso.NORMAL, 1.3f)
            })
            filaNivel.addView(textos, lp(0, WRAP, 1f))
            contenido.addView(filaNivel)
        }

        contenido.addView(TextView(this).apply {
            text = "Rama ${Identidad.VERSION} · modelo de código abierto sobre ${Catalogo.BASE} (${Catalogo.LICENCIA}) · llama.cpp · Nunito"
            estilo(11.5f, Colores.TEXTO_3, Peso.MEDIO, 1.3f)
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
            setPadding(dp(4f), dp(26f), dp(4f), dp(8f))
        }
        val logo = avatarRama(78f)
        logo.elevation = dp(10f).toFloat()
        logo.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(vista: View, contorno: Outline) = contorno.setOval(0, 0, vista.width, vista.height)
        }
        columna.addView(logo, lp(dp(78f), dp(78f)))
        columna.addView(TextView(this).apply {
            text = "Hola, soy Rama"
            estilo(31f, Colores.TEXTO, Peso.EXTRA, 1.05f)
            gravity = Gravity.CENTER
            textoDegradado(Colores.MARCA)
        }, lp(WRAP, WRAP) { topMargin = dp(16f) })
        columna.addView(TextView(this).apply {
            text = "Tu IA de código abierto. Pienso adentro de tu teléfono, busco en la web cuando hace falta y vos elegís cuánto razono."
            estilo(14.5f, Colores.TEXTO_2, Peso.MEDIO, 1.4f)
            gravity = Gravity.CENTER
        }, lp(MATCH, WRAP) { setMargins(dp(12f), dp(8f), dp(12f), dp(22f)) })

        val sugerencias = listOf(
            Triple("¿Qué pasó hoy en Argentina?", Trazos.globo(), Colores.CELESTE),
            Triple("¿A cuánto está el dólar hoy?", Trazos.calculadora(), Colores.MENTA),
            Triple("Explicame la relatividad como si tuviera 12 años", Trazos.lamparita(), Colores.LILA),
            Triple("Ayudame a escribir un mensaje para mi jefe", Trazos.lapiz(), Colores.NARANJA),
        )
        for (fila in sugerencias.chunked(2)) {
            val renglon = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fila.forEachIndexed { i, (texto, trazo, tono) ->
                renglon.addView(tarjetaSugerencia(texto, trazo, tono), lp(0, MATCH, 1f) { if (i == 0) rightMargin = dp(10f) })
            }
            columna.addView(renglon, lp(MATCH, WRAP) { bottomMargin = dp(10f) })
        }
        return columna
    }

    private fun tarjetaSugerencia(texto: String, trazo: android.graphics.Path, tono: Int): View {
        val radio = dp(20f).toFloat()
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pulsable(redondeado(Colores.alfa(Colores.SUPERFICIE, 0.82f), radio, Colores.alfa(tono, 0.35f), dp(1f)), radio)
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            setOnClickListener {
                rebote(this)
                enviar(texto)
            }
        }
        tarjeta.addView(ImageView(this).apply {
            setImageDrawable(Icono(trazo, Colores.FONDO, 2.1f))
            setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
            background = degradado(intArrayOf(tono, Colores.mezclar(tono, Colores.FUCSIA, 0.35f)), dp(11f).toFloat())
        }, lp(dp(32f), dp(32f)) { bottomMargin = dp(10f) })
        tarjeta.addView(TextView(this).apply {
            text = texto
            estilo(13.5f, Colores.TEXTO, Peso.NEGRITA, 1.3f)
        })
        return tarjeta
    }

    private fun burbujaUsuario(texto: String) {
        val burbuja = TextView(this).apply {
            text = texto
            estilo(15.5f, Colores.TEXTO, Peso.MEDIO, 1.35f)
            setPadding(dp(15f), dp(11f), dp(15f), dp(11f))
            val grande = dp(22f).toFloat()
            background = degradado(intArrayOf(Colores.VIOLETA, Colores.FUCSIA), 0f, GradientDrawable.Orientation.TL_BR, esquinas(grande, dp(6f).toFloat(), grande))
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
        val turnos = historial.toList()
        historial.add(Mensaje("user", limpio))
        val ajustes = AjustesRama(nivelUsado, estilo, buscarWeb)
        val adjunto = ultimoAdjunto

        val escrito = StringBuilder()
        var pintarPendiente = false
        val oyente = object : OyenteRama {
            override fun paso(paso: PasoRama) {
                principal.post { tarjeta?.agregarPaso(paso) }
            }

            override fun pensamiento(fragmento: String) {
                principal.post { tarjeta?.agregarPensamiento(fragmento) }
            }

            override fun texto(fragmento: String) {
                principal.post {
                    tarjeta?.terminarPensar()
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
            principal.post { terminarRespuesta(vista, tarjeta, resultado) }
        }
    }

    private fun terminarRespuesta(vista: VistaRespuesta, tarjeta: TarjetaPensar?, respuesta: RespuestaRama?) {
        generando = false
        pintarBotonEnviar()
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
            val plan = MotorRama.planear(archivo, gguf, ramTotalGb(), libre)
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
                    if (!silencioso) avisar("${abierto.nombre} lista · ${plan.contexto} tokens de contexto · usa ~${plan.totalLegible} de RAM")
                }
                pintarEstadoModelo()
                if (pantallaModelo.visible) pantallaModelo.refrescar()
            }
        }
    }

    private fun faltaMemoria(archivo: File, plan: PlanDeMemoria): String {
        val edicion = Catalogo.deArchivo(archivo.name)
        val menor = Catalogo.EDICIONES.lastOrNull { it.memoriaTipica.total < plan.total && it != edicion }
        return "No me alcanza la memoria para abrir **${edicion?.nombre ?: archivo.name}**: necesita unos ${plan.totalLegible} " +
            "(pesos ${PlanDeMemoria.legible(plan.bytesPesos)}, memoria de la charla ${PlanDeMemoria.legible(plan.bytesCache)} y cálculo ${PlanDeMemoria.legible(plan.bytesComputo)}) " +
            "y ahora hay ${PlanDeMemoria.legible(plan.libre)} libres.\n\n" +
            "Cerrá otras aplicaciones (sobre todo juegos, cámara y navegador) y probá de nuevo" +
            (if (menor != null) ", o usá **${menor.nombre}**, que ocupa unos ${menor.memoriaTipica.totalLegible}." else ".")
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
            val plan = MotorRama.planear(guardado, gguf, ramTotalGb(), memoriaLibre())
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
            background = redondeado(Colores.alfa(Colores.SUPERFICIE, 0.9f), dp(16f).toFloat(), Colores.BORDE, dp(1f))
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
        val grande = dp(22f).toFloat()
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bordeDegradado(intArrayOf(Colores.VIOLETA, Colores.FUCSIA), Colores.SUPERFICIE_ALTA, grande, dp(1.5f))
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
                        contorno.setRoundRect(0, 0, vista.width, vista.height, dp(17f).toFloat())
                }
            }, lp(ancho, alto))
        }
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(10f), dp(8f), 0)
        }
        fila.addView(ImageView(this).apply { setImageDrawable(Icono(Trazos.documento(), Colores.LILA, 2f)) }, lp(dp(16f), dp(16f)) { rightMargin = dp(8f) })
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
            background = redondeado(Colores.alfa(Colores.SUPERFICIE, 0.9f), dp(16f).toFloat(), Colores.BORDE_TENUE, dp(1f))
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        }
        for ((clave, valor) in datos) {
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3f), 0, dp(3f))
            }
            fila.addView(TextView(this).apply {
                text = clave
                estilo(12f, Colores.TEXTO_3, Peso.NEGRITA, 1.2f)
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
        /** Arriba de esto (≈ Rama Ultra) el modelo se suelta apenas escasea la memoria. */
        private const val MODELO_GRANDE = 3_500_000_000L
        // Niveles de onTrimMemory (varios quedaron obsoletos como constantes en API 34).
        private const val TRIM_BAJO = 10
        private const val TRIM_CRITICO = 15
        private const val TRIM_FONDO = 40
        private const val TRIM_MODERADO = 60
    }
}
