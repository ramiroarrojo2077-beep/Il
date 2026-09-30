package ar.rama.ai.motor

/** Herramientas mínimas para sacar texto legible de HTML sin librerías. */
object Html {
    private val ETIQUETA = Regex("<[^>]+>")
    private val ESPACIOS = Regex("\\s+")
    private val ENTIDAD_NUMERICA = Regex("&#(x?)([0-9a-fA-F]+);")
    private val ENTIDAD_NOMBRADA = Regex("&([a-zA-Z]+);")
    private val BLOQUES_RUIDO = Regex(
        "<(script|style|noscript|svg|nav|header|footer|aside|form|iframe|template|button|select)\\b[^>]*>.*?</\\1\\s*>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val COMENTARIOS = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val ATRIBUTO_CACHE = HashMap<String, Regex>()

    private val ENTIDADES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "deg" to "°",
        "euro" to "€", "copy" to "©", "reg" to "®", "middot" to "·", "bull" to "•",
        "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
        "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í", "Oacute" to "Ó", "Uacute" to "Ú",
        "ntilde" to "ñ", "Ntilde" to "Ñ", "uuml" to "ü", "Uuml" to "Ü", "iquest" to "¿",
        "iexcl" to "¡", "ccedil" to "ç", "agrave" to "à", "egrave" to "è", "ograve" to "ò",
        "times" to "×", "divide" to "÷", "ordm" to "º", "ordf" to "ª", "sup2" to "²", "sup3" to "³",
    )

    /** Quita etiquetas, decodifica entidades y colapsa espacios. */
    fun texto(html: String): String {
        val sinEtiquetas = ETIQUETA.replace(html, " ")
        return ESPACIOS.replace(entidades(sinEtiquetas), " ").trim()
    }

    fun entidades(texto: String): String {
        val numericas = ENTIDAD_NUMERICA.replace(texto) { m ->
            val base = if (m.groupValues[1].isEmpty()) 10 else 16
            val codigo = m.groupValues[2].toIntOrNull(base)
            if (codigo == null || codigo !in 1..0x10FFFF) m.value else String(Character.toChars(codigo))
        }
        return ENTIDAD_NOMBRADA.replace(numericas) { m -> ENTIDADES[m.groupValues[1]] ?: m.value }
    }

    /** HTML sin scripts, estilos, menús ni comentarios: lo que queda es contenido. */
    fun sinRuido(html: String): String = BLOQUES_RUIDO.replace(COMENTARIOS.replace(html, " "), " ")

    /** Valor de un atributo dentro de una etiqueta de apertura, o null. */
    fun atributo(etiqueta: String, nombre: String): String? {
        val regex = synchronized(ATRIBUTO_CACHE) {
            ATRIBUTO_CACHE.getOrPut(nombre) {
                Regex("\\b" + nombre + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
            }
        }
        val m = regex.find(etiqueta) ?: return null
        return m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
    }

    /** Dominio legible de una URL: "es.wikipedia.org". */
    fun dominio(url: String): String =
        url.substringAfter("://", url).substringBefore('/').substringBefore('?').removePrefix("www.")
}
