package ar.rama.ai.motor

/** Lo que el Asistente necesita de un modelo de lenguaje. [MotorRama] es el real. */
interface ModeloDeLenguaje {
    val nombre: String
    val info: String
    /** Tokens de contexto con los que se abrió. */
    val contexto: Int
    /** Habla ChatML (Qwen): sabe razonar dentro de <think> y apagarlo. */
    val esChatML: Boolean

    /**
     * Abierto en modo ahorro de RAM (parte de los pesos se lee del almacenamiento):
     * cada token cuesta más, así que conviene pensar y escribir menos.
     */
    val enAhorro: Boolean

    /** Prompt armado con la plantilla propia del modelo. */
    fun formatearNativo(mensajes: List<Mensaje>): String

    /** Genera a partir del prompt; [alFragmento] devuelve false para cortar. */
    fun generar(
        prompt: String,
        maxTokens: Int,
        temperatura: Float,
        topP: Float,
        topK: Int,
        alFragmento: (String) -> Boolean,
    ): Int

    fun cancelar()
}
