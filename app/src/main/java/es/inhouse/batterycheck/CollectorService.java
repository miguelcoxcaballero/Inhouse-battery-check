package es.inhouse.batterycheck;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthTdscdma;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Low-frequency observer. No wake locks, exact alarms, uploads or forced app restrictions. */
@SuppressWarnings("deprecation")
public final class CollectorService extends Service {
  private static final String START = "es.inhouse.batterycheck.START";
  private static final String PAUSE = "es.inhouse.batterycheck.PAUSE";
  private static final String STOP = "es.inhouse.batterycheck.STOP";
  private static final String REFRESH = "es.inhouse.batterycheck.REFRESH";
  private static final String SNAPSHOT = "es.inhouse.batterycheck.SNAPSHOT";
  private static final String CHANNEL = "local_monitor";
  private static final int NOTIFICATION = 41;
  private static final long PERIOD_MS = 5 * 60 * 1000L;
  private static volatile boolean running;
  private static volatile CollectorService instance;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private Store store;
  private SharedPreferences prefs;
  private BatteryManager battery;
  private PowerManager power;
  private ConnectivityManager connectivity;
  private TelephonyManager phone;
  private TelephonyCallback phoneCallback;
  private PhoneStateListener oldPhoneListener;
  private ConnectivityManager.NetworkCallback networkCallback;
  private PowerManager.OnThermalStatusChangedListener thermalListener;
  private SignalStrength signal;
  private int dataType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
  private int displayOverride;
  private boolean noCellularService, radioOff;
  private int subscribedId = Integer.MIN_VALUE;
  private int boot = Integer.MIN_VALUE;
  private boolean receiverRegistered, active, finishing;
  private String continuity;
  private Sample last;

  public static boolean isRunning() {
    return running;
  }

  public static void start(Context c) {
    Context app = c.getApplicationContext();
    try {
      app.startForegroundService(new Intent(app, CollectorService.class).setAction(START));
    } catch (RuntimeException e) {
      app.getSharedPreferences("inhouse", MODE_PRIVATE)
          .edit()
          .putString("monitorState", "STOPPED")
          .putString(
              "lastError",
              "No se pudo iniciar el registro. Abre la app y vuelve a pulsar Iniciar. "
                  + e.getClass().getSimpleName())
          .apply();
    }
  }

  public static void pause(Context c) {
    command(c, PAUSE, "PAUSED");
  }

  public static void stop(Context c) {
    command(c, STOP, "STOPPED");
  }

  /** Capture a real test boundary. Immediate on the UI thread in our single process. */
  public static void snapshot(Context c) {
    CollectorService current = instance;
    if (current != null && running && Looper.myLooper() == Looper.getMainLooper()) {
      current.record("PERIODIC", true);
    } else if (running) command(c, SNAPSHOT, null);
  }

  /** Call when returning from settings: picks up revoked or newly granted phone permission. */
  public static void refresh(Context c) {
    if (running) command(c, REFRESH, null);
  }

  private static void command(Context c, String action, String fallback) {
    CollectorService current = instance;
    if (current != null
        && running
        && Looper.myLooper() == Looper.getMainLooper()
        && (PAUSE.equals(action) || STOP.equals(action))) {
      // Finish the final sample before the Activity can clear the database.
      current.finish(PAUSE.equals(action));
      return;
    }
    Context app = c.getApplicationContext();
    if (!running) {
      if (fallback != null)
        app.getSharedPreferences("inhouse", MODE_PRIVATE)
            .edit()
            .putString("monitorState", fallback)
            .apply();
      return;
    }
    try {
      app.startService(new Intent(app, CollectorService.class).setAction(action));
    } catch (RuntimeException e) {
      app.getSharedPreferences("inhouse", MODE_PRIVATE)
          .edit()
          .putString(
              "lastError", "No se pudo enviar la orden al monitor: " + e.getClass().getSimpleName())
          .apply();
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    instance = this;
    prefs = getSharedPreferences("inhouse", MODE_PRIVATE);
    store = new Store(this);
    battery = getSystemService(BatteryManager.class);
    power = getSystemService(PowerManager.class);
    connectivity = getSystemService(ConnectivityManager.class);
    continuity = UUID.randomUUID().toString();
    try {
      boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT);
    } catch (Exception ignored) {
    }
    NotificationChannel channel =
        new NotificationChannel(
            CHANNEL, "Registro local de batería", NotificationManager.IMPORTANCE_LOW);
    channel.setDescription(
        "Permite mantener la monitorización visible; puedes pausarla o detenerla aquí.");
    getSystemService(NotificationManager.class).createNotificationChannel(channel);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? null : intent.getAction();
    if (PAUSE.equals(action) || STOP.equals(action)) {
      finish(PAUSE.equals(action));
      return START_NOT_STICKY;
    }
    if (SNAPSHOT.equals(action)) {
      if (active) record("PERIODIC", true);
      else stopSelf();
      return START_NOT_STICKY;
    }
    if (REFRESH.equals(action)) {
      if (active) {
        syncPhone();
        record("SIGNAL", false);
      } else stopSelf();
      return START_NOT_STICKY;
    }
    if (!START.equals(action)) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (active) return START_NOT_STICKY;
    try {
      Notification n = notification(null);
      if (Build.VERSION.SDK_INT >= 34)
        startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
      else startForeground(NOTIFICATION, n);
    } catch (RuntimeException e) {
      prefs
          .edit()
          .putString("monitorState", "STOPPED")
          .putString(
              "lastError",
              "Android no permitió el servicio de monitorización: " + e.getClass().getSimpleName())
          .apply();
      stopSelf();
      return START_NOT_STICKY;
    }
    boolean resuming = prefs.getLong("lastSample", 0) > 0;
    active = true;
    running = true;
    prefs.edit().putString("monitorState", "RUNNING").remove("lastError").apply();
    event(
        "INICIO",
        "Registro iniciado por el usuario. Nueva continuidad; los huecos anteriores no se"
            + " rellenan.");
    record(resuming ? "RESUME" : "START", true);
    if (!active) return START_NOT_STICKY;
    registerObservers();
    handler.postDelayed(periodic, PERIOD_MS);
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private final Runnable periodic =
      new Runnable() {
        @Override
        public void run() {
          if (!active) return;
          syncPhone();
          record("PERIODIC", true);
          if (active) handler.postDelayed(this, PERIOD_MS);
        }
      };
  private final BroadcastReceiver receiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (!active) return;
          String a = intent.getAction();
          if (Intent.ACTION_SCREEN_ON.equals(a)) record("SCREEN_ON", false);
          else if (Intent.ACTION_SCREEN_OFF.equals(a)) record("SCREEN_OFF", false);
          else if (Intent.ACTION_POWER_CONNECTED.equals(a)
              || Intent.ACTION_POWER_DISCONNECTED.equals(a)) record("POWER", false);
          else if (Intent.ACTION_BATTERY_CHANGED.equals(a)) record("BATTERY", false);
          else if (Intent.ACTION_TIME_CHANGED.equals(a)
              || Intent.ACTION_TIMEZONE_CHANGED.equals(a)) {
            event(
                "RELOJ",
                "Cambio de hora o zona. Se conserva la hora local y el reloj monotónico de cada"
                    + " muestra.");
            record("PERIODIC", true);
          }
        }
      };

  private void registerObservers() {
    IntentFilter f = new IntentFilter();
    f.addAction(Intent.ACTION_BATTERY_CHANGED);
    f.addAction(Intent.ACTION_SCREEN_ON);
    f.addAction(Intent.ACTION_SCREEN_OFF);
    f.addAction(Intent.ACTION_POWER_CONNECTED);
    f.addAction(Intent.ACTION_POWER_DISCONNECTED);
    f.addAction(Intent.ACTION_TIME_CHANGED);
    f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
    try {
      if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
      else registerReceiver(receiver, f);
      receiverRegistered = true;
    } catch (RuntimeException e) {
      event(
          "LIMITACIÓN",
          "No se pudieron observar los cambios de pantalla/batería; quedan las muestras"
              + " periódicas.");
    }
    try {
      networkCallback =
          new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
              networkChanged();
            }

            @Override
            public void onLost(Network n) {
              networkChanged();
            }

            @Override
            public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
              networkChanged();
            }
          };
      if (connectivity != null) connectivity.registerDefaultNetworkCallback(networkCallback);
    } catch (RuntimeException e) {
      networkCallback = null;
      event("LIMITACIÓN", "Sin callback de conexión; se consultará en cada muestra.");
    }
    try {
      thermalListener =
          status ->
              handler.post(
                  () -> {
                    if (active) record("THERMAL", false);
                  });
      if (power != null) power.addThermalStatusListener(getMainExecutor(), thermalListener);
    } catch (RuntimeException e) {
      thermalListener = null;
    }
    syncPhone();
  }

  private void networkChanged() {
    handler.post(
        () -> {
          if (active) record("NETWORK", false);
        });
  }

  private boolean canReadPhone() {
    return checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void syncPhone() {
    if (!canReadPhone()) {
      unregisterPhone();
      return;
    }
    int sub = SubscriptionManager.getDefaultDataSubscriptionId();
    if (phone != null && sub == subscribedId) return;
    unregisterPhone();
    try {
      TelephonyManager base = getSystemService(TelephonyManager.class);
      if (base == null) return;
      phone =
          SubscriptionManager.isValidSubscriptionId(sub) ? base.createForSubscriptionId(sub) : base;
      subscribedId = sub;
      try {
        dataType = phone.getDataNetworkType();
      } catch (RuntimeException ignored) {
        dataType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
      }
      // Cached signal is deliberately not used: observe a callback in this continuity.
      if (Build.VERSION.SDK_INT >= 31) {
        PhoneObserver observer = new PhoneObserver();
        phoneCallback = observer;
        phone.registerTelephonyCallback(getMainExecutor(), observer);
      } else {
        oldPhoneListener =
            new PhoneStateListener(getMainExecutor()) {
              @Override
              public void onServiceStateChanged(ServiceState state) {
                serviceChanged(state);
              }

              @Override
              public void onSignalStrengthsChanged(SignalStrength s) {
                signal = s;
                if (active) record("SIGNAL", false);
              }

              @Override
              public void onDataConnectionStateChanged(int state, int networkType) {
                dataType = networkType;
                if (active) record("NETWORK", false);
              }
            };
        phone.listen(
            oldPhoneListener,
            PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
                | PhoneStateListener.LISTEN_SERVICE_STATE);
      }
    } catch (RuntimeException e) {
      unregisterPhone();
      event(
          "TELÉFONO",
          "Android no expuso señal/tecnología de datos con este permiso. El registro básico sigue"
              + " activo.");
    }
  }

  private final class PhoneObserver extends TelephonyCallback
      implements TelephonyCallback.SignalStrengthsListener,
          TelephonyCallback.DataConnectionStateListener,
          TelephonyCallback.DisplayInfoListener,
          TelephonyCallback.ServiceStateListener {
    @Override
    public void onServiceStateChanged(ServiceState state) {
      serviceChanged(state);
    }

    @Override
    public void onSignalStrengthsChanged(SignalStrength s) {
      signal = s;
      if (active) record("SIGNAL", false);
    }

    @Override
    public void onDataConnectionStateChanged(int state, int networkType) {
      dataType = networkType;
      if (active) record("NETWORK", false);
    }

    @Override
    public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
      displayOverride = info.getOverrideNetworkType();
      if (active) record("NETWORK", false);
    }
  }

  /**
   * getState() describes voice, so it alone never proves missing data service. Only registration
   * flags of WWAN domains are read. No cell IDs, operators or location.
   */
  private void serviceChanged(ServiceState state) {
    radioOff = state != null && state.getState() == ServiceState.STATE_POWER_OFF;
    noCellularService = false;
    if (state != null && !radioOff && Build.VERSION.SDK_INT >= 30) {
      boolean sawDataDomain = false, registeredCellular = false;
      try {
        for (NetworkRegistrationInfo info : state.getNetworkRegistrationInfoList()) {
          if (info.getTransportType() != AccessNetworkConstants.TRANSPORT_TYPE_WWAN) continue;
          boolean registered =
              Build.VERSION.SDK_INT >= 34 ? info.isNetworkRegistered() : info.isRegistered();
          if (registered) registeredCellular = true;
          if ((info.getDomain() & NetworkRegistrationInfo.DOMAIN_PS) != 0) {
            sawDataDomain = true;
            if (registered) dataType = info.getAccessNetworkTechnology();
          }
        }
        noCellularService =
            sawDataDomain
                && !registeredCellular
                && state.getState() != ServiceState.STATE_IN_SERVICE;
      } catch (RuntimeException ignored) {
        noCellularService = false;
      }
    }
    if (noCellularService || radioOff) {
      signal = null;
      dataType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
      displayOverride = 0;
    }
    if (active) record("SERVICE", false);
  }

  private void unregisterPhone() {
    if (phone != null) {
      try {
        if (Build.VERSION.SDK_INT >= 31 && phoneCallback != null)
          phone.unregisterTelephonyCallback(phoneCallback);
      } catch (RuntimeException ignored) {
      }
      try {
        if (oldPhoneListener != null)
          phone.listen(oldPhoneListener, PhoneStateListener.LISTEN_NONE);
      } catch (RuntimeException ignored) {
      }
    }
    phone = null;
    phoneCallback = null;
    oldPhoneListener = null;
    signal = null;
    dataType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
    displayOverride = 0;
    subscribedId = Integer.MIN_VALUE;
    noCellularService = false;
    radioOff = false;
  }

  private Sample capture(String reason) {
    Sample s = new Sample();
    s.wallMs = System.currentTimeMillis();
    s.elapsedMs = SystemClock.elapsedRealtime();
    s.boot = boot;
    s.continuity = continuity;
    s.reason = reason;
    s.zone = ZoneId.systemDefault().getId();
    try {
      if (power != null) {
        s.screen = power.isInteractive() ? 1 : 0;
        s.thermal = power.getCurrentThermalStatus();
      }
    } catch (RuntimeException ignored) {
    }
    // These events define the boundary's new state even on OEMs with delayed isInteractive updates.
    if ("SCREEN_ON".equals(reason)) s.screen = 1;
    if ("SCREEN_OFF".equals(reason)) s.screen = 0;
    try {
      Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      if (b != null) {
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level >= 0 && scale > 0 && level <= scale)
          s.level = (int) Math.round(100.0 * level / scale);
        s.plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, Integer.MIN_VALUE);
        int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (t != Integer.MIN_VALUE) s.tempC = t / 10.0;
        s.voltageMv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Integer.MIN_VALUE);
        prefs
            .edit()
            .putInt(
                "healthStatus",
                b.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN))
            .apply();
      }
    } catch (RuntimeException ignored) {
    }
    if (battery != null) {
      try {
        s.chargeUah = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        if (s.chargeUah < 0) s.chargeUah = Integer.MIN_VALUE;
      } catch (RuntimeException ignored) {
      }
      try {
        s.currentUa = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
      } catch (RuntimeException ignored) {
      }
    }
    try {
      Network current = connectivity == null ? null : connectivity.getActiveNetwork();
      if (current == null) s.network = "NONE";
      else {
        NetworkCapabilities n = connectivity.getNetworkCapabilities(current);
        if (n == null) s.network = "UNKNOWN";
        else if (n.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s.network = "WIFI";
        else if (n.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s.network = "MOBILE";
        else s.network = "OTHER";
      }
    } catch (RuntimeException ignored) {
    }
    if (canReadPhone()) {
      s.radio =
          radioOff
              ? "Radio móvil apagada"
              : noCellularService ? "Sin servicio" : radioName(dataType, displayOverride);
      if (radioOff || noCellularService) s.signalLevel = 0;
      CellSignalStrength strength = (radioOff || noCellularService) ? null : dataSignal();
      if (strength != null) {
        int dbm = strength.getDbm();
        if (dbm < 0 && dbm > -200) {
          s.signalDbm = dbm;
          s.signalLevel = strength.getLevel();
        }
      }
    } else {
      s.radio = "Sin permiso de teléfono";
      // A revoked permission must not leave old signal values in fresh rows.
      if (phone != null) unregisterPhone();
    }
    return s;
  }

  private CellSignalStrength dataSignal() {
    if (signal == null) return null;
    Class<? extends CellSignalStrength> type;
    switch (dataType) {
      case TelephonyManager.NETWORK_TYPE_NR:
        type = CellSignalStrengthNr.class;
        break;
      case TelephonyManager.NETWORK_TYPE_LTE:
      case 19:
        type = CellSignalStrengthLte.class;
        break;
      case TelephonyManager.NETWORK_TYPE_GPRS:
      case TelephonyManager.NETWORK_TYPE_EDGE:
      case TelephonyManager.NETWORK_TYPE_GSM:
        type = CellSignalStrengthGsm.class;
        break;
      case TelephonyManager.NETWORK_TYPE_UMTS:
      case TelephonyManager.NETWORK_TYPE_HSDPA:
      case TelephonyManager.NETWORK_TYPE_HSUPA:
      case TelephonyManager.NETWORK_TYPE_HSPA:
      case TelephonyManager.NETWORK_TYPE_HSPAP:
        type = CellSignalStrengthWcdma.class;
        break;
      case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
        type = CellSignalStrengthTdscdma.class;
        break;
      case TelephonyManager.NETWORK_TYPE_CDMA:
      case TelephonyManager.NETWORK_TYPE_EVDO_0:
      case TelephonyManager.NETWORK_TYPE_EVDO_A:
      case TelephonyManager.NETWORK_TYPE_EVDO_B:
      case TelephonyManager.NETWORK_TYPE_1xRTT:
      case TelephonyManager.NETWORK_TYPE_EHRPD:
        type = CellSignalStrengthCdma.class;
        break;
      default:
        return null;
    }
    try {
      List<? extends CellSignalStrength> strengths = signal.getCellSignalStrengths(type);
      for (CellSignalStrength s : strengths) if (s.getDbm() < 0 && s.getDbm() > -200) return s;
    } catch (RuntimeException ignored) {
    }
    return null;
  }

  private static String radioName(int type, int override) {
    if (type == TelephonyManager.NETWORK_TYPE_NR) return "5G NR";
    if (type == TelephonyManager.NETWORK_TYPE_LTE || type == 19) {
      if (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA
          || override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)
        return "5G indicado (base LTE; señal LTE)";
      return "4G LTE (NSA no descartado)";
    }
    switch (type) {
      case TelephonyManager.NETWORK_TYPE_GPRS:
        return "2G GPRS";
      case TelephonyManager.NETWORK_TYPE_EDGE:
        return "2G EDGE";
      case TelephonyManager.NETWORK_TYPE_GSM:
        return "2G GSM";
      case TelephonyManager.NETWORK_TYPE_UMTS:
        return "3G UMTS";
      case TelephonyManager.NETWORK_TYPE_HSDPA:
      case TelephonyManager.NETWORK_TYPE_HSUPA:
      case TelephonyManager.NETWORK_TYPE_HSPA:
      case TelephonyManager.NETWORK_TYPE_HSPAP:
        return "3G HSPA";
      case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
        return "3G TD-SCDMA";
      case TelephonyManager.NETWORK_TYPE_CDMA:
      case TelephonyManager.NETWORK_TYPE_1xRTT:
        return "CDMA";
      case TelephonyManager.NETWORK_TYPE_EVDO_0:
      case TelephonyManager.NETWORK_TYPE_EVDO_A:
      case TelephonyManager.NETWORK_TYPE_EVDO_B:
      case TelephonyManager.NETWORK_TYPE_EHRPD:
        return "3G CDMA";
      case TelephonyManager.NETWORK_TYPE_IWLAN:
        return "IWLAN (datos de telefonía)";
      default:
        return "Datos móviles no identificados";
    }
  }

  private void record(String reason, boolean force) {
    if (!active) return;
    Sample s = capture(reason);
    if (!force && last != null && !materialChange(last, s)) return;
    try {
      store.insert(s);
      last = s;
      prefs.edit().putLong("lastSample", s.wallMs).apply();
      getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(s));
    } catch (RuntimeException e) {
      prefs
          .edit()
          .putString("monitorState", "STOPPED")
          .putString(
              "lastError",
              "Se interrumpió el registro local: "
                  + e.getClass().getSimpleName()
                  + ". Comprueba el espacio disponible y reanuda desde la app.")
          .apply();
      active = false;
      running = false;
      finishing = true;
      cleanup();
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
    }
  }

  private static boolean materialChange(Sample a, Sample b) {
    if (a.level != b.level
        || a.plugged != b.plugged
        || a.screen != b.screen
        || a.thermal != b.thermal
        || !a.network.equals(b.network)
        || !a.radio.equals(b.radio)) return true;
    if (Double.isNaN(a.tempC) != Double.isNaN(b.tempC) || Math.abs(a.tempC - b.tempC) >= 0.5)
      return true;
    if (a.signalDbm == Integer.MIN_VALUE && b.signalDbm != Integer.MIN_VALUE) return true;
    if (a.signalDbm != Integer.MIN_VALUE && b.signalDbm == Integer.MIN_VALUE) return true;
    return b.elapsedMs - a.elapsedMs >= 60_000L
        && (a.signalLevel != b.signalLevel || Math.abs((long) a.signalDbm - b.signalDbm) >= 5);
  }

  private Notification notification(Sample s) {
    Intent open =
        new Intent(this, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent content =
        PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent pause =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, CollectorService.class).setAction(PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            2,
            new Intent(this, CollectorService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String detail = "Registro local activo · sin bloqueos del procesador";
    if (s != null)
      detail =
          (s.level >= 0 && s.level <= 100 ? s.level + " %" : "Batería sin lectura")
              + (Double.isNaN(s.tempC)
                  ? ""
                  : String.format(new Locale("es", "ES"), " · %.1f °C", s.tempC))
              + " · Registro local";
    Notification.Builder b =
        new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Inhouse battery check")
            .setContentText(detail)
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(null, "Pausar", pause).build())
            .addAction(new Notification.Action.Builder(null, "Detener", stop).build());
    if (Build.VERSION.SDK_INT >= 31)
      b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
    return b.build();
  }

  private void event(String kind, String note) {
    try {
      store.event(kind, note);
    } catch (RuntimeException ignored) {
    }
  }

  private void finish(boolean pause) {
    finishing = true;
    if (active) record(pause ? "PAUSE" : "STOP", true);
    active = false;
    running = false;
    prefs.edit().putString("monitorState", pause ? "PAUSED" : "STOPPED").apply();
    event(
        pause ? "PAUSA" : "FIN",
        pause
            ? "Pausa solicitada por el usuario. Reanuda desde la app."
            : "Monitorización detenida por el usuario.");
    cleanup();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  private void cleanup() {
    handler.removeCallbacksAndMessages(null);
    if (receiverRegistered) {
      try {
        unregisterReceiver(receiver);
      } catch (RuntimeException ignored) {
      }
      receiverRegistered = false;
    }
    if (networkCallback != null && connectivity != null) {
      try {
        connectivity.unregisterNetworkCallback(networkCallback);
      } catch (RuntimeException ignored) {
      }
      networkCallback = null;
    }
    if (thermalListener != null && power != null) {
      try {
        power.removeThermalStatusListener(thermalListener);
      } catch (RuntimeException ignored) {
      }
      thermalListener = null;
    }
    unregisterPhone();
  }

  @Override
  public void onDestroy() {
    if (active && !finishing) {
      record("PAUSE", true);
      prefs
          .edit()
          .putString("monitorState", "PAUSED")
          .putString(
              "lastError",
              "El servicio se interrumpió. Reanuda manualmente; no se rellenarán los huecos.")
          .apply();
      event("INTERRUPCIÓN", "Servicio interrumpido; el reinicio requiere acción del usuario.");
    }
    active = false;
    running = false;
    if (instance == this) instance = null;
    cleanup();
    if (store != null) store.close();
    super.onDestroy();
  }
}
