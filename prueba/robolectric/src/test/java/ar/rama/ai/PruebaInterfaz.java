package ar.rama.ai;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/**
 * Prueba de humo de la interfaz: arranca la actividad real (con los recursos y
 * assets del APK compilado), recorre las pantallas, manda preguntas sin modelo
 * y guarda capturas en build/capturas para mirarlas.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PruebaInterfaz {

    private static void esperar(int decimas) {
        for (int i = 0; i < decimas; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
        }
    }

    private static void capturar(MainActivity actividad, String nombre) throws Exception {
        View raiz = actividad.getWindow().getDecorView();
        int ancho = actividad.getResources().getDisplayMetrics().widthPixels;
        int alto = actividad.getResources().getDisplayMetrics().heightPixels;
        raiz.measure(View.MeasureSpec.makeMeasureSpec(ancho, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(alto, View.MeasureSpec.EXACTLY));
        raiz.layout(0, 0, ancho, alto);
        Bitmap imagen = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888);
        raiz.draw(new Canvas(imagen));
        File carpeta = new File(System.getProperty("rama.capturas", "build/capturas"));
        carpeta.mkdirs();
        try (FileOutputStream salida = new FileOutputStream(new File(carpeta, nombre + ".png"))) {
            imagen.compress(Bitmap.CompressFormat.PNG, 100, salida);
        }
    }

    private static List<TextView> textos(View vista) {
        List<TextView> salida = new ArrayList<>();
        if (vista instanceof TextView) salida.add((TextView) vista);
        if (vista instanceof ViewGroup) {
            ViewGroup grupo = (ViewGroup) vista;
            for (int i = 0; i < grupo.getChildCount(); i++) salida.addAll(textos(grupo.getChildAt(i)));
        }
        return salida;
    }

    private static TextView buscarTexto(View raiz, String contiene) {
        for (TextView t : textos(raiz)) {
            if (t.getVisibility() == View.VISIBLE && t.getText() != null && t.getText().toString().contains(contiene)) return t;
        }
        return null;
    }

    private static View buscarDescripcion(View vista, String descripcion) {
        CharSequence d = vista.getContentDescription();
        if (d != null && d.toString().equals(descripcion)) return vista;
        if (vista instanceof ViewGroup) {
            ViewGroup grupo = (ViewGroup) vista;
            for (int i = 0; i < grupo.getChildCount(); i++) {
                View encontrada = buscarDescripcion(grupo.getChildAt(i), descripcion);
                if (encontrada != null) return encontrada;
            }
        }
        return null;
    }

    private static EditText entrada(View raiz) {
        for (TextView t : textos(raiz)) if (t instanceof EditText) return (EditText) t;
        return null;
    }

    private static void preguntar(MainActivity a, String pregunta) {
        View raiz = a.getWindow().getDecorView();
        EditText campo = entrada(raiz);
        assertNotNull("no encontré el campo de texto", campo);
        campo.setText(pregunta);
        View enviar = buscarDescripcion(raiz, "Enviar");
        assertNotNull("no encontré el botón Enviar", enviar);
        enviar.performClick();
        esperar(40);
    }

    @Test
    public void recorreLaAppYRespondeSinModelo() throws Exception {
        ActivityController<MainActivity> control = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = control.get();
        esperar(40);
        View raiz = a.getWindow().getDecorView();

        assertNotNull("falta la bienvenida", buscarTexto(raiz, "Hola, soy Rama"));
        assertNotNull("falta el selector de niveles", buscarTexto(raiz, "Max"));
        assertNotNull("falta el aviso para bajar el modelo", buscarTexto(raiz, "Descargá el modelo Rama"));
        capturar(a, "1-bienvenida");

        // Cambiar de nivel.
        buscarTexto(raiz, "Alto").performClick();
        esperar(3);
        capturar(a, "2-nivel-alto");

        // Una cuenta exacta la resuelve la habilidad aunque no haya modelo.
        preguntar(a, "¿Cuánto es 12 * 7?");
        assertNotNull("no respondió la cuenta", buscarTexto(raiz, "84"));
        capturar(a, "3-cuenta");

        // Sin modelo, una pregunta abierta explica cómo bajarlo.
        buscarTexto(raiz, "Bajo").performClick();
        preguntar(a, "Contame algo sobre los pingüinos emperador y su forma de cuidar a las crías");
        assertNotNull("no explicó cómo bajar el modelo", buscarTexto(raiz, "modelo Rama"));
        capturar(a, "4-sin-modelo");

        // Pantalla del modelo.
        buscarDescripcion(raiz, "Modelo Rama").performClick();
        esperar(5);
        assertNotNull("no se ve la pantalla del modelo", buscarTexto(raiz, "Tu modelo de IA de código abierto"));
        assertNotNull(buscarTexto(raiz, "Rama Liviana"));
        assertNotNull(buscarTexto(raiz, "Rama Completa"));
        assertNotNull(buscarTexto(raiz, "Rama Ultra"));
        capturar(a, "5-modelo");
        // Bajar hasta la tarjeta de Rama Ultra, con el selector de memoria.
        TextView ultra = buscarTexto(raiz, "Rama Ultra");
        View v = ultra;
        int arriba = 0;
        while (v != null && !(v.getParent() instanceof android.widget.ScrollView)) {
            arriba += v.getTop();
            v = (View) v.getParent();
        }
        assertNotNull("no encontré el desplazable del modelo", v);
        ((android.widget.ScrollView) v.getParent()).scrollTo(0, Math.max(0, arriba - 40));
        assertNotNull("falta el selector de memoria de Ultra", buscarTexto(raiz, "Compacta"));
        assertNotNull(buscarTexto(raiz, "Equilibrada"));
        capturar(a, "5b-ultra");
        a.onBackPressed();
        esperar(5);

        // Ajustes.
        buscarDescripcion(raiz, "Ajustes").performClick();
        esperar(5);
        assertNotNull("no se ven los ajustes", buscarTexto(raiz, "ESTILO DE RESPUESTA"));
        assertNotNull(buscarTexto(raiz, "Mostrar el razonamiento"));
        capturar(a, "6-ajustes");
        a.onBackPressed();
        esperar(5);

        // Chats guardados.
        buscarDescripcion(raiz, "Chats guardados").performClick();
        esperar(5);
        assertNotNull("no se ve la lista de chats", buscarTexto(raiz, "Tus chats"));
        assertTrue("el chat actual no quedó guardado", buscarTexto(raiz, "GUARDADOS") != null);
        capturar(a, "7-chats");
        a.onBackPressed();
        esperar(5);

        control.pause().stop().destroy();
    }

    /**
     * Con un modelo simulado que razona y responde en Markdown: se captura la
     * tarjeta "Pensando…" en vivo y la respuesta terminada.
     */
    @Test
    public void muestraElRazonamientoEnVivo() throws Exception {
        ActivityController<MainActivity> control = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = control.get();
        esperar(40);
        View raiz = a.getWindow().getDecorView();

        java.lang.reflect.Field campo = MainActivity.class.getDeclaredField("asistente");
        campo.setAccessible(true);
        ar.rama.ai.motor.Asistente asistente = (ar.rama.ai.motor.Asistente) campo.get(a);
        assertNotNull("el asistente no cargó", asistente);
        java.util.concurrent.CountDownLatch mitad = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch seguir = new java.util.concurrent.CountDownLatch(1);
        ModeloSimulado simulado = new ModeloSimulado(mitad, seguir);
        asistente.setMotor(simulado);

        buscarTexto(raiz, "Max").performClick();
        esperar(3);
        EditText campoTexto = entrada(raiz);
        campoTexto.setText("¿Por qué el cielo es azul?");
        buscarDescripcion(raiz, "Enviar").performClick();
        assertTrue("el modelo simulado no arrancó", mitad.await(20, java.util.concurrent.TimeUnit.SECONDS));
        esperar(8);
        assertNotNull("no se ve la tarjeta de razonamiento", buscarTexto(raiz, "Pensando"));
        capturar(a, "8-pensando");
        seguir.countDown();
        esperar(40);
        assertNotNull("no llegó la respuesta", buscarTexto(raiz, "dispersión de Rayleigh"));
        assertNotNull("la tarjeta no se cerró con el resumen", buscarTexto(raiz, "Pensó"));
        capturar(a, "9-respuesta");
        assertTrue("el prompt no abrió <think>", simulado.prompts.get(0).endsWith("<think>\n"));
        control.pause().stop().destroy();
    }

    /** Un modelo que piensa unos tokens, espera a que la prueba capture, y responde. */
    static class ModeloSimulado implements ar.rama.ai.motor.ModeloDeLenguaje {
        final java.util.concurrent.CountDownLatch mitad;
        final java.util.concurrent.CountDownLatch seguir;
        final List<String> prompts = new ArrayList<>();

        ModeloSimulado(java.util.concurrent.CountDownLatch mitad, java.util.concurrent.CountDownLatch seguir) {
            this.mitad = mitad;
            this.seguir = seguir;
        }

        @Override public String getNombre() { return "Rama Simulada"; }
        @Override public String getInfo() { return "modelo simulado para la prueba"; }
        @Override public int getContexto() { return 8192; }
        @Override public boolean getEsChatML() { return true; }
        @Override public boolean getEnAhorro() { return false; }
        @Override public String formatearNativo(List<ar.rama.ai.motor.Mensaje> mensajes) { return ""; }
        @Override public void cancelar() {}

        @Override
        public int generar(String prompt, int maxTokens, float temperatura, float topP, int topK,
                           kotlin.jvm.functions.Function1<? super String, Boolean> alFragmento) {
            prompts.add(prompt);
            String[] pensar = ("La pregunta es por qué el cielo se ve azul. La luz del sol trae todos los colores. " +
                "Al chocar con las moléculas del aire, las longitudes de onda cortas se dispersan mucho más. ").split("(?<= )");
            String[] responder = ("El cielo es azul por la **dispersión de Rayleigh**:\n\n" +
                "- La luz del Sol mezcla todos los colores.\n" +
                "- Las moléculas del aire desvían mucho más el azul (onda corta) que el rojo.\n" +
                "- Ese azul rebotado nos llega desde todas las direcciones.\n\n" +
                "Al atardecer la luz cruza más aire, el azul se pierde en el camino y quedan los `naranjas`.").split("(?<= )");
            int n = 0;
            for (String t : pensar) {
                if (!alFragmento.invoke(t)) return n;
                n++;
                dormir(15);
            }
            mitad.countDown();
            try { seguir.await(20, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { return n; }
            if (!alFragmento.invoke("</think>\n\n")) return n;
            for (String t : responder) {
                if (!alFragmento.invoke(t)) return n;
                n++;
                dormir(10);
            }
            return n;
        }

        private static void dormir(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException e) { }
        }
    }
}
