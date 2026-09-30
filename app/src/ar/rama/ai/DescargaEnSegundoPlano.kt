package ar.rama.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import ar.rama.ai.motor.Catalogo
import ar.rama.ai.motor.Edicion
import java.io.File

/** En qué anda la descarga de una edición. */
sealed class EstadoDescarga {
    object Ninguna : EstadoDescarga()
    data class EnCurso(val bajados: Long, val totales: Long, val enPausa: Boolean) : EstadoDescarga() {
        val fraccion: Float get() = if (totales > 0) bajados.toFloat() / totales else 0f
    }
    data class Terminada(val archivo: File) : EstadoDescarga()
    data class Fallo(val motivo: String) : EstadoDescarga()
}

/**
 * Baja el modelo con el gestor de descargas de Android: sigue aunque se cierre
 * la app, muestra su propia notificación y reanuda si se corta la red.
 */
class DescargaEnSegundoPlano(private val contexto: Context) {
    private val gestor = contexto.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    private val preferencias = contexto.getSharedPreferences("descargas", Context.MODE_PRIVATE)

    val carpeta: File? get() = contexto.getExternalFilesDir(CARPETA)

    fun archivoDe(edicion: Edicion): File = File(carpeta ?: File(contexto.filesDir, CARPETA), edicion.archivoLocal)

    private fun idDe(edicion: Edicion): Long = preferencias.getLong(edicion.id, -1L)

    fun encolar(edicion: Edicion, url: String): Boolean {
        val administrador = gestor ?: return false
        cancelar(edicion)
        archivoDe(edicion).delete()
        return try {
            val pedido = DownloadManager.Request(Uri.parse(url))
                .setTitle("Rama AI · ${edicion.nombre}")
                .setDescription("Descargando el modelo Rama (${edicion.parametros})")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(contexto, CARPETA, edicion.archivoLocal)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .addRequestHeader("User-Agent", "RamaAI/4.0")
            val id = administrador.enqueue(pedido)
            preferencias.edit().putLong(edicion.id, id).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun cancelar(edicion: Edicion) {
        val id = idDe(edicion)
        if (id >= 0) {
            try {
                gestor?.remove(id)
            } catch (e: Exception) {
            }
            preferencias.edit().remove(edicion.id).apply()
        }
    }

    fun estado(edicion: Edicion): EstadoDescarga {
        val archivo = archivoDe(edicion)
        val id = idDe(edicion)
        if (id < 0) return if (archivo.exists() && archivo.length() > 0) EstadoDescarga.Terminada(archivo) else EstadoDescarga.Ninguna
        val administrador = gestor ?: return EstadoDescarga.Ninguna
        val cursor = try {
            administrador.query(DownloadManager.Query().setFilterById(id))
        } catch (e: Exception) {
            null
        } ?: return EstadoDescarga.Ninguna
        cursor.use {
            if (!it.moveToFirst()) {
                preferencias.edit().remove(edicion.id).apply()
                return if (archivo.exists()) EstadoDescarga.Terminada(archivo) else EstadoDescarga.Ninguna
            }
            val estado = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bajados = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val totales = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val razon = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (estado) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    preferencias.edit().remove(edicion.id).apply()
                    EstadoDescarga.Terminada(archivo)
                }
                DownloadManager.STATUS_FAILED -> {
                    preferencias.edit().remove(edicion.id).apply()
                    EstadoDescarga.Fallo(explicar(razon))
                }
                DownloadManager.STATUS_PAUSED -> EstadoDescarga.EnCurso(bajados, totales, true)
                else -> EstadoDescarga.EnCurso(bajados, totales, false)
            }
        }
    }

    /** Modelos que quedaron de versiones anteriores y ya no se usan. */
    fun huerfanos(): List<File> {
        val validos = Catalogo.EDICIONES.map { it.archivoLocal }.toSet() + "importado.gguf"
        return carpeta?.listFiles()?.filter { it.isFile && it.name.endsWith(".gguf") && it.name !in validos }
            ?.sortedByDescending { it.length() } ?: emptyList()
    }

    companion object {
        const val CARPETA = "modelos"

        fun explicar(razon: Int): String = when (razon) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "no hay espacio suficiente en el teléfono"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "no encuentro dónde guardarlo"
            DownloadManager.ERROR_CANNOT_RESUME -> "se cortó y no se pudo reanudar"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "se cortó la transferencia"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "el enlace da demasiadas vueltas"
            DownloadManager.ERROR_FILE_ERROR -> "hubo un problema con el archivo"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "el servidor respondió algo inesperado"
            in 400..599 -> "el servidor respondió $razon"
            else -> "error $razon"
        }
    }
}
