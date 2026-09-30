package ar.rama.ai.motor

/** Un lugar de Hugging Face donde está publicado el archivo GGUF. */
data class Fuente(val repositorio: String, val archivo: String)

/**
 * Una edición del modelo Rama. Es siempre el mismo modelo (misma identidad,
 * mismas herramientas, mismos niveles de pensamiento); lo que cambia es el
 * tamaño de la red que escribe, para que entre en la memoria del teléfono.
 */
data class Edicion(
    val id: String,
    val nombre: String,
    val parametros: String,
    val bytesAproximados: Long,
    /** RAM total del teléfono a partir de la cual la recomendamos. */
    val ramRecomendadaGb: Int,
    val descripcion: String,
    val fuentes: List<Fuente>,
) {
    val archivoLocal: String get() = "$id.gguf"
}

/**
 * Rama es el único modelo de la app. Está construido sobre los pesos abiertos
 * de Qwen3 (licencia Apache 2.0), que razonan dentro de <think> y permiten
 * apagar ese razonamiento: justo lo que necesitan los cuatro niveles de pensar.
 * Encima de esos pesos van la identidad de Rama, su búsqueda web, sus
 * habilidades exactas (cuentas, fechas, conversiones) y su base de conocimiento.
 */
object Catalogo {
    const val BASE = "Qwen3"
    const val LICENCIA = "Apache 2.0"

    val LIVIANA = Edicion(
        id = "rama-liviana",
        nombre = "Rama Liviana",
        parametros = "1.7 B",
        bytesAproximados = 1_107_409_472L,
        ramRecomendadaGb = 4,
        descripcion = "Rápida y liviana: anda bien en casi cualquier teléfono y razona sorprendentemente bien para su tamaño.",
        fuentes = qwen3("1.7B", primeroOficial = false),
    )

    val COMPLETA = Edicion(
        id = "rama-completa",
        nombre = "Rama Completa",
        parametros = "4 B",
        bytesAproximados = 2_497_280_256L,
        ramRecomendadaGb = 6,
        descripcion = "Más conocimiento y mucho mejor razonamiento: se equivoca bastante menos. Pide un teléfono con 6 GB de RAM o más.",
        fuentes = qwen3("4B", primeroOficial = true),
    )

    val ULTRA = Edicion(
        id = "rama-ultra",
        nombre = "Rama Ultra",
        parametros = "8 B",
        bytesAproximados = 5_027_783_488L,
        ramRecomendadaGb = 11,
        descripcion = "La más potente que puede correr un teléfono: el doble de parámetros que la Completa, más conocimiento y el mejor razonamiento. Pide 12 GB de RAM y es más lenta.",
        fuentes = qwen3("8B", primeroOficial = true),
    )

    val EDICIONES = listOf(LIVIANA, COMPLETA, ULTRA)

    /** La más potente que entra en la RAM total del teléfono (0 = no se sabe). */
    fun recomendada(ramGb: Int): Edicion = when {
        ramGb >= ULTRA.ramRecomendadaGb -> ULTRA
        ramGb >= COMPLETA.ramRecomendadaGb -> COMPLETA
        else -> LIVIANA
    }

    fun porId(id: String?): Edicion? = EDICIONES.firstOrNull { it.id == id }

    /** A qué edición corresponde un archivo, mirando su nombre. */
    fun deArchivo(nombre: String): Edicion? = EDICIONES.firstOrNull { it.archivoLocal == nombre }

    private fun qwen3(tamanio: String, primeroOficial: Boolean): List<Fuente> {
        val archivo = "Qwen3-$tamanio-Q4_K_M.gguf"
        val oficial = Fuente("Qwen/Qwen3-$tamanio-GGUF", archivo)
        val espejos = listOf(
            Fuente("unsloth/Qwen3-$tamanio-GGUF", archivo),
            Fuente("bartowski/Qwen_Qwen3-$tamanio-GGUF", "Qwen_Qwen3-$tamanio-Q4_K_M.gguf"),
            Fuente("ggml-org/Qwen3-$tamanio-GGUF", archivo),
        )
        return if (primeroOficial) listOf(oficial) + espejos else espejos + oficial
    }
}
