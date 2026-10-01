package ar.rama.ai

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import ar.rama.ai.motor.Enfasis
import ar.rama.ai.motor.Formato
import ar.rama.ai.motor.Resultado

/**
 * Convierte el Markdown simple que escribe Rama en texto con estilos:
 * negrita, cursiva, títulos de color, `código`, bloques ``` y citas [n] que
 * abren la fuente al tocarlas.
 */
object Markdown {
    private val CITA = Regex("\\[(\\d{1,2})]")
    private val ITEM = Regex("^(\\s*)(•  |\\d{1,2}[.)] )")
    private const val VALLA = "```"

    /** Sangría en píxeles para los renglones que siguen a una viñeta (la fija la actividad). */
    var sangria = 0

    fun formatear(
        markdown: String,
        fuentes: List<Resultado> = emptyList(),
        alAbrir: ((String) -> Unit)? = null,
    ): CharSequence {
        val salida = SpannableStringBuilder()
        val partes = markdown.split(VALLA)
        partes.forEachIndexed { i, parte ->
            if (i % 2 == 1) {
                // Bloque de código: se saltea la primera línea si es el nombre del lenguaje.
                val lineas = parte.trimEnd().lines()
                val codigo = if (lineas.size > 1 && lineas.first().trim().matches(Regex("[A-Za-z0-9+#._-]{0,20}"))) {
                    lineas.drop(1).joinToString("\n")
                } else {
                    parte.trim('\n')
                }
                if (salida.isNotEmpty() && !salida.endsWith("\n")) salida.append('\n')
                val desde = salida.length
                salida.append(codigo)
                val hasta = salida.length
                salida.setSpan(SpanFuente(Typeface.MONOSPACE), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                salida.setSpan(ForegroundColorSpan(Colores.TEXTO), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                salida.setSpan(BackgroundColorSpan(Colores.SUPERFICIE_ALTA), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                salida.setSpan(RelativeSizeSpan(0.9f), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (i < partes.size - 1) salida.append('\n')
            } else {
                agregarTexto(salida, parte)
            }
        }
        if (fuentes.isNotEmpty()) marcarCitas(salida, fuentes, alAbrir)
        return salida
    }

    private fun agregarTexto(salida: SpannableStringBuilder, markdown: String) {
        if (markdown.isEmpty()) return
        val analizado = try {
            Formato.analizar(markdown)
        } catch (e: Exception) {
            salida.append(markdown)
            return
        }
        val base = salida.length
        salida.append(analizado.texto)
        if (sangria > 0) {
            var inicio = 0
            for (linea in analizado.texto.split('\n')) {
                val item = ITEM.find(linea)
                if (item != null) {
                    val extra = (item.groupValues[1].length * sangria / 3) + if (item.groupValues[2].startsWith("•")) sangria else (sangria * 13 / 10)
                    salida.setSpan(LeadingMarginSpan.Standard(0, extra), base + inicio, base + inicio + linea.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                inicio += linea.length + 1
            }
        }
        for (marca in analizado.marcas) {
            val desde = base + marca.desde
            val hasta = base + marca.hasta
            if (desde >= hasta || hasta > salida.length) continue
            when (marca.enfasis) {
                Enfasis.NEGRITA -> {
                    salida.setSpan(SpanFuente(Fuentes.de(Peso.NEGRITA)), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    salida.setSpan(ForegroundColorSpan(Colores.TEXTO), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                Enfasis.CURSIVA -> salida.setSpan(StyleSpan(Typeface.ITALIC), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                Enfasis.CODIGO -> {
                    salida.setSpan(SpanFuente(Typeface.MONOSPACE), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    salida.setSpan(ForegroundColorSpan(Colores.TEXTO), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    salida.setSpan(BackgroundColorSpan(Colores.SUPERFICIE_ALTA), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    salida.setSpan(RelativeSizeSpan(0.92f), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                Enfasis.TITULO -> {
                    salida.setSpan(SpanFuente(Fuentes.de(Peso.NEGRITA)), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    salida.setSpan(ForegroundColorSpan(Colores.TEXTO), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    salida.setSpan(RelativeSizeSpan(1.12f), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {}
            }
        }
    }

    private fun marcarCitas(texto: SpannableStringBuilder, fuentes: List<Resultado>, alAbrir: ((String) -> Unit)?) {
        for (m in CITA.findAll(texto)) {
            val indice = m.groupValues[1].toInt() - 1
            val fuente = fuentes.getOrNull(indice) ?: continue
            val desde = m.range.first
            val hasta = m.range.last + 1
            texto.setSpan(SpanFuente(Fuentes.de(Peso.NEGRITA)), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            texto.setSpan(RelativeSizeSpan(0.85f), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            texto.setSpan(BackgroundColorSpan(Colores.alfa(Colores.ACENTO, 0.16f)), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (alAbrir != null) {
                texto.setSpan(object : ClickableSpan() {
                    override fun onClick(vista: View) = alAbrir(fuente.url)
                    override fun updateDrawState(pintura: TextPaint) {
                        pintura.color = Colores.ACENTO_CLARO
                        pintura.isUnderlineText = false
                    }
                }, desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                texto.setSpan(ForegroundColorSpan(Colores.ACENTO_CLARO), desde, hasta, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
