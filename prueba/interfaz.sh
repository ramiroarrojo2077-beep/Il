#!/usr/bin/env bash
# Prueba de humo de la interfaz con Robolectric, sobre lo que dejó ./compilar.sh
# (build/clases, build/motor-base.jar y el APK sin firmar con los recursos).
# Deja capturas de pantalla en build/capturas/.
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
P="$RAIZ/prueba/robolectric"
export HERRAMIENTAS="${HERRAMIENTAS:-$RAIZ/.herramientas}"

# androidx.test reconstruido desde las fuentes públicas (Google Maven no siempre es accesible).
if [ ! -s "$P/build/androidx-test.jar" ]; then
  mkdir -p "$P/build/libs-r"
  T="$P/build/androidx-test"
  rm -rf "$T" && mkdir -p "$T/out"
  cp "$P/androidx-test/armar.py" "$P/androidx-test/clases.txt" "$T/"
  # errorprone y guava para compilar: las baja Gradle junto con Robolectric.
  (cd "$P" && gradle --no-daemon -q copiarDependencias)
  (cd "$T" && python3 armar.py "$HERRAMIENTAS/android.jar:$(ls "$P"/build/libs-r/*.jar | tr '\n' ':')")
  (cd "$T/out" && zip -q -r "$P/build/androidx-test.jar" .)
fi

mkdir -p "$P/src/test/resources/com/android/tools"
cat > "$P/src/test/resources/com/android/tools/test_config.properties" <<CONF
android_resource_apk=$RAIZ/build/sin-firmar.apk
android_merged_manifest=$RAIZ/app/AndroidManifest.xml
android_custom_package=ar.rama.ai
CONF
cd "$P"
gradle --no-daemon -q test "$@"
echo "Capturas en $RAIZ/build/capturas"
