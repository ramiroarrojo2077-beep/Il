package ar.rama.ai.motor

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Lo que importa de la cabecera de un archivo GGUF para calcular memoria:
 * arquitectura, capas, cabezas de atención y cuantización.
 */
data class InfoGguf(
    val arquitectura: String,
    val nombre: String,
    val capas: Int,
    val cabezas: Int,
    val cabezasKv: Int,
    val dimension: Int,
    val dimensionClave: Int,
    val dimensionValor: Int,
    val contextoEntrenado: Int,
    val tipoArchivo: Int,
) {
    /** Elementos de caché K+V por token de contexto. */
    val elementosKvPorToken: Long get() = capas.toLong() * cabezasKv * (dimensionClave + dimensionValor)

    val cuantizacion: String get() = TIPOS[tipoArchivo] ?: "tipo $tipoArchivo"

    companion object {
        /** general.file_type → nombre (llama_ftype). */
        val TIPOS = mapOf(
            0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1",
            10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M",
            16 to "Q5_K_S", 17 to "Q5_K_M", 18 to "Q6_K", 19 to "IQ2_XXS", 20 to "IQ2_XS", 21 to "Q2_K_S",
            22 to "IQ3_XS", 23 to "IQ3_XXS", 24 to "IQ1_S", 25 to "IQ4_NL", 26 to "IQ3_S", 27 to "IQ3_M",
            28 to "IQ2_S", 29 to "IQ2_M", 30 to "IQ4_XS", 31 to "IQ1_M", 32 to "BF16",
        )
    }
}

/** Lector mínimo de la cabecera GGUF (formato v2/v3, little endian). */
object Gguf {
    private const val T_UINT8 = 0
    private const val T_INT8 = 1
    private const val T_UINT16 = 2
    private const val T_INT16 = 3
    private const val T_UINT32 = 4
    private const val T_INT32 = 5
    private const val T_FLOAT32 = 6
    private const val T_BOOL = 7
    private const val T_STRING = 8
    private const val T_ARRAY = 9
    private const val T_UINT64 = 10
    private const val T_INT64 = 11
    private const val T_FLOAT64 = 12

    fun leer(archivo: File): InfoGguf? = try {
        FileInputStream(archivo).use { leer(it) }
    } catch (e: Exception) {
        null
    }

    fun leer(entrada: InputStream): InfoGguf? {
        val datos = LittleEndian(DataInputStream(BufferedInputStream(entrada, 1 shl 16)))
        val magia = ByteArray(4)
        datos.leerBytes(magia)
        if (String(magia, Charsets.US_ASCII) != "GGUF") return null
        val version = datos.u32()
        if (version < 2) return null
        datos.u64() // cantidad de tensores
        val claves = datos.u64()
        val valores = HashMap<String, Any>()
        for (i in 0 until claves) {
            val clave = datos.texto()
            val tipo = datos.u32().toInt()
            val valor = leerValor(datos, tipo, clave)
            if (valor != null) valores[clave] = valor
        }
        val arquitectura = valores["general.architecture"] as? String ?: return null
        fun entero(nombre: String): Int? = (valores["$arquitectura.$nombre"] as? Number)?.toInt()
        val capas = entero("block_count") ?: return null
        val dimension = entero("embedding_length") ?: return null
        val cabezas = entero("attention.head_count") ?: return null
        val cabezasKv = entero("attention.head_count_kv") ?: cabezas
        val porCabeza = if (cabezas > 0) dimension / cabezas else 0
        return InfoGguf(
            arquitectura = arquitectura,
            nombre = valores["general.name"] as? String ?: "",
            capas = capas,
            cabezas = cabezas,
            cabezasKv = cabezasKv,
            dimension = dimension,
            dimensionClave = entero("attention.key_length") ?: porCabeza,
            dimensionValor = entero("attention.value_length") ?: porCabeza,
            contextoEntrenado = entero("context_length") ?: 0,
            tipoArchivo = (valores["general.file_type"] as? Number)?.toInt() ?: -1,
        )
    }

    /** Lee un valor. Los arreglos (vocabulario, etc.) se saltean: no hacen falta. */
    private fun leerValor(d: LittleEndian, tipo: Int, clave: String): Any? = when (tipo) {
        T_UINT8, T_INT8, T_BOOL -> d.u8()
        T_UINT16, T_INT16 -> d.u16()
        T_UINT32 -> d.u32()
        T_INT32 -> d.u32().toInt()
        T_FLOAT32 -> Float.fromBits(d.u32().toInt())
        T_UINT64, T_INT64 -> d.u64()
        T_FLOAT64 -> Double.fromBits(d.u64())
        T_STRING -> if (clave.startsWith("tokenizer.chat_template")) {
            d.saltearTexto()
            null
        } else {
            d.texto()
        }
        T_ARRAY -> {
            val tipoElemento = d.u32().toInt()
            val cantidad = d.u64()
            val ancho = when (tipoElemento) {
                T_UINT8, T_INT8, T_BOOL -> 1L
                T_UINT16, T_INT16 -> 2L
                T_UINT32, T_INT32, T_FLOAT32 -> 4L
                T_UINT64, T_INT64, T_FLOAT64 -> 8L
                else -> -1L
            }
            when {
                ancho > 0 -> d.saltear(ancho * cantidad)
                tipoElemento == T_STRING -> for (i in 0 until cantidad) d.saltearTexto()
                else -> for (i in 0 until cantidad) leerValor(d, tipoElemento, "")
            }
            null
        }
        else -> throw IllegalStateException("tipo GGUF desconocido $tipo")
    }

    private class LittleEndian(private val d: DataInputStream) {
        private val ocho = ByteArray(8)

        fun leerBytes(destino: ByteArray) = d.readFully(destino)

        fun u8(): Int = d.readUnsignedByte()

        fun u16(): Int {
            d.readFully(ocho, 0, 2)
            return (ocho[0].toInt() and 0xff) or ((ocho[1].toInt() and 0xff) shl 8)
        }

        fun u32(): Long {
            d.readFully(ocho, 0, 4)
            var v = 0L
            for (i in 3 downTo 0) v = (v shl 8) or (ocho[i].toLong() and 0xff)
            return v
        }

        fun u64(): Long {
            d.readFully(ocho, 0, 8)
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (ocho[i].toLong() and 0xff)
            return v
        }

        fun texto(): String {
            val largo = u64()
            if (largo > 64L * 1024 * 1024) throw IllegalStateException("texto GGUF demasiado largo")
            val bytes = ByteArray(largo.toInt())
            d.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        fun saltearTexto() = saltear(u64())

        fun saltear(bytes: Long) {
            var falta = bytes
            while (falta > 0) {
                val salteados = d.skip(falta)
                if (salteados <= 0) {
                    if (d.read() < 0) throw EOFException()
                    falta--
                } else {
                    falta -= salteados
                }
            }
        }
    }
}
