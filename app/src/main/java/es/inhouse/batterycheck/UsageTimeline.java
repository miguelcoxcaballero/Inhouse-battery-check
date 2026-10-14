package es.inhouse.batterycheck;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Union of observed resumed activities per package, clipped to an explicit window. Multiple resumed
 * apps can overlap (multi-window); durations are not energy weights.
 */
final class UsageTimeline {
  private final long begin, end;
  private final Map<String, Set<String>> active = new HashMap<>();
  private final Map<String, Long> starts = new HashMap<>();
  private final Map<String, Long> totals = new HashMap<>();

  UsageTimeline(long begin, long end) {
    this.begin = begin;
    this.end = end;
  }

  void resume(long time, String pkg, String activity) {
    if (pkg == null || time > end) return;
    Set<String> instances = active.get(pkg);
    if (instances == null) {
      instances = new HashSet<>();
      active.put(pkg, instances);
    }
    if (instances.isEmpty()) starts.put(pkg, time);
    instances.add(activity);
  }

  void pause(long time, String pkg, String activity) {
    Set<String> instances = active.get(pkg);
    if (instances == null || !instances.remove(activity)) return;
    if (instances.isEmpty()) close(time, pkg);
  }

  void reset(long time) {
    for (String pkg : active.keySet()) {
      close(time, pkg);
      active.get(pkg).clear();
    }
  }

  Map<String, Long> finish() {
    reset(end);
    return totals;
  }

  private void close(long time, String pkg) {
    Long start = starts.remove(pkg);
    if (start == null) return;
    long amount = Math.max(0, Math.min(end, time) - Math.max(begin, start));
    if (amount > 0) totals.put(pkg, totals.getOrDefault(pkg, 0L) + amount);
  }
}
