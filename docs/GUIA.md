# Guía breve · Inhouse battery check

## Instalar

1. Descarga [Inhouse-battery-check.apk](https://github.com/miguelcoxcaballero/Inhouse-battery-check/releases/latest/download/Inhouse-battery-check.apk) en el móvil desde la [Release pública](https://github.com/miguelcoxcaballero/Inhouse-battery-check/releases/latest).
2. Abre el archivo y, si Android lo solicita, permite instalar aplicaciones desde esa fuente concreta. Completa la instalación y abre **Inhouse battery check**. Puedes retirar después la autorización de instalación a esa fuente.
3. Sigue los cuatro pasos del asistente. No hace falta root ni Shizuku. Sin permisos opcionales seguirás viendo batería, temperatura, carga, pantalla y conexión básica.

Si usas un ordenador con ADB autorizado, la instalación equivalente es:

```text
adb install -r Inhouse-battery-check.apk
```

La versión 1.0.1 conserva el paquete y la firma de la entrega anterior y permite actualizarla. Un conflicto de firma con una APK compilada por ti requiere exportar tus datos y desinstalar la anterior antes de cambiar de firma. Una actualización normal necesita la misma firma. La clave privada no se incluye en el fuente.

## Permisos: qué habilitan

| Permiso o ajuste | Para qué se utiliza | Si lo rechazas |
| --- | --- | --- |
| Notificaciones | Mostrar el seguimiento activo y las acciones Pausar/Detener. | Android puede ocultar la notificación del servicio; puedes detenerlo en la app o en el gestor de aplicaciones activas. |
| Acceso a datos de uso | Consultar eventos de primer plano y tráfico por UID cuando Android lo permite. | El registro básico sigue funcionando; no habrá ranking de uso. Este acceso nunca concede energía por app. |
| Estado del teléfono | Observar tecnología de datos y señal/cambios compatibles con el dispositivo y la SIM. | La conexión wifi/datos básica sigue disponible; señal y tecnología detalladas pueden figurar como no disponibles. |

La app abre el ajuste correspondiente y comprueba el estado al volver. Los permisos pueden revocarse después. No necesita GPS, acceso a mensajes, acceso general al almacenamiento ni Internet.

**No concedas DUMP, BATTERY_STATS ni INTERACT_ACROSS_USERS a Inhouse:** esta versión no los declara ni los necesita. Los comandos de otras aplicaciones no son intercambiables. Para energía avanzada se importa un archivo generado por ADB, como se explica abajo.

## Recoger la primera sesión

1. Activa el registro en Resumen o al terminar el asistente. Comprueba que indica registro activo.
2. Desconecta el cargador. En **Pruebas → Reposo sin cargador**, anota la conexión, temperatura ambiente aproximada, funda y cualquier circunstancia especial.
3. Deja el móvil bloqueado durante unos 60 minutos. No necesitas mantener esta app abierta. Evita cambiar otras condiciones durante la prueba.
4. Vuelve, finaliza la prueba y consulta sus muestras efectivas, pérdida, horas válidas, temperatura y huecos. Si hay pocos puntos de bajada o muchos huecos, amplía o repite antes de concluir.
5. Repite una prueba comparable con una sola intervención. Para investigar cobertura, compara wifi y datos móviles manteniendo parecidas duración, pantalla, temperatura inicial y actividad.

No actives modo avión si necesitas recibir llamadas. La app no cierra aplicaciones, modifica redes, borra datos ni limita notificaciones por su cuenta.

## Entender los resultados

- **Puntos porcentuales/hora:** perder del 80 % al 77 % son 3 puntos. Esta tasa no es mAh ni un porcentaje de la energía atribuida a una app.
- **Pantalla apagada:** describe una condición observada. No prueba que el procesador haya dormido ni que no haya reproducción, sincronización o descargas.
- **Sesión desde el 100 %:** requiere una lectura real de 100 %. Una autonomía equivalente extrapolada desde una sesión parcial se identifica por separado y requiere datos mínimos.
- **Recargas:** cierran o interrumpen sesiones; no se restan como «consumo negativo».
- **Huecos:** intervalos sin medición utilizable, reinicios, pausas o cambios importantes de reloj. Se excluyen del ritmo; no significan gasto cero. Los tramos válidos de una hora pueden ser parciales.
- **Apps:** los minutos en primer plano y bytes de red sirven como pistas, pero no prueban qué app gastó más energía. El tráfico puede corresponder a un UID compartido por varios paquetes.
- **Antena:** señal baja y mayor descarga sólo establecen correlación. Wifi no garantiza que el módem móvil esté apagado. La app no convierte dBm en un porcentaje exacto de gasto.
- **Temperatura:** los grados corresponden a la batería. El estado térmico Android es cualitativo; no es temperatura de CPU/GPU.

El historial de batería empieza al activar el registro. Android puede conservar algunos eventos de uso de apps anteriores a la instalación; éstos se identifican como historial limitado del sistema y no como mediciones previas de Inhouse.

Los hallazgos incluyen periodo, evidencia, confianza y acción. Una diferencia aislada entre dos pruebas no confirma causalidad. Si sigue caliente tras bastante tiempo bloqueado y sin cargar, sigue investigando actividad y red; si hay hinchazón, olor, daños o una alerta térmica, deja de exigir/cargar el dispositivo y sigue las indicaciones del fabricante o del servicio técnico.

## Energía por aplicación con ADB

Instala [Android SDK Platform-Tools oficial](https://developer.android.com/tools/releases/platform-tools) en el ordenador. En el móvil: **Ajustes → Acerca del teléfono → Información de software → Número de compilación** (siete pulsaciones), después activa **Depuración USB** en Opciones de desarrollador. Autoriza sólo un ordenador en el que confíes.

En una terminal con ADB disponible:

```text
adb devices
adb shell dumpsys batterystats > inhouse-batterystats.txt
adb push inhouse-batterystats.txt /sdcard/Download/inhouse-batterystats.txt
```

La primera orden debe mostrar el teléfono en estado `device`. En Windows, desde la carpeta de Platform-Tools puede ser necesario escribir `adb.exe` o `.\adb.exe`. Para PowerShell y una codificación explícita:

```text
adb shell dumpsys batterystats | Out-File -Encoding utf8 inhouse-batterystats.txt
```

En **Apps → Importar estadísticas de batería**, elige ese archivo desde Descargas. La guía integrada ofrece los comandos completos y botones para copiarlos. La app comunica si reconoce el bloque de energía, el periodo que Android indica y el denominador de los porcentajes. Un formato desconocido no se sustituye por cifras inventadas.

El archivo es una **captura estática**: genera e importa otro para actualizarlo. Su periodo suele ser desde una carga o reinicio de estadísticas del sistema; **no se transforma en Hoy, Ayer o la sesión local seleccionada**. No hay ranking energético automático para cada día en esta versión. Los componentes globales se solapan con las apps y no deben sumarse a ellas.

No ejecutes `dumpsys batterystats --reset`: borraría la referencia del sistema. Puedes desactivar depuración USB después de transferir la captura. Más detalles en [ADB.md](ADB.md) y [INVESTIGACION.md](INVESTIGACION.md).

## Salud sin el menú *#9900#

En **Ajustes → Salud de batería** se muestran fuentes separadas:

- **Sistema:** sondeo experimental de la propiedad SOH conocida en AOSP. Sólo aparece un porcentaje si Android lo devuelve de forma interpretable; Samsung puede bloquearlo o no implementarlo. No hay un 100 % por defecto.
- **Manual:** puedes introducir localmente un porcentaje conocido por otra vía, editarlo o eliminarlo. Se identifica como manual/no verificado y no como lectura actual del dispositivo. Una instalación nueva no precarga ningún porcentaje.
- **Estimación propia:** no disponible en esta versión. Una lectura aislada de carga restante no permite calcular una salud precisa; harían falta varias sesiones amplias y sensores fiables.
- **Estado Android:** GOOD u otro estado cualitativo, siempre separado del porcentaje.

Para inspeccionar lo que expone Samsung sin abrir ese menú puede usarse:

```text
adb shell dumpsys battery > inhouse-battery.txt
```

Ese archivo se revisa en el ordenador; esta APK **no convierte automáticamente ASOC o campos OEM crudos en salud**, porque no se ha verificado su significado para tu firmware. Samsung Members puede ofrecer un diagnóstico cualitativo. Ninguna de esas vías garantiza un porcentaje disponible en todos los S24 Ultra.

## Pausar, reanudar y exportar

Pausa o detén desde Resumen, Ajustes o la notificación. Tras reiniciar el teléfono o si Android interrumpe el proceso, abre la app y reanuda manualmente. No hay arranque oculto del servicio tras reiniciar.

En **Ajustes → Datos y privacidad**:

- **Exportar mediciones CSV:** guarda las muestras locales completas mediante el selector Android, con horas, zona, continuidad y valores no disponibles vacíos cuando procede.
- **Exportar informe de diagnóstico:** guarda un resumen con evidencia, límites y pruebas.
- **Guardar y compartir informe:** primero elige el archivo; después selecciona tú el destinatario en la hoja de compartir.
- **Exportar informe ADB original:** conserva el texto importado para inspección.
- **Borrar historial e informes:** detiene el registro y borra mediciones, eventos, pruebas e informes importados. Conserva permisos y el dato manual de salud.

Nada se sube automáticamente. Los logs ADB pueden incluir inventario de apps y actividad; revisa qué compartes. No publiques claves de firma ni archivos personales en GitHub.

## Lo que falta validar en un S24 Ultra

Disponibilidad de SOH, formato exacto del volcado de Samsung, señal y 5G según SIM/firmware, unidades de corriente y continuidad real durante reposo. Confirma la versión de One UI en Información de software. Las comprobaciones de compilación y pruebas de cálculo no prueban esos aspectos del hardware.

La aplicación está preparada para mostrar funciones no disponibles y registro incompleto. No se ha probado en un Samsung físico; las correlaciones y las capturas parciales no bastan para confirmar una causa.

Los archivos CSV o informes que ya hayas exportado permanecen en la carpeta elegida aunque borres el historial de la app. Puedes borrarlos desde el gestor de archivos de Android. La pantalla Resumen se actualiza al entrar o al pulsar **Actualizar lectura**, sin reconstruir continuamente la interfaz mientras lees.
