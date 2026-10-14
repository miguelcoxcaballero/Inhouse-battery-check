# Datos avanzados mediante ADB

La app funciona sin ordenador: registra porcentaje, temperatura de batería, conexiones y periodos observados. El acceso a datos de uso añade **tiempo en primer plano y tráfico**, no energía por aplicación. Para una estimación de energía por UID se importa la salida del servicio de estadísticas de Android.

## Decisión técnica para esta versión

Se utiliza **ADB ejecutado en el ordenador + importación del archivo con el selector de Android**. No se ejecuta `dumpsys` como una app normal ni se da por hecho que un antiguo `pm grant` sea suficiente. Android 16 verifica los permisos y app-ops del proceso que consulta el servicio. El proceso shell de ADB es el que realiza la consulta; la app sólo analiza el archivo que has seleccionado.

Shizuku permite ejecutar servicios con identidad shell sin root, pero requiere instalarlo, emparejar depuración inalámbrica y gestionar permisos, desconexiones y reactivación después de reinicios. No se incorpora su SDK en esta primera versión: no existe un botón que prometa conectarlo sin implementación. ADB por USB ofrece un procedimiento explícito comprobable y no condiciona la monitorización normal a una conexión privilegiada persistente.

## Preparación

1. Descarga **SDK Platform Tools** de Google: <https://developer.android.com/tools/releases/platform-tools>. Extrae el archivo en tu ordenador. No hace falta instalar Android Studio.
2. En el Samsung: Ajustes → Acerca del teléfono → Información de software → pulsa siete veces «Número de compilación». En Opciones de desarrollador activa **Depuración USB**. Los nombres pueden variar en One UI.
3. Conecta el teléfono con un cable de datos, desbloquéalo y acepta la huella de tu ordenador. Utiliza sólo un ordenador de confianza. Si el dispositivo aparece como `unauthorized`, falta esa confirmación. Si hay varios dispositivos, desconecta los demás o añade `-s NUMERO_DE_SERIE` a cada comando.
4. Abre un terminal en la carpeta `platform-tools`. Los comandos siguientes sólo consultan y copian datos. **No restablecen las estadísticas ni simulan estados de batería.**

## Linux o macOS

En la carpeta extraída:

```sh
./adb devices
./adb shell getprop ro.product.model
./adb shell getprop ro.build.version.release
./adb shell getprop ro.build.version.sdk
./adb shell dumpsys batterystats > inhouse-batterystats.txt
./adb shell dumpsys battery > inhouse-battery.txt
./adb push inhouse-batterystats.txt /sdcard/Download/inhouse-batterystats.txt
./adb push inhouse-battery.txt /sdcard/Download/inhouse-battery.txt
```

## Windows PowerShell

Abre PowerShell en la carpeta extraída. `Out-File -Encoding utf8` evita la salida UTF-16 que producen algunas versiones de PowerShell al usar `>`.

```powershell
.\adb.exe devices
.\adb.exe shell getprop ro.product.model
.\adb.exe shell getprop ro.build.version.release
.\adb.exe shell getprop ro.build.version.sdk
.\adb.exe shell dumpsys batterystats | Out-File -Encoding utf8 inhouse-batterystats.txt
.\adb.exe shell dumpsys battery | Out-File -Encoding utf8 inhouse-battery.txt
.\adb.exe push inhouse-batterystats.txt /sdcard/Download/inhouse-batterystats.txt
.\adb.exe push inhouse-battery.txt /sdcard/Download/inhouse-battery.txt
```

Si has añadido `platform-tools` al PATH, puedes usar `adb` en lugar de `./adb` o `.\adb.exe`.

## Importación y comprobación

En la app, abre los datos avanzados, importa el archivo de **batterystats** y selecciona Descargas → `inhouse-batterystats.txt`. El informe debe reconocer estas cabeceras:

```text
Statistics since last charge:
  Estimated power use (mAh):
    Capacity: ..., Computed drain: ...
```

La comprobación muestra el periodo declarado por el archivo, el inicio si Android lo incluye, el denominador **Computed drain**, y las filas UID/componentes que se hayan podido interpretar. El analizador rechaza archivos vacíos, formatos no reconocidos, varios bloques de energía mezclados y UID duplicados. Si Samsung modifica el formato, no inventa un ranking; la monitorización local sigue disponible. La aceptación del parser confirma el **formato**, no la precisión del modelo de Samsung.

Comprueba que el modelo del comando coincide con el teléfono que estás investigando y que el archivo procede de ese mismo dispositivo. Un volcado no prueba por sí solo la identidad del dispositivo: no importes el de otro móvil. La fecha de importación no es el inicio del consumo. Genera una nueva exportación para actualizarlo; las cifras importadas no se actualizan solas.

`Statistics since last charge` se presenta como el periodo definido por Android y sus reinicios de estadísticas. No se convierte automáticamente en «hoy», «últimas 24 h», ni «sesión medida desde el 100 %». Una recarga parcial o la política de reinicio del fabricante puede afectar a ese periodo.

Cuando se muestre el archivo de **battery**, cualquier campo específico del fabricante sin semántica y compatibilidad verificadas debe considerarse **texto informativo sin interpretar**. El valor `Capacity` del informe de consumo es una capacidad usada por el modelo: no prueba salud. Tampoco `health: 2` / `GOOD` significa 100 %. Esta versión no asegura extraer un ASOC Samsung válido. Un porcentaje introducido localmente por cada usuario se muestra por separado como dato manual/no verificado.

## Cómo leer el ranking

- **UID** es una identidad del sistema. Puede agrupar varios paquetes, servicios o un perfil de trabajo. La app muestra los nombres e iconos que Android le deja consultar; si no puede identificarlos conserva el UID.
- **mAh** son estimaciones del sistema. Los porcentajes usan `Computed drain` como denominador, no el porcentaje actual de batería ni la suma del ranking visible.
- **Componentes globales** (pantalla, CPU, radio…) y **UID** son vistas solapadas. No se suman. La columna `apps:` de un componente ya forma parte de su total.
- Los subtotales por pantalla/carga y por estado de proceso no se vuelven a añadir. Un resultado cuyo total no cierre perfectamente puede reflejar redondeos, consumo no atribuido o límites del modelo.
- Los tiempos de radio, red y bloqueos de suspensión se muestran sólo si el bloque del UID los expone; son indicadores del sistema. La duración de un bloqueo no equivale a mAh ni demuestra que haya causado toda la descarga.
- Un ranking único señala candidatos para pruebas; no identifica automáticamente culpables. Compara periodos con carga, señal, pantalla y actividad semejantes.

Para terminar, desconecta el cable. Puedes desactivar Depuración USB y revocar las autorizaciones de depuración en Ajustes. La app conserva su monitorización normal sin ADB.

## Validación y límites

El formato se contrastó con el código oficial `android16-release` de AOSP (`BatteryUsageStats.dump`, `UidBatteryConsumer.dump`, `BatteryStats.dump`, `UserHandle.formatUid`). Las pruebas del parser usan ejemplos **sintéticos** para verificar selección de bloques, UID de varios usuarios, estados superpuestos, formato de Windows, rechazo de entradas ambiguas y denominador cero; no son lecturas de un Samsung real. La disponibilidad y calidad de la salida de Samsung/One UI requieren comprobarse en cada dispositivo.

Fuentes:

- ADB oficial: <https://developer.android.com/tools/adb>
- `BatteryUsageStats`: <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/os/BatteryUsageStats.java>
- `UidBatteryConsumer`: <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/os/UidBatteryConsumer.java>
- `BatteryStats`: <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/os/BatteryStats.java>
- `UserHandle`: <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/os/UserHandle.java>
- `NetworkStatsManager`: <https://developer.android.com/reference/android/app/usage/NetworkStatsManager>
- `UsageStatsManager`: <https://developer.android.com/reference/android/app/usage/UsageStatsManager>
- Shizuku: <https://shizuku.rikka.app/guide/setup/>
