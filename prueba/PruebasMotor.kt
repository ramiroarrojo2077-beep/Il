// Pruebas del motor que no necesitan Android: separador de razonamiento,
// parsers de buscadores, armado de consultas y extracción de páginas.
// Se corren con prueba/correr.sh (compila con kotlinc y ejecuta en la JVM).

import ar.rama.ai.motor.AjustesRama
import ar.rama.ai.motor.Asistente
import ar.rama.ai.motor.Buscador
import ar.rama.ai.motor.Estilos
import ar.rama.ai.motor.Identidad
import ar.rama.ai.motor.Mensaje
import ar.rama.ai.motor.NivelPensar
import ar.rama.ai.motor.SeparadorPensamiento
import ar.rama.ai.motor.Texto
import java.util.Calendar

private var fallas = 0
private var pruebas = 0

private fun verificar(nombre: String, condicion: Boolean, detalle: () -> String = { "" }) {
    pruebas++
    if (condicion) {
        println("ok   $nombre")
    } else {
        fallas++
        println("FALLA $nombre ${detalle()}")
    }
}

private fun separar(fragmentos: List<String>, empiezaPensando: Boolean): Triple<String, String, Boolean> {
    val s = SeparadorPensamiento(empiezaPensando)
    val pensado = StringBuilder()
    val dicho = StringBuilder()
    for (f in fragmentos) s.procesar(f, { pensado.append(it) }, { dicho.append(it) })
    s.cerrar({ pensado.append(it) }, { dicho.append(it) })
    return Triple(pensado.toString(), dicho.toString(), s.terminado)
}

fun main() {
    // --- Separador de razonamiento
    run {
        val (p, d, _) = separar(listOf("Hay que ", "sumar 2 y 2.", "\n</th", "ink>\n\n", "Son **4**."), true)
        verificar("separa pensamiento y respuesta con etiqueta partida", p == "Hay que sumar 2 y 2.\n" && d == "Son **4**.") { "p=[$p] d=[$d]" }
    }
    run {
        val (p, d, fin) = separar(listOf("Hola", ", ¿qué tal?", "<|im", "_end|>", "basura"), false)
        verificar("corta en la marca de fin", p.isEmpty() && d == "Hola, ¿qué tal?" && fin) { "p=[$p] d=[$d] fin=$fin" }
    }
    run {
        val (p, d, _) = separar(listOf("<think>pienso</think>", "respuesta"), false)
        verificar("detecta <think> abierto por el modelo", p == "pienso" && d == "respuesta") { "p=[$p] d=[$d]" }
    }
    run {
        val (_, d, _) = separar(listOf("a < b y 3 <", " 4"), false)
        verificar("no se traga los '<' comunes", d == "a < b y 3 < 4") { "d=[$d]" }
    }
    run {
        val s = SeparadorPensamiento(true)
        val dicho = StringBuilder()
        s.procesar("razono mucho <", {}, {})
        s.pasarARespuesta()
        s.procesar("La respuesta.", {}, { dicho.append(it) })
        verificar("pasarARespuesta deja el resto como respuesta", dicho.toString() == "La respuesta." && s.pensamiento.toString() == "razono mucho <") {
            "d=[$dicho] p=[${s.pensamiento}]"
        }
    }

    // --- Parsers de buscadores
    val ddg = """
        <div class="result results_links results_links_deep web-result ">
          <div class="links_main links_deep result__body">
            <h2 class="result__title">
              <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fes.wikipedia.org%2Fwiki%2FCanberra&amp;rut=abc">Canberra - Wikipedia, la enciclopedia libre</a>
            </h2>
            <div class="result__extras"><a class="result__url" href="//duckduckgo.com/l/?uddg=x">es.wikipedia.org/wiki/Canberra</a></div>
            <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">Canberra es la <b>capital</b> de <b>Australia</b> desde 1913.</a>
          </div>
        </div>
        <div class="result results_links results_links_deep result--ad ">
          <a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad=1">Anuncio</a>
        </div>
        <div class="result results_links results_links_deep web-result ">
            <a rel="nofollow" class="result__a" href="https://www.australia.com/es">Australia &amp; su capital</a>
            <a class="result__snippet" href="https://www.australia.com/es">Todo sobre el pa&iacute;s.</a>
        </div>
    """.trimIndent()
    val rDdg = Buscador.depurar(Buscador.parsearDuckDuckGo(ddg))
    verificar("DuckDuckGo: 2 resultados sin anuncios", rDdg.size == 2) { rDdg.toString() }
    verificar("DuckDuckGo: desenvuelve uddg", rDdg.firstOrNull()?.url == "https://es.wikipedia.org/wiki/Canberra") { rDdg.toString() }
    verificar("DuckDuckGo: resumen limpio", rDdg.firstOrNull()?.resumen == "Canberra es la capital de Australia desde 1913.") { rDdg.toString() }
    verificar("DuckDuckGo: entidades", rDdg.getOrNull(1)?.titulo == "Australia & su capital" && rDdg[1].resumen == "Todo sobre el país.") { rDdg.toString() }

    val lite = """
        <table><tr><td>1.&nbsp;</td><td>
        <a rel="nofollow" href="https://es.wikipedia.org/wiki/Canberra" class='result-link'>Canberra - Wikipedia</a>
        </td></tr><tr><td></td><td class='result-snippet'>Canberra es la <b>capital</b> federal.</td></tr>
        <tr><td>2.&nbsp;</td><td><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fa" class='result-link'>Ejemplo</a></td></tr>
        <tr><td></td><td class='result-snippet'>Otro texto.</td></tr></table>
    """.trimIndent()
    val rLite = Buscador.parsearDuckDuckGoLite(lite)
    verificar("DDG Lite: 2 resultados", rLite.size == 2 && rLite[1].url == "https://example.org/a" && rLite[0].resumen == "Canberra es la capital federal.") { rLite.toString() }

    val bing = """
        <ol id="b_results"><li class="b_algo" data-id=""><h2><a target="_blank" href="https://www.bing.com/ck/a?!&amp;&amp;p=abc&amp;u=a1aHR0cHM6Ly9lcy53aWtpcGVkaWEub3JnL3dpa2kvQ2FuYmVycmE&amp;ntb=1" h="ID=SERP">Canberra - <strong>Wikipedia</strong></a></h2>
        <div class="b_caption"><p class="b_lineclamp4 b_algoSlug">Canberra es la capital de Australia.</p></div></li>
        <li class="b_algo"><h2><a href="https://ejemplo.com/">Ejemplo</a></h2><p>Resumen dos</p></li></ol>
    """.trimIndent()
    val rBing = Buscador.parsearBing(bing)
    verificar("Bing: decodifica u=a1 base64", rBing.firstOrNull()?.url == "https://es.wikipedia.org/wiki/Canberra") { rBing.toString() }
    verificar("Bing: 2 resultados con resumen", rBing.size == 2 && rBing[0].resumen == "Canberra es la capital de Australia.") { rBing.toString() }

    val mojeek = """
        <ul class="results-standard"><li class="r1"><a class="ob" href="https://es.wikipedia.org/wiki/Canberra"><p class="i">x</p></a>
        <h2><a class="title" href="https://es.wikipedia.org/wiki/Canberra">Canberra</a></h2><p class="s">Capital de Australia.</p></li>
        <li class="r2"><h2><a class="title" href="https://b.com">B</a></h2><p class="s">Texto B</p></li></ul>
    """.trimIndent()
    val rMojeek = Buscador.parsearMojeek(mojeek)
    verificar("Mojeek: 2 resultados", rMojeek.size == 2 && rMojeek[0].resumen == "Capital de Australia.") { rMojeek.toString() }

    val rss = """
        <rss><channel><title>Google Noticias</title>
        <item><title>El dólar blue sube - Clarín</title><link>https://news.google.com/rss/articles/abc</link>
        <pubDate>Tue, 30 Sep 2026 14:00:00 GMT</pubDate><source url="https://www.clarin.com">Clarín</source></item>
        <item><title><![CDATA[Otra noticia & más]]></title><link>https://news.google.com/rss/articles/def</link><pubDate>x</pubDate></item>
        </channel></rss>
    """.trimIndent()
    val rNot = Buscador.parsearNoticias(rss)
    verificar("Noticias RSS: 2 titulares", rNot.size == 2 && rNot[0].resumen.startsWith("Clarín · 30 de septiembre") && rNot[1].titulo == "Otra noticia & más") { rNot.toString() }

    val pagina = """
        <html><head><style>p{color:red}</style><script>var x = "<p>no</p>";</script></head><body>
        <nav><p>Menú de navegación con muchas palabras que no importan para nada acá</p></nav>
        <p>La Ópera de Sídney es un edificio icónico inaugurado en 1973, diseñado por Jørn Utzon.</p>
        <p>Este párrafo habla de otra cosa totalmente distinta, sobre cocina y recetas de pastas caseras.</p>
        <p>Canberra fue elegida capital de Australia en 1908 como punto intermedio entre Sídney y Melbourne.</p>
        <footer><p>Copyright y enlaces legales de la página, suscribite al newsletter hoy mismo.</p></footer>
        </body></html>
    """.trimIndent()
    val extraido = Buscador.extraerRelevante(pagina, "¿Cuál es la capital de Australia?", 400) ?: ""
    verificar("Lectura: toma el párrafo relevante", extraido.contains("Canberra fue elegida") && !extraido.contains("recetas") && !extraido.contains("Menú")) { extraido }

    verificar("Clima: descripción", Buscador.describirClima(61) == "con lluvia")

    // --- Decisiones del asistente (no necesitan el modelo)
    val asistente = Asistente::class.java.declaredConstructors // sólo para asegurar que la clase carga
    verificar("Asistente carga", asistente.isNotEmpty())
    val a = sinRama()
    val normal = AjustesRama(NivelPensar.NORMAL, Estilos.CHARLA, true)
    fun motivo(p: String, ajustes: AjustesRama = normal) = a.motivoParaBuscar(Texto.normalizar(p), ajustes, false, false, false)
    verificar("Busca si se lo piden", motivo("buscá en internet quién ganó el mundial") != null)
    verificar("Busca datos actuales", motivo("¿a cuánto está el dólar hoy?") != null)
    verificar("No busca para charlar", motivo("hola, ¿cómo andás?") == null)
    verificar("No busca un poema", motivo("escribime un poema sobre el mar") == null)
    verificar("No busca con la web apagada", motivo("buscá el precio del dólar", normal.copy(buscarWeb = false)) == null)
    verificar("Max verifica en la web", motivo("explicame la teoría de cuerdas bosónicas", normal.copy(nivel = NivelPensar.MAX)) != null)
    verificar("Consulta sin muletillas", a.consultaDeBusqueda("Rama, buscame en internet quién ganó el mundial 2022?", emptyList()) == "quién ganó el mundial 2022") {
        a.consultaDeBusqueda("Rama, buscame en internet quién ganó el mundial 2022?", emptyList())
    }
    val repregunta = a.consultaDeBusqueda("¿y en Chile?", listOf(Mensaje("user", "¿Cuál es la capital de Perú?"), Mensaje("assistant", "Lima.")))
    verificar("Repregunta suma el tema anterior", repregunta.contains("capital de Perú") && repregunta.endsWith("y en Chile")) { repregunta }
    verificar("Lugar del clima", a.lugarDelClima(Texto.normalizar("¿Qué clima hace en Mar del Plata hoy?")) == "mar del plata") {
        a.lugarDelClima(Texto.normalizar("¿Qué clima hace en Mar del Plata hoy?")).toString()
    }

    // --- Identidad y formato
    val cal = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 30) }
    verificar("Fecha en castellano", Identidad.fechaDeHoy(cal) == "miércoles 30 de septiembre de 2026") { Identidad.fechaDeHoy(cal) }
    val prompt = Asistente.chatML(listOf(Mensaje("system", "S"), Mensaje("user", "U")))
    verificar("ChatML", prompt == "<|im_start|>system\nS<|im_end|>\n<|im_start|>user\nU<|im_end|>\n<|im_start|>assistant\n") { prompt }
    verificar("Sistema estable entre niveles", Identidad.sistema(Estilos.CHARLA, "x") == Identidad.sistema(Estilos.CHARLA, "x"))

    // --- Niveles de pensamiento con un modelo simulado
    run {
        val falso = ModeloFalso(listOf(List(400) { "pienso " }, listOf("La ", "respuesta ", "final.")))
        val asistente = sinRama().also { it.motor = falso }
        val oyente = OyenteDePrueba()
        val r = asistente.responder("contame algo lindo", emptyList(), null, AjustesRama(NivelPensar.NORMAL, Estilos.CHARLA, false), oyente)
        verificar("Normal: corta el razonamiento en el presupuesto", r.tokensPensados == NivelPensar.NORMAL.presupuesto) { "tokens=${r.tokensPensados}" }
        verificar("Normal: continúa con un cierre de </think>", falso.prompts.size == 2 && falso.prompts[1].startsWith(falso.prompts[0]) && falso.prompts[1].endsWith("</think>\n\n")) {
            falso.prompts.map { it.takeLast(80) }.toString()
        }
        verificar("Normal: el primer prompt abre <think>", falso.prompts[0].endsWith("<|im_start|>assistant\n<think>\n")) { falso.prompts[0].takeLast(60) }
        verificar("Normal: respuesta y pensamiento separados", r.texto == "La respuesta final." && r.pensamiento.startsWith("pienso") && oyente.dicho.toString() == "La respuesta final.") {
            "texto=[${r.texto}] dicho=[${oyente.dicho}]"
        }
        verificar("Normal: pide pensar en el mensaje", falso.prompts[0].contains(Identidad.pedidoDelNivel(NivelPensar.NORMAL)))
    }
    run {
        val falso = ModeloFalso(listOf(listOf("Hola", "!")))
        val asistente = sinRama().also { it.motor = falso }
        val r = asistente.responder("hola", emptyList(), null, AjustesRama(NivelPensar.BAJO, Estilos.CHARLA, false), OyenteDePrueba())
        verificar("Bajo: no piensa", falso.prompts.size == 1 && falso.prompts[0].endsWith("<think>\n\n</think>\n\n") && r.texto == "Hola!" && r.pensamiento.isEmpty()) {
            falso.prompts.map { it.takeLast(40) }.toString() + " " + r.texto
        }
        verificar("Bajo: temperatura del estilo", falso.temperaturas[0] == Estilos.CHARLA.temperatura)
    }
    run {
        val falso = ModeloFalso(listOf(listOf("Mmm, ", "veamos.", "</think>", "\n\n", "Listo.")))
        val asistente = sinRama().also { it.motor = falso }
        val r = asistente.responder("pregunta", emptyList(), null, AjustesRama(NivelPensar.MAX, Estilos.CHARLA, false), OyenteDePrueba())
        verificar("Max: si cierra solo no hay segunda llamada", falso.prompts.size == 1 && r.texto == "Listo." && r.pensamiento == "Mmm, veamos.") {
            "${falso.prompts.size} [${r.texto}] [${r.pensamiento}]"
        }
        verificar("Max: temperatura de razonamiento", falso.temperaturas[0] == 0.6f)
    }
    run {
        // Contexto chico: el presupuesto de Max tiene que achicarse para entrar.
        val falso = ModeloFalso(listOf(listOf("ok</think>", "Bien.")), contexto = 2048)
        val asistente = sinRama().also { it.motor = falso }
        val historial = (1..20).flatMap { listOf(Mensaje("user", "pregunta larga ".repeat(40)), Mensaje("assistant", "respuesta larga ".repeat(40))) }
        asistente.responder("¿y entonces?", historial, null, AjustesRama(NivelPensar.MAX, Estilos.CHARLA, false), OyenteDePrueba())
        val usados = Asistente.tokens(falso.prompts[0]) + falso.maxTokens[0]
        verificar("Contexto: prompt + pensar + respuesta entran", usados <= 2048 + 64) { "usados=$usados max=${falso.maxTokens[0]}" }
    }
    run {
        val falso = ModeloFalso(listOf(List(50) { "x " }))
        val asistente = sinRama().also { it.motor = falso }
        val oyente = OyenteDePrueba(cortarDespuesDe = 5)
        val r = asistente.responder("algo", emptyList(), null, AjustesRama(NivelPensar.NORMAL, Estilos.CHARLA, false), oyente)
        verificar("Detener corta sin segunda llamada", r.detenida && falso.prompts.size == 1) { "detenida=${r.detenida} llamadas=${falso.prompts.size}" }
    }
    run {
        val asistente = sinRama()
        val r = asistente.responder("hola", emptyList(), null, AjustesRama(NivelPensar.NORMAL, Estilos.CHARLA, false), OyenteDePrueba())
        verificar("Sin modelo explica cómo bajarlo", !r.usoModelo && r.texto.contains("Modelo")) { r.texto }
    }

    // --- Catálogo: la edición más potente que entra en la memoria
    verificar("4 GB → Liviana", ar.rama.ai.motor.Catalogo.recomendada(4) == ar.rama.ai.motor.Catalogo.LIVIANA)
    verificar("6 GB → Completa", ar.rama.ai.motor.Catalogo.recomendada(6) == ar.rama.ai.motor.Catalogo.COMPLETA)
    verificar("8 GB → Completa", ar.rama.ai.motor.Catalogo.recomendada(8) == ar.rama.ai.motor.Catalogo.COMPLETA)
    verificar("12 GB → Ultra", ar.rama.ai.motor.Catalogo.recomendada(12) == ar.rama.ai.motor.Catalogo.ULTRA)
    verificar("RAM desconocida → Liviana", ar.rama.ai.motor.Catalogo.recomendada(0) == ar.rama.ai.motor.Catalogo.LIVIANA)
    verificar("Archivos distintos por edición", ar.rama.ai.motor.Catalogo.EDICIONES.map { it.archivoLocal }.toSet().size == 3)

    // --- Memoria de Rama Ultra
    run {
        val gguf = java.io.File.createTempFile("rama", ".gguf")
        escribirGgufDePrueba(gguf)
        val info = ar.rama.ai.motor.Gguf.leer(gguf)
        verificar("GGUF: lee la arquitectura", info != null && info.arquitectura == "qwen3" && info.capas == 36 && info.cabezasKv == 8) { info.toString() }
        verificar("GGUF: caché por token (36 × 8 × 256)", info?.elementosKvPorToken == 73_728L) { info.toString() }
        verificar("GGUF: cuantización IQ4_XS", info?.cuantizacion == "IQ4_XS" && info.contextoEntrenado == 40960) { info.toString() }
        gguf.delete()
        val falso = java.io.File.createTempFile("rama", ".gguf").apply { writeText("no soy un gguf") }
        verificar("GGUF: rechaza lo que no es GGUF", ar.rama.ai.motor.Gguf.leer(falso) == null)
        falso.delete()
    }
    run {
        val ultra = ar.rama.ai.motor.Catalogo.ULTRA
        val P = ar.rama.ai.motor.PlanDeMemoria
        val holgado = P.calcular(ultra.bytesAproximados, ultra.elementosKvPorToken, 8192, 7_000_000_000L)
        verificar("Plan: con 7 GB libres usa el tope de 8192", holgado.contexto == 8192 && holgado.alcanza) { holgado.toString() }
        val justo = P.calcular(ultra.bytesAproximados, ultra.elementosKvPorToken, 8192, 5_500_000_000L)
        verificar("Plan: con 5,5 GB libres baja al mínimo pero carga", justo.contexto == 2048 && justo.alcanza) { justo.toString() }
        val medio = P.calcular(ultra.bytesAproximados, ultra.elementosKvPorToken, 8192, 5_800_000_000L)
        verificar("Plan: con 5,8 GB libres ajusta el contexto en el medio", medio.contexto in 4096..7168 && medio.alcanza) { medio.toString() }
        verificar("Plan: el total entra en lo libre", medio.total + P.MARGEN <= 5_800_000_000L) { medio.toString() }
        val corto = P.calcular(ultra.bytesAproximados, ultra.elementosKvPorToken, 8192, 4_800_000_000L)
        verificar("Plan: con 4,8 GB libres no la carga", !corto.alcanza) { corto.toString() }
        val tipica = ultra.memoriaTipica.total
        verificar("Ultra ocupa ~5,2 GB con 4096 tokens", tipica in 5_000_000_000L..5_400_000_000L) { tipica.toString() }
        verificar("Ultra baja de Q4_K_M (5,0 GB) a IQ4_XS (4,6 GB)", ultra.cuantizacion == "IQ4_XS" && ultra.bytesAproximados < 4_700_000_000L)
    }
    run {
        val D = ar.rama.ai.motor.Descargador
        val archivos = listOf("Qwen3-8B-Q4_K_M.gguf", "Qwen3-8B-UD-Q4_K_XL.gguf", "Qwen3-8B-IQ4_XS.gguf", "Qwen3-8B-Q8_0.gguf")
        verificar("Descarga: prefiere IQ4_XS para Ultra", D.elegirArchivo(archivos, "IQ4_XS") == "Qwen3-8B-IQ4_XS.gguf")
        verificar("Descarga: si no hay IQ4_XS, Q4_K_M", D.elegirArchivo(archivos - "Qwen3-8B-IQ4_XS.gguf", "IQ4_XS") == "Qwen3-8B-Q4_K_M.gguf")
    }

    println()
    println("$pruebas pruebas, $fallas fallas")
    if (fallas > 0) System.exit(1)
}

/** Escribe un GGUF v3 sin tensores, con la cabecera de un Qwen3-8B en IQ4_XS. */
private fun escribirGgufDePrueba(archivo: java.io.File) {
    val b = java.nio.ByteBuffer.allocate(4096).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    fun texto(s: String) {
        val bytes = s.toByteArray()
        b.putLong(bytes.size.toLong())
        b.put(bytes)
    }
    fun clave(k: String, tipo: Int) {
        texto(k)
        b.putInt(tipo)
    }
    val enteros = listOf(
        "qwen3.block_count" to 36, "qwen3.embedding_length" to 4096, "qwen3.attention.head_count" to 32,
        "qwen3.attention.head_count_kv" to 8, "qwen3.attention.key_length" to 128,
        "qwen3.attention.value_length" to 128, "qwen3.context_length" to 40960, "general.file_type" to 30,
    )
    b.put("GGUF".toByteArray())
    b.putInt(3)
    b.putLong(0)
    b.putLong((enteros.size + 5).toLong())
    clave("general.architecture", 8); texto("qwen3")
    clave("general.name", 8); texto("Qwen3 8B")
    clave("tokenizer.ggml.tokens", 9); b.putInt(8); b.putLong(3); texto("<|im_start|>"); texto("hola"); texto("ñ")
    clave("tokenizer.ggml.scores", 9); b.putInt(6); b.putLong(3); b.putFloat(0f); b.putFloat(1f); b.putFloat(2f)
    clave("tokenizer.chat_template", 8); texto("{% for m in messages %}{{ m.content }}{% endfor %}")
    for ((k, v) in enteros) {
        clave(k, 4)
        b.putInt(v)
    }
    archivo.writeBytes(b.array().copyOf(b.position()))
}

/** Un modelo que devuelve fragmentos guionados, uno por llamada, y anota lo que le pidieron. */
private class ModeloFalso(
    private val guiones: List<List<String>>,
    override val contexto: Int = 8192,
) : ar.rama.ai.motor.ModeloDeLenguaje {
    val prompts = ArrayList<String>()
    val temperaturas = ArrayList<Float>()
    val maxTokens = ArrayList<Int>()
    override val nombre = "Falso"
    override val info = "modelo de prueba"
    override val esChatML = true
    override fun formatearNativo(mensajes: List<Mensaje>) = ""
    override fun cancelar() {}
    override fun generar(prompt: String, maxTokens: Int, temperatura: Float, topP: Float, topK: Int, alFragmento: (String) -> Boolean): Int {
        prompts.add(prompt)
        temperaturas.add(temperatura)
        this.maxTokens.add(maxTokens)
        val guion = guiones.getOrElse(prompts.size - 1) { emptyList() }
        var n = 0
        for (f in guion) {
            if (n >= maxTokens) break
            n++
            if (!alFragmento(f)) break
        }
        return n
    }
}

private class OyenteDePrueba(private val cortarDespuesDe: Int = Int.MAX_VALUE) : ar.rama.ai.motor.OyenteRama {
    val dicho = StringBuilder()
    private var fragmentos = 0
    override fun paso(paso: ar.rama.ai.motor.PasoRama) {}
    override fun pensamiento(fragmento: String) { fragmentos++ }
    override fun texto(fragmento: String) { dicho.append(fragmento); fragmentos++ }
    override fun seguir() = fragmentos < cortarDespuesDe
}

/** Un Asistente sin base de conocimiento: sólo para probar sus decisiones. */
private fun sinRama(): Asistente {
    val json = """{"intenciones": [], "paises": {}}"""
    return Asistente(ar.rama.ai.motor.Rama(json, ar.rama.ai.motor.Memoria(), null, null))
}
