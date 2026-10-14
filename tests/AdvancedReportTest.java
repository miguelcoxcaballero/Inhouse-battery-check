package es.inhouse.batterycheck;

/** Synthetic fixtures based on documented AOSP formatting; not Samsung measurements. */
public final class AdvancedReportTest {
  private static int checks;
  private static final String FIXTURE =
      "Statistics since last charge:\n"
          + "  Time on battery: 4h 1m (80%) realtime, 2h (50%) uptime\n"
          + "  Time on battery screen off: 3h (75%) realtime, 1h (33%) uptime\n"
          + "  Start clock time: 2026-10-14-08-00-00\n"
          + "  Estimated power use (mAh):\n"
          + "    Capacity: 5000, Computed drain: 200, actual drain: 190-210\n"
          + "    Global\n"
          + "    screen: 40 apps: 0 duration: 1h 0m\n"
          + "    mobile_radio: 80 apps: 70 duration: 20m\n"
          + "      (battery, screen on)\n"
          + "      screen: 20 apps: 0 duration: 30m\n"
          + "  UID u0a123: 90 foreground: 30 (20m) background: 60 (3h)\n"
          + "      cpu=50 mobile_radio=40\n"
          + "      (battery, screen on) total=30 cpu=30\n"
          + "  UID 1000: 35\n"
          + "      cpu=35\n"
          + "  UID u10a12: 15\n\n"
          + "  u0a123:\n"
          + "    Mobile radio active: 20m (70%) 7x @ 4 mspp\n"
          + "    TOTAL wake: 1h actual partial, 40m actual background partial\n"
          + "    Wi-Fi network: 2MB received, 1MB sent (packets 100 received, 80 sent)\n";

  public static void main(String[] args) {
    AdvancedReport.Report r = AdvancedReport.parse(FIXTURE);
    check(r.recognized, "valid AOSP16 format");
    check(r.apps.size() == 3, "UID totals only, not subcomponents");
    check(r.components.size() == 2, "exclude state component subsets");
    check(r.apps.get(0).uid == 10123 && r.apps.get(0).appUid == 10123, "UID decoded");
    check(r.apps.get(2).uid == 1010012, "work/profile UID decoded");
    near(r.percent(r.apps.get(0).mah), 45, "denominator computed drain");
    near(r.computedDrainMah, 200, "do not sum UID plus components");
    check(r.apps.get(0).details.contains("TOTAL wake"), "optional wake details");
    check(r.elapsedOnBattery.startsWith("4h"), "battery elapsed metadata");
    check(r.elapsedScreenOff.startsWith("3h"), "screen off metadata");
    check(r.startClock.equals("2026-10-14-08-00-00"), "start clock metadata");
    check(!AdvancedReport.parse(FIXTURE + FIXTURE).recognized, "reject multiple periods");
    check(
        !AdvancedReport.parse(FIXTURE.replace("  UID 1000: 35", "  UID u0a123: 35")).recognized,
        "reject duplicate uid");
    check(
        !AdvancedReport.parse("screen: 900 apps: 800\nUID u0a12: 45").recognized,
        "reject free text outside block");
    check(
        !AdvancedReport.parse(FIXTURE.replace("Computed drain: 200", "Computed drain: NaN"))
            .recognized,
        "reject nonfinite");
    check(
        !AdvancedReport.parse(FIXTURE.replace("Computed drain: 200", "Computed drain: -20"))
            .recognized,
        "reject negative");
    AdvancedReport.Report zero =
        AdvancedReport.parse(FIXTURE.replace("Computed drain: 200", "Computed drain: 0"));
    check(
        zero.recognized && Double.isNaN(zero.percent(5)), "zero denominator not fake zero percent");
    AdvancedReport.Report clipped =
        AdvancedReport.parse(FIXTURE.substring(FIXTURE.indexOf("  Estimated")));
    check(
        clipped.recognized && clipped.period.contains("no especificado"),
        "missing period stays unknown");
    check(
        AdvancedReport.parse("\ufeff" + FIXTURE.replace("\n", "\r\n")).recognized,
        "Windows line endings and BOM");
    near(
        AdvancedReport.parse(FIXTURE.replace("Computed drain: 200", "Computed drain: 2e2"))
            .computedDrainMah,
        200,
        "exponent notation");
    check(AdvancedReport.parse("").apps.isEmpty(), "empty input");
    check(AdvancedReport.parse(null).apps.isEmpty(), "null input");
    check(AdvancedReport.parse("Estimated power use (mAh):").apps.isEmpty(), "truncated input");
    check(AdvancedReport.parseUid("u0i7") == 99007, "isolated UID");
    check(AdvancedReport.parseUid("u0ai3") == 90003, "app zygote UID");
    check(AdvancedReport.parseUid("u2s1000") == 201000, "system profile UID");
    check(AdvancedReport.parseUid("u999999a10") == -1, "UID overflow rejected");
    check(AdvancedReport.parseUid("u0a999999") == -1, "app id overflow rejected");
    System.out.println("AdvancedReportTest: " + checks + " checks passed");
  }

  private static void check(boolean b, String msg) {
    checks++;
    if (!b) throw new AssertionError(msg);
  }

  private static void near(double x, double y, String msg) {
    check(Math.abs(x - y) < 0.0001, msg);
  }
}
