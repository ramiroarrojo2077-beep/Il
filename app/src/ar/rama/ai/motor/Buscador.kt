package ar.rama.ai.motor

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * La búsqueda web de Rama. No usa claves ni servicios pagos: consulta a la vez
 * varios buscadores públicos (DuckDuckGo, Bing, Mojeek) y se queda con el
 * primero que responda; suma Wikipedia para los datos, Google Noticias para la actualidad
 * y dos fuentes estructuradas (cotizaciones del dólar y clima). Además puede
 * abrir las páginas encontradas y quedarse con los párrafos que importan.
 */
class Buscador {

    /**
     * Resultados web generales. Los buscadores se consultan todos a la vez y
     * gana el primero que devuelve algo: no hay que esperar a que uno falle
     * para probar el siguiente.
     */
    fun web(consulta: String, maximo: Int, segundos: Long = 8): List<Resultado> {
        val tareas = listOf<Callable<List<Resultado>>>(
            Callable { duckDuckGo(consulta) },
            Callable { duckDuckGoLite(consulta) },
            Callable { bing(consulta) },
            Callable { mojeek(consulta) },
        )
        val servicio = ExecutorCompletionService<List<Resultado>>(HILOS)
        val futuros = tareas.map { servicio.submit(it) }
        val limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(segundos)
        try {
            repeat(tareas.size) {
                val restante = limite - System.nanoTime()
                if (restante <= 0) return emptyList()
                val listo = servicio.poll(restante, TimeUnit.NANOSECONDS) ?: return emptyList()
                val limpios = depurar(
                    try {
                        listo.get()
                    } catch (e: Exception) {
                        emptyList()
                    },
                )
                if (limpios.isNotEmpty()) return limpios.take(maximo)
            }
            return emptyList()
        } finally {
            futuros.forEach { it.cancel(true) }
        }
    }

    /** Corre una consulta en paralelo (herramientas, Wikipedia, noticias…). */
    fun <T> lanzar(tarea: () -> T): Future<T> = HILOS.submit(Callable { tarea() })

    // ------------------------------------------------------------ buscadores

    private fun duckDuckGo(consulta: String): List<Resultado> {
        val html = Red.post("https://html.duckduckgo.com/html/", mapOf("q" to consulta, "kl" to "ar-es"), tiempo = TIEMPO)
            ?: Red.get("https://html.duckduckgo.com/html/?q=" + Red.codificar(consulta) + "&kl=ar-es", tiempo = TIEMPO)
            ?: return emptyList()
        return parsearDuckDuckGo(html)
    }

    private fun duckDuckGoLite(consulta: String): List<Resultado> {
        val html = Red.get("https://lite.duckduckgo.com/lite/?q=" + Red.codificar(consulta) + "&kl=ar-es", tiempo = TIEMPO)
            ?: return emptyList()
        return parsearDuckDuckGoLite(html)
    }

    private fun bing(consulta: String): List<Resultado> {
        val html = Red.get("https://www.bing.com/search?q=" + Red.codificar(consulta) + "&setlang=es&cc=AR", tiempo = TIEMPO)
            ?: return emptyList()
        return parsearBing(html)
    }

    private fun mojeek(consulta: String): List<Resultado> {
        val html = Red.get("https://www.mojeek.com/search?q=" + Red.codificar(consulta) + "&lb=es", tiempo = TIEMPO)
            ?: return emptyList()
        return parsearMojeek(html)
    }

    // ------------------------------------------------------------ Wikipedia

    /** El resumen del artículo de Wikipedia en español que mejor coincide. */
    fun wikipedia(consulta: String, oraciones: Int = 6): Resultado? {
        val url = "https://es.wikipedia.org/w/api.php?action=query&format=json&generator=search" +
            "&gsrsearch=" + Red.codificar(consulta) + "&gsrlimit=1&prop=extracts%7Cinfo&exintro=1" +
            "&explaintext=1&exsentences=" + oraciones + "&inprop=url&redirects=1&utf8=1"
        val json = Red.get(url, tiempo = TIEMPO, cabeceras = mapOf("User-Agent" to "RamaAI/4.1 (app Android; busqueda)")) ?: return null
        return try {
            val paginas = JSONObject(json).optJSONObject("query")?.optJSONObject("pages") ?: return null
            val claves = paginas.keys()
            if (!claves.hasNext()) return null
            val pagina = paginas.getJSONObject(claves.next())
            val extracto = pagina.optString("extract").trim()
            if (extracto.length < 40) return null
            Resultado(
                pagina.optString("title", "Wikipedia") + " — Wikipedia",
                pagina.optString("fullurl", "https://es.wikipedia.org"),
                extracto,
            )
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ Noticias

    /** Titulares recientes de Google Noticias (edición Argentina). */
    fun noticias(consulta: String, maximo: Int): List<Resultado> {
        val url = "https://news.google.com/rss/search?q=" + Red.codificar(consulta) + "&hl=es-419&gl=AR&ceid=AR:es-419"
        val xml = Red.get(url, tiempo = TIEMPO) ?: return emptyList()
        return parsearNoticias(xml).take(maximo)
    }

    // ------------------------------------------------------------ Datos estructurados

    /** Cotizaciones del dólar en Argentina (oficial, blue, MEP, CCL…). */
    fun dolar(): Resultado? {
        val json = Red.get("https://dolarapi.com/v1/dolares", tiempo = 8000) ?: return null
        return try {
            val lista = JSONArray(json)
            val texto = StringBuilder()
            var actualizado = ""
            for (i in 0 until lista.length()) {
                val casa = lista.getJSONObject(i)
                val nombre = casa.optString("nombre", casa.optString("casa"))
                val compra = casa.optDouble("compra", Double.NaN)
                val venta = casa.optDouble("venta", Double.NaN)
                if (venta.isNaN()) continue
                texto.append("Dólar ").append(nombre).append(": ")
                if (!compra.isNaN()) texto.append("compra $").append(pesos(compra)).append(" / ")
                texto.append("venta $").append(pesos(venta)).append(". ")
                if (actualizado.isEmpty()) actualizado = casa.optString("fechaActualizacion")
            }
            if (texto.isEmpty()) return null
            if (actualizado.isNotEmpty()) texto.append("Actualizado: ").append(fechaLegible(actualizado)).append('.')
            Resultado("Cotizaciones del dólar hoy — DolarApi", "https://dolarapi.com", texto.toString().trim())
        } catch (e: Exception) {
            null
        }
    }

    /** Clima actual y pronóstico corto de un lugar, con Open-Meteo. */
    fun clima(lugar: String): Resultado? {
        return try {
            val geo = Red.get(
                "https://geocoding-api.open-meteo.com/v1/search?name=" + Red.codificar(lugar) + "&count=1&language=es&format=json",
                tiempo = 8000,
            ) ?: return null
            val sitio = JSONObject(geo).optJSONArray("results")?.optJSONObject(0) ?: return null
            val lat = sitio.getDouble("latitude")
            val lon = sitio.getDouble("longitude")
            val nombre = listOf(sitio.optString("name"), sitio.optString("admin1"), sitio.optString("country"))
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            val pronostico = Red.get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=3",
                tiempo = 8000,
            ) ?: return null
            val raiz = JSONObject(pronostico)
            val actual = raiz.getJSONObject("current")
            val texto = StringBuilder()
            texto.append("Ahora en ").append(nombre).append(": ")
                .append(Math.round(actual.getDouble("temperature_2m"))).append(" °C")
                .append(" (sensación ").append(Math.round(actual.optDouble("apparent_temperature", actual.getDouble("temperature_2m")))).append(" °C), ")
                .append(describirClima(actual.optInt("weather_code"))).append(", humedad ")
                .append(actual.optInt("relative_humidity_2m")).append("%, viento ")
                .append(Math.round(actual.optDouble("wind_speed_10m", 0.0))).append(" km/h. ")
            val diario = raiz.optJSONObject("daily")
            if (diario != null) {
                val dias = diario.getJSONArray("time")
                val nombresDia = listOf("Hoy", "Mañana", "Pasado mañana")
                for (i in 0 until minOf(3, dias.length())) {
                    texto.append(nombresDia[i]).append(": ")
                        .append(describirClima(diario.getJSONArray("weather_code").optInt(i))).append(", entre ")
                        .append(Math.round(diario.getJSONArray("temperature_2m_min").optDouble(i))).append(" y ")
                        .append(Math.round(diario.getJSONArray("temperature_2m_max").optDouble(i))).append(" °C")
                    val lluvia = diario.optJSONArray("precipitation_probability_max")?.optInt(i, -1) ?: -1
                    if (lluvia >= 0) texto.append(", ").append(lluvia).append("% de probabilidad de lluvia")
                    texto.append(". ")
                }
            }
            Resultado("Clima en $nombre — Open-Meteo", "https://open-meteo.com", texto.toString().trim())
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ Lectura de páginas

    /**
     * Abre varias páginas en paralelo y devuelve, para cada una, los párrafos
     * más relacionados con la consulta (hasta [caracteres] por página).
     */
    fun leer(resultados: List<Resultado>, consulta: String, caracteres: Int): Map<String, String> {
        if (resultados.isEmpty() || caracteres <= 0) return emptyMap()
        val hilos = Executors.newFixedThreadPool(minOf(3, resultados.size))
        return try {
            val tareas = resultados.map { r -> Callable { r.url to (leerPagina(r.url, consulta, caracteres) ?: "") } }
            val futuros = hilos.invokeAll(tareas, 9, TimeUnit.SECONDS)
            val salida = LinkedHashMap<String, String>()
            for (f in futuros) {
                if (f.isCancelled) continue
                val (url, texto) = try {
                    f.get()
                } catch (e: Exception) {
                    continue
                }
                if (texto.isNotBlank()) salida[url] = texto
            }
            salida
        } catch (e: Exception) {
            emptyMap()
        } finally {
            hilos.shutdownNow()
        }
    }

    fun leerPagina(url: String, consulta: String, caracteres: Int): String? {
        if (!url.startsWith("http")) return null
        if (url.endsWith(".pdf", ignoreCase = true)) return null
        val html = Red.get(url, maxBytes = 600_000, tiempo = 7000) ?: return null
        return extraerRelevante(html, consulta, caracteres)
    }

    companion object {
        /** Tiempo máximo de cada pedido a un buscador (milisegundos). */
        const val TIEMPO = 6000

        /** Hilos para pedidos de red en paralelo; no frenan el cierre de la app. */
        val HILOS: ExecutorService = Executors.newCachedThreadPool { tarea ->
            Thread(tarea, "rama-red").apply { isDaemon = true }
        }

        private val OPCIONES = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        private val ENLACE = Regex("<a\\b([^>]*)>(.*?)</a>", OPCIONES)
        private val PARRAFO = Regex("<(p|li|h[1-4]|blockquote|dd|td)\\b[^>]*>(.*?)</\\1\\s*>", OPCIONES)
        private val ITEM_RSS = Regex("<item>(.*?)</item>", OPCIONES)
        private val CDATA = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
        private val PALABRA = Regex("[\\p{L}\\p{N}]+")

        private val VACIAS = setOf(
            "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "al", "a", "en", "y", "o", "que",
            "es", "son", "por", "para", "con", "sin", "se", "su", "sus", "lo", "le", "les", "me", "mi", "te", "tu",
            "como", "cual", "cuales", "quien", "quienes", "cuando", "donde", "cuanto", "cuanta", "cuantos", "cuantas",
            "este", "esta", "esto", "ese", "esa", "eso", "hay", "fue", "era", "ser", "mas", "muy", "ya", "sobre",
            "the", "of", "and", "to", "in", "is", "what", "who", "how",
        )

        fun parsearDuckDuckGo(html: String): List<Resultado> {
            val salida = ArrayList<Resultado>()
            val bloques = html.split(Regex("class=\"result results_links|class=\"result result--|<div class=\"result "))
            for (bloque in bloques.drop(1)) {
                if (bloque.contains("result--ad") || bloque.contains("badge--ad")) continue
                var titulo: String? = null
                var url: String? = null
                var resumen = ""
                for (m in ENLACE.findAll(bloque)) {
                    val atributos = m.groupValues[1]
                    val clase = Html.atributo(atributos, "class") ?: ""
                    if (titulo == null && clase.contains("result__a")) {
                        titulo = Html.texto(m.groupValues[2])
                        url = Html.atributo(atributos, "href")?.let { desenvolver(it) }
                    } else if (clase.contains("result__snippet")) {
                        resumen = Html.texto(m.groupValues[2])
                    }
                }
                if (resumen.isEmpty()) {
                    Regex("class=\"result__snippet\"[^>]*>(.*?)</(div|td|span)>", OPCIONES).find(bloque)?.let {
                        resumen = Html.texto(it.groupValues[1])
                    }
                }
                if (!titulo.isNullOrBlank() && url != null) salida.add(Resultado(titulo, url, resumen))
            }
            return salida
        }

        fun parsearDuckDuckGoLite(html: String): List<Resultado> {
            val salida = ArrayList<Resultado>()
            val partes = html.split(Regex("class=['\"]result-link['\"]"))
            // El enlace aparece en la parte anterior al corte (el atributo href va antes de class).
            for (i in 1 until partes.size) {
                val antes = partes[i - 1]
                val inicioEtiqueta = antes.lastIndexOf("<a")
                if (inicioEtiqueta < 0) continue
                val etiqueta = antes.substring(inicioEtiqueta)
                val href = Html.atributo(etiqueta, "href") ?: continue
                val despues = partes[i]
                val titulo = Html.texto(despues.substringAfter('>').substringBefore("</a>"))
                val resumen = Regex("class=['\"]result-snippet['\"][^>]*>(.*?)</td>", OPCIONES).find(despues)
                    ?.let { Html.texto(it.groupValues[1]) } ?: ""
                if (titulo.isNotBlank()) salida.add(Resultado(titulo, desenvolver(href), resumen))
            }
            return salida
        }

        fun parsearBing(html: String): List<Resultado> {
            val salida = ArrayList<Resultado>()
            for (bloque in html.split("class=\"b_algo\"").drop(1)) {
                val h2 = Regex("<h2[^>]*>(.*?)</h2>", OPCIONES).find(bloque) ?: continue
                val enlace = ENLACE.find(h2.groupValues[1]) ?: continue
                val href = Html.atributo(enlace.groupValues[1], "href") ?: continue
                val titulo = Html.texto(enlace.groupValues[2])
                val resumen = Regex("<p\\b[^>]*>(.*?)</p>", OPCIONES).find(bloque)?.let { Html.texto(it.groupValues[1]) } ?: ""
                if (titulo.isNotBlank()) salida.add(Resultado(titulo, desenvolver(href), resumen.removePrefix("Web ")))
            }
            return salida
        }

        fun parsearMojeek(html: String): List<Resultado> {
            val salida = ArrayList<Resultado>()
            for (bloque in html.split(Regex("<li\\b[^>]*>")).drop(1)) {
                val enlace = ENLACE.findAll(bloque).firstOrNull {
                    (Html.atributo(it.groupValues[1], "class") ?: "").split(' ').contains("title")
                } ?: continue
                val href = Html.atributo(enlace.groupValues[1], "href") ?: continue
                val titulo = Html.texto(enlace.groupValues[2])
                val resumen = Regex("<p class=\"s\"[^>]*>(.*?)</p>", OPCIONES).find(bloque)?.let { Html.texto(it.groupValues[1]) } ?: ""
                if (titulo.isNotBlank()) salida.add(Resultado(titulo, href, resumen))
            }
            return salida
        }

        fun parsearNoticias(xml: String): List<Resultado> {
            val salida = ArrayList<Resultado>()
            for (item in ITEM_RSS.findAll(xml)) {
                val cuerpo = item.groupValues[1]
                fun campo(nombre: String): String {
                    val crudo = Regex("<$nombre\\b[^>]*>(.*?)</$nombre>", OPCIONES).find(cuerpo)?.groupValues?.get(1) ?: return ""
                    val sinCdata = CDATA.replace(crudo) { it.groupValues[1] }
                    return Html.texto(sinCdata)
                }
                val titulo = campo("title")
                val enlace = campo("link")
                if (titulo.isBlank() || enlace.isBlank()) continue
                val medio = campo("source")
                val fecha = campo("pubDate")
                val resumen = listOf(medio, fechaRss(fecha)).filter { it.isNotBlank() }.joinToString(" · ")
                salida.add(Resultado(titulo, enlace, resumen))
            }
            return salida
        }

        /** Los enlaces de DuckDuckGo y Bing pasan por un redireccionador: saca la URL real. */
        fun desenvolver(crudo: String): String {
            val enlace = Html.entidades(crudo).let { if (it.startsWith("//")) "https:$it" else it }
            val uddg = Regex("[?&]uddg=([^&]+)").find(enlace)
            if (uddg != null) {
                return try {
                    URLDecoder.decode(uddg.groupValues[1], "UTF-8")
                } catch (e: Exception) {
                    enlace
                }
            }
            if (enlace.contains("bing.com/ck/")) {
                val u = Regex("[?&]u=a1([^&]+)").find(enlace)?.groupValues?.get(1)
                if (u != null) {
                    try {
                        val base = u.replace('-', '+').replace('_', '/')
                        val relleno = base + "=".repeat((4 - base.length % 4) % 4)
                        val bytes = java.util.Base64.getDecoder().decode(relleno)
                        return String(bytes, Charsets.UTF_8)
                    } catch (e: Exception) {
                    }
                }
            }
            return enlace
        }

        /** Saca publicidad, duplicados y enlaces internos de los buscadores. */
        fun depurar(resultados: List<Resultado>): List<Resultado> {
            val vistos = HashSet<String>()
            return resultados.filter { r ->
                val url = r.url
                val interno = url.contains("duckduckgo.com/y.js") || url.contains("bing.com/aclick") ||
                    url.contains("duckduckgo.com/?") || !url.startsWith("http")
                !interno && vistos.add(url.substringBefore('#').trimEnd('/'))
            }
        }

        fun palabrasClave(consulta: String): Set<String> {
            val plano = Texto.quitarAcentos(consulta.lowercase(Locale.ROOT))
            return PALABRA.findAll(plano).map { it.value }.filter { it.length > 2 && it !in VACIAS }.toSet()
        }

        /**
         * De una página entera, se queda con los párrafos que más palabras de la
         * consulta tienen, respetando su orden original.
         */
        fun extraerRelevante(html: String, consulta: String, caracteres: Int): String? {
            val cuerpo = Html.sinRuido(html)
            var parrafos = PARRAFO.findAll(cuerpo).map { Html.texto(it.groupValues[2]) }
                .filter { it.length >= 50 }.distinct().toList()
            if (parrafos.isEmpty()) {
                val todo = Html.texto(cuerpo)
                if (todo.length < 80) return null
                parrafos = todo.split(Regex("(?<=[.!?])\\s+")).chunked(3).map { it.joinToString(" ") }
            }
            val claves = palabrasClave(consulta)
            val puntuados = parrafos.mapIndexed { i, p ->
                val plano = Texto.quitarAcentos(p.lowercase(Locale.ROOT))
                val aciertos = claves.count { plano.contains(it) }
                val cifras = if (p.any { it.isDigit() }) 0.3 else 0.0
                Triple(i, p, aciertos + cifras - i * 0.002)
            }
            val elegidos = puntuados.sortedByDescending { it.third }
            val salida = ArrayList<Pair<Int, String>>()
            var usados = 0
            for ((i, p, puntaje) in elegidos) {
                if (usados >= caracteres) break
                if (claves.isNotEmpty() && puntaje < 0.9 && salida.isNotEmpty()) break
                val recorte = if (p.length > caracteres - usados) p.take(maxOf(0, caracteres - usados)).substringBeforeLast(' ') + "…" else p
                if (recorte.length < 30) continue
                salida.add(i to recorte)
                usados += recorte.length
            }
            if (salida.isEmpty()) return null
            return salida.sortedBy { it.first }.joinToString(" ") { it.second }
        }

        fun describirClima(codigo: Int): String = when (codigo) {
            0 -> "despejado"
            1 -> "mayormente despejado"
            2 -> "parcialmente nublado"
            3 -> "nublado"
            45, 48 -> "con niebla"
            51, 53, 55 -> "con llovizna"
            56, 57 -> "con llovizna helada"
            61, 63 -> "con lluvia"
            65 -> "con lluvia fuerte"
            66, 67 -> "con lluvia helada"
            71, 73, 75, 77 -> "con nieve"
            80, 81 -> "con chaparrones"
            82 -> "con chaparrones fuertes"
            85, 86 -> "con nevadas"
            95 -> "con tormentas"
            96, 99 -> "con tormentas y granizo"
            else -> "variable"
        }

        private fun pesos(valor: Double): String =
            if (valor == Math.floor(valor)) String.format(Locale("es", "AR"), "%,.0f", valor)
            else String.format(Locale("es", "AR"), "%,.2f", valor)

        private fun fechaLegible(iso: String): String = try {
            val entrada = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
            entrada.timeZone = TimeZone.getTimeZone("UTC")
            val fecha = entrada.parse(iso.take(19))
            SimpleDateFormat("d/M HH:mm", Locale("es", "AR")).format(fecha!!)
        } catch (e: Exception) {
            iso
        }

        private fun fechaRss(texto: String): String = try {
            val entrada = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
            val fecha = entrada.parse(texto)
            SimpleDateFormat("d 'de' MMMM, HH:mm", Locale("es", "AR")).format(fecha!!)
        } catch (e: Exception) {
            texto
        }
    }
}
