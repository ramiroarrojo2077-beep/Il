package ar.rama.ai.motor

import java.util.Locale

/**
 * Cuánta RAM va a ocupar un modelo abierto y con cuánto contexto conviene
 * abrirlo para que entre en la memoria libre del teléfono.
 *
 * El motor nativo mapea los pesos desde el archivo (mmap), guarda la caché de
 * atención cuantizada en q8_0 (34 bytes cada 32 valores) y procesa el prompt en
 * lotes de 256 tokens, así que la cuenta es:
 *   pesos (el archivo) + caché (tokens × capas × cabezas KV × dimensiones) + cómputo.
 */
data class PlanDeMemoria(
    val contexto: Int,
    val bytesPesos: Long,
    val bytesCache: Long,
    val bytesComputo: Long,
    /** Memoria libre con la que se calculó. */
    val libre: Long,
    /** false = ni con el contexto mínimo entra sin arriesgar que Android cierre la app. */
    val alcanza: Boolean,
    /**
     * Cuánto de los pesos puede quedar en RAM. Si es menos que [bytesPesos], el
     * resto se lee del almacenamiento a medida que hace falta (modo ahorro).
     */
    val pesosResidentes: Long = bytesPesos,
) {
    /** Lo que el modelo usa en total si todos los pesos entran en RAM. */
    val total: Long get() = bytesPesos + bytesCache + bytesComputo

    /** Lo que la app pide sí o sí: caché de la charla y cálculo (los pesos son del archivo). */
    val fijo: Long get() = bytesCache + bytesComputo

    /** Lo que realmente ocupa en RAM con este plan. */
    val enUso: Long get() = pesosResidentes + fijo

    /** Parte de los pesos se lee del almacenamiento: anda, pero más lento. */
    val enAhorro: Boolean get() = pesosResidentes < bytesPesos - bytesPesos / 32

    /** "5,2 GB" */
    val totalLegible: String get() = PlanDeMemoria.legible(total)

    val enUsoLegible: String get() = PlanDeMemoria.legible(enUso)

    companion object {
        private const val MB = 1024L * 1024L
        /** q8_0: 32 valores en 34 bytes. */
        const val BYTES_POR_ELEMENTO_KV = 34.0 / 32.0
        const val CONTEXTO_MINIMO = 2048
        /** Aire que se le deja a Android y al resto de la app. */
        val MARGEN = 450L * MB
        /** Si no se conoce la arquitectura: una estimación prudente para modelos de hasta 8 B. */
        const val KV_DESCONOCIDO = 80_000L
        /**
         * En modo ahorro, al menos esta parte de los pesos tiene que poder quedar
         * en RAM: con menos, casi todo se leería del almacenamiento en cada token.
         */
        const val FRACCION_MINIMA_RESIDENTE = 0.2

        fun bytesCache(elementosPorToken: Long, contexto: Int): Long =
            (elementosPorToken * contexto * BYTES_POR_ELEMENTO_KV).toLong()

        /** Búferes de cómputo, salida y tokenizador: crecen un poco con el tamaño del modelo. */
        fun bytesComputo(bytesPesos: Long): Long = 180L * MB + bytesPesos * 3 / 100

        /**
         * El contexto más grande (hasta [tope]) que entra en [libre]. Se redondea a
         * múltiplos de 1024 y nunca baja de [CONTEXTO_MINIMO].
         */
        fun calcular(
            bytesPesos: Long,
            elementosKvPorToken: Long,
            tope: Int,
            libre: Long,
            permitirAhorro: Boolean = false,
        ): PlanDeMemoria {
            val kv = if (elementosKvPorToken > 0) elementosKvPorToken else KV_DESCONOCIDO
            val computo = bytesComputo(bytesPesos)
            val paraCache = libre - bytesPesos - computo - MARGEN
            val porToken = kv * BYTES_POR_ELEMENTO_KV
            val entra = if (paraCache <= 0) 0 else ((paraCache / porToken).toLong() / 1024 * 1024).toInt()
            val contexto = entra.coerceAtMost(tope).coerceAtLeast(CONTEXTO_MINIMO)
            // Con el mínimo se acepta usar la mitad del margen: es mejor que no poder usar el modelo.
            val alcanza = entra >= CONTEXTO_MINIMO ||
                libre - bytesPesos - computo - bytesCache(kv, CONTEXTO_MINIMO) >= MARGEN / 2
            if (alcanza || !permitirAhorro) {
                return PlanDeMemoria(contexto, bytesPesos, bytesCache(kv, contexto), computo, libre, alcanza)
            }
            // Modo ahorro: se reserva sólo lo fijo (caché mínima + cálculo) y los
            // pesos ocupan lo que quede; el resto se lee del archivo al vuelo.
            val cache = bytesCache(kv, CONTEXTO_MINIMO)
            val residentes = (libre - cache - computo - MARGEN).coerceIn(0L, bytesPesos)
            val alcanzaAhorro = residentes >= (bytesPesos * FRACCION_MINIMA_RESIDENTE).toLong()
            return PlanDeMemoria(CONTEXTO_MINIMO, bytesPesos, cache, computo, libre, alcanzaAhorro, residentes)
        }

        /** Memoria libre mínima para abrir un modelo, con o sin modo ahorro. */
        fun minimoParaAbrir(bytesPesos: Long, elementosKvPorToken: Long, ahorro: Boolean): Long {
            val kv = if (elementosKvPorToken > 0) elementosKvPorToken else KV_DESCONOCIDO
            val fijo = bytesCache(kv, CONTEXTO_MINIMO) + bytesComputo(bytesPesos)
            return if (ahorro) fijo + MARGEN + (bytesPesos * FRACCION_MINIMA_RESIDENTE).toLong()
            else fijo + bytesPesos + MARGEN / 2
        }

        /** Lo que usaría con un contexto dado (para mostrar antes de descargar). */
        fun estimar(bytesPesos: Long, elementosKvPorToken: Long, contexto: Int): PlanDeMemoria {
            val kv = if (elementosKvPorToken > 0) elementosKvPorToken else KV_DESCONOCIDO
            return PlanDeMemoria(contexto, bytesPesos, bytesCache(kv, contexto), bytesComputo(bytesPesos), Long.MAX_VALUE, true)
        }

        fun legible(bytes: Long): String = when {
            bytes >= 1_000_000_000L -> String.format(Locale("es", "AR"), "%.1f GB", bytes / 1_000_000_000.0)
            else -> String.format(Locale("es", "AR"), "%.0f MB", bytes / 1_000_000.0)
        }
    }
}
