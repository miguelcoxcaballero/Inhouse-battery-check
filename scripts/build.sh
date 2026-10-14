#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
for TOOL_NAME in java javac jar zip python3; do
  command -v "$TOOL_NAME" >/dev/null || { echo "Falta herramienta: $TOOL_NAME (instala un JDK completo, no solo un JRE)." >&2; exit 1; }
done
SDK_DIR=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$SDK_DIR" ]]; then
  echo "Define ANDROID_SDK_ROOT con la ruta del SDK Android." >&2; exit 1
fi
BT="$SDK_DIR/build-tools/36.0.0"
ANDROID_JAR="$SDK_DIR/platforms/android-36/android.jar"
for TOOL_PATH in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$ANDROID_JAR"; do
  [[ -f "$TOOL_PATH" ]] || { echo "Falta herramienta: $TOOL_PATH" >&2; exit 1; }
done
[[ -n "${INHOUSE_SIGNING_DIR:-}" ]] || { echo "Define INHOUSE_SIGNING_DIR con la ruta privada creada por scripts/create-signing-key.sh." >&2; exit 1; }
[[ -r "$INHOUSE_SIGNING_DIR/release.jks" && -r "$INHOUSE_SIGNING_DIR/password" ]] || { echo "Falta clave o archivo de contraseña." >&2; exit 1; }
BUILD_DIR="$PROJECT_DIR/build"
MAIN_DIR="$PROJECT_DIR/app/src/main"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/generated" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$PROJECT_DIR/dist"
"$BT/aapt2" compile --dir "$MAIN_DIR/res" -o "$BUILD_DIR/resources.zip"
"$BT/aapt2" link -o "$BUILD_DIR/base.apk" -I "$ANDROID_JAR" \
  --manifest "$MAIN_DIR/AndroidManifest.xml" --java "$BUILD_DIR/generated" \
  --min-sdk-version 29 --target-sdk-version 36 --auto-add-overlay "$BUILD_DIR/resources.zip"
python3 - "$MAIN_DIR/java" "$BUILD_DIR/generated" "$BUILD_DIR/java-sources.txt" <<'PY'
from pathlib import Path
import sys
sources = sorted(str(p) for root in sys.argv[1:3] for p in Path(root).rglob('*.java'))
Path(sys.argv[3]).write_text('\n'.join('"' + p.replace('\\', '\\\\').replace('"', '\\"') + '"' for p in sources), encoding='utf-8')
PY
javac -encoding UTF-8 --release 17 -classpath "$ANDROID_JAR" -d "$BUILD_DIR/classes" @"$BUILD_DIR/java-sources.txt"
jar --create --file "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/classes" .
"$BT/d8" --release --min-api 29 --lib "$ANDROID_JAR" --output "$BUILD_DIR/dex" "$BUILD_DIR/classes.jar"
cp "$BUILD_DIR/base.apk" "$BUILD_DIR/unsigned.apk"
(cd "$BUILD_DIR/dex" && zip -q -X "$BUILD_DIR/unsigned.apk" classes*.dex)
"$BT/zipalign" -P 16 -f 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"
rm -f "$PROJECT_DIR/dist/Inhouse-battery-check.apk.idsig"
"$BT/apksigner" sign --ks "$INHOUSE_SIGNING_DIR/release.jks" --ks-key-alias inhouse \
  --ks-pass "file:$INHOUSE_SIGNING_DIR/password" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
  --out "$PROJECT_DIR/dist/Inhouse-battery-check.apk" "$BUILD_DIR/aligned.apk"
"$BT/apksigner" verify --verbose --print-certs "$PROJECT_DIR/dist/Inhouse-battery-check.apk" > "$BUILD_DIR/signature-verification.txt"
"$BT/zipalign" -c -P 16 4 "$PROJECT_DIR/dist/Inhouse-battery-check.apk"
"$BT/aapt2" dump badging "$PROJECT_DIR/dist/Inhouse-battery-check.apk" > "$BUILD_DIR/manifest-verification.txt"
(cd "$PROJECT_DIR/dist" && sha256sum Inhouse-battery-check.apk > SHA256SUMS.txt)
echo "APK compilada y firma verificada: $PROJECT_DIR/dist/Inhouse-battery-check.apk"
