# Compilar Inhouse battery check

La APK utiliza métodos del SDK Android y no depende de reflexión ni de interfaces Binder ocultas. Para la salud incluye un sondeo conservador de la propiedad numérica 10 conocida por AOSP, cuyo valor no es un contrato público garantizado en Samsung; se muestra como experimental o no disponible. No requiere Gradle, AndroidX, una API remota ni dependencias Java de terceros. El script incluye compilación de recursos, Java, DEX, alineación, firma y verificación.

## Entorno utilizado

- Linux x86_64, Eclipse Temurin OpenJDK 21.0.12.1 (`javac --release 17`).
- Android SDK Platform 36, revisión 2.
- Android SDK Build Tools 36.0.0.
- Command-line Tools 19.0, archivo oficial `commandlinetools-linux-13114758_latest.zip`.
- `bash`, `python3`, `zip`, `unzip` y `keytool`.
- Aplicación: `es.inhouse.batterycheck`, minSdk 29 (Android 10), targetSdk 36 (Android 16).

El archivo de herramientas se obtuvo de <https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip>. SHA-256 comprobado: `7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee`. La distribución oficial del SDK y sus licencias son de Google; no se incluyen en el código fuente.

## Pasos

Instala las herramientas oficiales de Android y el JDK. Después, en la raíz de este proyecto:

```bash
export ANDROID_SDK_ROOT="/ruta/a/android-sdk"
# Si el JDK no está en PATH, indica JAVA_HOME="/ruta/a/tu/jdk".
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
  'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'

# Solo una vez: crea TU propia clave, fuera del repositorio.
./scripts/create-signing-key.sh "$HOME/.config/inhouse-battery-check/signing"
export INHOUSE_SIGNING_DIR="$HOME/.config/inhouse-battery-check/signing"
./scripts/build.sh
```

El resultado es `dist/Inhouse-battery-check.apk`. Su SHA-256 se guarda en `dist/SHA256SUMS.txt`. Los resultados de `apksigner verify` y del manifiesto están en `build/signature-verification.txt` y `build/manifest-verification.txt`.

La clave de la APK entregada no se incluye en el código ni debe publicarse en GitHub. Al crear tu propia clave, obtendrás una APK funcional con otra identidad de firma. Android requiere la misma firma para actualizar una instalación existente; para cambiar de firma debes exportar tus datos y desinstalar la APK anterior. Conserva tu clave privada para tus futuras actualizaciones. La compilación es reproducible siguiendo estos pasos; no se promete identidad binaria entre compilaciones con claves o marcas de tiempo distintas.

## Inspección independiente

```bash
./scripts/test.sh
"$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" verify --verbose --print-certs \
  dist/Inhouse-battery-check.apk
"$ANDROID_SDK_ROOT/build-tools/36.0.0/zipalign" -c -P 16 4 \
  dist/Inhouse-battery-check.apk
"$ANDROID_SDK_ROOT/build-tools/36.0.0/aapt2" dump badging \
  dist/Inhouse-battery-check.apk
"$ANDROID_SDK_ROOT/platform-tools/adb" install -r dist/Inhouse-battery-check.apk
```

`scripts/test.sh` ejecuta pruebas JVM independientes del dispositivo sobre sesiones, huecos, recargas, cambios de hora y los analizadores de informes y tiempo de uso. No reemplaza las pruebas de permisos, sensores y límites en un dispositivo Samsung.

No hay bibliotecas nativas `.so`; la APK contiene Java/DEX y recursos. El servicio de monitorización declara `specialUse` para Android 14 o posterior y solo debe iniciarse mediante una acción visible del usuario. Una publicación en Google Play, que no forma parte de esta entrega, podría requerir revisión adicional de esa categoría de servicio.
