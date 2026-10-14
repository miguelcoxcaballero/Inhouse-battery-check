#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v keytool >/dev/null || { echo "Falta keytool; instala un JDK o configura JAVA_HOME." >&2; exit 1; }
if [[ $# != 1 ]]; then
  echo "Uso: $0 /ruta/privada/fuera/del/proyecto" >&2
  exit 1
fi
SIGNING_DIR=$(realpath -m "$1")
PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
case "$SIGNING_DIR/" in "$PROJECT_DIR/"*) echo "La clave debe estar fuera del proyecto." >&2; exit 1;; esac
if [[ -e "$SIGNING_DIR/release.jks" || -e "$SIGNING_DIR/password" ]]; then
  echo "Ya existe una clave o contraseña en ese directorio; no se sobrescribirá." >&2
  exit 1
fi
umask 077
mkdir -p "$SIGNING_DIR"
python3 -c 'import secrets; print(secrets.token_urlsafe(36))' > "$SIGNING_DIR/password"
keytool -genkeypair -keystore "$SIGNING_DIR/release.jks" -storetype JKS \
  -storepass:file "$SIGNING_DIR/password" -keypass:file "$SIGNING_DIR/password" \
  -alias inhouse -keyalg RSA -keysize 3072 -validity 10000 \
  -dname 'CN=Inhouse Battery Check, O=Independent local build, C=ES'
echo "Clave creada fuera del proyecto. Consérvala de forma privada para firmar futuras actualizaciones."
