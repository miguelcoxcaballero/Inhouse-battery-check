package es.inhouse.batterycheck;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic calculations from observations, in insertion order (never wall-clock sort). No
 * historical reconstruction, application-energy attribution, or missing-value imputation.
 * Percentage is battery percentage points, not a percentage of estimated application energy.
 */
public final class Analytics {
  private Analytics() {}

  public static final long MAX_INTERVAL_MS = 15 * 60_000L;
  private static final long CLOCK_TOLERANCE_MS = 60_000L;
  private static final double HOUR_MS = 3_600_000d;
  private static final Locale ES = Locale.forLanguageTag("es-ES");

  public static final class Result {
    public List<Session> sessions = new ArrayList<>();
    public List<Bucket> hours = new ArrayList<>(), days = new ArrayList<>();
    public List<Finding> findings = new ArrayList<>();
    public double drop, dischargeHours, onDrop, offDrop, onHours, offHours;
    public double maxTemp = Double.NaN, recentRate = Double.NaN, unknownHours;
    public int gaps;
    public long start, end;
    public String startZone = "UTC", endZone = "UTC";

    /** Different prefixes are overlapping views; never add their totals together. */
    public Map<String, Condition> conditions = new LinkedHashMap<>();
  }

  public static final class Session {
    public long start, end;
    public int startLevel, endLevel;
    public boolean from100, complete;
    public double drop, hours;

    /** Extrapolation, only after >=5 percentage points and >=30 minutes. */
    public double equivalentFullHours = Double.NaN;

    public int gaps;
    public String endReason = "En curso";
  }

  public static final class Bucket {
    public String label;
    public long start;
    public double drop, hours, onDrop, offDrop;
    public int gaps;

    /** The distribution across calendar boundaries is a linear interpolation. */
    public boolean interpolated;
  }

  public static final class Condition {
    public String label;
    public double drop, hours, tempSum;
    public int tempCount;
    public double maxTemp = Double.NaN;
  }

  public static final class Finding {
    public String title, evidence, confidence, action;

    public Finding(String title, String evidence, String confidence, String action) {
      this.title = title;
      this.evidence = evidence;
      this.confidence = confidence;
      this.action = action;
    }
  }

  private static final class Interval {
    final Sample a, b;
    final double hours, drop;
    final int endIndex;

    Interval(Sample a, Sample b, int endIndex) {
      this.a = a;
      this.b = b;
      this.endIndex = endIndex;
      hours = (b.elapsedMs - a.elapsedMs) / HOUR_MS;
      drop = a.level - b.level;
    }
  }

  public static Result analyze(List<Sample> input) {
    Result r = new Result();
    if (input == null || input.isEmpty()) {
      r.findings.add(
          new Finding(
              "Aún no hay mediciones",
              "No existe historial anterior al primer registro de esta aplicación.",
              "Sin datos",
              "Inicia la monitorización y recoge una sesión de descarga."));
      return r;
    }
    // Null observations are interruption markers, never silently bridged.
    List<Sample> samples = input;
    Map<String, Bucket> hours = new LinkedHashMap<>(), days = new LinkedHashMap<>();
    List<Interval> valid = new ArrayList<>();
    Session current = null;
    Sample previous = null;
    double hotHours = 0;
    int maximumThermal = Integer.MIN_VALUE;
    boolean first = true;
    for (int index = 0; index < samples.size(); index++) {
      Sample sample = samples.get(index);
      if (sample == null) {
        if (current != null) finish(current, "Registro incompleto", false);
        current = null;
        previous = null;
        r.gaps++;
        continue;
      }
      if (first) {
        r.start = sample.wallMs;
        r.startZone = zone(sample).getId();
        first = false;
      }
      r.end = sample.wallMs;
      r.endZone = zone(sample).getId();
      r.maxTemp = max(r.maxTemp, sample.tempC);
      maximumThermal = Math.max(maximumThermal, sample.thermal);
      if (previous == null) {
        if (eligibleStart(sample) && !terminal(sample)) {
          current = begin(sample);
          r.sessions.add(current);
        }
        previous = sample;
        continue;
      }

      Sample p = previous;
      String discontinuity = discontinuity(p, sample);
      if (discontinuity != null) {
        r.gaps++;
        r.unknownHours += unknownDuration(p, sample);
        markGap(hours, days, p, sample);
        if (current != null) {
          current.gaps++;
          finish(current, discontinuity, false);
        }
        current = null;
        if (eligibleStart(sample) && !terminal(sample)) {
          current = begin(sample);
          r.sessions.add(current);
        }
        previous = sample;
        continue;
      }

      double duration = (sample.elapsedMs - p.elapsedMs) / HOUR_MS;
      if (finite(p.tempC) && p.tempC >= 40) hotHours += duration;
      if (p.plugged == 0) {
        if (!validLevel(p.level) || !validLevel(sample.level) || sample.level > p.level) {
          r.gaps++;
          r.unknownHours += duration;
          markGap(hours, days, p, sample);
          if (current != null) {
            current.gaps++;
            finish(
                current,
                sample.level > p.level
                    ? "Subida de porcentaje; posible recarga o ajuste del indicador"
                    : "Porcentaje no disponible",
                false);
          }
          current = null;
          if (eligibleStart(sample) && !terminal(sample)) {
            current = begin(sample);
            r.sessions.add(current);
          }
        } else {
          if (current == null) {
            current = begin(p);
            r.sessions.add(current);
          }
          Interval interval = new Interval(p, sample, index);
          valid.add(interval);
          r.drop += interval.drop;
          r.dischargeHours += duration;
          if (p.screen == 1) {
            r.onDrop += interval.drop;
            r.onHours += duration;
          }
          if (p.screen == 0) {
            r.offDrop += interval.drop;
            r.offHours += duration;
          }
          current.drop += interval.drop;
          current.hours += duration;
          current.end = sample.wallMs;
          current.endLevel = sample.level;
          if (current.drop >= 5 && current.hours >= .5)
            current.equivalentFullHours = 100 * current.hours / current.drop;
          addBuckets(hours, days, interval);
          addConditions(r, interval);
          if (sample.plugged > 0) {
            finish(current, "Recarga detectada", true);
            current = null;
          } else if (terminal(sample)) {
            finish(
                current,
                "PAUSE".equals(sample.reason)
                    ? "Monitorización pausada"
                    : "Monitorización detenida",
                false);
            current = null;
          }
        }
      } else if (p.plugged > 0) {
        // Charging is not negative discharge. Its temperature is still observed.
        if (current != null) {
          finish(current, "Recarga detectada", true);
          current = null;
        }
        if (eligibleStart(sample) && !terminal(sample)) {
          current = begin(sample);
          r.sessions.add(current);
        }
      } else {
        r.gaps++;
        r.unknownHours += duration;
        markGap(hours, days, p, sample);
        if (current != null) {
          current.gaps++;
          finish(current, "Estado de carga desconocido", false);
        }
        current = null;
        if (eligibleStart(sample) && !terminal(sample)) {
          current = begin(sample);
          r.sessions.add(current);
        }
      }
      previous = sample;
    }
    r.hours.addAll(hours.values());
    r.days.addAll(days.values());
    computeRecent(r, valid, samples);
    diagnose(r, hotHours, maximumThermal);
    return r;
  }

  private static String discontinuity(Sample a, Sample b) {
    if (terminal(a)) return "Reanudación después de una pausa o parada";
    if (!Objects.equals(a.continuity, b.continuity)) return "Monitorización reiniciada";
    if (a.boot != b.boot) return "Reinicio del dispositivo";
    long elapsed = b.elapsedMs - a.elapsedMs, wall = b.wallMs - a.wallMs;
    if (elapsed <= 0) return "Reloj monotónico discontinuo";
    if (wall <= 0 || Math.abs(wall - elapsed) > CLOCK_TOLERANCE_MS)
      return "Cambio de fecha u hora del dispositivo";
    if (!zone(a).equals(zone(b))) return "Cambio de zona horaria";
    if (elapsed > MAX_INTERVAL_MS) return "Hueco de más de 15 minutos sin muestras";
    return null;
  }

  private static boolean eligibleStart(Sample s) {
    return s.plugged == 0 && validLevel(s.level);
  }

  private static boolean validLevel(int level) {
    return level >= 0 && level <= 100;
  }

  private static boolean terminal(Sample s) {
    return "PAUSE".equals(s.reason) || "STOP".equals(s.reason);
  }

  private static boolean finite(double x) {
    return !Double.isNaN(x) && !Double.isInfinite(x);
  }

  private static double max(double a, double b) {
    return !finite(b) ? a : !finite(a) ? b : Math.max(a, b);
  }

  private static Session begin(Sample sample) {
    Session s = new Session();
    s.start = s.end = sample.wallMs;
    s.startLevel = s.endLevel = sample.level;
    s.from100 = sample.level == 100; // An actual sample at 100, never an extrapolated value.
    return s;
  }

  private static void finish(Session s, String reason, boolean complete) {
    s.endReason = reason;
    s.complete = complete;
  }

  private static double unknownDuration(Sample a, Sample b) {
    if (a.boot == b.boot && b.elapsedMs > a.elapsedMs) return (b.elapsedMs - a.elapsedMs) / HOUR_MS;
    return b.wallMs > a.wallMs ? (b.wallMs - a.wallMs) / HOUR_MS : 0;
  }

  private static ZoneId zone(Sample s) {
    try {
      return ZoneId.of(s.zone);
    } catch (Exception ignored) {
      return ZoneId.of("UTC");
    }
  }

  private static void addBuckets(Map<String, Bucket> hours, Map<String, Bucket> days, Interval i) {
    splitInto(hours, i, false);
    splitInto(days, i, true);
  }

  private static void splitInto(Map<String, Bucket> map, Interval i, boolean daily) {
    ZoneId zone = zone(i.a);
    long cursor = i.a.wallMs;
    while (cursor < i.b.wallMs) {
      ZonedDateTime at = Instant.ofEpochMilli(cursor).atZone(zone);
      ZonedDateTime start =
          daily ? at.toLocalDate().atStartOfDay(zone) : at.truncatedTo(ChronoUnit.HOURS);
      ZonedDateTime next =
          daily ? at.toLocalDate().plusDays(1).atStartOfDay(zone) : start.plusHours(1);
      long end = Math.min(i.b.wallMs, next.toInstant().toEpochMilli());
      if (end <= cursor) break;
      Bucket b = bucket(map, cursor, zone, daily);
      double fraction = (double) (end - cursor) / (i.b.wallMs - i.a.wallMs);
      double drop = i.drop * fraction;
      b.hours += i.hours * fraction;
      b.drop += drop;
      if (i.a.screen == 1) b.onDrop += drop;
      if (i.a.screen == 0) b.offDrop += drop;
      if (cursor != i.a.wallMs || end != i.b.wallMs) b.interpolated = true;
      cursor = end;
    }
  }

  private static Bucket bucket(Map<String, Bucket> map, long time, ZoneId zone, boolean daily) {
    ZonedDateTime at = Instant.ofEpochMilli(time).atZone(zone);
    ZonedDateTime start =
        daily ? at.toLocalDate().atStartOfDay(zone) : at.truncatedTo(ChronoUnit.HOURS);
    String label =
        daily
            ? at.toLocalDate() + " · " + zone.getId()
            : start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX", ES))
                + " · "
                + zone.getId();
    Bucket b = map.get(label);
    if (b == null) {
      b = new Bucket();
      b.label = label;
      b.start = start.toInstant().toEpochMilli();
      map.put(label, b);
    }
    return b;
  }

  private static void markGap(
      Map<String, Bucket> hours, Map<String, Bucket> days, Sample a, Sample b) {
    // Enumerate bounded missing buckets without inventing discharge or hours in them.
    if (b.wallMs > a.wallMs
        && b.wallMs - a.wallMs <= 31L * 86_400_000L
        && zone(a).equals(zone(b))) {
      for (boolean daily : new boolean[] {false, true}) {
        Map<String, Bucket> map = daily ? days : hours;
        long cursor = a.wallMs;
        while (cursor < b.wallMs) {
          Bucket target = bucket(map, cursor, zone(a), daily);
          target.gaps++;
          ZonedDateTime at = Instant.ofEpochMilli(cursor).atZone(zone(a));
          long next =
              (daily
                      ? at.toLocalDate().plusDays(1).atStartOfDay(zone(a))
                      : at.truncatedTo(ChronoUnit.HOURS).plusHours(1))
                  .toInstant()
                  .toEpochMilli();
          if (next <= cursor) break;
          cursor = next;
        }
      }
    } else {
      bucket(hours, a.wallMs, zone(a), false).gaps++;
      bucket(days, a.wallMs, zone(a), true).gaps++;
    }
    // Include the receiving bucket; the endpoint may be the only record in that hour.
    Bucket h = bucket(hours, b.wallMs, zone(b), false);
    Bucket d = bucket(days, b.wallMs, zone(b), true);
    if (h.gaps == 0) h.gaps++;
    if (d.gaps == 0) d.gaps++;
  }

  private static String network(Sample s) {
    String n = s.network;
    return "WIFI".equals(n) || "MOBILE".equals(n) || "NONE".equals(n) || "OTHER".equals(n)
        ? n
        : "UNKNOWN";
  }

  private static String screen(Sample s) {
    return s.screen == 1 ? "ON" : s.screen == 0 ? "OFF" : "UNKNOWN";
  }

  private static String coverage(Sample s) {
    // Explicit service information is independent of the active internet transport.
    // A Wi-Fi connection can coexist with no cellular service. An unknown RAT is not proof.
    if ("Sin servicio".equals(s.radio)) return "NO_SERVICE";
    if (!"MOBILE".equals(network(s)) || s.signalLevel < 0 || s.signalLevel > 4) return "UNKNOWN";
    return s.signalLevel <= 1 ? "POOR" : "GOOD";
  }

  private static String networkLabel(String n) {
    switch (n) {
      case "WIFI":
        return "Wifi";
      case "MOBILE":
        return "Datos móviles";
      case "NONE":
        return "Sin conexión activa";
      case "OTHER":
        return "Otra conexión";
      default:
        return "Conexión desconocida";
    }
  }

  private static String screenLabel(String s) {
    return "ON".equals(s)
        ? "Pantalla encendida"
        : "OFF".equals(s) ? "Pantalla apagada" : "Pantalla desconocida";
  }

  private static String coverageLabel(String s) {
    if ("NO_SERVICE".equals(s)) return "Sin servicio móvil informado";
    return "POOR".equals(s)
        ? "Cobertura móvil baja"
        : "GOOD".equals(s) ? "Cobertura móvil media/alta" : "Cobertura no evaluable";
  }

  private static void addConditions(Result r, Interval i) {
    String n = network(i.a), s = screen(i.a), c = coverage(i.a);
    addCondition(r, "NETWORK:" + n, networkLabel(n), i);
    addCondition(r, "SCREEN:" + s, screenLabel(s), i);
    addCondition(r, "SIGNAL:" + c, coverageLabel(c), i);
    addCondition(
        r,
        "COMBINED:" + n + ":" + s + ":" + c,
        networkLabel(n) + " · " + screenLabel(s) + " · " + coverageLabel(c),
        i);
  }

  private static void addCondition(Result r, String key, String label, Interval i) {
    Condition c = r.conditions.get(key);
    if (c == null) {
      c = new Condition();
      c.label = label;
      r.conditions.put(key, c);
    }
    c.hours += i.hours;
    c.drop += i.drop;
    // Temperature is the average of interval-start observations, not CPU/GPU or a time-weighted
    // mean.
    if (finite(i.a.tempC)) {
      c.tempSum += i.a.tempC;
      c.tempCount++;
      c.maxTemp = max(c.maxTemp, i.a.tempC);
    }
  }

  private static void computeRecent(Result r, List<Interval> valid, List<Sample> samples) {
    if (valid.isEmpty() || samples.isEmpty()) return;
    Sample last = samples.get(samples.size() - 1);
    if (last == null || last.plugged != 0 || terminal(last)) return;
    int expectedIndex = samples.size() - 1;
    double hours = 0, drop = 0;
    for (int k = valid.size() - 1; k >= 0 && hours < 1; k--) {
      Interval i = valid.get(k);
      if (i.endIndex != expectedIndex) break;
      double used = Math.min(i.hours, 1 - hours);
      drop += i.drop * used / i.hours;
      hours += used;
      expectedIndex--;
    }
    if (hours >= .5 - 1e-9 && drop >= 2) r.recentRate = drop / hours;
  }

  private static String f(double value) {
    return String.format(ES, "%.1f", value);
  }

  private static String period(Result r) {
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm XXX", ES);
    return "Periodo registrado: "
        + Instant.ofEpochMilli(r.start).atZone(ZoneId.of(r.startZone)).format(formatter)
        + " ("
        + r.startZone
        + ") a "
        + Instant.ofEpochMilli(r.end).atZone(ZoneId.of(r.endZone)).format(formatter)
        + " ("
        + r.endZone
        + "). ";
  }

  private static void diagnose(Result r, double hotHours, int maximumThermal) {
    String period = period(r);
    if (r.gaps > 0) {
      r.findings.add(
          new Finding(
              "Hay tramos sin medición utilizable",
              period
                  + r.gaps
                  + " interrupciones; aproximadamente "
                  + f(r.unknownHours)
                  + " h excluidas. Reinicios, pausas, saltos de reloj, subidas del indicador y"
                  + " huecos de más de 15 min no se contabilizan como consumo cero.",
              "Alta sobre la limitación del registro",
              "Reanuda la monitorización si está detenida. Recoge un tramo continuo; Android puede"
                  + " retrasar muestras cuando duerme, y no se usan bloqueos del procesador para"
                  + " evitarlo."));
    }
    if ((finite(r.maxTemp) && r.maxTemp >= 45) || hotHours >= 1d / 6 || maximumThermal >= 3) {
      r.findings.add(
          new Finding(
              "Temperatura o estado térmico elevados observados",
              period
                  + "Máximo de batería: "
                  + (finite(r.maxTemp) ? f(r.maxTemp) + " °C" : "no disponible")
                  + ". Tramos con temperatura inicial ≥40 °C: "
                  + f(hotHours * 60)
                  + " min. Estado térmico máximo Android: "
                  + (maximumThermal == Integer.MIN_VALUE ? "no disponible" : maximumThermal)
                  + ". No es temperatura de CPU ni GPU.",
              "Alta para las lecturas; causa sin confirmar",
              "Compara 30–60 min sin cargador y con pantalla apagada con un tramo de carga. Si"
                  + " sigue caliente en reposo, revisa el diagnóstico de Samsung; ante hinchazón o"
                  + " daño físico, deja de usarlo y acude al servicio técnico."));
    }
    if (r.offHours >= .5 && r.offDrop >= 2 && r.offDrop / r.offHours > 2) {
      r.findings.add(
          new Finding(
              "Descarga elevada con pantalla apagada",
              period
                  + f(r.offDrop)
                  + " puntos porcentuales en "
                  + f(r.offHours)
                  + " h sin cargador: "
                  + f(r.offDrop / r.offHours)
                  + " pp/h. Umbral orientativo de 2 pp/h; pantalla apagada no demuestra reposo"
                  + " profundo.",
              r.offHours >= 2 && r.offDrop >= 4
                  ? "Media; correlación, no causa confirmada"
                  : "Baja; muestra todavía corta",
              "Haz una prueba de 60–120 min bloqueado, sin cargar y con la misma conexión. Después"
                  + " repítela cambiando solo la red; contrasta actividad en segundo plano en"
                  + " Batería de Samsung."));
    }
    Condition noService = r.conditions.get("SIGNAL:NO_SERVICE");
    if (noService != null && noService.hours >= .25 - 1e-9) {
      r.findings.add(
          new Finding(
              "Se registraron periodos sin servicio móvil",
              period
                  + "El sistema informó falta de servicio celular durante "
                  + f(noService.hours * 60)
                  + " min de tramos válidos sin cargador; bajada coincidente: "
                  + f(noService.drop)
                  + " pp. El wifi puede seguir conectado. Un tipo de red desconocido por sí solo no"
                  + " se cuenta como falta de servicio.",
              "Alta sobre el estado informado; efecto sobre consumo sin confirmar",
              "Comprueba que la SIM esté habilitada. Compara una prueba en el mismo lugar con otra"
                  + " de duración y uso similares donde haya servicio; la diferencia no cuantifica"
                  + " energía de la radio."));
    }
    for (String state : new String[] {"OFF", "ON"}) {
      Condition wifi = r.conditions.get("COMBINED:WIFI:" + state + ":UNKNOWN");
      Condition poor = r.conditions.get("COMBINED:MOBILE:" + state + ":POOR");
      if (comparable(wifi) && comparable(poor)) {
        double wr = wifi.drop / wifi.hours, mr = poor.drop / poor.hours;
        if (mr >= wr * 1.5 && mr - wr >= 1) {
          r.findings.add(
              new Finding(
                  "Mayor descarga observada con cobertura móvil baja",
                  period
                      + screenLabel(state)
                      + ": datos móviles con señal baja "
                      + f(mr)
                      + " pp/h ("
                      + f(poor.hours)
                      + " h), wifi "
                      + f(wr)
                      + " pp/h ("
                      + f(wifi.hours)
                      + " h). La actividad de apps y el tráfico pueden ser distintos; esto no"
                      + " cuantifica energía de la antena.",
                  "Baja; correlación con condiciones parcialmente comparables",
                  "Repite dos pruebas de duración similar, sin cargar, con la misma pantalla y uso:"
                      + " una con wifi y otra con datos móviles. No cambies más de una"
                      + " condición."));
        }
      }
    }
    if (r.dischargeHours >= .5 && r.drop >= 1) {
      double unknownDrop = Math.max(0, r.drop - r.onDrop - r.offDrop);
      r.findings.add(
          new Finding(
              "Dónde se observó la bajada de batería",
              period
                  + "Bajada medida: "
                  + f(r.drop)
                  + " pp en "
                  + f(r.dischargeHours)
                  + " h válidas sin cargar. "
                  + "Pantalla encendida: "
                  + f(r.onDrop)
                  + " pp; apagada: "
                  + f(r.offDrop)
                  + " pp; estado desconocido: "
                  + f(unknownDrop)
                  + " pp. Estos grupos describen las condiciones de uso y no separan energía de"
                  + " pantalla, apps o radio.",
              "Media; indicador de batería con resolución de 1 punto porcentual",
              "Revisa la franja con mayor descarga y el ranking de energía del sistema si está"
                  + " disponible. El tiempo de uso por sí solo no identifica la app que más energía"
                  + " gastó."));
    } else {
      r.findings.add(
          new Finding(
              "Hace falta ampliar la sesión",
              period
                  + "Solo "
                  + f(r.dischargeHours)
                  + " h y "
                  + f(r.drop)
                  + " pp de descarga con continuidad comprobada. No se extrapola el historial"
                  + " anterior.",
              "Insuficiente para identificar una causa",
              "Deja la monitorización activa durante uso normal y después realiza una prueba de"
                  + " reposo sin cargar. La tasa reciente requiere al menos 30 min y 2 pp de"
                  + " bajada."));
    }
  }

  private static boolean comparable(Condition c) {
    return c != null && c.hours >= .5 && c.drop >= 1;
  }
}
