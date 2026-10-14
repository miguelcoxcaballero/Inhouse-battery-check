package es.inhouse.batterycheck;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Process;
import android.os.UserManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads optional Android usage/network statistics; never computes app energy from usage. Invoke off
 * the UI thread. AppOps can change while a query is running.
 */
public final class UsageReader {
  private UsageReader() {}

  public static final class AppUsage {
    public int uid = -1;
    public String packageName = "";
    public String name = "";
    public long foregroundMs;
    public Long rxBytes, txBytes;
    public Long wifiRxBytes, wifiTxBytes, mobileRxBytes, mobileTxBytes;
    public boolean sharedUid;
    public String note = "";
  }

  public static final class Result {
    public boolean usageAccess;
    public boolean eventsAvailable;
    public final List<AppUsage> apps = new ArrayList<>();
    public String note = "";
    public long startMs, endMs;
  }

  public static boolean hasUsageAccess(Context context) {
    try {
      AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
      return ops != null
          && ops.unsafeCheckOpNoThrow(
                  AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName())
              == AppOpsManager.MODE_ALLOWED;
    } catch (RuntimeException e) {
      return false;
    }
  }

  public static Result read(Context context, long start, long end) {
    Result out = new Result();
    out.startMs = start;
    out.endMs = end;
    out.usageAccess = hasUsageAccess(context);
    if (!out.usageAccess) {
      out.note =
          "Concede Acceso a datos de uso para consultar primer plano y tráfico. Esto no concede"
              + " acceso a energía por app.";
      return out;
    }
    if (end <= start || start < 0) {
      out.note = "Periodo de consulta no válido.";
      return out;
    }
    UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
    if (users != null && !users.isUserUnlocked()) {
      out.note = "Desbloquea el teléfono para consultar el historial de uso.";
      return out;
    }
    long queryEnd = Math.min(end, System.currentTimeMillis());
    if (queryEnd <= start) {
      out.note = "No existen datos futuros.";
      return out;
    }
    Map<String, Long> foreground = new HashMap<>();
    try {
      UsageStatsManager manager =
          (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
      // Look back to reconstruct an activity resumed before the selected boundary.
      UsageEvents events =
          manager == null ? null : manager.queryEvents(Math.max(0, start - 86400000L), queryEnd);
      if (events != null) {
        UsageTimeline timeline = new UsageTimeline(start, queryEnd);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
          events.getNextEvent(event);
          long time = event.getTimeStamp();
          // Public SDK does not expose activity instance IDs. Class-level union is conservative.
          String key = String.valueOf(event.getClassName());
          switch (event.getEventType()) {
            case UsageEvents.Event.ACTIVITY_RESUMED:
              timeline.resume(time, event.getPackageName(), key);
              break;
            case UsageEvents.Event.ACTIVITY_PAUSED:
            case UsageEvents.Event.ACTIVITY_STOPPED:
              timeline.pause(time, event.getPackageName(), key);
              break;
            case UsageEvents.Event.DEVICE_SHUTDOWN:
            case UsageEvents.Event.DEVICE_STARTUP:
            case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
              timeline.reset(time);
              break;
            default:
              break;
          }
        }
        foreground = timeline.finish();
        out.eventsAvailable = true;
      }
    } catch (RuntimeException e) {
      out.note = "Android no ha permitido leer los eventos de uso. ";
    }
    Map<Integer, Bytes> wifi = network(context, ConnectivityManager.TYPE_WIFI, start, queryEnd);
    Map<Integer, Bytes> mobile = network(context, ConnectivityManager.TYPE_MOBILE, start, queryEnd);
    PackageManager pm = context.getPackageManager();
    Map<String, AppUsage> byPackage = new HashMap<>();
    Set<Integer> represented = new HashSet<>();
    for (Map.Entry<String, Long> entry : foreground.entrySet()) {
      AppUsage app = app(pm, entry.getKey(), -1);
      app.foregroundMs = entry.getValue();
      byPackage.put(app.packageName, app);
      if (app.uid >= 0) represented.add(app.uid);
    }
    Set<Integer> networkUids = new HashSet<>();
    if (wifi != null) networkUids.addAll(wifi.keySet());
    if (mobile != null) networkUids.addAll(mobile.keySet());
    // Include background traffic even when an app has no observed foreground activity.
    for (int uid : networkUids) {
      if (uid < 0 || represented.contains(uid)) continue;
      String[] packages = null;
      try {
        packages = pm.getPackagesForUid(uid);
      } catch (RuntimeException ignored) {
      }
      String pkg = packages != null && packages.length > 0 ? packages[0] : "uid:" + uid;
      AppUsage app = app(pm, pkg, uid);
      byPackage.put(pkg, app);
      represented.add(uid);
    }
    Set<Integer> shared = new HashSet<>();
    Set<Integer> first = new HashSet<>();
    for (AppUsage app : byPackage.values())
      if (app.uid >= 0 && !first.add(app.uid)) shared.add(app.uid);
    for (AppUsage app : byPackage.values()) {
      if (app.uid >= 0) {
        Bytes w = wifi == null ? null : wifi.getOrDefault(app.uid, new Bytes());
        Bytes m = mobile == null ? null : mobile.getOrDefault(app.uid, new Bytes());
        if (w != null) {
          app.wifiRxBytes = w.rx;
          app.wifiTxBytes = w.tx;
        }
        if (m != null) {
          app.mobileRxBytes = m.rx;
          app.mobileTxBytes = m.tx;
        }
        if (w != null && m != null) {
          app.rxBytes = w.rx + m.rx;
          app.txBytes = w.tx + m.tx;
        }
        String[] packages = null;
        try {
          packages = pm.getPackagesForUid(app.uid);
        } catch (RuntimeException ignored) {
        }
        app.sharedUid = shared.contains(app.uid) || (packages != null && packages.length > 1);
        if (app.sharedUid)
          app.note = "Tráfico compartido por UID: no sumarlo entre los paquetes de este UID.";
      }
      out.apps.add(app);
    }
    Collections.sort(out.apps, Comparator.comparingLong((AppUsage a) -> a.foregroundMs).reversed());
    out.usageAccess = hasUsageAccess(context);
    if (!out.usageAccess) {
      out.apps.clear();
      out.eventsAvailable = false;
      out.note = "Acceso a datos de uso revocado durante la consulta.";
      return out;
    }
    out.note +=
        "Tiempo de actividades en primer plano observado por Android, limitado al periodo elegido;"
            + " no es energía. El historial que conserva Android puede ser parcial (habitualmente"
            + " pocos días); ausencia de eventos no significa uso cero. Se reconstruyen límites con"
            + " hasta 24 h previas y se descartan intervalos sin inicio conocido. En multiventana"
            + " varias apps pueden solaparse; instancias de la misma actividad pueden no"
            + " distinguirse. Tráfico: Wi-Fi y móvil del UID, con la resolución/retención del"
            + " sistema; incluye segundo plano, no mide consumo de batería. ";
    if (wifi == null || mobile == null)
      out.note +=
          "Tráfico "
              + (wifi == null ? "Wi-Fi " : "")
              + (mobile == null ? "móvil " : "")
              + "no disponible; no se muestra como cero. ";
    if (!out.eventsAvailable) out.note += "No se han podido consultar eventos de primer plano. ";
    return out;
  }

  private static AppUsage app(PackageManager pm, String pkg, int uid) {
    AppUsage app = new AppUsage();
    app.packageName = pkg;
    app.name = pkg;
    app.uid = uid;
    try {
      ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
      app.uid = info.uid;
      app.name = String.valueOf(pm.getApplicationLabel(info));
    } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
      if (uid >= 0 && pkg.startsWith("uid:")) app.name = "UID " + uid + " (app no visible)";
    }
    return app;
  }

  private static final class Bytes {
    long rx, tx;
  }

  @SuppressWarnings("deprecation")
  private static Map<Integer, Bytes> network(Context context, int type, long start, long end) {
    NetworkStats stats = null;
    try {
      NetworkStatsManager manager =
          (NetworkStatsManager) context.getSystemService(Context.NETWORK_STATS_SERVICE);
      if (manager == null) return null;
      // null subscriber ID selects all subscriptions; no subscriber identifier is collected.
      stats = manager.querySummary(type, null, start, end);
      if (stats == null) return null;
      Map<Integer, Bytes> values = new HashMap<>();
      NetworkStats.Bucket bucket = new NetworkStats.Bucket();
      while (stats.hasNextBucket()) {
        if (!stats.getNextBucket(bucket)) break;
        if (bucket.getUid() < 0) continue; // removed/tethered aggregates are not apps
        Bytes b = values.get(bucket.getUid());
        if (b == null) {
          b = new Bytes();
          values.put(bucket.getUid(), b);
        }
        b.rx += bucket.getRxBytes();
        b.tx += bucket.getTxBytes();
      }
      return values;
    } catch (Exception e) {
      return null;
    } finally {
      if (stats != null) stats.close();
    }
  }
}
