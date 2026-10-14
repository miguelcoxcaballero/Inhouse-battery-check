# Inhouse battery check

Aplicación Android local para investigar descarga y calentamiento mediante observaciones reales, pruebas comparables y explicaciones de sus límites. Interfaz en español de España. Paquete: `es.inhouse.batterycheck`; Android 10 o posterior, compilada para Android 16.

**Versión 1.0.1:** [descargar Inhouse-battery-check.apk](https://github.com/miguelcoxcaballero/Inhouse-battery-check/releases/latest/download/Inhouse-battery-check.apk) · [última Release y archivos](https://github.com/miguelcoxcaballero/Inhouse-battery-check/releases/latest). Instala la APK en el móvil; no requiere compilar el proyecto para utilizarla.

## Empezar

1. Instala la APK y abre el asistente. Los permisos de notificaciones, uso de apps y estado del teléfono son opcionales y se explican por separado.
2. Activa el registro. La notificación permite pausarlo o detenerlo. No se mantiene el procesador despierto.
3. En **Pruebas**, registra 60 minutos con el móvil bloqueado y sin cargador. Repite cambiando una sola condición para evaluar una intervención.
4. Consulta **Resumen**, **Registro** y **Apps**. Las hipótesis distinguen evidencia, confianza y acción. El tiempo de uso no se convierte en consumo energético.

[Guía de instalación y uso](docs/GUIA.md) · [Estadísticas avanzadas con ADB](docs/ADB.md) · [Compilar y verificar](BUILD.md) · [Investigación y fuentes](docs/INVESTIGACION.md) · [Interpretar el CSV](docs/DATOS.md) · [Verificación de la entrega](docs/VALIDACION.md)

## Qué ofrece esta versión

| Función | Disponible y límite |
| --- | --- |
| Nivel, descarga y recargas | Muestras reales; puntos porcentuales/hora, sesiones y recargas parciales separadas. Excluye huecos y saltos de reloj. |
| Historial | Desde la activación del registro; franjas horarias y días locales. No reconstruye batería histórica anterior. |
| Pantalla y reposo | Compara pantalla encendida/apagada. Pantalla apagada no demuestra suspensión profunda ni identifica wakelocks. |
| Temperatura | Batería y estado térmico cualitativo de Android. No presume sensores de CPU/GPU. |
| Red | Wifi/datos y, con permiso y soporte, tecnología de datos/señal móvil. 5G NSA y caché del módem tienen límites explícitos. |
| Ranking de apps | Nombre/icono cuando Android los permite; primer plano y tráfico por UID cuando existe acceso. Es un ranking de uso, no energía. |
| Energía por app y componentes | Importa una captura `adb shell dumpsys batterystats`, con mAh estimados por Android y denominador explícito. Su periodo es independiente de Hoy/Ayer/sesión. No suma componentes globales y UID. |
| Actividad en segundo plano | El informe ADB puede contener radio activa, tráfico o bloqueos agregados reconocibles. No hay detector universal de apps culpables. |
| Salud | Sondeo opcional de SOH del sistema, si el firmware lo permite; dato manual separado; no disponible cuando corresponde. No hay estimación propia de capacidad en esta versión. |
| Pruebas y exportación | Guarda condiciones y resultados, compara pruebas con advertencias y exporta CSV/informe por el selector Android. |

Cada usuario puede introducir localmente un porcentaje de salud, identificado como **manual y no verificado**, o dejarlo sin completar. La APK no incluye antecedentes personales ni un porcentaje de salud precargado. No presenta GOOD como porcentaje, no asigna energía a partir de minutos de uso y no promete reparar daños físicos.

## Privacidad y funcionamiento

Datos locales; sin permiso de Internet, cuenta, anuncios ni API de IA. No solicita GPS, lee mensajes ni registra identificadores de antenas. Exportar o compartir requiere una acción explícita. El historial puede borrarse dentro de la app. Un volcado ADB puede contener paquetes y actividad: revísalo antes de compartirlo.

El servicio recoge eventos y muestras aproximadamente cada cinco minutos cuando Android permite ejecutar el proceso. No usa wake locks ni alarmas exactas. Doze, reinicios y límites de Samsung pueden dejar huecos; se señalan y no se interpretan como cero. Tras reiniciar el teléfono hay que reanudar manualmente.

## Entrega y verificación

El código fuente incluye pruebas JVM y scripts para compilar, alinear y firmar. Ejecuta `./scripts/test.sh` y sigue [BUILD.md](BUILD.md) si deseas comprobar o reproducir la compilación. La clave privada de firma no se incluye ni debe publicarse.

No se ha validado en un Galaxy S24 Ultra físico. El registro de verificación de la entrega detalla qué compilación, firma, manifiesto y flujos pudieron comprobarse; las pruebas JVM no sustituyen la validación de permisos, sensores, formato Samsung y reposo en ese teléfono.

La sección **Assets** de la Release contiene la APK firmada, el código fuente, la documentación y las sumas SHA-256 como archivos adjuntos. `main` contiene el proyecto fuente; la rama `downloads-v1.0.1` conserva una copia de la distribución. La versión 1.0.1 conserva el paquete y la identidad de firma de la entrega anterior para permitir su actualización. Las claves privadas y los datos personales quedan fuera del repositorio y de los archivos publicados.
