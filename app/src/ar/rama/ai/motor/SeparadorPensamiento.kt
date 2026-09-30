package ar.rama.ai.motor

/**
 * Separa, a medida que llegan los fragmentos del modelo, lo que piensa (dentro
 * de <think>…</think>) de lo que responde. Las etiquetas pueden llegar partidas
 * entre dos fragmentos, así que retiene la cola que podría ser el comienzo de
 * una etiqueta hasta estar seguro.
 */
class SeparadorPensamiento(empiezaPensando: Boolean) {
    var pensando: Boolean = empiezaPensando
        private set

    /** El modelo emitió una marca de fin de turno: no hay que seguir. */
    var terminado: Boolean = false
        private set

    val pensamiento = StringBuilder()
    val respuesta = StringBuilder()
    private val pendiente = StringBuilder()

    fun procesar(fragmento: String, alPensar: (String) -> Unit, alResponder: (String) -> Unit) {
        if (terminado) return
        pendiente.append(fragmento)
        while (true) {
            val fin = primera(pendiente, MARCAS_FIN)
            val etiqueta = primera(pendiente, if (pensando) CIERRES else APERTURAS)
            if (fin != null && (etiqueta == null || fin.first <= etiqueta.first)) {
                emitir(pendiente.substring(0, fin.first), alPensar, alResponder)
                pendiente.setLength(0)
                terminado = true
                return
            }
            if (etiqueta == null) break
            emitir(pendiente.substring(0, etiqueta.first), alPensar, alResponder)
            pendiente.delete(0, etiqueta.first + etiqueta.second.length)
            pensando = !pensando
        }
        val seguro = pendiente.length - colaDudosa(pendiente)
        if (seguro > 0) {
            emitir(pendiente.substring(0, seguro), alPensar, alResponder)
            pendiente.delete(0, seguro)
        }
    }

    /** Vacía lo retenido al terminar la generación. */
    fun cerrar(alPensar: (String) -> Unit, alResponder: (String) -> Unit) {
        if (!terminado && pendiente.isNotEmpty()) emitir(pendiente.toString(), alPensar, alResponder)
        pendiente.setLength(0)
    }

    /** Cortamos el razonamiento desde afuera: todo lo que llegue ahora es respuesta. */
    fun pasarARespuesta() {
        if (pendiente.isNotEmpty() && pensando) pensamiento.append(pendiente)
        pendiente.setLength(0)
        pensando = false
        terminado = false
    }

    private fun emitir(texto: String, alPensar: (String) -> Unit, alResponder: (String) -> Unit) {
        if (texto.isEmpty()) return
        if (pensando) {
            pensamiento.append(texto)
            alPensar(texto)
        } else {
            // El modelo suele dejar saltos de línea sueltos justo después de </think>.
            val limpio = if (respuesta.isEmpty()) texto.trimStart() else texto
            if (limpio.isEmpty()) return
            respuesta.append(limpio)
            alResponder(limpio)
        }
    }

    companion object {
        val APERTURAS = listOf("<think>", "<thinking>", "<reasoning>")
        val CIERRES = listOf("</think>", "</thinking>", "</reasoning>")
        val MARCAS_FIN = listOf("<|im_end|>", "<|im_start|>", "<|endoftext|>", "<|eot_id|>", "<|end|>", "<end_of_turn>")
        private val TODAS = APERTURAS + CIERRES + MARCAS_FIN
        private val LARGO_MAXIMO = TODAS.maxOf { it.length }

        private fun primera(texto: CharSequence, etiquetas: List<String>): Pair<Int, String>? {
            var mejor: Pair<Int, String>? = null
            val plano = texto.toString()
            for (etiqueta in etiquetas) {
                val posicion = plano.indexOf(etiqueta)
                if (posicion >= 0 && (mejor == null || posicion < mejor.first)) mejor = posicion to etiqueta
            }
            return mejor
        }

        /** Largo de la cola que podría ser el principio de una etiqueta. */
        fun colaDudosa(texto: CharSequence): Int {
            val desde = maxOf(0, texto.length - LARGO_MAXIMO + 1)
            for (i in desde until texto.length) {
                if (texto[i] != '<') continue
                val cola = texto.subSequence(i, texto.length).toString()
                if (TODAS.any { it.startsWith(cola) }) return texto.length - i
            }
            return 0
        }
    }
}
