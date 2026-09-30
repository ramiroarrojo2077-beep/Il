package ar.rama.ai.motor

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

/** Pedidos HTTP chicos y tolerantes a fallas: si algo sale mal devuelven null. */
object Red {
    const val AGENTE =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"

    private val CHARSET_META = Regex("<meta[^>]+charset=[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)

    fun codificar(texto: String): String = URLEncoder.encode(texto, "UTF-8")

    fun get(
        url: String,
        maxBytes: Int = 900_000,
        tiempo: Int = 10_000,
        cabeceras: Map<String, String> = emptyMap(),
    ): String? = pedir(url, "GET", null, maxBytes, tiempo, cabeceras)

    fun post(
        url: String,
        formulario: Map<String, String>,
        maxBytes: Int = 900_000,
        tiempo: Int = 10_000,
        cabeceras: Map<String, String> = emptyMap(),
    ): String? {
        val cuerpo = formulario.entries.joinToString("&") { codificar(it.key) + "=" + codificar(it.value) }
        return pedir(url, "POST", cuerpo, maxBytes, tiempo, cabeceras)
    }

    private fun pedir(
        url: String,
        metodo: String,
        cuerpo: String?,
        maxBytes: Int,
        tiempo: Int,
        cabeceras: Map<String, String>,
    ): String? {
        var conexion: HttpURLConnection? = null
        try {
            conexion = URL(url).openConnection() as HttpURLConnection
            conexion.requestMethod = metodo
            conexion.connectTimeout = tiempo
            conexion.readTimeout = tiempo
            conexion.instanceFollowRedirects = true
            conexion.setRequestProperty("User-Agent", AGENTE)
            conexion.setRequestProperty("Accept-Language", "es-AR,es;q=0.9,en;q=0.6")
            conexion.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,application/xml;q=0.9,*/*;q=0.8")
            for ((clave, valor) in cabeceras) conexion.setRequestProperty(clave, valor)
            if (cuerpo != null) {
                conexion.doOutput = true
                conexion.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conexion.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            }
            val codigo = conexion.responseCode
            if (codigo !in 200..299) return null
            val tipo = conexion.contentType ?: ""
            if (tipo.startsWith("image/") || tipo.startsWith("video/") || tipo.startsWith("audio/")) return null
            val bytes = leerHasta(conexion.inputStream, maxBytes)
            return decodificar(bytes, tipo)
        } catch (e: Exception) {
            return null
        } finally {
            try {
                conexion?.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    private fun leerHasta(entrada: InputStream, maxBytes: Int): ByteArray {
        val salida = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        entrada.use {
            while (salida.size() < maxBytes) {
                val leidos = it.read(buffer)
                if (leidos < 0) break
                salida.write(buffer, 0, leidos)
            }
        }
        return salida.toByteArray()
    }

    private fun decodificar(bytes: ByteArray, tipo: String): String {
        val declarado = Regex("charset=([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(tipo)?.groupValues?.get(1)
        val candidato = declarado ?: run {
            val cabeza = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
            CHARSET_META.find(cabeza)?.groupValues?.get(1)
        }
        val charset = try {
            if (candidato != null) Charset.forName(candidato) else Charsets.UTF_8
        } catch (e: Exception) {
            Charsets.UTF_8
        }
        return String(bytes, charset)
    }
}
