package ar.rama.ai.motor

import java.io.File

/**
 * El modelo de lenguaje abierto en memoria. Envuelve a [Llama] (llama.cpp por
 * JNI) y agrega lo que necesita Rama: saber si el modelo habla ChatML (y por lo
 * tanto sabe razonar con <think>), cortar la generación cuando se le pide y
 * elegir un contexto acorde a la memoria del teléfono.
 *
 * El motor nativo guarda los tokens de la última llamada y, en la siguiente,
 * sólo procesa lo que cambió desde el prefijo común. Por eso conviene que el
 * mensaje de sistema no cambie entre turnos y que al cortar el razonamiento se
 * continúe exactamente desde el mismo texto.
 */
class MotorRama private constructor(
    private val handle: Long,
    val archivo: File,
    override val contexto: Int,
) : ModeloDeLenguaje {
    @Volatile private var cerrado = false
    @Volatile private var generandoAhora = false

    val edicion: Edicion? get() = Catalogo.deArchivo(archivo.name)

    override val nombre: String get() = edicion?.nombre ?: archivo.nameWithoutExtension

    override val info: String by lazy { if (cerrado) "" else Llama.nativeInfo(handle) }

    /**
     * true si el modelo usa ChatML (<|im_start|>…), el formato de Qwen: en ese
     * caso armamos nosotros el prompt y controlamos el bloque <think>.
     */
    override val esChatML: Boolean by lazy {
        if (edicion != null) return@lazy true
        val muestra = formatearNativo(listOf(Mensaje("system", "a"), Mensaje("user", "b")))
        muestra.isEmpty() || muestra.contains("<|im_start|>")
    }

    /** Arma el prompt con la plantilla que trae el propio archivo GGUF. */
    override fun formatearNativo(mensajes: List<Mensaje>): String {
        if (cerrado) return ""
        val roles = mensajes.map { it.rol }.toTypedArray()
        val contenidos = mensajes.map { it.contenido }.toTypedArray()
        return try {
            Llama.nativeFormatearChat(handle, roles, contenidos, true)
        } catch (e: Throwable) {
            ""
        }
    }

    /**
     * Genera a partir de [prompt]. [alFragmento] recibe cada pedacito de texto
     * y devuelve false para cortar. Devuelve lo que informe el motor nativo.
     */
    override fun generar(
        prompt: String,
        maxTokens: Int,
        temperatura: Float,
        topP: Float,
        topK: Int,
        alFragmento: (String) -> Boolean,
    ): Int {
        if (cerrado) return -1
        val semilla = System.nanoTime() and 0xffffffffL
        generandoAhora = true
        try {
            return Llama.nativeGenerar(
                handle, prompt, maxTokens, temperatura, topP, topK, semilla,
                object : Llama.Receptor {
                    override fun onToken(texto: String): Boolean = alFragmento(texto)
                },
            )
        } finally {
            generandoAhora = false
        }
    }

    /** Pide al motor nativo que corte lo que esté procesando (sólo si hay algo en curso). */
    override fun cancelar() {
        if (!cerrado && generandoAhora) {
            try {
                Llama.nativeCancelar(handle)
            } catch (e: Throwable) {
            }
        }
    }

    fun cerrar() {
        if (cerrado) return
        cerrado = true
        Llama.nativeCerrar(handle)
    }

    companion object {
        fun abrir(archivo: File, contexto: Int, hilos: Int = hilosRecomendados()): MotorRama? {
            if (!Llama.disponible || !archivo.exists()) return null
            val handle = Llama.nativeAbrir(archivo.absolutePath, contexto, hilos)
            return if (handle == 0L) null else MotorRama(handle, archivo, contexto)
        }

        /**
         * Tokens de contexto según la RAM total del teléfono y el peso del
         * archivo. Más contexto = más lugar para pensar y para las fuentes web,
         * pero también más memoria (la caché crece con cada token).
         */
        fun contextoPara(archivo: File, ramGb: Int): Int {
            val peso = archivo.length()
            val grande = peso > 1_600_000_000L
            val enorme = peso > 4_000_000_000L
            return when {
                ramGb >= 15 -> if (enorme) 12288 else 16384
                ramGb >= 11 -> 8192
                ramGb >= 7 -> if (enorme) 4096 else if (grande) 6144 else 8192
                ramGb >= 5 -> if (enorme) 2048 else if (grande) 4096 else 6144
                ramGb in 1..4 -> if (grande) 2048 else 4096
                else -> 4096
            }
        }

        fun hilosRecomendados(): Int = try {
            Nucleos.recomendados()
        } catch (e: Throwable) {
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        }
    }
}
