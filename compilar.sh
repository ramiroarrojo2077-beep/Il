#!/usr/bin/env bash
# Compila Rama AI 4 y deja el APK firmado en dist/.
#
# No usa Gradle ni el SDK de Android: arma todo con herramientas sueltas que
# baja la primera vez a .herramientas/ (apktool, kotlinc 2.0.21, D8, android.jar
# 34, dex2jar y uber-apk-signer).
#
# Partes del APK:
#   · base/rama32-original.apk aporta el motor nativo (llama.cpp), la librería
#     estándar de Kotlin y las clases del motor que se reutilizan tal cual
#     (habilidades, base de conocimiento, memoria, chats, adjuntos).
#   · app/src aporta todo lo nuevo (interfaz, modelo Rama, búsqueda web, niveles
#     de pensamiento). Las clases viejas que reemplaza están en
#     app/clases-quitadas.txt y se sacan del dex original.
#   · app/res, app/assets y app/AndroidManifest.xml reemplazan a los originales.
#
# Uso: ./compilar.sh [--sin-pruebas]
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")" && pwd)"
H="${HERRAMIENTAS:-$RAIZ/.herramientas}"
B="$RAIZ/build"
BASE="$RAIZ/base/rama32-original.apk"
VERSION_NOMBRE="4.0.0"
VERSION_CODIGO="6"
SALIDA="$RAIZ/dist/RamaAI-$VERSION_NOMBRE.apk"
PRUEBAS=1
[ "${1:-}" = "--sin-pruebas" ] && PRUEBAS=0

paso() { printf '\n\033[1;35m▸ %s\033[0m\n' "$*"; }
java_q() { java "$@" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true; }

bajar() { # url destino
  local url="$1" destino="$2"
  [ -s "$destino" ] && return 0
  echo "  bajando $(basename "$destino")"
  curl -fsSL --retry 4 --retry-delay 3 -o "$destino.tmp" "$url"
  mv "$destino.tmp" "$destino"
}

# ---------------------------------------------------------------- herramientas
paso "Herramientas"
mkdir -p "$H"
bajar https://github.com/iBotPeaches/Apktool/releases/download/v2.12.1/apktool_2.12.1.jar "$H/apktool.jar"
bajar https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar "$H/android.jar"
bajar https://storage.googleapis.com/r8-releases/raw/8.5.35/r8lib.jar "$H/r8lib.jar"
bajar https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar "$H/uber-apk-signer.jar"
bajar https://maven-central.storage-download.googleapis.com/maven2/org/json/json/20240303/json-20240303.jar "$H/json.jar"
if [ ! -x "$H/kotlinc/bin/kotlinc" ]; then
  bajar https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip "$H/kotlinc.zip"
  (cd "$H" && unzip -q -o kotlinc.zip)
fi
if [ ! -d "$H/d2j/dex-tools-2.4.22" ]; then
  bajar https://github.com/ThexXTURBOXx/dex2jar/releases/download/2.4.22/dex-tools-2.4.22.zip "$H/d2j.zip"
  (cd "$H" && unzip -q -o d2j.zip -d d2j)
fi
KOTLINC="$H/kotlinc/bin/kotlinc"
STDLIB="$H/kotlinc/lib/kotlin-stdlib.jar"

# ---------------------------------------------------------------- decodificar la base
paso "Decodificando la base (v3.2)"
rm -rf "$B/decodificado" "$B/base-jar" && mkdir -p "$B"
java_q -jar "$H/apktool.jar" d -f "$BASE" -o "$B/decodificado" >/dev/null

paso "Quitando las clases reemplazadas"
QUITADAS="$B/clases-quitadas.lst"
: > "$QUITADAS"
while IFS= read -r patron; do
  case "$patron" in \#*|"") continue ;; esac
  for dir in "$B"/decodificado/smali*; do
    for f in $dir/$patron; do
      [ -e "$f" ] || continue
      echo "L${f#$dir/}" | sed 's/\.smali$/;/' >> "$QUITADAS"
      rm -f "$f"
    done
  done
done < "$RAIZ/app/clases-quitadas.txt"
echo "  $(wc -l < "$QUITADAS") clases quitadas"
# Si un dex quedó vacío se descarta y se renumeran los siguientes: Android carga
# classes.dex, classes2.dex, … y se detiene en el primer número que falte.
for dir in "$B"/decodificado/smali_classes*; do
  [ -n "$(find "$dir" -name '*.smali' -print -quit)" ] || rm -rf "$dir"
done
n=2
for dir in $(ls -d "$B"/decodificado/smali_classes* 2>/dev/null | sort -V); do
  destino="$B/decodificado/smali_classes$n"
  [ "$dir" = "$destino" ] || mv "$dir" "$destino"
  n=$((n + 1))
done
# Ninguna clase que queda puede apuntar a una que se fue.
if grep -rlF -f "$QUITADAS" "$B"/decodificado/smali* > "$B/colgantes.lst"; then
  echo "ERROR: quedan referencias a clases quitadas en:"; cat "$B/colgantes.lst"; exit 1
fi

# Jar con las clases que quedan del motor, para compilar contra ellas.
paso "Preparando el classpath del motor"
mkdir -p "$B/base-jar/x"
for dex in $(unzip -Z1 "$BASE" | grep -E '^classes[0-9]*\.dex$'); do
  unzip -q -o "$BASE" "$dex" -d "$B/base-jar"
  sh "$H/d2j/dex-tools-2.4.22/d2j-dex2jar.sh" -f -o "$B/base-jar/${dex%.dex}.jar" "$B/base-jar/$dex" 2>&1 | grep -v '^Picked up' >/dev/null || true
  (cd "$B/base-jar/x" && unzip -q -o "../${dex%.dex}.jar")
done
while IFS= read -r desc; do
  clase="${desc#L}"; clase="${clase%;}"
  rm -f "$B/base-jar/x/$clase.class"
done < "$QUITADAS"
rm -f "$B/motor-base.jar"
(cd "$B/base-jar/x" && zip -q -r "$B/motor-base.jar" ar)

# ---------------------------------------------------------------- pruebas
if [ "$PRUEBAS" = 1 ]; then
  paso "Pruebas del motor"
  HERRAMIENTAS="$H" "$RAIZ/prueba/correr.sh"
fi

# ---------------------------------------------------------------- compilar
paso "Compilando Kotlin"
rm -rf "$B/clases" "$B/dex" && mkdir -p "$B/clases" "$B/dex"
"$KOTLINC" $(find "$RAIZ/app/src" -name '*.kt' | sort) \
  -cp "$H/android.jar:$B/motor-base.jar" -jvm-target 1.8 -Werror=false \
  -d "$B/clases" 2>&1 | grep -v '^Picked up' || true
[ -f "$B/clases/ar/rama/ai/MainActivity.class" ] || { echo "ERROR: no compiló"; exit 1; }

paso "Pasando a DEX (D8)"
java_q -cp "$H/r8lib.jar" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$H/android.jar" --classpath "$B/motor-base.jar" --classpath "$STDLIB" \
  --output "$B/dex" $(find "$B/clases" -name '*.class')
ls "$B/dex"/*.dex >/dev/null

# ---------------------------------------------------------------- recursos y manifiesto
paso "Recursos, assets y manifiesto nuevos"
rm -rf "$B/decodificado/res" "$B/decodificado/assets"
cp -r "$RAIZ/app/res" "$B/decodificado/res"
cp -r "$RAIZ/app/assets" "$B/decodificado/assets"
cp "$RAIZ/app/AndroidManifest.xml" "$B/decodificado/AndroidManifest.xml"
sed -i -E "s/^( *versionCode:).*/\1 $VERSION_CODIGO/; s/^( *versionName:).*/\1 $VERSION_NOMBRE/" "$B/decodificado/apktool.yml"

paso "Armando el APK"
rm -f "$B/sin-firmar.apk"
java_q -jar "$H/apktool.jar" b "$B/decodificado" -o "$B/sin-firmar.apk" >/dev/null
[ -f "$B/sin-firmar.apk" ] || { echo "ERROR: apktool no armó el APK"; exit 1; }
N=$(unzip -Z1 "$B/sin-firmar.apk" | grep -cE '^classes[0-9]*\.dex$')
i=0
for dex in $(ls "$B/dex"/*.dex | sort); do
  destino="classes$((N + 1 + i)).dex"
  cp "$dex" "$B/$destino"
  (cd "$B" && zip -q sin-firmar.apk "$destino")
  echo "  + $destino ($(du -h "$dex" | cut -f1))"
  i=$((i + 1))
done

paso "Verificando los dex"
# Cada clase tiene que estar en un solo dex, y los dex tienen que ir de corrido.
rm -rf "$B/verificar" && mkdir -p "$B/verificar"
(cd "$B/verificar" && unzip -q "$B/sin-firmar.apk" 'classes*.dex')
TOTAL=$(ls "$B/verificar"/classes*.dex | wc -l)
for k in $(seq 1 "$TOTAL"); do
  nombre=$([ "$k" = 1 ] && echo classes.dex || echo "classes$k.dex")
  [ -f "$B/verificar/$nombre" ] || { echo "ERROR: falta $nombre (los dex no van de corrido)"; exit 1; }
done
for dex in "$B/verificar"/classes*.dex; do
  sh "$H/d2j/dex-tools-2.4.22/d2j-dex2jar.sh" -f -o "${dex%.dex}.jar" "$dex" 2>&1 | grep -v '^Picked up' >/dev/null || true
  unzip -Z1 "${dex%.dex}.jar" | grep '\.class$' >> "$B/verificar/todas.lst"
done
DUPLICADAS=$(sort "$B/verificar/todas.lst" | uniq -d)
[ -z "$DUPLICADAS" ] || { echo "ERROR: clases repetidas entre dex:"; echo "$DUPLICADAS"; exit 1; }
echo "  $TOTAL dex, $(wc -l < "$B/verificar/todas.lst") clases, sin repetidas"

paso "Alineando y firmando"
rm -rf "$B/firmado"
java_q -jar "$H/uber-apk-signer.jar" -a "$B/sin-firmar.apk" -o "$B/firmado" --allowResign | grep -E 'VERIFY|SUCCESS|FAIL|ERROR' || true
FIRMADO=$(ls "$B/firmado"/*.apk | head -1)
mkdir -p "$RAIZ/dist"
cp "$FIRMADO" "$SALIDA"

paso "Listo"
AAPT2=$(ls "$H"/aapt2* 2>/dev/null | head -1 || true)
if [ -z "$AAPT2" ]; then
  unzip -q -o -j "$H/apktool.jar" prebuilt/linux/aapt2_64 -d "$H" && mv "$H/aapt2_64" "$H/aapt2" && chmod +x "$H/aapt2"
  AAPT2="$H/aapt2"
fi
"$AAPT2" dump badging "$SALIDA" 2>/dev/null | grep -E "^package|^sdkVersion|^targetSdkVersion|^application-label:|^native-code" || true
echo "APK: $SALIDA ($(du -h "$SALIDA" | cut -f1))"
