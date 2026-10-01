package ar.rama.ai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Mantiene viva a Rama mientras responde con la app en segundo plano.
 *
 * La respuesta la sigue escribiendo el hilo del motor de la actividad; este
 * servicio en primer plano sólo evita que Android congele o cierre el proceso
 * a mitad de camino, y retiene la CPU (wake lock parcial) para que no se
 * duerma con la pantalla apagada. Se prende al enviar y se apaga al terminar.
 */
class ServicioRama : Service() {
    private var despierto: PowerManager.WakeLock? = null

    override fun onBind(intencion: Intent?): IBinder? = null

    override fun onStartCommand(intencion: Intent?, flags: Int, idInicio: Int): Int {
        if (intencion?.action == ACCION_DETENER) {
            alDetener?.invoke()
            if (!enMarcha) stopSelf(idInicio)
            return START_NOT_STICKY
        }
        val aviso = Avisos.progreso(this, intencion?.getStringExtra(EXTRA_PREGUNTA) ?: "", intencion?.getStringExtra(EXTRA_ETAPA) ?: "Pensando…")
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Avisos.ID_PROGRESO, aviso, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(Avisos.ID_PROGRESO, aviso)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        iniciado = true
        // La respuesta pudo terminar antes de que el servicio llegara a arrancar.
        if (!enMarcha) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (despierto == null) {
            despierto = try {
                (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rama:respuesta")
                    .apply {
                        setReferenceCounted(false)
                        acquire(TOPE_DESPIERTO)
                    }
            } catch (e: Exception) {
                null
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            despierto?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
        }
        despierto = null
        iniciado = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACCION_DETENER = "ar.rama.ai.DETENER"
        private const val EXTRA_PREGUNTA = "pregunta"
        private const val EXTRA_ETAPA = "etapa"
        /** Ninguna respuesta tarda tanto; es sólo un tope por si algo se cuelga. */
        private const val TOPE_DESPIERTO = 20 * 60 * 1000L

        /** Lo llama el botón «Detener» de la notificación. */
        @Volatile
        var alDetener: (() -> Unit)? = null

        @Volatile
        var enMarcha = false
            private set

        /** true cuando el servicio ya llamó a startForeground (todo esto corre en el hilo principal). */
        private var iniciado = false

        fun empezar(contexto: Context, pregunta: String) {
            enMarcha = true
            try {
                contexto.startForegroundService(
                    Intent(contexto, ServicioRama::class.java)
                        .putExtra(EXTRA_PREGUNTA, pregunta)
                        .putExtra(EXTRA_ETAPA, "Pensando…"),
                )
            } catch (e: Exception) {
                // Sin el servicio la respuesta sigue igual; sólo puede pausarse si la app queda atrás.
                enMarcha = false
            }
        }

        fun terminar(contexto: Context) {
            if (!enMarcha) return
            enMarcha = false
            // Pararlo antes de que llame a startForeground hace que Android cierre la app;
            // si todavía no arrancó, se apaga solo apenas lo haga (ver onStartCommand).
            if (!iniciado) return
            try {
                contexto.stopService(Intent(contexto, ServicioRama::class.java))
            } catch (e: Exception) {
            }
        }
    }
}

/** Las notificaciones de Rama: el progreso mientras responde y el aviso de respuesta lista. */
object Avisos {
    const val ID_PROGRESO = 7
    const val ID_LISTA = 8
    private const val CANAL_PROGRESO = "respondiendo"
    private const val CANAL_LISTA = "respuestas"

    private fun gestor(contexto: Context): NotificationManager? =
        contexto.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    fun crearCanales(contexto: Context) {
        val gestor = gestor(contexto) ?: return
        try {
            gestor.createNotificationChannel(NotificationChannel(CANAL_PROGRESO, "Respondiendo", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Se muestra mientras Rama escribe una respuesta con la app cerrada."
                setShowBadge(false)
            })
            gestor.createNotificationChannel(NotificationChannel(CANAL_LISTA, "Respuestas listas", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Avisa cuando Rama terminó de responder y no estabas en la app."
            })
        } catch (e: Exception) {
        }
    }

    /** Hace falta pedir permiso para notificar desde Android 13. */
    fun permitidas(contexto: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || contexto.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun icono(contexto: Context): Int {
        @Suppress("DiscouragedApi")
        val id = contexto.resources.getIdentifier("ic_notificacion", "drawable", contexto.packageName)
        return if (id != 0) id else contexto.applicationInfo.icon
    }

    private fun abrirApp(contexto: Context): PendingIntent = PendingIntent.getActivity(
        contexto, 0,
        Intent(contexto, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progreso(contexto: Context, pregunta: String, etapa: String): Notification {
        crearCanales(contexto)
        val detener = PendingIntent.getService(
            contexto, 1,
            Intent(contexto, ServicioRama::class.java).setAction(ServicioRama.ACCION_DETENER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(contexto, CANAL_PROGRESO)
            .setSmallIcon(icono(contexto))
            .setColor(Colores.ACENTO)
            .setContentTitle(if (pregunta.isBlank()) "Rama está respondiendo" else "Respondiendo: ${recortar(pregunta, 60)}")
            .setContentText(etapa)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(abrirApp(contexto))
            .addAction(Notification.Action.Builder(null as Icon?, "Detener", detener).build())
            .build()
    }

    /** Cambia el texto de la notificación de progreso (qué está haciendo ahora). */
    fun actualizar(contexto: Context, pregunta: String, etapa: String) {
        if (!ServicioRama.enMarcha || !permitidas(contexto)) return
        try {
            gestor(contexto)?.notify(ID_PROGRESO, progreso(contexto, pregunta, etapa))
        } catch (e: Exception) {
        }
    }

    /** Avisa que la respuesta está lista, con el comienzo del texto. */
    fun lista(contexto: Context, pregunta: String, respuesta: String) {
        if (!permitidas(contexto)) return
        crearCanales(contexto)
        val plano = textoPlano(respuesta)
        val resumen = if (plano.length <= 400) plano else plano.take(399).trimEnd() + "…"
        val aviso = Notification.Builder(contexto, CANAL_LISTA)
            .setSmallIcon(icono(contexto))
            .setColor(Colores.ACENTO)
            .setContentTitle(if (pregunta.isBlank()) "Rama respondió" else "Rama respondió: ${recortar(pregunta, 50)}")
            .setContentText(recortar(plano, 120))
            .setStyle(Notification.BigTextStyle().bigText(resumen))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(abrirApp(contexto))
            .build()
        try {
            gestor(contexto)?.notify(ID_LISTA, aviso)
        } catch (e: Exception) {
        }
    }

    fun limpiarLista(contexto: Context) {
        try {
            gestor(contexto)?.cancel(ID_LISTA)
        } catch (e: Exception) {
        }
    }

    /** Saca las marcas de Markdown y las citas [n] para mostrar el texto en una notificación. */
    fun textoPlano(markdown: String): String = markdown
        .replace(Regex("```[a-zA-Z0-9]*\\n?"), "")
        .replace(Regex("\\[(\\d+)]"), "")
        .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("(?m)^[ \\t]{0,3}#{1,6}[ \\t]*"), "")
        .replace(Regex("(?m)^[ \\t]*[-*•][ \\t]+"), "· ")
        .replace(Regex("\\*\\*|__|`|~~"), "")
        .replace(Regex("\\*([^*\\n]+)\\*"), "$1")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" +([.,;:!?])"), "$1")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    fun recortar(texto: String, maximo: Int): String {
        val limpio = texto.replace(Regex("\\s+"), " ").trim()
        return if (limpio.length <= maximo) limpio else limpio.take(maximo - 1).trimEnd() + "…"
    }
}
