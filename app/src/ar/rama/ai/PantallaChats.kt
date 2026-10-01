package ar.rama.ai

import android.app.Activity
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ar.rama.ai.motor.Conversaciones
import ar.rama.ai.motor.ResumenConversacion

/** El panel de chats guardados: se desliza desde la izquierda. */
class PantallaChats(
    private val actividad: Activity,
    private val raiz: FrameLayout,
    private val conversaciones: Conversaciones,
    private val hoja: HojaInferior,
    private val chatActual: () -> String,
    private val alAbrir: (String) -> Unit,
    private val alNuevo: () -> Unit,
    private val alBorrarActual: () -> Unit,
) {
    private val lista = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
    val vista: View = construir()
    val visible: Boolean get() = vista.visibility == View.VISIBLE


    private fun construir(): View {
        val capa = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Colores.FONDO)
            visibility = View.GONE
            isClickable = true
        }
        val cabecera = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(8f))
        }
        cabecera.addView(actividad.botonIcono(Trazos.atras(), "Volver", alTocar = { ocultar() }))
        cabecera.addView(TextView(actividad).apply {
            text = "Tus chats"
            estilo(20f, Colores.TEXTO, Peso.NEGRITA, 1f)
        }, lp(0, WRAP, 1f) { leftMargin = dp(12f) })
        cabecera.addView(actividad.botonIcono(Trazos.papelera(), "Borrar todos los chats", color = Colores.TEXTO_2, alTocar = { confirmarBorrarTodo() }))
        capa.addView(cabecera)

        capa.addView(actividad.boton("Chat nuevo", Colores.ACENTO, Trazos.mas()) {
            ocultar()
            alNuevo()
        }, lp(MATCH, WRAP) { setMargins(dp(16f), dp(6f), dp(16f), dp(14f)) })

        val desplazable = ScrollView(actividad).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(16f), 0, dp(16f), dp(24f))
            addView(lista)
        }
        capa.addView(desplazable, lp(MATCH, 0, 1f))
        raiz.addView(capa, FrameLayout.LayoutParams(MATCH, MATCH))
        return capa
    }

    fun mostrar() {
        refrescar()
        vista.visibility = View.VISIBLE
        vista.translationX = -vista.width.toFloat().coerceAtLeast(dp(300f).toFloat())
        vista.alpha = 0.6f
        vista.animate().translationX(0f).alpha(1f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    fun ocultar() {
        if (!visible) return
        vista.animate().translationX(-vista.width.toFloat()).alpha(0.4f).setDuration(200).withEndAction {
            vista.visibility = View.GONE
        }.start()
    }

    fun refrescar() {
        lista.removeAllViews()
        val todos = try {
            conversaciones.listar()
        } catch (e: Exception) {
            emptyList()
        }
        if (todos.isEmpty()) {
            lista.addView(vacio())
            return
        }
        lista.addView(actividad.rotulo("${todos.size} guardados"), lp(WRAP, WRAP) { bottomMargin = dp(10f) })
        for (resumen in todos) lista.addView(fila(resumen))
    }

    private fun vacio(): View = LinearLayout(actividad).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(12f), dp(48f), dp(12f), dp(12f))
        addView(ar.rama.ai.Icono(Trazos.chats(), Colores.TEXTO_3, 1.8f).let { icono ->
            android.widget.ImageView(actividad).apply { setImageDrawable(icono) }
        }, lp(dp(56f), dp(56f)) { bottomMargin = dp(14f) })
        addView(TextView(actividad).apply {
            text = "Todavía no hay chats guardados"
            estilo(16f, Colores.TEXTO, Peso.NEGRITA, 1.2f)
            gravity = Gravity.CENTER
        })
        addView(TextView(actividad).apply {
            text = "Cada conversación se guarda sola apenas escribís algo, y queda acá hasta que la borres."
            estilo(13.5f, Colores.TEXTO_2, Peso.NORMAL, 1.35f)
            gravity = Gravity.CENTER
        }, lp(MATCH, WRAP) { topMargin = dp(6f) })
    }

    private fun fila(resumen: ResumenConversacion): View {
        val actual = resumen.id == chatActual()
        val fila = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(12f), dp(8f), dp(12f))
            val radio = dp(12f).toFloat()
            background = if (actual) pulsable(Colores.SUPERFICIE_ALTA, radio, Colores.alfa(Colores.ACENTO, 0.6f), dp(1f))
            else pulsable(Colores.SUPERFICIE, radio, Colores.BORDE_TENUE, dp(1f))
            setOnClickListener {
                ocultar()
                alAbrir(resumen.id)
            }
            setOnLongClickListener {
                opciones(resumen)
                true
            }
        }
        val inicial = resumen.titulo.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
        fila.addView(TextView(actividad).apply {
            text = inicial
            estilo(15f, Colores.TEXTO_2, Peso.NEGRITA, 1f)
            gravity = Gravity.CENTER
            background = redondeado(Colores.SUPERFICIE_ALTA, dp(10f).toFloat())
        }, lp(dp(40f), dp(40f)) { rightMargin = dp(12f) })
        val textos = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(TextView(actividad).apply {
            text = resumen.titulo
            estilo(14.5f, Colores.TEXTO, Peso.NEGRITA, 1.2f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val cuando = DateUtils.getRelativeTimeSpanString(resumen.actualizada, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        textos.addView(TextView(actividad).apply {
            text = "$cuando · ${resumen.cantidadMensajes} mensajes" + if (actual) " · abierto" else ""
            estilo(12f, if (actual) Colores.ACENTO_CLARO else Colores.TEXTO_3, Peso.MEDIO, 1f)
        }, lp(WRAP, WRAP) { topMargin = dp(3f) })
        fila.addView(textos, lp(0, WRAP, 1f))
        fila.addView(actividad.botonIcono(Trazos.lapiz(), "Opciones de «${resumen.titulo}»", 36f, 16f, Colores.TEXTO_3, 0, 0) {
            opciones(resumen)
        })
        return FrameLayout(actividad).apply {
            addView(fila)
            layoutParams = lp(MATCH, WRAP) { bottomMargin = dp(8f) }
        }
    }

    private fun opciones(resumen: ResumenConversacion) {
        val contenido = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
        val campo = EditText(actividad).apply {
            setText(resumen.titulo)
            estilo(15.5f, Colores.TEXTO, Peso.MEDIO, 1.2f)
            setHintTextColor(Colores.TEXTO_3)
            hint = "Nombre del chat"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = redondeado(Colores.SUPERFICIE_ALTA, dp(14f).toFloat(), Colores.BORDE, dp(1f))
            relleno(dp(14f), dp(12f))
            setSelection(text.length)
        }
        contenido.addView(actividad.rotulo("Nombre"), lp(WRAP, WRAP) { bottomMargin = dp(6f) })
        contenido.addView(campo, lp(MATCH, WRAP))
        contenido.addView(actividad.boton("Guardar nombre", Colores.ACENTO, Trazos.visto()) {
            val nuevo = campo.text.toString().trim()
            if (nuevo.isNotEmpty()) {
                try {
                    conversaciones.renombrar(resumen.id, nuevo)
                } catch (e: Exception) {
                }
            }
            hoja.cerrar()
            refrescar()
        }, lp(MATCH, WRAP) { topMargin = dp(12f) })
        contenido.addView(actividad.boton("Borrar este chat", Colores.PELIGRO, Trazos.papelera()) {
            try {
                conversaciones.borrar(resumen.id)
            } catch (e: Exception) {
            }
            if (resumen.id == chatActual()) alBorrarActual()
            hoja.cerrar()
            refrescar()
        }, lp(MATCH, WRAP) { topMargin = dp(10f) })
        hoja.mostrar("Chat", contenido)
    }

    private fun confirmarBorrarTodo() {
        val contenido = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
        contenido.addView(TextView(actividad).apply {
            text = "Se borran todas las conversaciones guardadas en este teléfono. No se puede deshacer."
            estilo(14.5f, Colores.TEXTO_2, Peso.NORMAL, 1.35f)
        })
        contenido.addView(actividad.boton("Sí, borrar todo", Colores.PELIGRO, Trazos.papelera()) {
            try {
                conversaciones.borrarTodo()
            } catch (e: Exception) {
            }
            alBorrarActual()
            hoja.cerrar()
            refrescar()
        }, lp(MATCH, WRAP) { topMargin = dp(16f) })
        contenido.addView(actividad.boton("Cancelar") { hoja.cerrar() }, lp(MATCH, WRAP) { topMargin = dp(10f) })
        hoja.mostrar("¿Borrar todos los chats?", contenido)
    }

    private fun dp(valor: Float) = actividad.dp(valor)
}
