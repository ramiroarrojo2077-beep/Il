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
        256, 1.2f, 5, 0, 0,
    ),
    ALTO(
        "alto", "Alto",
        "Razona con cuidado y lee la mejor página que encuentra antes de responder.",
        768, 1.4f, 6, 1, 900,
    ),
    MAX(
        "max", "Max",
        "Razona a fondo, lee más fuentes y revisa su propia respuesta. Es el más lento.",
        2048, 1.8f, 8, 2, 1100,
    );

    val piensa: Boolean get() = presupuesto > 0

    companion object {
        /** Bajo: la respuesta empieza a salir enseguida. */
        val PREDETERMINADO = BAJO

        fun porId(id: String?): NivelPensar = entries.firstOrNull { it.id == id } ?: PREDETERMINADO
    }
}
