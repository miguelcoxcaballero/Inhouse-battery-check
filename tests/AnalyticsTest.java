package es.inhouse.batterycheck;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Standalone regression checks: javac Sample.java Analytics.java AnalyticsTest.java; java
 * ...AnalyticsTest.
 */
public final class AnalyticsTest {
  private static int checks;
  private static final long BASE = Instant.parse("2026-10-14T20:00:00Z").toEpochMilli();

  private static Sample s(int minute, int level, int plugged, int screen) {
    Sample s = new Sample();
    s.wallMs = BASE + minute * 60_000L;
    s.elapsedMs = 100_000 + minute * 60_000L;
    s.boot = 7;
    s.level = level;
    s.plugged = plugged;
    s.screen = screen;
    s.continuity = "first";
    s.zone = "Europe/Madrid";
    s.network = "WIFI";
    return s;
  }

  private static void near(double expected, double actual, String message) {
    checks++;
    if (!Double.isFinite(actual) || Math.abs(expected - actual) > 1e-7)
      throw new AssertionError(message + ": expected " + expected + " but got " + actual);
  }

  private static void yes(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  private static Analytics.Result a(Sample... s) {
    return Analytics.analyze(Arrays.asList(s));
  }

  public static void main(String[] args) {
    emptyAndUnknown();
    real100AndExtrapolation();
    partialRecharge();
    stateBoundaries();
    gapAndRestart();
    clockAndTimezone();
    midnightAndDst();
    recentRate();
    unknownNetwork();
    noService();
    findings();
    terminalAndNull();
    invalidAndCharging();
    conservation();
    System.out.println("PASS: " + checks + " analytics assertions");
  }

  private static void emptyAndUnknown() {
    Analytics.Result r = Analytics.analyze(new ArrayList<>());
    near(0, r.drop, "empty total");
    yes(Double.isNaN(r.maxTemp), "unknown temperature not zero");
    yes(Double.isNaN(r.recentRate), "unknown rate not zero");
    yes(!r.findings.isEmpty(), "initial instruction");
    Sample x = s(0, Integer.MIN_VALUE, 0, -1), y = s(10, 60, 0, -1);
    r = a(x, y);
    near(0, r.drop, "unknown level excluded");
    yes(r.gaps == 1, "unknown level recorded");
  }

  private static void real100AndExtrapolation() {
    Analytics.Result r =
        a(s(0, 100, 1, 0), s(5, 100, 0, 0), s(15, 98, 0, 0), s(25, 96, 0, 0), s(35, 95, 0, 0));
    yes(r.sessions.size() == 1, "unplug begins one session");
    Analytics.Session session = r.sessions.get(0);
    yes(session.from100, "actual sampled 100");
    near(5, session.drop, "5 pp");
    near(.5, session.hours, "30 min discharge");
    near(10, session.equivalentFullHours, "equivalent extrapolation");
    r = a(s(0, 91, 0, 0), s(10, 90, 0, 0));
    yes(!r.sessions.get(0).from100, "partial session is not from100");
    yes(
        Double.isNaN(r.sessions.get(0).equivalentFullHours),
        "one point cannot imply precise capacity");
  }

  private static void partialRecharge() {
    Analytics.Result r =
        a(
            s(0, 60, 0, 1),
            s(10, 58, 0, 1),
            s(15, 57, 1, 1),
            s(25, 70, 1, 0),
            s(30, 72, 0, 0),
            s(40, 70, 0, 0));
    near(5, r.drop, "recarga no resta consumo");
    yes(r.sessions.size() == 2, "partial recharge splits sessions");
    yes(r.sessions.get(0).complete, "normal recharge closes first session");
    near(3, r.sessions.get(0).drop, "pre-charge drop");
    near(2, r.sessions.get(1).drop, "post-charge drop");
    yes(!r.sessions.get(1).from100, "72 is not full");
    r = a(s(0, 60, 0, 0), s(10, 59, 0, 0), s(20, 80, 0, 0), s(30, 78, 0, 0));
    near(3, r.drop, "unobserved partial recharge excluded");
    yes(r.gaps == 1, "rise visible");
    yes(r.sessions.size() == 2, "rise splits session");
  }

  private static void stateBoundaries() {
    Sample on = s(0, 70, 0, 1), off = s(10, 68, 0, 0), after = s(20, 67, 0, 0);
    off.network = "MOBILE";
    off.signalLevel = 1;
    after.network = "MOBILE";
    after.signalLevel = 1;
    Analytics.Result r = a(on, off, after);
    near(2, r.onDrop, "event endpoint does not reclassify preceding screen period");
    near(1, r.offDrop, "off starts at event");
    near(2, r.conditions.get("NETWORK:WIFI").drop, "network boundary uses preceding state");
    near(1, r.conditions.get("SIGNAL:POOR").drop, "poor mobile level");
    near(3, r.onDrop + r.offDrop, "screen partition no double count");
    r = a(s(0, 70, 0, -1), s(10, 69, 0, -1));
    near(0, r.onDrop + r.offDrop, "unknown screen is not off");
    near(1, r.conditions.get("SCREEN:UNKNOWN").drop, "unknown screen separately visible");
  }

  private static void gapAndRestart() {
    Analytics.Result r = a(s(0, 70, 0, 0), s(10, 69, 0, 0), s(40, 65, 0, 0), s(50, 64, 0, 0));
    near(2, r.drop, "long gap excluded");
    near(.5, r.unknownHours, "missing duration");
    yes(r.gaps == 1 && r.sessions.size() == 2, "gap split");
    yes(r.hours.get(0).gaps > 0, "gap visible in graph bucket");
    Sample x = s(0, 70, 0, 0), y = s(10, 60, 0, 0);
    y.continuity = "restarted";
    r = a(x, y);
    near(0, r.drop, "new service cannot bridge continuity");
    yes(r.gaps == 1, "restart marked");
    y.continuity = x.continuity;
    y.boot++;
    y.elapsedMs = 10_000;
    r = a(x, y);
    near(0, r.drop, "reboot does not bridge elapsed reset");
    yes(r.gaps == 1, "reboot visible");
    r = a(s(0, 70, 0, 0), s(15, 68, 0, 0));
    near(2, r.drop, "15min boundary valid");
  }

  private static void clockAndTimezone() {
    Sample x = s(0, 80, 0, 0), y = s(10, 79, 0, 0), z = s(20, 78, 0, 0);
    y.wallMs -= 3_600_000;
    z.wallMs -= 3_600_000;
    Analytics.Result r = a(x, y, z);
    near(1, r.drop, "backwards clock split but following data valid");
    yes(r.gaps == 1, "clock jump visible");
    near(1d / 6, r.dischargeHours, "duration uses elapsed");
    y = s(10, 79, 0, 0);
    y.wallMs += 3_600_000;
    r = a(x, y);
    near(0, r.drop, "forward clock gap");
    near(1d / 6, r.unknownHours, "unknown time uses monotonic clock");
    y = s(10, 79, 0, 0);
    y.zone = "Asia/Tokyo";
    r = a(x, y);
    near(0, r.drop, "timezone boundary conservative");
    yes(r.gaps == 1, "zone gap shown");
    x = s(0, 80, 0, 0);
    y = s(10, 79, 0, 0);
    y.wallMs += 30_000;
    r = a(x, y);
    near(1d / 6, r.dischargeHours, "small wall drift never alters elapsed duration");
  }

  private static void midnightAndDst() {
    Sample x = s(0, 80, 0, 1), y = s(10, 78, 0, 0);
    x.wallMs = Instant.parse("2026-10-14T21:55:00Z").toEpochMilli();
    y.wallMs = x.wallMs + 600_000;
    Analytics.Result r = a(x, y);
    yes(r.days.size() == 2, "local midnight split");
    near(1, r.days.get(0).drop, "midnight linear interpolation first half");
    near(1, r.days.get(1).drop, "midnight second half");
    yes(r.days.get(0).interpolated, "interpolation disclosed");
    x.wallMs = Instant.parse("2026-10-25T00:55:00Z").toEpochMilli();
    y.wallMs = x.wallMs + 600_000;
    r = a(x, y);
    yes(r.hours.size() == 2, "autumn repeated hour stays separate");
    yes(
        r.hours.get(0).label.contains("+02:00") && r.hours.get(1).label.contains("+01:00"),
        "DST offsets explicit");
    yes(r.days.size() == 1, "DST fallback same local day");
    near(2, r.drop, "DST is not a clock gap");
    x.wallMs = Instant.parse("2026-03-29T00:55:00Z").toEpochMilli();
    y.wallMs = x.wallMs + 600_000;
    r = a(x, y);
    yes(r.hours.size() == 2, "spring skip handled");
    yes(r.gaps == 0, "spring DST no gap");
    near(1d / 6, r.dischargeHours, "DST durations monotonic");
  }

  private static void recentRate() {
    List<Sample> list = new ArrayList<>();
    for (int m = 0; m <= 90; m += 10) list.add(s(m, 100 - m / 10, 0, 0));
    Analytics.Result r = Analytics.analyze(list);
    near(6, r.recentRate, "rolling most recent hour");
    list.add(s(130, 86, 0, 0));
    r = Analytics.analyze(list);
    yes(Double.isNaN(r.recentRate), "recent rate does not cross gap");
    r = a(s(0, 70, 0, 0), s(10, 69, 0, 0));
    yes(Double.isNaN(r.recentRate), "short rate unavailable");
    r = a(s(0, 70, 0, 0), s(10, 69, 0, 0), s(20, 68, 0, 0), s(30, 67, 1, 0));
    yes(Double.isNaN(r.recentRate), "no current discharge rate while charging");
  }

  private static void unknownNetwork() {
    Sample x = s(0, 50, 0, 0), y = s(10, 48, 0, 0);
    x.network = null;
    x.signalDbm = -120;
    Analytics.Result r = a(x, y);
    yes(r.conditions.containsKey("NETWORK:UNKNOWN"), "missing permission is unknown network");
    yes(
        !r.conditions.containsKey("SIGNAL:POOR"),
        "dBm without compatible RAT or level is not universally classified");
    x.network = "MOBILE";
    x.signalLevel = Integer.MIN_VALUE;
    r = a(x, y);
    yes(r.conditions.containsKey("SIGNAL:UNKNOWN"), "missing phone permission is unknown signal");
  }

  private static void noService() {
    Sample x = s(0, 60, 0, 0), y = s(10, 59, 0, 0), z = s(20, 58, 0, 0);
    x.radio = "Sin servicio";
    x.signalLevel = 0;
    y.radio = "Sin servicio";
    y.signalLevel = 0;
    y.network = "NONE";
    Analytics.Result r = a(x, y, z);
    near(
        2,
        r.conditions.get("SIGNAL:NO_SERVICE").drop,
        "explicit no-service works with Wi-Fi and NONE");
    near(
        1,
        r.conditions.get("COMBINED:WIFI:OFF:NO_SERVICE").drop,
        "Wi-Fi can coexist with no cellular service");
    near(
        1,
        r.conditions.get("COMBINED:NONE:OFF:NO_SERVICE").drop,
        "no transport with confirmed no service");
    yes(
        r.findings.stream().anyMatch(f -> f.title.contains("sin servicio móvil")),
        "sufficient no-service evidence creates finding");
    x.radio = "Datos móviles no identificados";
    x.network = "NONE";
    r = a(x, y);
    yes(
        r.conditions.containsKey("SIGNAL:UNKNOWN"),
        "unknown RAT with no transport remains unknown");
    yes(!r.conditions.containsKey("SIGNAL:NO_SERVICE"), "unknown RAT does not imply no service");
    x.network = "MOBILE";
    y = s(10, 59, 0, 0);
    r = a(x, y);
    yes(
        r.conditions.containsKey("SIGNAL:POOR"),
        "low signal with unknown RAT is poor rather than proven no service");
    yes(
        !r.conditions.containsKey("SIGNAL:NO_SERVICE"),
        "signal level zero alone does not prove no service");
    x.radio = "Radio móvil apagada";
    x.network = "WIFI";
    r = a(x, y);
    yes(
        !r.conditions.containsKey("SIGNAL:NO_SERVICE"),
        "explicit radio off does not imply network search");
  }

  private static void findings() {
    List<Sample> list = new ArrayList<>();
    for (int m = 0; m <= 60; m += 10) {
      Sample s = s(m, 70 - m / 10, 0, 0);
      s.tempC = 41;
      list.add(s);
    }
    Analytics.Result r = Analytics.analyze(list);
    yes(
        r.findings.stream().anyMatch(f -> f.title.contains("pantalla apagada")),
        "elevated off-screen finding");
    yes(r.findings.stream().anyMatch(f -> f.title.contains("térmico")), "sustained battery heat");
    yes(
        r.findings.stream()
            .allMatch(f -> f.evidence != null && f.confidence != null && f.action != null),
        "all findings evidence confidence action");
    near(41, r.maxTemp, "temperature is battery reading");
    for (int m = 70; m <= 120; m += 10) {
      Sample s = s(m, 64 - (m - 60) / 5, 0, 0);
      s.network = "MOBILE";
      s.signalLevel = 1;
      list.add(s);
    }
    r = Analytics.analyze(list);
    yes(
        r.findings.stream().anyMatch(f -> f.title.contains("cobertura móvil baja")),
        "controlled-screen network correlation");
  }

  private static void terminalAndNull() {
    Sample stop = s(10, 79, 0, 0);
    stop.reason = "PAUSE";
    Analytics.Result r = a(s(0, 80, 0, 0), stop, s(20, 77, 0, 0));
    near(1, r.drop, "never bridge explicit pause");
    yes(r.gaps == 1, "pause gap recorded");
    yes(r.sessions.get(0).endReason.contains("pausada"), "pause session label retained");
    r = a(s(0, 80, 0, 0), null, s(20, 70, 0, 0));
    near(0, r.drop, "null does not silently bridge records");
  }

  private static void invalidAndCharging() {
    Sample x = s(0, 60, 1, 0), y = s(10, 70, 1, 0);
    x.tempC = 42;
    y.tempC = 46;
    Analytics.Result r = a(x, y);
    near(0, r.drop, "charge is not discharge");
    near(46, r.maxTemp, "charging temperature retained");
    yes(r.sessions.isEmpty(), "no discharge sessions while charging");
    x = s(0, 60, Integer.MIN_VALUE, 0);
    y = s(10, 59, 0, 0);
    r = a(x, y);
    near(0, r.drop, "unknown charge state excluded");
    yes(r.gaps == 1, "unknown charge state visible");
    x = s(0, 80, 0, 0);
    y = s(0, 79, 0, 0);
    r = a(x, y);
    near(0, r.drop, "zero duration excluded");
    yes(r.gaps == 1, "duplicate timestamp marked");
    x = s(0, 80, 0, 0);
    y = s(10, 79, 0, 0);
    y.thermal = 3;
    r = a(x, y);
    yes(
        r.findings.stream().anyMatch(f -> f.title.contains("térmico")),
        "thermal state works without battery sensor");
    yes(
        r.findings.stream().noneMatch(f -> f.evidence.contains("NaN")),
        "unavailable never rendered as a number");
    yes(
        r.findings.get(0).evidence.contains("Europe/Madrid"),
        "findings use recorded local timezone");
  }

  private static void conservation() {
    List<Sample> list = new ArrayList<>();
    for (int m = 0; m <= 180; m += 5) {
      Sample s = s(m, 100 - m / 5, 0, (m / 5) % 3 - 1);
      s.wallMs += 117 * 60_000L;
      list.add(s);
    }
    Analytics.Result r = Analytics.analyze(list);
    near(r.drop, r.hours.stream().mapToDouble(b -> b.drop).sum(), "hour totals conserved");
    near(r.drop, r.days.stream().mapToDouble(b -> b.drop).sum(), "day totals conserved");
    near(
        r.dischargeHours,
        r.hours.stream().mapToDouble(b -> b.hours).sum(),
        "hour durations conserved");
    near(r.drop, r.sessions.stream().mapToDouble(b -> b.drop).sum(), "session totals conserved");
    near(
        r.drop,
        r.conditions.entrySet().stream()
            .filter(e -> e.getKey().startsWith("NETWORK:"))
            .mapToDouble(e -> e.getValue().drop)
            .sum(),
        "network view totals conserved");
    yes(r.drop > r.onDrop + r.offDrop, "unknown screen never silently allocated");
  }
}
