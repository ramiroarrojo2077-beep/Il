package ar.rama.ai.motor

/** Un lugar de Hugging Face donde está publicado el archivo GGUF. */
data class Fuente(val repositorio: String, val archivo: String)

/**
 * Una forma de guardar los pesos de una edición. Menos bits por peso = menos
 * RAM y un poco menos de precisión; el modelo, la identidad y todo lo demás
 * son iguales.
 */
data class Variante(
    val id: String,
    val nombre: String,
    val descripcion: String,
    val cuantizacion: String,
    /** Si el repositorio no tiene la preferida, en este orden. */
    val alternativas: List<String>,
    val bytesAproximados: Long,
    val fuentes: List<Fuente>,
)

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
    /** Cuantización preferida, para elegir entre los archivos de un repositorio. */
    val cuantizacion: String,
    /** capas × cabezas KV × (dim. clave + dim. valor): lo que ocupa cada token de contexto. */
    val elementosKvPorToken: Long,
    /** Si no la tiene el repositorio, estas cuantizaciones en este orden. */
    val alternativas: List<String> = listOf("Q4_K_M"),
    /** Formas de guardar los pesos para elegir (vacío = una sola). */
    val variantes: List<Variante> = emptyList(),
) {
    /** La misma edición, con los pesos de otra variante. */
    fun con(variante: Variante): Edicion = copy(
        bytesAproximados = variante.bytesAproximados,
        fuentes = variante.fuentes,
        cuantizacion = variante.cuantizacion,
        alternativas = variante.alternativas,
    )

    val cuantizaciones: List<String> get() = (listOf(cuantizacion) + alternativas).distinct()

    /** Lo que ocupa en RAM con un contexto típico de 4096 tokens. */
    val memoriaTipica: PlanDeMemoria get() = PlanDeMemoria.estimar(bytesAproximados, elementosKvPorToken, 4096)

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
        cuantizacion = "Q4_K_M",
        elementosKvPorToken = 28L * 8 * (128 + 128),
    )

    val COMPLETA = Edicion(
        id = "rama-completa",
        nombre = "Rama Completa",
        parametros = "4 B",
        bytesAproximados = 2_497_280_256L,
        ramRecomendadaGb = 6,
        descripcion = "Más conocimiento y mucho mejor razonamiento: se equivoca bastante menos. Pide un teléfono con 6 GB de RAM o más.",
        fuentes = qwen3("4B", primeroOficial = true),
        cuantizacion = "Q4_K_M",
        elementosKvPorToken = 36L * 8 * (128 + 128),
    )

    /**
     * Ultra: IQ3_M (Compacta, 3,9 GB) en teléfonos de menos de 16 GB, o IQ4_XS
     * (Equilibrada, 4,6 GB). Q4_K_M ocuparía 5,0 GB. Los pesos se leen del
     * archivo mapeado en memoria, así que son páginas que Android puede soltar y
     * volver a leer si le hace falta.
     */
    val ULTRA_COMPACTA = Variante(
        id = "compacta",
        nombre = "Compacta",
        descripcion = "La que menos RAM usa. Pierde un poco de precisión, pero en general sigue rindiendo más que la Completa.",
        cuantizacion = "IQ3_M",
        alternativas = listOf("Q3_K_M", "IQ4_XS", "Q4_K_M"),
        bytesAproximados = 3_900_000_000L,
        fuentes = listOf(
            Fuente("bartowski/Qwen_Qwen3-8B-GGUF", "Qwen_Qwen3-8B-IQ3_M.gguf"),
            Fuente("unsloth/Qwen3-8B-GGUF", "Qwen3-8B-Q3_K_M.gguf"),
            Fuente("bartowski/Qwen_Qwen3-8B-GGUF", "Qwen_Qwen3-8B-Q3_K_M.gguf"),
            // Si no hay ninguna de 3 bits, la Equilibrada.
            Fuente("unsloth/Qwen3-8B-GGUF", "Qwen3-8B-IQ4_XS.gguf"),
        ),
    )

    val ULTRA_EQUILIBRADA = Variante(
        id = "equilibrada",
        nombre = "Equilibrada",
        descripcion = "Casi toda la precisión del modelo, con 700 MB más de RAM que la Compacta.",
        cuantizacion = "IQ4_XS",
        alternativas = listOf("Q4_K_S", "Q4_K_M"),
        bytesAproximados = 4_560_000_000L,
        fuentes = listOf(
            Fuente("unsloth/Qwen3-8B-GGUF", "Qwen3-8B-IQ4_XS.gguf"),
            Fuente("bartowski/Qwen_Qwen3-8B-GGUF", "Qwen_Qwen3-8B-IQ4_XS.gguf"),
            // Respaldo oficial si no aparece ninguna IQ4_XS: más pesado, misma calidad o mejor.
            Fuente("Qwen/Qwen3-8B-GGUF", "Qwen3-8B-Q4_K_M.gguf"),
            Fuente("ggml-org/Qwen3-8B-GGUF", "Qwen3-8B-Q4_K_M.gguf"),
        ),
    )

    val ULTRA = Edicion(
        id = "rama-ultra",
        nombre = "Rama Ultra",
        parametros = "8 B",
        bytesAproximados = ULTRA_COMPACTA.bytesAproximados,
        ramRecomendadaGb = 10,
        descripcion = "La más potente que puede correr un teléfono: el doble de parámetros que la Completa, más conocimiento y el mejor razonamiento. " +
            "Optimizada para la RAM: pesos de 3 o 4 bits y un contexto que se ajusta a la memoria libre. Es más lenta.",
        fuentes = ULTRA_COMPACTA.fuentes,
        cuantizacion = ULTRA_COMPACTA.cuantizacion,
        elementosKvPorToken = 36L * 8 * (128 + 128),
        alternativas = ULTRA_COMPACTA.alternativas,
        variantes = listOf(ULTRA_COMPACTA, ULTRA_EQUILIBRADA),
    )

    /** La variante que conviene: la Equilibrada sólo si sobra memoria (16 GB o más). */
    fun varianteRecomendada(edicion: Edicion, ramGb: Int): Variante? = when {
        edicion.variantes.isEmpty() -> null
        edicion.id == ULTRA.id -> if (ramGb >= 16) ULTRA_EQUILIBRADA else ULTRA_COMPACTA
        else -> edicion.variantes.first()
    }

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
