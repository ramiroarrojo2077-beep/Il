package ar.rama.ai.motor

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Encuentra desde dónde bajar una edición de Rama. Prueba cada fuente con un
 * pedido HEAD y, si ninguna tiene el archivo exacto, le pregunta a la API de
 * Hugging Face qué .gguf hay en cada repositorio y elige el Q4_K_M.
 * La descarga en sí la hace el gestor de descargas de Android.
 */
object Descargador {

    data class Resolucion(val url: String?, val intentos: List<String>)

    fun urlDeArchivo(repositorio: String, archivo: String): String =
        "https://huggingface.co/$repositorio/resolve/main/$archivo?download=true"

    fun resolver(edicion: Edicion): Resolucion {
        val intentos = ArrayList<String>()
        for (fuente in edicion.fuentes) {
            val directa = urlDeArchivo(fuente.repositorio, fuente.archivo)
            if (existe(directa)) return Resolucion(directa, intentos)
            intentos.add("${fuente.repositorio}/${fuente.archivo}: no respondió")
        }
        for (fuente in edicion.fuentes) {
            val encontrado = buscarEnRepositorio(fuente.repositorio, edicion.cuantizacion)
            if (encontrado != null) return Resolucion(encontrado, intentos)
            intentos.add("${fuente.repositorio}: sin un .gguf utilizable")
        }
        return Resolucion(null, intentos)
    }

    /** Enlace para copiar y bajar a mano desde el navegador. */
    fun enlaceManual(edicion: Edicion): String {
        val fuente = edicion.fuentes.first()
        return urlDeArchivo(fuente.repositorio, fuente.archivo)
    }

    fun existe(url: String): Boolean {
        var conexion: HttpURLConnection? = null
        return try {
            conexion = URL(url).openConnection() as HttpURLConnection
            conexion.requestMethod = "HEAD"
            conexion.connectTimeout = 15_000
            conexion.readTimeout = 15_000
            conexion.instanceFollowRedirects = true
            conexion.setRequestProperty("User-Agent", "RamaAI/4.0")
            conexion.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conexion?.disconnect()
        }
    }

    fun buscarEnRepositorio(repositorio: String, preferida: String = "Q4_K_M"): String? {
        val json = Red.get(
            "https://huggingface.co/api/models/$repositorio",
            tiempo = 15_000,
            cabeceras = mapOf("User-Agent" to "RamaAI/4.0"),
        ) ?: return null
        return try {
            val hermanos = JSONObject(json).optJSONArray("siblings") ?: return null
            val nombres = (0 until hermanos.length())
                .mapNotNull { hermanos.optJSONObject(it)?.optString("rfilename") }
                .filter { it.endsWith(".gguf", ignoreCase = true) && !it.contains("-of-") && !it.contains("mmproj", true) }
            val elegido = elegirArchivo(nombres, preferida) ?: return null
            urlDeArchivo(repositorio, elegido)
        } catch (e: Exception) {
            null
        }
    }

    /** Entre los .gguf de un repositorio, la cuantización preferida y si no, la más parecida. */
    fun elegirArchivo(nombres: List<String>, preferida: String): String? {
        fun con(etiqueta: String) = nombres.firstOrNull {
            Regex("(^|[-_.])" + Regex.escape(etiqueta) + "([-_.]|$)", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }
        return con(preferida)
            ?: con("Q4_K_M")
            ?: nombres.firstOrNull { it.contains("Q4", true) && !it.contains("UD-", true) }
            ?: nombres.firstOrNull { it.contains("Q5", true) }
            ?: nombres.firstOrNull()
    }

    /** Un archivo GGUF válido empieza con los bytes "GGUF". */
    fun esGguf(archivo: File): Boolean = try {
        FileInputStream(archivo).use { entrada ->
            val cabecera = ByteArray(4)
            entrada.read(cabecera) == 4 && String(cabecera, Charsets.US_ASCII) == "GGUF"
        }
    } catch (e: Exception) {
        false
    }
}
