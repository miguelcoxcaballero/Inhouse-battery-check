# Cómo interpretar el CSV y los periodos

El CSV contiene muestras reales recogidas al activar el servicio. No incluye filas sintéticas ni rellena huecos. Una celda vacía indica dato no disponible, salvo `screen_on=-1` (estado interactivo desconocido) y, en versiones de Android sin información de cargador, `plugged=-2147483648` (desconocido).

| Campo | Significado |
| --- | --- |
| `epoch_ms`, `local_time`, `zone` | Instante UTC, fecha ISO local con desplazamiento y zona de la muestra. |
| `elapsed_ms`, `boot`, `continuity` | Reloj monotónico desde arranque, contador de arranque si existe y continuidad de esta activación del servicio. Permiten detectar saltos del reloj y reinicios. |
| `reason` | Evento que ocasionó la muestra; START/RESUME, PERIODIC, BATTERY, SCREEN_ON/OFF, POWER, NETWORK, SIGNAL, SERVICE, THERMAL, PAUSE o STOP. |
| `level_percent` | Indicador Android 0–100, redondeado a un punto; no mAh ni salud. |
| `plugged` | 0 desconectado; bits Android de conexión a cargador: AC 1, USB 2, inalámbrica 4 y dock 8 cuando exista. Estar conectado no implica que la batería esté recibiendo energía constantemente. |
| `screen_on` | Estado interactivo Android: 1 o 0; -1 desconocido. Always On Display puede clasificarse como no interactivo. No es energía atribuida al panel. |
| `battery_temp_c` | Temperatura reportada por el sensor de batería en °C. No CPU/GPU ni temperatura ambiente. |
| `thermal_status` | Estado térmico Android: 0 sin limitación; 1 leve; 2 moderado; 3 grave; 4 crítico; 5 emergencia; 6 apagado. No una temperatura. |
| `connection`, `data_technology` | Red activa y tecnología móvil de datos/indicación de Android cuando exista; nunca se usa tecnología de voz como sustituto de datos. |
| `signal_dbm`, `signal_level` | Intensidad de señal celular de la tecnología seleccionada y nivel Android 0–4. Sin servicio confirmado se conserva como texto en `data_technology`; no se fabrica un dBm. |
| `charge_uah` | Carga restante reportada en microamperios hora. No es capacidad total ni salud. Puede no estar soportada. |
| `current_ua` | Corriente instantánea cruda del sistema en microamperios, con el signo expuesto por el dispositivo. No se integra para inventar consumo por apps. |
| `voltage_mv` | Voltaje reportado en milivoltios. |

## Cálculos

La duración utiliza `elapsed_ms` dentro del mismo arranque/continuidad. Se excluyen intervalos superiores a 15 minutos, discontinuidades de reloj superiores a 60 segundos, cambios de zona, pausas/reinicios y lecturas inválidas. La pérdida de nivel se atribuye a las condiciones de la muestra anterior hasta la siguiente frontera observada. Las recargas y subidas de porcentaje cortan la sesión: nunca restan al consumo como una descarga negativa.

La división entre horas y días interpola temporalmente intervalos que cruzan una frontera; se etiqueta como reparto estimado. Los días sin muestras utilizables no entran como cero en el promedio diario. Los grupos de red, pantalla y señal se solapan y no se suman.

La tasa reciente exige al menos 30 minutos continuos y 2 puntos de pérdida. La autonomía equivalente de una sesión requiere 30 minutos y 5 puntos; es una extrapolación, no una sesión completa medida desde 100 %. El indicador por puntos introduce ruido, sobre todo en periodos cortos. Un hueco permite conocer sus extremos, pero no demuestra que no hubiera cargas o cambios durante él.

El registro de uso de aplicaciones lo conserva Android y puede ser anterior a la instalación; se presenta aparte del registro de batería. El informe energético ADB tiene el periodo que declara el propio sistema. No se transforma ese total en energía diaria o por sesión local.
