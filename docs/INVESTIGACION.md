# Investigación técnica y límites de compatibilidad

Fecha de consulta: **14 de octubre de 2026**. Objetivo técnico: Samsung Galaxy S24 Ultra SM-S928B con Android 16. La compatibilidad con cada versión de One UI requiere validación. Esta investigación documental no equivale a una prueba en un Samsung físico; no se ha analizado un log original de ese modelo.

## Decisión para esta primera versión

Implementación propia, sin reutilizar código ni recursos de los cuatro proyectos estudiados. Se utiliza Android nativo, almacenamiento local y capacidades opcionales. La monitorización básica funciona sin ADB, sin Shizuku y sin root. Para datos avanzados se elige **importar un volcado generado por ADB**, mediante el selector de archivos de Android. La app reconoce de forma conservadora el formato de energía de AOSP y comunica qué campos y periodo ha podido interpretar. No se ejecuta `dumpsys` desde el proceso de la app ni se conceden permisos privilegiados a Inhouse.

Se evita depender de interfaces Binder ocultas, desactivar la lista de APIs ocultas, obtener root o instalar un servicio privilegiado permanente. Que el comando funcione desde ADB no garantiza que todos los campos estén presentes en Samsung: el resultado debe examinarse y los campos desconocidos deben permanecer como no disponibles. Se documenta debajo la alternativa de permisos de desarrollo investigada, que no se implementa ni es necesaria para esta versión.

## Proyectos solicitados: estado observado

Las versiones se comprobaron en la API de GitHub, sus páginas de Releases y los README; no se deducen de artículos de terceros.

| Proyecto | Estado / versión observada | Licencia y decisión |
| --- | --- | --- |
| [BBS Reloaded](https://github.com/asksven/bbs_reloaded-releases) | Última publicación: [v1.0.0-beta7](https://github.com/asksven/bbs_reloaded-releases/releases/tag/v1.0.0-beta7), 16-04-2026, prerelease. README con introducción Android 13+ pero requisitos actuales Android 14+. | README: «BBS Reloaded is closed-source software». Repositorio de APKs, sin código ni licencia de reutilización. No se incorpora ni se descompila. |
| [BetterBatteryStats clásico](https://github.com/asksven/BetterBatteryStats) | Archivado. Sin GitHub Releases publicadas; `app/build.gradle` de la rama principal declara 3.4.0, código 108. Último commit de la rama: `ccb0904791ab20e35a187dbd2a4cf53643dcba19`, 08-05-2023; metadato `pushed_at` 05-01-2024. Los tags que devuelve primero GitHub no son una lista cronológica fiable de versiones actuales. | README declara Apache 2.0 para código y excluye nombres e icono/artwork. No se reutilizan. Su compatibilidad histórica no demuestra funcionamiento en Android 16. |
| [ABattery](https://github.com/abanana84/abattery) | [v1.1.5](https://github.com/abanana84/abattery/releases/tag/v1.1.5), 14-08-2026. Release anuncia target Android 16/API 36. | [Apache 2.0](https://github.com/abanana84/abattery/blob/main/LICENSE). Se estudió su jerarquía de datos, sin copiar código. Algunos fallbacks normalizan una sola lectura de carga y otros terminan en capacidad de diseño: no son prueba suficiente de salud y no se trasladan. |
| [Beam](https://github.com/montafra/beam) | [v2.0](https://github.com/montafra/beam/releases/tag/v2.0), 07-09-2026. | [MIT](https://github.com/montafra/beam/blob/master/LICENSE); fuentes de tipografía con licencias separadas. No se incorporan código, fuentes ni artwork. Su README advierte inconsistencias de escala de corriente en algunos Samsung: no se corrigen unidades adivinando. |

[BBS Reloaded README](https://github.com/asksven/bbs_reloaded-releases/blob/main/README.md) publica comandos de concesión de `BATTERY_STATS`, `DUMP`, `PACKAGE_USAGE_STATS` e `INTERACT_ACROSS_USERS`. Esto es evidencia sobre esa aplicación, no una autorización para copiarla ni garantía de que toda app necesite los cuatro. Inhouse no necesita acceso entre usuarios para su ámbito principal.

[ABattery: Android battery data](https://github.com/abanana84/abattery/blob/main/docs/android-battery-data.md) explica límites de OEM, unidades y procedencia. Revisar una implementación de terceros ayuda a detectar trampas; no convierte sus inferencias en mediciones verificadas.

## Android 16: qué permiten realmente ADB y Shizuku

Se consultó **AOSP `android-16.0.0_r1`**, no únicamente instrucciones antiguas:

- [AndroidManifest del framework](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/res/AndroidManifest.xml): `BATTERY_STATS` y `DUMP` mantienen `signature|privileged|development`. El indicador `development` permite concesión mediante `pm grant` si el permiso está declarado. `PACKAGE_USAGE_STATS` incluye `development|appop` además de otros niveles.
- [BatteryStatsService](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/am/BatteryStatsService.java): `dump()` llama a `checkDumpAndUsageStatsPermission`. No basta declarar DUMP; la app debe tener acceso efectivo.
- [DumpUtils](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/com/android/internal/util/DumpUtils.java): el proceso normal necesita el permiso `PACKAGE_USAGE_STATS` y el AppOp de uso permitido/default. UID shell tiene tratamiento especial. **Un volcado que funciona en `adb shell` no demuestra que funcione desde el UID de la app**; por eso esta versión recibe un archivo explícito del usuario en vez de presumir acceso al servicio desde su UID.
- [Documentación Android sobre Batterystats](https://developer.android.com/topic/performance/power/setup-battery-historian): los informes sirven para investigar consumo, pero no constituyen una medición física independiente de energía por componente.
- [ADB oficial](https://developer.android.com/tools/adb): habilitar opciones de desarrollador, depuración USB y autorizar el ordenador en el teléfono. No hace falta root.

**Flujo implementado:** instalar ADB en el ordenador, habilitar depuración USB y autorizar ese ordenador. Ejecutar:

```text
adb devices
adb shell dumpsys batterystats > batterystats.txt
```

La redirección guarda `batterystats.txt` en el ordenador. Transferirlo al móvil y elegirlo en **Apps → Importar estadísticas de batería**. La app analiza el formato y el periodo; un formato OEM desconocido se muestra como no reconocido. Las cifras importadas no cambian al seleccionar Hoy/Ayer y se separan del registro local. No se importan archivos automáticamente ni se suben a servidores.

El nombre del paquete no es necesario para este comando. **No hay que conceder DUMP ni BATTERY_STATS a Inhouse.** Esta versión no declara esos permisos. Los comandos `pm grant` de BBS Reloaded o de otras aplicaciones no deben copiarse cambiando sólo el paquete. La alternativa de desarrollo investigada exigiría declarar los permisos y probarlos dentro del UID de la app; no está implementada.

Para una investigación manual adicional, `adb shell dumpsys battery > battery.txt` permite ver qué publica el firmware Samsung sin abrir `*#9900#`. Esta APK no interpreta automáticamente campos ASOC de ese archivo; no promete que haya una cifra ni que pueda usarse como salud.

Los archivos `dumpsys` pueden contener nombres de apps y actividad: se procesan localmente y no se suben automáticamente. No ejecutar `dumpsys batterystats --reset`: destruiría el periodo de referencia. Tampoco usar los comandos de simulación `dumpsys battery set`, `unplug` o `reset` para obtener una medición real. No se usa `INTERACT_ACROSS_USERS` ni se modifica `hidden_api_policy`.

La depuración USB puede desactivarse después de transferir el archivo. El archivo representa una captura estática; para disponer de datos avanzados actualizados hay que generar e importar otra. Esta decisión reduce permisos y dependencias, con el coste explícito de no ofrecer seguimiento energético automático por UID en esta primera versión.

**Shizuku se investigó y no se integra en esta versión.** Su última release consultada es [v13.6.0](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0), 25-05-2025. Su [documentación oficial de API](https://github.com/RikkaApps/Shizuku-API) indica que permite ejecutar operaciones con identidad shell/root; sin root debe reiniciarse manualmente tras cada arranque y en Android 11+ puede arrancar mediante depuración inalámbrica. El servidor desconectado, permisos revocados y los límites SELinux continúan siendo relevantes. No otorga por sí mismo un porcentaje Samsung ni acceso universal a sysfs. ADB evita introducir esa dependencia y su ciclo de reconexión para esta primera versión. No se ha verificado Shizuku en un S24 Ultra físico.

## Salud: porcentaje, estado y ASOC no son lo mismo

### Fuentes Android

1. `ACTION_BATTERY_CHANGED` / `EXTRA_HEALTH` devuelve estados como GOOD, OVERHEAT, COLD o UNKNOWN. **GOOD no significa 100 %**. Nivel actual (`BATTERY_PROPERTY_CAPACITY`) tampoco es salud.
2. `BATTERY_PROPERTY_CHARGE_COUNTER` expresa carga restante en microamperios-hora; `CURRENT_NOW` corriente instantánea en microamperios. Su ausencia, sentinelas, errores y discrepancias OEM deben conservarse como desconocidos; una lectura instantánea no es una prueba de capacidad completa.
3. [BatteryManager de AOSP 16](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/os/BatteryManager.java) define `BATTERY_PROPERTY_STATE_OF_HEALTH = 10` con `@FlaggedApi(FLAG_STATE_OF_HEALTH_PUBLIC)`. La definición literal es la capacidad completa estimada restante dividida por la capacidad nominal, expresada en porcentaje.
4. [BatteryService de AOSP 16](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/BatteryService.java) exime `BATTERY_STATS` cuando la bandera `stateOfHealthPublic()` está habilitada. En caso contrario lo exige. La presencia en código fuente no significa que todos los SDK/OEM lo expongan públicamente: la [referencia pública BatteryManager](https://developer.android.com/reference/android/os/BatteryManager) descargada en esta investigación no listaba esa constante. Además, se inspeccionó el `android.jar` del SDK público API 36 realmente instalado con `javap`: expone EXTRA_HEALTH y EXTRA_CYCLE_COUNT, pero no BATTERY_PROPERTY_STATE_OF_HEALTH.
5. [BatteryHealthData AIDL](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/android16-release/health/aidl/android/hardware/health/BatteryHealthData.aidl) fija rango 0–100 y especifica 0 cuando el estado es UNKNOWN. Para evitar convertir un sentinel en «batería muerta», esta versión sólo presenta 1–100 cuando la lectura es accesible. Un 0 se trata como no disponible.

La lectura opcional del identificador 10 es, por tanto, un **sondeo de compatibilidad documentado en AOSP**, protegido por versión y captura de excepciones. No se solicita BATTERY_STATS; si el firmware lo exige, el dato queda no disponible. Si devuelve un valor plausible se identifica como «SOH informado por Android», con la advertencia de que es una estimación del sistema y no una calibración de laboratorio. Si falla, el resultado es «No disponible». No se extrapola un 100 %.

### Samsung S24 Ultra y ASOC

No se ha encontrado ni verificado una especificación pública Samsung que garantice una API de porcentaje ASOC utilizable por una APK normal en **SM-S928B + Android 16 + cualquier versión de One UI**. No se dispone del volcado original ni de un dispositivo Samsung para verificar el significado y las unidades de sus campos. Nombres como `mSavedBatteryAsoc` o nodos `batt_asoc` citados en foros y herramientas no bastan: el significado, persistencia y acceso dependen del firmware.

Se intentaron consultar las páginas oficiales Samsung de diagnóstico de batería (`https://www.samsung.com/uk/support/galaxy-battery/diagnostics/` y variante US); devolvieron HTTP 403 en este entorno y no se presentan como contenido leído. La referencia oficial accesible [Knox DeviceInventory](https://docs.samsungknox.com/devref/knox-sdk/reference/com/samsung/android/knox/deviceinfo/DeviceInventory.html) no documenta una función de salud/ASOC aplicable al caso; consultar esa clase no demuestra ausencia absoluta en todos los SDK Samsung. No se incorpora Knox ni se piden privilegios empresariales.

Conclusión práctica: probar el SOH de Android y, opcionalmente, el volcado de Samsung. **Un campo OEM crudo debe mostrarse como dato técnico sin interpretar hasta verificar su semántica**, no como porcentaje de salud confirmado. No se exige `*#9900#` para utilizar la app. Cada usuario puede introducir un porcentaje localmente, marcado como **manual/no verificado** y separado de las lecturas del sistema. No se precarga un porcentaje ni se utiliza el dato manual para fabricar mediciones.

La estimación propia por carga sólo tendría sentido con varias sesiones amplias, carga contada fiable, control de huecos y temperatura, y una capacidad nominal documentada. La razón `carga restante / porcentaje actual` a partir de una sola lectura puede engañar. Esta primera versión puede mantener «estimación propia no disponible»; no debe publicar un valor preciso sin ese conjunto de mediciones. Una futura estimación debería exigir al menos varias sesiones independientes de decenas de puntos, medir dispersión, rechazar saltos y explicar la incertidumbre y el denominador nominal.

## Consumo de apps y componentes

`UsageStatsManager` mide uso/tiempo visible, no energía. El ranking básico debe decir **uso**, con periodo exacto, sin repartir el drenaje total según minutos abiertos. Los tiempos en primer plano tampoco prueban actividad culpable en segundo plano.

Los datos `dumpsys batterystats` tienen su propio intervalo (desde el reinicio/reset que el sistema indique); no se deben renombrar «hoy» ni «sesión actual» si no se tiene una referencia compatible. Para obtener diferencias fiables hacen falta dos capturas de un contador acumulativo del mismo periodo base y mismas UIDs. Una carga, reset, reinicio, desaparición de UID o cambio de base invalida diferencias que no puedan justificarse.

[BatteryUsageStats AOSP16](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/os/BatteryUsageStats.java) imprime `Estimated power use (mAh)`, `Capacity`, `Computed drain` y UIDs. Son estimaciones contables del sistema (algunas fuentes internas pueden apoyarse en contadores medidos); la etiqueta general no debe convertirse en una medición física exacta. `Capacity` aquí no prueba salud.

El porcentaje de una UID respecto a `Computed drain` debe decir ese denominador. Varias apps pueden compartir UID y Android puede distribuir consumo de servicios compartidos. Los componentes globales (pantalla, wifi, radio, CPU, etc.) **se solapan** con su atribución a las apps; no se suman ambos bloques. Un formato OEM no reconocido produce «no disponible», no una tabla vacía fingida como cero.

Radio activa, wakelocks y bytes pueden ser pistas para una prueba, pero no son equivalentes a mAh. El sistema puede agregar, limitar o omitirlos. Conviene guardar origen y periodo y no inventar comparaciones históricas cuando sólo hay una captura.

## Red, cobertura y temperatura

- [TelephonyManager.getDataNetworkType](https://developer.android.com/reference/android/telephony/TelephonyManager#getDataNetworkType()) devuelve la tecnología de **datos**, no la de voz. Requiere READ_PHONE_STATE, READ_BASIC_PHONE_STATE en versiones compatibles, o privilegios de operador. Se usan permisos opcionales y se gestiona su revocación.
- [getSignalStrength](https://developer.android.com/reference/android/telephony/TelephonyManager#getSignalStrength()) devuelve la información reciente **en caché**, posiblemente vieja por ahorro del módem; la referencia actual no le impone permiso de ubicación. `getAllCellInfo` sí requiere ubicación precisa y expone identificadores innecesarios para esta app, por lo que se evita.
- [ServiceState.getNetworkRegistrationInfoList](https://developer.android.com/reference/android/telephony/ServiceState#getNetworkRegistrationInfoList()) es pública desde API 30. Se puede distinguir el registro de **datos** mediante DOMAIN_PS y transporte WWAN; una lista vacía significa desconocido, no ausencia de servicio. En API 34+ `isNetworkRegistered()` evita las modificaciones por configuración del operador de `isRegistered()`. [ServiceStateListener](https://developer.android.com/reference/android/telephony/TelephonyCallback.ServiceStateListener) documenta que Android redacta datos de localización si no hay esos permisos; no se consultan identidades de celda ni nombres del operador. `ServiceState.getState()` no se usa para inferir registro de datos a partir de voz.
- LTE puede ser el ancla de una conexión 5G NSA. `TelephonyDisplayInfo` ofrece indicios/override de presentación cuando están disponibles; no demuestra todos los estados NR ni que NR transporte tráfico en ese instante. Los dBm deben etiquetarse con la tecnología de la señal, sin llamar «señal 5G» a una medición LTE.
- La conectividad wifi/datos no demuestra qué radio consume toda la energía. En wifi el módem móvil puede continuar conectado. VPN, doble SIM, itinerancia y agregación añaden incertidumbre.
- Los intervalos de mala cobertura sólo permiten **correlaciones** con descarga y temperatura. No existe una fórmula válida que convierta −115 dBm en un porcentaje exacto de gasto de «la antena».
- `EXTRA_TEMPERATURE` es temperatura de **batería** en décimas de grado; no es CPU/GPU. [PowerManager.getCurrentThermalStatus](https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus()) ofrece un estado térmico cualitativo del sistema, no grados de CPU.

## Seguimiento y validación en el dispositivo

El servicio visible debe iniciarse por acción del usuario, explicar su notificación y permitir pausa/parada. Las muestras periódicas no usan wake lock ni alarma exacta: durante Doze pueden retrasarse. Esto reduce el impacto de la medición, pero obliga a marcar los huecos y descartar extrapolaciones sin respaldo. Un reinicio o parada del proceso rompe continuidad y no se oculta como consumo cero.

Verificar en el S24 Ultra: conexión ADB autorizada, archivo exportado por shell, salida/periodo/UIDs reconocidos al importar, SOH disponible o no, temperatura plausible, signo/unidades de corriente, señal con cada SIM, LTE/5G según firmware y continuidad con pantalla apagada. Contrastar siempre una prueba de reposo sin carga con otra de condiciones equivalentes antes de atribuir una causa.

Lo verificado documentalmente aquí: versiones y licencias de los cuatro proyectos; definición de permisos en AOSP16; comprobaciones de acceso a dumpsys; semántica/flag del SOH; límites de las APIs de datos móviles y temperatura; requisitos de ciclo de vida de Shizuku. Las pruebas de compilación, firma, manifiesto, cálculos y emulador se documentan aparte con sus resultados reales. No se declara ninguna prueba en Samsung físico.
