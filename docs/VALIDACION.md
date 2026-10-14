# Verificación de la entrega · 14/10/2026

Versión 1.0.1, paquete `es.inhouse.batterycheck`. APK firmada y alineada: `Inhouse-battery-check.apk`.

SHA-256 de la APK entregada:

```text
dbbfb2b687a752ea0bf7e5d51003ab756b9433e10b0800ac131b1d128d82fe10
```

## Comprobaciones completadas

En la versión 1.0.1 se han repetido compilación, firma, alineación, manifiesto y las 132 comprobaciones JVM. Las pruebas de interfaz, monitor, permisos, CSV e importación descritas abajo se realizaron en la versión 1.0.0; se conserva su alcance sin atribuirles una ejecución nueva. El cambio a 1.0.1 retira los antecedentes personales y la salud manual precargada, actualiza los textos y aumenta el código de versión. No modifica los cálculos ni los permisos.

El intento de repetir el arranque de 1.0.1 no llegó a instalar la APK: ADB respondió, pero el emulador no había iniciado el servicio de paquetes (`Can't find service: package`). Por tanto, no se declara una prueba de ejecución de 1.0.1. La inspección de su DEX y código confirma que no incorpora los antecedentes personalizados ni un porcentaje manual predeterminado.

- Compilación de recursos y manifiesto con AAPT2; Java con JDK 21 y `--release 17`; DEX con D8, usando Android SDK 36 revisión 2 y Build Tools 36.0.0.
- Firma APK Signature Scheme v3, RSA de 3072 bits; verificación con `apksigner verify --verbose --print-certs` correcta. La clave privada está fuera del proyecto y no se incluye en la entrega.
- Alineación verificada con `zipalign -c -P 16 4`.
- Manifiesto: minSdk 29, targetSdk 36, Activity de inicio exportada, servicio y receptor privados, tipo foreground `specialUse`. Sin permiso de Internet, localización, wakelock ni almacenamiento general. Backup y transferencia de datos de la app excluidos.
- No hay bibliotecas nativas: APK Java/DEX compatible con las arquitecturas que admite Android; no se ha hecho una prueba física de cada modelo/API.
- **132 comprobaciones JVM superadas:** 98 del motor de análisis, 28 del parser energético y 6 de la reconstrucción del tiempo de uso. Incluyen cargas/recargas parciales, subidas de porcentaje, sesiones desde 100 %, pausas, arranques, huecos, reloj, zona horaria, medianoche/DST, desconocidos, atribución por condiciones previas y denominadores.
- Comprobación adicional del parser con una salida real de `adb shell dumpsys batterystats` de Android 16 en el emulador. Reconoce el informe y, al contener drenaje calculado de 0 mAh y ninguna fila UID, muestra advertencias y no fabrica porcentajes ni rankings energéticos.
- Instalación y arranque de la APK firmada 1.0.0 en emulador Android 16/API 36, imagen Google ATD x86_64. Se observó el renderizado real del asistente y Resumen.
- Asistente completado sin conceder permisos opcionales. Se muestra batería/temperatura del emulador y ausencia inicial de historial.
- Inicio del monitor sin `READ_PHONE_STATE` ni `POST_NOTIFICATIONS`: registro de una muestra y servicio foreground `specialUse` confirmado mediante `dumpsys activity services`, con acciones de Pausar/Detener en la notificación construida.
- Secuencia por la interfaz: activar, pausar, reanudar y detener. Se verificó después que el servicio ya no estaba activo.
- Exportación CSV mediante el selector Android: archivo guardado y recuperado con cabecera y cinco muestras del emulador. Se comprobó con un lector CSV la secuencia START, NETWORK, PAUSE, RESUME, STOP, la separación de continuidades y los campos de señal vacíos sin permiso.
- Importación por el selector Android de la captura real AOSP16: informe reconocido dentro de la app, con el periodo independiente del selector diario y advertencia de ausencia de denominador al ser 0 mAh.
- Concesión de Estado del teléfono mediante el diálogo real de permisos de Android y comprobación de «Concedido» al volver a la app. Las pruebas del monitor sin permisos precedieron a esta concesión.

Los resultados de firma, manifiesto y pruebas están en [verificacion](verificacion/). Las entradas sintéticas de las pruebas JVM sólo están en el código de pruebas; no se incluyen como historial o datos de demostración dentro de la APK.

## Alcance y límites de las pruebas

El entorno no dispone de KVM. El primer intento de arranque tardó más que el timeout de ADB y el emulador sufrió un fallo de **BluetoothManagerService** que reinició el sistema. No fue una excepción de Inhouse. Para continuar se desactivó Bluetooth sólo en el emulador y se activó el renderizado que la imagen ATD trae deshabilitado. Tras estabilizarlo se reinstaló y abrió la APK final. Estos ajustes de laboratorio no se realizan ni se necesitan como pasos de la app en el teléfono del usuario. No se extrapola su rendimiento a un S24 Ultra.

No se ha probado en un Samsung físico ni se ha observado una descarga real de varias horas. Los sensores y la batería del emulador son virtuales. Las pruebas de cálculos utilizan casos sintéticos identificados como pruebas; no demuestran exactitud del medidor de carga del fabricante.

## Validación pendiente en un Galaxy S24 Ultra

- Versión concreta de One UI; retorno de los permisos y restricciones de segundo plano de Samsung.
- Disponibilidad y significado OEM de la propiedad experimental SOH. La salud manual se introduce localmente y no equivale a una medición; no hay un valor predeterminado. No se interpreta ASOC automáticamente.
- Coherencia de temperatura, corriente y contador de carga, señal con cada SIM, LTE/5G NSA/SA y periodos sin servicio.
- Formato real Samsung del informe `dumpsys batterystats`, UID visibles y estimaciones energéticas expuestas por ese firmware.
- Continuidad con pantalla bloqueada, Doze, reinicios, permisos revocados y varios ciclos de carga. Los huecos se mantienen explícitos.
- Consumo que añade el propio monitor: su diseño no usa wakelocks ni consultas constantes, pero no se ha medido su impacto energético en hardware físico.

La energía diaria por aplicación no se reconstruye a partir de una captura ADB acumulada ni del tiempo de uso. No se ha implementado una estimación propia de salud/capacidad. Estas limitaciones se muestran dentro de la app.

Los flujos completos de monitor, CSV e importación de la versión 1.0.0 se ejecutaron antes del ajuste limitado a conservar la fecha elegida al recrear la Activity. Esa APK se recompiló y se repitió su instalación/arranque. La versión 1.0.1 mantiene el paquete y certificado de firma, por lo que puede actualizar la anterior conservando los datos locales.
