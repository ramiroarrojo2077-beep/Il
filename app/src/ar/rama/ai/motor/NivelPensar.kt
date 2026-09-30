package ar.rama.ai.motor

/**
 * Cuánto razona Rama antes de contestar.
 *
 * El modelo piensa dentro de un bloque <think>…</think>. El presupuesto es la
 * cantidad máxima de tokens que le dejamos gastar ahí: si se pasa, cerramos el
 * bloque nosotros y le pedimos la respuesta. El nivel también decide cuánto
 * trabaja la búsqueda web (cuántos resultados trae y cuántas páginas abre).
 */
enum class NivelPensar(
    val id: String,
    val nombre: String,
    val descripcion: String,
    /** Tokens de razonamiento permitidos. 0 = contesta sin pensar. */
    val presupuesto: Int,
    /** Multiplica el largo máximo de la respuesta del estilo elegido. */
    val factorRespuesta: Float,
    /** Resultados web que pide al buscador. */
    val resultadosWeb: Int,
    /** Páginas que abre y lee enteras (además de los resúmenes). */
    val paginasALeer: Int,
    /** Caracteres que toma de cada página leída. */
    val caracteresPorPagina: Int,
) {
    BAJO(
        "bajo", "Bajo",
        "Responde al toque, sin razonar antes. Ideal para charlar o preguntas simples.",
        0, 1.0f, 4, 0, 0,
    ),
    NORMAL(
        "normal", "Normal",
        "Piensa un momento antes de contestar. El equilibrio entre velocidad y calidad.",
        400, 1.25f, 5, 1, 900,
    ),
    ALTO(
        "alto", "Alto",
        "Razona a fondo y lee las páginas que encuentra antes de responder.",
        1200, 1.5f, 6, 2, 1100,
    ),
    MAX(
        "max", "Max",
        "Todo el razonamiento que entra en memoria: lee más fuentes y revisa su propia respuesta.",
        4096, 2.0f, 8, 3, 1400,
    );

    val piensa: Boolean get() = presupuesto > 0

    companion object {
        val PREDETERMINADO = NORMAL

        fun porId(id: String?): NivelPensar = entries.firstOrNull { it.id == id } ?: PREDETERMINADO
    }
}
