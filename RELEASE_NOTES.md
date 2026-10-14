Primera versión distribuible de **Inhouse battery check** para Android 10 o posterior, compilada para Android 16.

### Descarga e instalación

Descarga [**Inhouse-battery-check.apk**](https://github.com/miguelcoxcaballero/Inhouse-battery-check/raw/refs/heads/downloads-v1.0.1/Inhouse-battery-check.apk) y ábrela en el móvil. La APK está firmada y no requiere compilar el código. La versión 1.0.1 conserva el paquete y certificado de la entrega 1.0.0, por lo que permite actualizarla manteniendo los datos locales.

### Funciones

- Asistente en español y permisos opcionales explicados por función.
- Registro local de batería, carga, temperatura, pantalla y conexión; señal móvil cuando Android la expone y se concede acceso.
- Sesiones de descarga, recargas parciales, estadísticas por horas y días, huecos de registro explícitos y pruebas comparativas.
- Ranking de uso separado del consumo energético; importación ADB de estimaciones de energía por UID y componentes.
- Exportación CSV e informe mediante el selector de archivos Android.

### Privacidad y límites

No incluye antecedentes personales ni una salud manual predeterminada. Cada usuario puede introducir su dato local, identificado como manual y no verificado. Sin permiso de Internet, GPS, anuncios ni servicio de IA. No se incluyen claves privadas de firma.

El desglose avanzado requiere importar un informe ADB. No hay estimación propia de capacidad en esta versión. Las lecturas disponibles dependen del firmware; no se ha validado en un Samsung físico.

### Verificación

Compilación, firma, alineación y manifiesto comprobados; 132 comprobaciones JVM superadas. Consulta el documento de verificación para distinguir las pruebas de interfaz de la versión anterior y las comprobaciones repetidas sobre 1.0.1.

SHA-256 de la APK:
`dbbfb2b687a752ea0bf7e5d51003ab756b9433e10b0800ac131b1d128d82fe10`

### Archivos de descarga

Los archivos están versionados en la rama independiente `downloads-v1.0.1` y enlazados desde esta Release. La carga de adjuntos de GitHub rechazó la autenticación; estos enlaces permiten descargar los mismos archivos completos.

- [APK firmada](https://github.com/miguelcoxcaballero/Inhouse-battery-check/raw/refs/heads/downloads-v1.0.1/Inhouse-battery-check.apk)
- [Código fuente completo](https://github.com/miguelcoxcaballero/Inhouse-battery-check/raw/refs/heads/downloads-v1.0.1/Inhouse-battery-check-source.zip)
- [Guía de instalación y ADB](https://github.com/miguelcoxcaballero/Inhouse-battery-check/blob/downloads-v1.0.1/Inhouse-battery-check-guia.md)
- [Verificación y limitaciones](https://github.com/miguelcoxcaballero/Inhouse-battery-check/blob/downloads-v1.0.1/Inhouse-battery-check-verificacion.md)
- [Sumas SHA-256](https://github.com/miguelcoxcaballero/Inhouse-battery-check/blob/downloads-v1.0.1/SHA256SUMS.txt)

Para reproducir la compilación, sigue BUILD.md del código fuente.
