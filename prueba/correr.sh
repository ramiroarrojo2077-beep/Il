#!/usr/bin/env bash
# Compila el motor + las pruebas y las corre en la JVM (sin Android).
# Uso: prueba/correr.sh   (necesita que compilar.sh haya bajado las herramientas)
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
H="${HERRAMIENTAS:-$RAIZ/.herramientas}"
T="$RAIZ/build/pruebas"
rm -rf "$T" && mkdir -p "$T/clases"
"$H/kotlinc/bin/kotlinc" "$RAIZ"/app/src/ar/rama/ai/motor/*.kt "$RAIZ/prueba/PruebasMotor.kt" \
  -cp "$H/android.jar:$RAIZ/build/motor-base.jar" -jvm-target 1.8 -d "$T/clases" 2>&1 | grep -v "^Picked up" || true
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote -XX:-BytecodeVerificationLocal -cp "$T/clases:$RAIZ/build/motor-base.jar:$H/json.jar:$H/kotlinc/lib/kotlin-stdlib.jar:$H/android.jar" PruebasMotorKt 2>&1 | grep -v "^Picked up"
