package es.inhouse.batterycheck;

import java.util.Map;

public final class UsageTimelineTest {
  private static int checks;

  public static void main(String[] args) {
    UsageTimeline t = new UsageTimeline(1000, 5000);
    t.resume(500, "a", "a1");
    t.pause(2000, "a", "a1");
    t.resume(3000, "a", "a2");
    t.resume(3500, "a", "a3");
    t.pause(4000, "a", "a2");
    Map<String, Long> values = t.finish();
    eq(values.get("a"), 3000L, "union/clipping/start before period/end active");
    t = new UsageTimeline(1000, 5000);
    t.pause(1500, "unknown", "x");
    t.resume(2000, "a", "a1");
    t.resume(2100, "a", "a1");
    t.reset(2500);
    t.pause(2600, "a", "a1");
    t.resume(3000, "a", "a1");
    t.pause(3500, "a", "a1");
    values = t.finish();
    eq(values.get("a"), 1000L, "duplicate resume and reset close once");
    eq(values.get("unknown"), null, "never infer beginning from orphan pause");
    t = new UsageTimeline(1000, 5000);
    t.resume(1200, "a", "1");
    t.resume(1200, "b", "2");
    t.pause(2200, "a", "1");
    t.pause(2200, "b", "2");
    values = t.finish();
    eq(values.get("a"), 1000L, "multiwindow a");
    eq(values.get("b"), 1000L, "multiwindow b");
    t = new UsageTimeline(1000, 5000);
    t.resume(0, "a", "1");
    t.pause(500, "a", "1");
    eq(t.finish().get("a"), null, "entirely before period ignored");
    System.out.println("UsageTimelineTest: " + checks + " checks passed");
  }

  private static void eq(Object a, Object b, String msg) {
    checks++;
    if (a == null ? b != null : !a.equals(b)) throw new AssertionError(msg + ": " + a);
  }
}
