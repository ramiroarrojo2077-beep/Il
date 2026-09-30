package ar.rama.ai.motor

/** Cómo escribe Rama: tono, largo y cuánto se permite improvisar. */
data class Estilo(
    val id: String,
    val nombre: String,
    val descripcion: String,
    val instruccion: String,
    val temperatura: Float,
    val topP: Float,
    val topK: Int,
    val maxTokens: Int,
    val buscaEnWeb: Boolean,
)

object Estilos {
    val CHARLA = Estilo(
        "charla", "Charla",
        "Equilibrado. Contesta como en una conversación, sin dar vueltas.",
        "Contestá en tono de conversación, natural y claro. Dos o tres párrafos cortos como mucho, salvo que el tema pida más.",
        0.7f, 0.8f, 20, 420, true,
    )
    val PRECISO = Estilo(
        "preciso", "Preciso",
        "Va al dato, sin adornos. Ante la misma pregunta repite la misma respuesta.",
        "Contestá de forma factual y concreta. No adornes. Si no estás seguro de un dato, decí que no lo sabés en vez de aproximar.",
        0.2f, 0.8f, 5, 360, true,
    )
    val EXPLICAR = Estilo(
        "explicar", "Explicar",
        "Desarrolla con ejemplos y paso a paso. Para entender algo a fondo.",
        "Explicá con calma y en orden: primero la idea central en una frase, después el desarrollo con un ejemplo concreto. Usá lenguaje llano y listas cuando ayuden.",
        0.55f, 0.9f, 20, 800, true,
    )
    val CREATIVO = Estilo(
        "creativo", "Creativo",
        "Para escribir, imaginar y jugar. Se suelta más y no sale a buscar.",
        "Escribí con libertad e imaginación: historias, ideas, juegos, textos. En la ficción podés inventar, pero si te preguntan por un hecho real no lo inventes.",
        0.95f, 0.95f, 40, 800, false,
    )
    val AL_HUESO = Estilo(
        "al-hueso", "Al hueso",
        "Una o dos frases. Nada más.",
        "Contestá en una o dos oraciones como máximo. Sin introducción, sin cierre, sin repetir la pregunta.",
        0.4f, 0.8f, 20, 140, true,
    )

    val TODOS = listOf(CHARLA, PRECISO, EXPLICAR, CREATIVO, AL_HUESO)
    val PREDETERMINADO = CHARLA

    fun porId(id: String?): Estilo = TODOS.firstOrNull { it.id == id } ?: PREDETERMINADO
}
