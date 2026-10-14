package es.inhouse.batterycheck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative, dependency-free reader for the human-readable AOSP Android 16 dump. Not a
 * privileged API. Input is supplied explicitly by the user through SAF. Original implementation;
 * format studied in AOSP BatteryUsageStats/UidBatteryConsumer.
 */
public final class AdvancedReport {
  private AdvancedReport() {}

  private static final String NUMBER = "([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)";
  private static final Pattern CAPACITY =
      Pattern.compile(
          "^Capacity: " + NUMBER + ", Computed drain: " + NUMBER + "(?:, actual drain: (.*))?$");
  private static final Pattern UID = Pattern.compile("^UID ([^: ]+): " + NUMBER + "(?:\\s.*)?$");
  private static final Pattern COMPONENT =
      Pattern.compile("^([^:]+): " + NUMBER + " apps: " + NUMBER + "(?: duration: (.*))?$");
  private static final Pattern FORMATTED_UID = Pattern.compile("^u([0-9]+)(ai|a|i|s)([0-9]+)$");
  private static final Pattern UID_DETAIL =
      Pattern.compile("^([0-9]+|u[0-9]+(?:ai|a|i|s)[0-9]+):$");
  public static final int MAX_CHARS = 12 * 1024 * 1024;

  public static final class Row {
    public int uid = -1;
    public int appUid = -1;
    public String label = "";
    public double mah = Double.NaN;

    /** Already included in a global component total; never add it again. */
    public double appsMah = Double.NaN;

    public String details = "";
  }

  public static final class Report {
    public boolean recognized;
    public String textSummary = "No se ha reconocido un informe de energía compatible.";
    public String sourceType = "Estimación de energía de Android importada por ADB";
    public String period = "Periodo no especificado por el archivo";
    public String startClock = "";
    public String elapsedOnBattery = "";
    public String elapsedScreenOff = "";
    public String actualDrain = "";
    public double computedDrainMah = Double.NaN;

    /** Android model capacity. This is NOT remaining battery health. */
    public double capacityMah = Double.NaN;

    public final List<Row> apps = new ArrayList<>();
    public final List<Row> components = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public double percent(double mah) {
      return finite(computedDrainMah) && computedDrainMah > 0 && finite(mah)
          ? mah / computedDrainMah * 100.0
          : Double.NaN;
    }
  }

  public static Report parse(String raw) {
    Report out = new Report();
    if (raw == null || raw.isEmpty()) return reject(out, "Archivo vacío.");
    if (raw.length() > MAX_CHARS)
      return reject(out, "Archivo demasiado grande (máximo 12 MiB de texto).");
    // Strip a possible UTF-8 BOM; SAF also normalizes UTF-16 in the Activity.
    String[] lines = raw.replace("\ufeff", "").split("\\r?\\n", -1);
    int header = -1, headers = 0, periodLine = -1;
    for (int i = 0; i < lines.length; i++) {
      String t = lines[i].trim();
      if (t.equals("Statistics since last charge:")) periodLine = i;
      if (t.equals("Estimated power use (mAh):")) {
        if (header < 0) header = i;
        headers++;
      }
    }
    if (header < 0)
      return reject(
          out,
          "Falta «Estimated power use (mAh)». Importa la salida completa de adb shell dumpsys"
              + " batterystats, sin --checkin ni --proto.");
    if (headers != 1)
      return reject(
          out,
          "El archivo contiene varios bloques de energía. No se mezclan periodos: exporta un único"
              + " dumpsys batterystats actual.");
    if (periodLine >= 0 && periodLine < header) {
      out.period =
          "Desde la última carga / reinicio de estadísticas indicado por Android; no equivale"
              + " necesariamente a hoy ni a una descarga desde el 100 %.";
      for (int i = periodLine + 1; i < header; i++) {
        String t = lines[i].trim();
        if (t.startsWith("Start clock time: ")) out.startClock = t.substring(18).trim();
        if (t.startsWith("Time on battery: ")) out.elapsedOnBattery = t.substring(17).trim();
        if (t.startsWith("Time on battery screen off: "))
          out.elapsedScreenOff = t.substring(28).trim();
      }
    } else {
      out.warnings.add(
          "No se encontró la cabecera de periodo. No se atribuye este informe a un día o sesión"
              + " local.");
    }
    int headerIndent = indent(lines[header]);
    int capacityLine = header + 1;
    while (capacityLine < lines.length && lines[capacityLine].trim().isEmpty()) capacityLine++;
    if (capacityLine >= lines.length) return reject(out, "Bloque de energía incompleto.");
    Matcher cap = CAPACITY.matcher(lines[capacityLine].trim());
    if (!cap.matches())
      return reject(
          out,
          "Formato de capacidad/consumo no reconocido; no se interpretan cifras por aproximación.");
    out.capacityMah = value(cap.group(1));
    out.computedDrainMah = value(cap.group(2));
    out.actualDrain = cap.group(3) == null ? "" : cap.group(3);
    if (!finite(out.computedDrainMah) || !finite(out.capacityMah))
      return reject(out, "Valores de energía fuera de rango.");
    boolean inGlobal = false, seenUid = false;
    Set<Integer> seen = new HashSet<>();
    Set<String> componentNames = new HashSet<>();
    int end = capacityLine + 1;
    for (; end < lines.length; end++) {
      String line = lines[end];
      String t = line.trim();
      if (t.isEmpty()) break;
      int n = indent(line);
      if (t.equals("Global")) {
        inGlobal = true;
        continue;
      }
      Matcher uid = UID.matcher(t);
      if (uid.matches() && n == headerIndent) {
        seenUid = true;
        inGlobal = false;
        int id = parseUid(uid.group(1));
        double power = value(uid.group(2));
        if (id < 0 || !finite(power)) {
          out.warnings.add("Se omitió una fila UID de formato desconocido.");
          continue;
        }
        if (!seen.add(id))
          return reject(out, "UID duplicado en el bloque de energía: informe ambiguo.");
        Row row = new Row();
        row.uid = row.appUid = id;
        row.label = "UID " + uid.group(1);
        row.mah = power;
        // Foreground/background suffixes are components of this total, not additive rows.
        int firstSpace = t.indexOf(' ', t.indexOf(':') + 2);
        if (firstSpace >= 0)
          row.details = "Estados de proceso del sistema: " + t.substring(firstSpace).trim();
        out.apps.add(row);
        continue;
      }
      Matcher component = COMPONENT.matcher(t);
      // State breakdowns are further indented. Only read top-level Global totals.
      if (component.matches() && n == headerIndent + 2 && inGlobal && !seenUid) {
        String name = component.group(1);
        if (!componentNames.add(name))
          return reject(out, "Componente global duplicado: no se pueden separar los subtotales.");
        Row row = new Row();
        row.label = name;
        row.mah = value(component.group(2));
        row.appsMah = value(component.group(3));
        if (!finite(row.mah) || !finite(row.appsMah)) continue;
        row.details = "Incluye " + fmt(row.appsMah) + " mAh atribuidos por Android a apps";
        if (component.group(4) != null) row.details += "; duración: " + component.group(4).trim();
        out.components.add(row);
      } else if (n <= headerIndent && !t.startsWith("UID ") && !t.startsWith("User ")) {
        break;
      }
    }
    // Supplemental existing system indicators are kept as text, never treated as energy.
    readUidDetails(lines, end, out);
    Collections.sort(out.apps, Comparator.comparingDouble((Row r) -> r.mah).reversed());
    Collections.sort(out.components, Comparator.comparingDouble((Row r) -> r.mah).reversed());
    out.recognized = true;
    if (out.computedDrainMah == 0)
      out.warnings.add("El consumo calculado es cero: no hay denominador válido para porcentajes.");
    if (out.apps.isEmpty())
      out.warnings.add(
          "El archivo no expone estimaciones por UID. No se sustituyen por tiempo de uso.");
    out.warnings.add(
        "UID puede agrupar varios paquetes o servicios del sistema. Identificación de apps limitada"
            + " a los paquetes visibles en este móvil.");
    out.warnings.add(
        "Apps y componentes son dos vistas solapadas del mismo consumo; no se suman. Las cifras mAh"
            + " son estimaciones del sistema, no mediciones eléctricas por app.");
    out.warnings.add(
        "Capacity describe la capacidad usada por el modelo de consumo; no se interpreta como salud"
            + " de batería.");
    out.textSummary =
        "Estimación de Android: "
            + fmt(out.computedDrainMah)
            + " mAh calculados. "
            + "Cada porcentaje usa ese total como denominador. "
            + out.period
            + (out.startClock.isEmpty() ? "" : " Inicio informado: " + out.startClock + ".")
            + (out.elapsedOnBattery.isEmpty()
                ? ""
                : " Tiempo en batería: " + out.elapsedOnBattery + ".")
            + " No permite afirmar qué aplicación causó el deterioro sin comparar pruebas"
            + " equivalentes.";
    return out;
  }

  private static void readUidDetails(String[] lines, int from, Report out) {
    Row current = null;
    for (int i = from; i < lines.length; i++) {
      String t = lines[i].trim();
      Matcher m = UID_DETAIL.matcher(t);
      if (m.matches() && indent(lines[i]) == 2) {
        current = null;
        int uid = parseUid(m.group(1));
        for (Row row : out.apps)
          if (row.uid == uid) {
            current = row;
            break;
          }
      } else if (current != null
          && indent(lines[i]) == 4
          && (t.startsWith("Mobile radio active: ")
              || t.startsWith("Mobile network: ")
              || t.startsWith("Wi-Fi network: ")
              || t.startsWith("Wifi network: ")
              || t.startsWith("TOTAL wake: ")
              || t.startsWith("Foreground services: ")
              || t.startsWith("Foreground activities: "))) {
        if (current.details.length() < 3500)
          current.details += (current.details.isEmpty() ? "" : "\n") + t;
      }
    }
  }

  public static int parseUid(String text) {
    try {
      if (text.matches("[0-9]+")) return Integer.parseInt(text);
      Matcher m = FORMATTED_UID.matcher(text);
      if (!m.matches()) return -1;
      long user = Long.parseLong(m.group(1)), app = Long.parseLong(m.group(3));
      String type = m.group(2);
      if ("a".equals(type)) app += 10000;
      else if ("i".equals(type)) app += 99000;
      else if ("ai".equals(type)) app += 90000;
      if (app > 99999 || user > 21474) return -1;
      long result = user * 100000 + app;
      return result <= Integer.MAX_VALUE ? (int) result : -1;
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private static Report reject(Report out, String reason) {
    out.recognized = false;
    out.apps.clear();
    out.components.clear();
    out.computedDrainMah = Double.NaN;
    out.capacityMah = Double.NaN;
    out.textSummary = "No se ha importado un ranking de energía: " + reason;
    out.warnings.add(reason);
    return out;
  }

  private static int indent(String line) {
    int n = 0;
    while (n < line.length() && line.charAt(n) == ' ') n++;
    return n;
  }

  private static double value(String value) {
    try {
      return Double.parseDouble(value);
    } catch (RuntimeException e) {
      return Double.NaN;
    }
  }

  private static boolean finite(double x) {
    return !Double.isNaN(x) && !Double.isInfinite(x) && x >= 0;
  }

  private static String fmt(double n) {
    return String.format(Locale.forLanguageTag("es-ES"), "%.2f", n);
  }
}
