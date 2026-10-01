package ar.rama.ai.motor

import ar.rama.ai.Adjunto

enum class TipoPaso { HABILIDAD, BASE, WEB, LECTURA, HERRAMIENTA, MOTOR, PRESUPUESTO, ADJUNTO, AVISO }

data class PasoRama(val tipo: TipoPaso, val titulo: String, val detalle: String)

data class AjustesRama(val nivel: NivelPensar, val estilo: Estilo, val buscarWeb: Boolean)

/** Lo que va pasando mientras Rama contesta. Se llama desde el hilo de trabajo. */
interface OyenteRama {
    fun paso(paso: PasoRama)
    fun pensamiento(fragmento: String)
    fun texto(fragmento: String)
    /** false = el usuario pidió detener. */
    fun seguir(): Boolean
}

data class RespuestaRama(
    val texto: String,
    val pensamiento: String,
    val fuentes: List<Resultado>,
    val respaldo: Respaldo,
    val pasos: List<PasoRama>,
    val milisPensando: Long,
    val tokensPensados: Int,
    val detenida: Boolean,
    val usoModelo: Boolean,
)

/**
 * Orquesta una respuesta de Rama:
 *  1. órdenes directas (aprender, olvidar),
 *  2. habilidades exactas (cuentas, fechas, conversiones, geografía…),
 *  3. su base de conocimiento propia,
 *  4. búsqueda web (buscadores, Wikipedia, noticias, dólar, clima) y lectura de páginas,
 *  5. el modelo, que razona dentro de <think> con el presupuesto del nivel elegido
 *     y escribe la respuesta final apoyándose en todo lo anterior.
 */
class Asistente(val rama: Rama, private val buscador: Buscador = Buscador()) {

    @Volatile
    var motor: ModeloDeLenguaje? = null

    @Volatile
    private var cancelado = false

    fun cancelar() {
        cancelado = true
        motor?.cancelar()
    }

    /**
     * Procesa de antemano el mensaje de sistema, que es igual en todos los
     * turnos: el motor lo deja en su caché y la primera pregunta arranca sin
     * tener que leerlo. Conviene llamarlo apenas se carga el modelo.
     */
    fun precalentar(estilo: Estilo) {
        val m = motor ?: return
        if (!m.esChatML) return
        val prefijo = "<|im_start|>system\n" + Identidad.sistema(estilo) + "<|im_end|>\n"
        try {
            m.generar(prefijo, 1, 0.7f, 0.8f, 20) { false }
        } catch (e: Throwable) {
        }
    }

    fun responder(
        pregunta: String,
        historial: List<Mensaje>,
        adjunto: Adjunto?,
        ajustes: AjustesRama,
        oyente: OyenteRama,
    ): RespuestaRama {
        cancelado = false
        val pasos = ArrayList<PasoRama>()
        fun paso(tipo: TipoPaso, titulo: String, detalle: String) {
            val nuevo = PasoRama(tipo, titulo, detalle)
            pasos.add(nuevo)
            oyente.paso(nuevo)
        }
        fun sigue(): Boolean = !cancelado && oyente.seguir()

        val plano = Texto.normalizar(pregunta)
        rama.memoria.registrarTurno("usuario", pregunta)

        // 1. Órdenes directas: «aprende: …», «olvidá …».
        for ((nombre, comando) in Skills.COMANDOS) {
            val salida = seguro { comando.invoke(pregunta, rama) } ?: continue
            paso(TipoPaso.HABILIDAD, "Orden directa", "«$nombre»: es una orden, no hace falta el modelo")
            oyente.texto(salida)
            rama.memoria.registrarTurno("rama", salida)
            return RespuestaRama(salida, "", emptyList(), Respaldo.CALCULO, pasos, 0, 0, false, false)
        }

        // 2. Habilidades exactas.
        var dato: String? = null
        for ((nombre, habilidad) in Skills.TODAS) {
            val salida = seguro { habilidad.invoke(pregunta, rama) } ?: continue
            dato = salida
            paso(TipoPaso.HABILIDAD, "Dato exacto", "la habilidad «$nombre» resolvió: $salida")
            break
        }

        // 3. Base propia.
        val local = seguro { rama.recuperar(pregunta, 3, 0.42) } ?: emptyList()
        if (local.isNotEmpty()) {
            paso(TipoPaso.BASE, "Base propia", if (local.size == 1) "1 fragmento de mi base se parece a tu pregunta" else "${local.size} fragmentos de mi base se parecen a tu pregunta")
        }

        // 4. Adjunto.
        val usaAdjunto = adjunto != null && PREGUNTA_POR_ADJUNTO.containsMatchIn(plano)
        if (usaAdjunto) {
            paso(TipoPaso.ADJUNTO, "Archivo", "tu pregunta habla del archivo «${adjunto!!.nombre}», que ya analicé")
        }

        // 5. Búsqueda web.
        val fuentes = ArrayList<Resultado>()
        var paginas: Map<String, String> = emptyMap()
        val nivel = ajustes.nivel
        val motivo = motivoParaBuscar(plano, ajustes, dato != null, local.isNotEmpty(), usaAdjunto)
        if (motivo != null && sigue()) {
            val consulta = consultaDeBusqueda(pregunta, historial)
            paso(TipoPaso.WEB, "Búsqueda web", "$motivo — busco «$consulta»")
            // Todo sale a la vez: herramientas, noticias, buscadores y Wikipedia.
            val lugar = if (CLIMA.containsMatchIn(plano)) lugarDelClima(plano) else null
            val enDolar = if (DOLAR.containsMatchIn(plano)) buscador.lanzar { buscador.dolar() } else null
            val enClima = if (lugar != null) buscador.lanzar { buscador.clima(lugar) } else null
            val enNoticias = if (NOTICIAS.containsMatchIn(plano)) buscador.lanzar { buscador.noticias(consulta, 5) } else null
            val enWeb = buscador.lanzar { buscador.web(consulta, nivel.resultadosWeb) }
            val conWiki = esFactual(plano) || nivel >= NivelPensar.ALTO
            val enWiki = if (conWiki) buscador.lanzar { buscador.wikipedia(consulta) } else null
            fun <T> esperar(f: java.util.concurrent.Future<T>?): T? = try {
                f?.get(ESPERA_RED, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                f?.cancel(true)
                null
            }

            var herramientaResolvio = false
            esperar(enDolar)?.let {
                fuentes.add(it)
                herramientaResolvio = true
                paso(TipoPaso.HERRAMIENTA, "Cotizaciones", "traje las cotizaciones actuales del dólar")
            }
            esperar(enClima)?.let {
                fuentes.add(it)
                herramientaResolvio = true
                paso(TipoPaso.HERRAMIENTA, "Clima", "pronóstico de Open-Meteo para «$lugar»")
            }
            esperar(enNoticias)?.takeIf { it.isNotEmpty() }?.let { titulares ->
                fuentes.addAll(titulares)
                paso(TipoPaso.WEB, "Noticias", "${titulares.size} titulares recientes de Google Noticias")
            }
            val web = (esperar(enWeb) ?: emptyList()).let { if (herramientaResolvio) it.take(3) else it }
            fuentes.addAll(web)
            if (!herramientaResolvio) {
                esperar(enWiki)?.let { wiki ->
                    if (fuentes.none { it.url == wiki.url }) fuentes.add(0, wiki)
                    paso(TipoPaso.WEB, "Wikipedia", wiki.titulo)
                }
            } else {
                enWiki?.cancel(true)
            }
            if (fuentes.isEmpty()) {
                paso(TipoPaso.AVISO, "Sin resultados", "la web no respondió (¿hay conexión?)")
            } else {
                paso(TipoPaso.WEB, "Resultados", fuentes.joinToString("\n") { "· " + it.titulo + "  (" + Html.dominio(it.url) + ")" })
            }
            if (motor != null && nivel.paginasALeer > 0 && web.isNotEmpty() && sigue()) {
                val aLeer = web.filter { !it.url.contains("wikipedia.org") }.take(nivel.paginasALeer)
                if (aLeer.isNotEmpty()) {
                    paso(TipoPaso.LECTURA, "Leyendo páginas", aLeer.joinToString("\n") { "· " + Html.dominio(it.url) })
                    paginas = buscador.leer(aLeer, consulta, nivel.caracteresPorPagina)
                    paso(
                        TipoPaso.LECTURA, "Páginas leídas",
                        if (paginas.isEmpty()) "ninguna se dejó leer" else "saqué los párrafos útiles de ${paginas.size}",
                    )
                }
            }
        }
        val fuentesUnicas = Buscador.depurar(fuentes).ifEmpty { fuentes.distinctBy { it.url } }

        val respaldo = when {
            dato != null -> Respaldo.CALCULO
            fuentesUnicas.isNotEmpty() -> Respaldo.WEB
            local.isNotEmpty() -> Respaldo.BASE
            else -> Respaldo.SOLO_MODELO
        }

        // 6. Sin modelo (o con un dato exacto en niveles rápidos): se responde sin redactar.
        val m = motor
        val directo = dato != null && !usaAdjunto && nivel <= NivelPensar.NORMAL && fuentesUnicas.isEmpty()
        if (directo && m != null && sigue()) {
            val exacto = dato!!
            paso(TipoPaso.HABILIDAD, "Respuesta directa", "el dato exacto alcanza: no hace falta que el modelo lo redacte")
            oyente.texto(exacto)
            rama.memoria.registrarTurno("rama", exacto)
            return RespuestaRama(exacto, "", emptyList(), Respaldo.CALCULO, pasos, 0, 0, false, false)
        }
        if (m == null || !sigue()) {
            val texto = when {
                !sigue() -> "(detenido)"
                dato != null -> dato
                usaAdjunto -> adjunto!!.resumen
                local.isNotEmpty() -> local.first()
                fuentesUnicas.isNotEmpty() -> resumenDeBusqueda(fuentesUnicas)
                else -> Identidad.SIN_MODELO
            }
            if (m == null) paso(TipoPaso.AVISO, "Sin modelo", "todavía no hay un modelo cargado: respondo con lo que tengo")
            oyente.texto(texto)
            rama.memoria.registrarTurno("rama", texto)
            return RespuestaRama(texto, "", fuentesUnicas, respaldo, pasos, 0, 0, !sigue(), false)
        }

        // 7. El modelo escribe.
        val maxRespuesta = (ajustes.estilo.maxTokens * nivel.factorRespuesta).toInt().coerceAtMost(m.contexto / 3)
            .let { if (m.enAhorro) minOf(it, MAX_RESPUESTA_AHORRO) else it }
        val sistema = Identidad.sistema(ajustes.estilo)
        val contexto = armarContexto(dato, local, if (usaAdjunto) adjunto else null, fuentesUnicas, paginas)
        val pedido = Identidad.pedidoDelNivel(nivel).takeIf { m.esChatML && nivel.piensa } ?: ""
        val sinFuentes = contexto.isEmpty() && esFactual(plano)
        var armado = ajustarAlContexto(m, sistema, historial, contexto, pregunta, pedido, sinFuentes, maxRespuesta, nivel)
        if (m.enAhorro && armado.presupuesto > PRESUPUESTO_AHORRO) {
            armado = Armado(armado.prompt, PRESUPUESTO_AHORRO)
            paso(
                TipoPaso.PRESUPUESTO, "Ahorro de RAM",
                "el modelo está leyendo parte de sus pesos del almacenamiento, así que pienso hasta $PRESUPUESTO_AHORRO tokens para no tardar demasiado",
            )
        }
        paso(
            TipoPaso.MOTOR, "Escribiendo",
            "${m.nombre} · nivel ${nivel.nombre}" +
                (if (armado.presupuesto > 0) " · hasta ${armado.presupuesto} tokens para pensar" else " · sin razonar") +
                "\n" + m.info,
        )

        val escrito = escribir(m, armado.prompt, ajustes, armado.presupuesto, maxRespuesta, oyente, ::sigue) { t, d ->
            paso(TipoPaso.PRESUPUESTO, t, d)
        }
        var texto = escrito.respuesta.trim()
        if (texto.isEmpty()) {
            texto = when {
                !sigue() -> "(detenido)"
                dato != null -> dato
                else -> "Me quedé sin palabras. Probá preguntarlo de otra forma o subí el nivel de pensamiento."
            }
            oyente.texto(texto)
        }
        rama.memoria.registrarTurno("rama", texto)
        return RespuestaRama(
            texto, escrito.pensamiento.trim(), fuentesUnicas, respaldo, pasos,
            escrito.milisPensando, escrito.tokensPensados, !sigue(), true,
        )
    }

    // ------------------------------------------------------------ escritura con presupuesto

    private class Escrito(
        val respuesta: String,
        val pensamiento: String,
        val tokensPensados: Int,
        val milisPensando: Long,
    )

    /**
     * Genera la respuesta. Si el nivel piensa, el prompt termina en "<think>\n"
     * y el modelo razona hasta cerrar el bloque o agotar el presupuesto. Si lo
     * agota, se corta y se continúa desde el mismo texto con un cierre escrito
     * por nosotros: el motor reutiliza lo ya procesado y sólo lee el cierre.
     */
    private fun escribir(
        m: ModeloDeLenguaje,
        promptBase: String,
        ajustes: AjustesRama,
        presupuesto: Int,
        maxRespuesta: Int,
        oyente: OyenteRama,
        sigue: () -> Boolean,
        avisar: (String, String) -> Unit,
    ): Escrito {
        val piensa = presupuesto > 0
        val creativo = ajustes.estilo.id == Estilos.CREATIVO.id
        val temperatura = if (piensa) (if (creativo) 0.8f else 0.6f) else ajustes.estilo.temperatura
        val topP = if (piensa) 0.95f else ajustes.estilo.topP
        val topK = if (piensa) 20 else ajustes.estilo.topK

        val apertura = when {
            !m.esChatML -> ""
            piensa -> "<think>\n"
            else -> "<think>\n\n</think>\n\n"
        }
        val prompt = promptBase + apertura
        val separador = SeparadorPensamiento(empiezaPensando = piensa)
        val crudo = StringBuilder()
        var tokensPensados = 0
        var tokensRespuesta = 0
        var agotado = false
        // Un modelo importado que no es ChatML puede ponerse a pensar solo: le damos un margen fijo.
        val tope = if (piensa) presupuesto else 512
        val inicio = System.currentTimeMillis()
        var finPensar = 0L
        val alPensar: (String) -> Unit = { oyente.pensamiento(it) }
        val alResponder: (String) -> Unit = {
            if (finPensar == 0L) finPensar = System.currentTimeMillis()
            oyente.texto(it)
        }

        m.generar(prompt, if (piensa) presupuesto + maxRespuesta + 16 else maxRespuesta, temperatura, topP, topK) { fragmento ->
            if (!sigue()) return@generar false
            crudo.append(fragmento)
            val pensabaAntes = separador.pensando
            separador.procesar(fragmento, alPensar, alResponder)
            if (pensabaAntes || separador.pensando) tokensPensados++ else tokensRespuesta++
            when {
                separador.terminado -> false
                separador.pensando && tokensPensados >= tope -> {
                    agotado = true
                    false
                }
                !separador.pensando && tokensRespuesta >= maxRespuesta -> false
                else -> true
            }
        }
        separador.cerrar(alPensar, alResponder)

        // Si terminó pensando (agotó el presupuesto o el modelo se detuvo sin cerrar), cerramos nosotros.
        if ((agotado || separador.pensando) && separador.respuesta.isEmpty() && sigue()) {
            avisar(
                "Presupuesto de razonamiento",
                if (agotado) "usé los $presupuesto tokens para pensar del nivel ${ajustes.nivel.nombre}: cierro el razonamiento y respondo"
                else "el razonamiento quedó abierto: lo cierro y respondo",
            )
            val salto = if (crudo.endsWith("\n")) "" else "\n"
            val continuacion = prompt + crudo + salto + "\nListo, con esto alcanza. Ahora escribo la respuesta final.\n</think>\n\n"
            separador.pasarARespuesta()
            tokensRespuesta = 0
            m.generar(continuacion, maxRespuesta, temperatura, topP, topK) { fragmento ->
                if (!sigue()) return@generar false
                separador.procesar(fragmento, alPensar, alResponder)
                tokensRespuesta++
                !separador.terminado && tokensRespuesta < maxRespuesta
            }
            separador.cerrar(alPensar, alResponder)
        }
        val milis = if (piensa) ((if (finPensar > 0) finPensar else System.currentTimeMillis()) - inicio) else 0L
        return Escrito(separador.respuesta.toString(), separador.pensamiento.toString(), tokensPensados, milis)
    }

    // ------------------------------------------------------------ armado del prompt

    private class Armado(val prompt: String, val presupuesto: Int)

    private fun armarContexto(
        dato: String?,
        local: List<String>,
        adjunto: Adjunto?,
        fuentes: List<Resultado>,
        paginas: Map<String, String>,
    ): String {
        val c = StringBuilder()
        if (dato != null) c.append("DATO VERIFICADO (calculado por una herramienta exacta):\n").append(dato).append("\n\n")
        if (adjunto != null) {
            c.append("ARCHIVO ADJUNTO «").append(adjunto.nombre).append("» (").append(adjunto.tipo).append("):\n")
            for ((clave, valor) in adjunto.datos) c.append("- ").append(clave).append(": ").append(valor).append('\n')
            c.append(adjunto.resumen.take(1500)).append("\n\n")
        }
        if (local.isNotEmpty()) {
            c.append("DE LA BASE PROPIA DE RAMA:\n")
            for (fragmento in local) c.append("- ").append(fragmento).append('\n')
            c.append('\n')
        }
        if (fuentes.isNotEmpty()) {
            c.append("RESULTADOS WEB (consultados hoy):\n")
            fuentes.forEachIndexed { i, r ->
                c.append('[').append(i + 1).append("] ").append(r.titulo).append('\n')
                if (r.resumen.isNotBlank()) c.append(r.resumen.take(420)).append('\n')
                c.append("Fuente: ").append(r.url).append("\n\n")
            }
        }
        if (paginas.isNotEmpty()) {
            c.append("PÁGINAS LEÍDAS (fragmentos relevantes):\n")
            for ((url, texto) in paginas) {
                val indice = fuentes.indexOfFirst { it.url == url }
                c.append(if (indice >= 0) "[${indice + 1}] " else "").append(Html.dominio(url)).append(":\n")
                    .append(texto).append("\n\n")
            }
        }
        return c.toString().trim()
    }

    /**
     * Arma el prompt final y decide cuánto puede pensar: todo tiene que entrar
     * en el contexto del modelo (sistema + historial + fuentes + razonamiento +
     * respuesta). Si no entra, primero se va el historial viejo y después se
     * recorta el material de consulta.
     */
    private fun ajustarAlContexto(
        m: ModeloDeLenguaje,
        sistema: String,
        historialCompleto: List<Mensaje>,
        contextoCompleto: String,
        pregunta: String,
        pedido: String,
        sinFuentes: Boolean,
        maxRespuesta: Int,
        nivel: NivelPensar,
    ): Armado {
        val disponible = m.contexto - maxRespuesta - 48
        val deseado = if (m.esChatML) nivel.presupuesto else 0
        val minimoPensar = if (deseado > 0) minOf(deseado, 200) else 0

        var historial = historialCompleto.takeLast(MAX_HISTORIAL)
            .map { Mensaje(it.rol, recortar(it.contenido, 1500)) }
        while (historial.isNotEmpty() && historial.first().rol != "user") historial = historial.drop(1)
        var contexto = contextoCompleto

        fun ultimo(): String {
            val s = StringBuilder()
            if (contexto.isNotEmpty()) s.append(contexto).append("\n\n---\n")
            else if (sinFuentes) s.append(Identidad.SIN_FUENTES).append("\n\n")
            if (pedido.isNotEmpty()) s.append(pedido).append('\n')
            s.append(if (contexto.isNotEmpty() || pedido.isNotEmpty()) "Pregunta: " else "").append(pregunta)
            return s.toString()
        }

        fun armar(): String {
            val mensajes = ArrayList<Mensaje>()
            mensajes.add(Mensaje("system", sistema))
            mensajes.addAll(historial)
            mensajes.add(Mensaje("user", ultimo()))
            return if (m.esChatML) chatML(mensajes) else m.formatearNativo(mensajes).ifEmpty { chatML(mensajes) }
        }

        var prompt = armar()
        while (tokens(prompt) + minimoPensar > disponible && historial.isNotEmpty()) {
            historial = historial.drop(2)
            while (historial.isNotEmpty() && historial.first().rol != "user") historial = historial.drop(1)
            prompt = armar()
        }
        if (tokens(prompt) + minimoPensar > disponible && contexto.isNotEmpty()) {
            val sobra = ((tokens(prompt) + minimoPensar - disponible) * CARACTERES_POR_TOKEN).toInt() + 200
            contexto = contexto.take(maxOf(400, contexto.length - sobra)) + "\n[…]"
            prompt = armar()
        }
        val presupuesto = if (deseado == 0) 0 else minOf(deseado, disponible - tokens(prompt)).let { if (it < 96) 0 else it }
        return Armado(prompt, presupuesto)
    }

    // ------------------------------------------------------------ decisiones

    fun motivoParaBuscar(
        plano: String,
        ajustes: AjustesRama,
        hayDato: Boolean,
        hayLocal: Boolean,
        usaAdjunto: Boolean,
    ): String? {
        if (!ajustes.buscarWeb) return null
        if (PIDE_BUSCAR.containsMatchIn(plano)) return "me lo pediste"
        if (!ajustes.estilo.buscaEnWeb) return null
        if (hayDato || usaAdjunto) return null
        val palabras = plano.split(' ').count { it.isNotBlank() }
        if (DOLAR.containsMatchIn(plano) || CLIMA.containsMatchIn(plano)) return "es un dato que cambia todos los días"
        if (NECESITA_ACTUALIDAD.containsMatchIn(plano) && !CHARLA.containsMatchIn(plano)) return "la pregunta depende de datos actuales"
        if (esFactual(plano)) return "es una pregunta de datos y prefiero traer la fuente antes que confiar en la memoria"
        if (ajustes.nivel >= NivelPensar.ALTO && palabras >= 4 && !CHARLA.containsMatchIn(plano)) {
            return "en nivel ${ajustes.nivel.nombre} verifico en la web antes de responder"
        }
        if (!hayLocal && palabras >= 4 && ajustes.nivel != NivelPensar.BAJO && PARECE_CONSULTA.containsMatchIn(plano) &&
            !CHARLA.containsMatchIn(plano)
        ) {
            return "no tengo nada parecido en mi base"
        }
        return null
    }

    fun esFactual(plano: String): Boolean =
        !CHARLA.containsMatchIn(plano) && PIDE_HECHO.containsMatchIn(plano) && plano.split(' ').size >= 3

    /** La consulta que se manda al buscador: sin muletillas y, si es un repregunte corto, con el tema anterior. */
    fun consultaDeBusqueda(pregunta: String, historial: List<Mensaje>): String {
        var consulta = pregunta.trim().replace(Regex("[¿?¡!]"), " ")
        consulta = MULETILLAS.replace(consulta, " ").replace(Regex("\\s+"), " ").trim()
        if (consulta.isEmpty()) consulta = pregunta.trim()
        val palabras = consulta.split(' ').size
        if (palabras <= 3) {
            val anterior = historial.lastOrNull { it.rol == "user" }?.contenido
            if (anterior != null && anterior.length < 200) {
                consulta = MULETILLAS.replace(anterior.replace(Regex("[¿?¡!]"), " "), " ").trim() + " " + consulta
            }
        }
        return consulta.replace(Regex("\\s+"), " ").take(160)
    }

    fun lugarDelClima(plano: String): String? {
        val m = Regex("\\b(?:en|de|para|por)\\s+([a-zñ][a-zñ ]{1,40}?)(?:\\s+(?:hoy|manana|ahora|esta semana|este fin de semana|el finde|pasado manana))?\\s*$")
            .find(plano) ?: return null
        val lugar = m.groupValues[1].trim()
        if (lugar.isEmpty() || lugar in setOf("hoy", "manana", "la semana", "el dia")) return null
        return lugar
    }

    fun resumenDeBusqueda(resultados: List<Resultado>): String {
        val s = StringBuilder()
        s.append("Todavía no tengo el modelo Rama descargado para redactarte una respuesta, pero busqué en la web y encontré esto:\n\n")
        resultados.take(6).forEachIndexed { i, r ->
            s.append("**").append(i + 1).append(". ").append(r.titulo).append("**\n")
            if (r.resumen.isNotBlank()) s.append(r.resumen.take(300)).append('\n')
            s.append('\n')
        }
        s.append("Las fuentes están abajo. Bajá el modelo desde **Modelo** y la próxima vez te lo explico yo.")
        return s.toString()
    }

    companion object {
        const val MAX_HISTORIAL = 8
        /** Cuánto se espera a cada consulta de red que salió en paralelo (segundos). */
        const val ESPERA_RED = 9L
        /** En modo ahorro de RAM cada token tarda más: se piensa y se escribe menos. */
        const val PRESUPUESTO_AHORRO = 256
        const val MAX_RESPUESTA_AHORRO = 450
        const val CARACTERES_POR_TOKEN = 3.2

        fun tokens(texto: String): Int = (texto.length / CARACTERES_POR_TOKEN).toInt() + 8

        fun chatML(mensajes: List<Mensaje>): String {
            val s = StringBuilder()
            for (m in mensajes) s.append("<|im_start|>").append(m.rol).append('\n').append(m.contenido).append("<|im_end|>\n")
            s.append("<|im_start|>assistant\n")
            return s.toString()
        }

        fun recortar(texto: String, maximo: Int): String =
            if (texto.length <= maximo) texto else texto.take(maximo).substringBeforeLast(' ') + " […]"

        private inline fun <T> seguro(bloque: () -> T?): T? = try {
            bloque()
        } catch (e: Exception) {
            null
        }

        val PIDE_BUSCAR = Regex("\\b(busca|buscar|buscame|busques|googlea|googleame|investiga|chequea|fijate en (internet|la web|google)|en la web|en internet|en google)\\b")
        val PIDE_HECHO = Regex("\\b(quien|quienes|cuando|donde|cuantos?|cuantas?|cual|cuales|que ano|en que ano|que fecha|de que|por que|como se llama|nombre de|autor de|invento|descubrio|gano|fundo|escribio|nacio|murio|capital de|poblacion|altura de|distancia|precio|cuesta|vale)\\b")
        val CHARLA = Regex("\\b(hola|buenas|gracias|chau|como estas|como andas|contame un chiste|chiste|escribi|escribime|inventa|imagina|un cuento|un poema|una historia|que opinas|que te parece|ayudame a|traduci|resumi|redacta|corregi|programa|codigo|funcion en)\\b")
        val NECESITA_ACTUALIDAD = Regex("\\b(hoy|ahora|actual|actualmente|ultimo|ultima|ultimos|ultimas|reciente|recientes|noticia|noticias|precio|precios|cotizacion|dolar|quien es|quien gano|quien fue|cuando (sale|salio|es|juega)|resultado|partido|elecciones|clima|pronostico|20[2-9][0-9])\\b")
        val PARECE_CONSULTA = Regex("\\b(que es|que son|que significa|como funciona|como se|para que sirve|diferencia entre|historia de|informacion sobre|datos de|explicame)\\b")
        val DOLAR = Regex("\\b(dolar|dolares|blue|cotizacion|mep|ccl|contado con liqui)\\b")
        val CLIMA = Regex("\\b(clima|pronostico|temperatura|llueve|llover|lluvia|hace frio|hace calor)\\b")
        val NOTICIAS = Regex("\\b(noticia|noticias|novedades|ultimo momento|que paso|que esta pasando|actualidad)\\b")
        val PREGUNTA_POR_ADJUNTO = Regex("\\b(archivo|foto|imagen|pdf|video|adjunt\\w*|documento)\\b")
        private val MULETILLAS = Regex(
            "(?i)^\\s*(rama[,\\s]+)?((por favor|porfa)\\s+)?(busca(me)?|buscar|googlea(me)?|investiga|fijate|decime|contame|me (podes|podrias) decir|quiero saber|sabes|necesito saber)\\b( (en|por) (internet|la web|google))?( (si|que|sobre|acerca de))?",
        )
    }
}
