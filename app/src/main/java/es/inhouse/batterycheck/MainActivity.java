package es.inhouse.batterycheck;

import android.Manifest;
import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
  static final int INK = 0xff18343d,
      MUTED = 0xff60747c,
      TEAL = 0xff167d7f,
      BG = 0xfff3f6f7,
      LIME = 0xffd3eea4;
  final Locale es = Locale.forLanguageTag("es-ES");
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  final Handler handler = new Handler(Looper.getMainLooper());
  Store store;
  SharedPreferences prefs;
  LinearLayout root, body, nav;
  TextView status;
  List<Sample> all = new ArrayList<>(), selected = new ArrayList<>();
  Analytics.Result result;
  int tab = 0, period = 0, wizard = 0, renderVersion = 0;
  boolean resumed = false;
  long customDay = System.currentTimeMillis(), from, to;
  AdvancedReport.Report advanced;
  String advancedText = "", exportText = "", exportMime = "text/plain";
  boolean shareAfter = false;

  boolean alive() {
    return !isFinishing() && !isDestroyed();
  }

  String[] tabs = {"Resumen", "Registro", "Apps", "Pruebas", "Ajustes"};

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    store = new Store(this);
    prefs = getSharedPreferences("inhouse", MODE_PRIVATE);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(0xffffffff);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    try {
      File f = new File(getFilesDir(), "advanced.txt");
      if (f.exists()) {
        advancedText = read(new FileInputStream(f), 5 * 1024 * 1024);
        advanced = AdvancedReport.parse(advancedText);
      }
    } catch (Exception ignored) {
    }
    if (state != null) {
      tab = state.getInt("tab", 0);
      period = state.getInt("period", 0);
      wizard = state.getInt("wizard", 0);
      customDay = state.getLong("customDay", customDay);
    }
    refresh();
  }

  @Override
  protected void onSaveInstanceState(Bundle s) {
    s.putInt("tab", tab);
    s.putInt("period", period);
    s.putInt("wizard", wizard);
    s.putLong("customDay", customDay);
    super.onSaveInstanceState(s);
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    CollectorService.refresh(this);
    refresh();
  }

  @Override
  protected void onPause() {
    resumed = false;

    super.onPause();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    worker.shutdown();
    store.close();
    super.onDestroy();
  }

  int dp(float v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  String n(double v) {
    return Double.isFinite(v) ? String.format(es, "%.1f", v) : "—";
  }

  String date(long ms) {
    return ms <= 0 ? "—" : new SimpleDateFormat("dd/MM/yyyy HH:mm", es).format(new Date(ms));
  }

  String duration(double h) {
    return Double.isFinite(h)
        ? String.format(es, "%d h %02d min", Math.round(h * 60) / 60, Math.round(h * 60) % 60)
        : "—";
  }

  boolean has(String p) {
    return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
  }

  boolean usageAccess() {
    try {
      return ((AppOpsManager) getSystemService(APP_OPS_SERVICE))
              .unsafeCheckOpNoThrow(
                  AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName())
          == AppOpsManager.MODE_ALLOWED;
    } catch (Exception e) {
      return false;
    }
  }

  GradientDrawable bg(int color, int rad) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(rad));
    return d;
  }

  TextView text(String s, int size, int color) {
    TextView v = new TextView(this);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setLineSpacing(dp(3), 1);
    return v;
  }

  TextView bold(String s, int size) {
    TextView v = text(s, size, INK);
    v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return v;
  }

  void para(LinearLayout target, String s) {
    TextView v = text(s, 14, MUTED);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(6);
    target.addView(v, lp);
  }

  LinearLayout card(String title) {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(1);
    c.setPadding(dp(17), dp(16), dp(17), dp(16));
    c.setBackground(bg(0xffffffff, 20));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(12);
    body.addView(c, lp);
    if (title != null) c.addView(bold(title, 18));
    return c;
  }

  void pill(LinearLayout target, String s) {
    TextView v = bold(s, 12);
    v.setTextColor(TEAL);
    v.setBackground(bg(0xffe7f2ef, 8));
    v.setPadding(dp(9), dp(5), dp(9), dp(5));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.topMargin = dp(9);
    target.addView(v, lp);
  }

  void button(LinearLayout parent, String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setTextColor(TEAL);
    b.setBackgroundTintList(ColorStateList.valueOf(0xffe6f1ee));
    b.setMinHeight(dp(46));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(7);
    parent.addView(b, lp);
    b.setOnClickListener(v -> action.run());
  }

  void primary(LinearLayout parent, String label, Runnable action) {
    int before = parent.getChildCount();
    button(parent, label, action);
    Button b = (Button) parent.getChildAt(before);
    b.setTextColor(0xffffffff);
    b.setBackgroundTintList(ColorStateList.valueOf(TEAL));
  }

  void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  void refresh() {
    if (isFinishing() || isDestroyed()) return;
    all = store.all();
    setRange();
    selected = new ArrayList<>();
    List<Sample> analysisInput = new ArrayList<>();
    boolean skipped = false;
    for (Sample s : all) {
      if (s.wallMs >= from && s.wallMs <= to) {
        if (skipped && !analysisInput.isEmpty()) analysisInput.add(null);
        selected.add(s);
        analysisInput.add(s);
        skipped = false;
      } else skipped = true;
    }
    result = Analytics.analyze(analysisInput);
    render();
  }

  void setRange() {
    to = System.currentTimeMillis();
    Calendar c = Calendar.getInstance();
    c.set(Calendar.HOUR_OF_DAY, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    from = c.getTimeInMillis();
    if (period == 1) {
      to = from;
      c.add(Calendar.DAY_OF_YEAR, -1);
      from = c.getTimeInMillis();
    } else if (period == 2) {
      c.add(Calendar.DAY_OF_YEAR, -6);
      from = c.getTimeInMillis();
    } else if (period == 3) {
      Analytics.Result a = Analytics.analyze(all);
      if (!a.sessions.isEmpty()) {
        Analytics.Session s = a.sessions.get(a.sessions.size() - 1);
        from = s.start;
        to = "En curso".equals(s.endReason) ? to : s.end;
      } else from = to;
    } else if (period == 4) {
      c.setTimeInMillis(customDay);
      c.set(Calendar.HOUR_OF_DAY, 0);
      c.set(Calendar.MINUTE, 0);
      c.set(Calendar.SECOND, 0);
      c.set(Calendar.MILLISECOND, 0);
      from = c.getTimeInMillis();
      c.add(Calendar.DAY_OF_YEAR, 1);
      to = Math.min(c.getTimeInMillis(), to);
    }
  }

  void scaffold(String subtitle) {
    root = new LinearLayout(this);
    root.setOrientation(1);
    root.setBackgroundColor(BG);
    root.setFitsSystemWindows(true);
    setContentView(root);
    if (Build.VERSION.SDK_INT >= 35)
      root.setOnApplyWindowInsetsListener(
          (v, in) -> {
            android.graphics.Insets a =
                in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(a.left, a.top, a.right, a.bottom);
            return in;
          });
    LinearLayout head = new LinearLayout(this);
    head.setOrientation(1);
    head.setPadding(dp(21), dp(15), dp(21), dp(8));
    root.addView(head);
    head.addView(bold("Inhouse battery check", 23));
    status = text(subtitle, 12, MUTED);
    head.addView(status);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    body = new LinearLayout(this);
    body.setOrientation(1);
    body.setPadding(dp(16), dp(12), dp(16), dp(18));
    scroll.addView(body);
  }

  void render() {
    renderVersion++;
    if (!prefs.getBoolean("onboarded", false)) {
      onboarding();
      return;
    }
    scaffold("DATOS LOCALES  ·  " + monitorLabel());
    nav = new LinearLayout(this);
    nav.setPadding(dp(3), dp(5), dp(3), dp(5));
    nav.setBackgroundColor(0xffffffff);
    root.addView(nav);
    for (int i = 0; i < tabs.length; i++) {
      final int t = i;
      TextView b = text(tabs[i], 11, tab == i ? TEAL : MUTED);
      b.setGravity(Gravity.CENTER);
      b.setTypeface(Typeface.DEFAULT, tab == i ? Typeface.BOLD : Typeface.NORMAL);
      b.setPadding(0, dp(15), 0, dp(15));
      if (tab == i) b.setBackground(bg(0xffe6f1ee, 14));
      nav.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
      b.setOnClickListener(
          v -> {
            tab = t;
            refresh();
          });
    }
    switch (tab) {
      case 0:
        dashboard();
        break;
      case 1:
        history();
        break;
      case 2:
        apps();
        break;
      case 3:
        tests();
        break;
      default:
        settings();
    }
  }

  String monitorLabel() {
    String s = prefs.getString("monitorState", "STOPPED");
    return CollectorService.isRunning()
        ? "REGISTRO ACTIVO"
        : s.equals("PAUSED") ? "EN PAUSA" : "REGISTRO DETENIDO";
  }

  void periodPicker() {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(1);
    body.addView(c);
    String[] values = {
      "Hoy", "Ayer", "Últimos 7 días", "Última sesión de descarga", "Elegir fecha…"
    };
    Spinner sp = new Spinner(this);
    ArrayAdapter<String> ad =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values);
    sp.setAdapter(ad);
    sp.setSelection(period);
    c.addView(sp);
    sp.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(android.widget.AdapterView<?> p) {}

          public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
            if (pos == period) return;
            period = pos;
            if (pos == 4) {
              Calendar now = Calendar.getInstance();
              new DatePickerDialog(
                      MainActivity.this,
                      (x, y, m, d) -> {
                        Calendar picked = Calendar.getInstance();
                        picked.set(y, m, d, 0, 0, 0);
                        customDay = picked.getTimeInMillis();
                        refresh();
                      },
                      now.get(Calendar.YEAR),
                      now.get(Calendar.MONTH),
                      now.get(Calendar.DAY_OF_MONTH))
                  .show();
            } else refresh();
          }
        });
    para(c, date(from) + " → " + date(to) + " · hora local");
    if (period == 4)
      button(
          c,
          "Cambiar fecha",
          () -> {
            Calendar pick = Calendar.getInstance();
            pick.setTimeInMillis(customDay);
            new DatePickerDialog(
                    this,
                    (x, y, m, d) -> {
                      Calendar chosen = Calendar.getInstance();
                      chosen.set(y, m, d, 0, 0, 0);
                      customDay = chosen.getTimeInMillis();
                      refresh();
                    },
                    pick.get(Calendar.YEAR),
                    pick.get(Calendar.MONTH),
                    pick.get(Calendar.DAY_OF_MONTH))
                .show();
          });
  }

  void onboarding() {
    scaffold("ASISTENTE DE INICIO · PASO " + (wizard + 1) + " DE 4");
    LinearLayout c;
    if (wizard == 0) {
      c = card("Vamos a buscar qué ocurre");
      para(
          c,
          "Batería, temperatura de batería, carga, pantalla y conexión básica se registran sin"
              + " permisos sensibles. No medimos la temperatura de CPU o GPU.");
      para(
          c,
          "Los datos se guardan en este móvil. No hay servidores, anuncios ni API de IA. El"
              + " historial empieza al activar el registro.");
      para(
          c,
          "Android limita el consumo por app: mostraremos uso y energía por separado. Puedes"
              + " introducir un dato manual de salud; se identificará como no verificado.");
      primary(
          c,
          "Siguiente: funciones opcionales",
          () -> {
            wizard = 1;
            render();
          });
    } else if (wizard == 1) {
      c = card("Elige qué quieres medir");
      permissionCards();
      c = card("Puedes continuar sin concederlos");
      para(
          c,
          "Las mediciones básicas seguirán disponibles. Al volver de Ajustes se comprueba cada"
              + " permiso.");
      primary(
          c,
          "Continuar",
          () -> {
            wizard = 2;
            render();
          });
      button(
          c,
          "Atrás",
          () -> {
            wizard = 0;
            render();
          });
    } else if (wizard == 2) {
      c = card("Un registro que respeta el reposo");
      para(
          c,
          "Usa una notificación persistente con Pausar y Detener. Recoge eventos del sistema y una"
              + " muestra cada 5 minutos cuando Android permite ejecutar el proceso. No mantiene el"
              + " procesador despierto.");
      para(
          c,
          "El sistema puede suspender o cerrar el registro. Los intervalos sin muestras se señalan"
              + " como huecos; no se cuentan como consumo cero. Tras reiniciar el móvil tendrás que"
              + " reanudarlo.");
      primary(
          c,
          "Siguiente: primera prueba",
          () -> {
            wizard = 3;
            render();
          });
      button(
          c,
          "Atrás",
          () -> {
            wizard = 1;
            render();
          });
    } else {
      c = card("Primero: comprobar el reposo");
      para(
          c,
          "Activa el registro, desconecta el cargador y deja el móvil bloqueado durante 60 minutos."
              + " Anota si está caliente antes y después. Pruebas te ayudará a guardar una"
              + " referencia y compararla con otro periodo.");
      para(
          c,
          "Para comparar redes, repite después con wifi y con datos móviles en condiciones"
              + " similares. No se modifican tus ajustes automáticamente.");
      primary(
          c,
          "Activar registro y empezar",
          () -> {
            prefs.edit().putBoolean("onboarded", true).apply();
            startMonitor();
          });
      button(
          c,
          "Entrar sin activar",
          () -> {
            prefs.edit().putBoolean("onboarded", true).apply();
            refresh();
          });
      button(
          c,
          "Atrás",
          () -> {
            wizard = 2;
            render();
          });
    }
  }

  void permissionCards() {
    LinearLayout c = card("Notificación de seguimiento");
    para(
        c,
        Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
            ? "Disponible · podrás pausar desde la notificación."
            : "No autorizada · Android permite el servicio, pero puede ocultar su notificación."
                + " Puedes parar desde la app o el gestor de aplicaciones activas.");
    button(
        c,
        "Gestionar notificaciones",
        () -> {
          if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS))
            askPermission(Manifest.permission.POST_NOTIFICATIONS, 101);
          else
            open(
                new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        });
    c = card("Uso de aplicaciones");
    para(
        c,
        usageAccess()
            ? "Concedido · tiempo de primer plano y tráfico por UID cuando Android lo exponga."
            : "Opcional · permite consultar tiempo en primer plano y tráfico. No mide energía ni"
                + " lee mensajes.");
    button(
        c,
        "Abrir acceso a datos de uso",
        () ->
            open(
                new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName()))));
    c = card("Red móvil");
    para(
        c,
        has(Manifest.permission.READ_PHONE_STATE)
            ? "Concedido · tecnología de datos y señal disponibles según el dispositivo."
            : "Opcional · Estado del teléfono permite observar la tecnología de datos y cambios de"
                + " cobertura. No pedimos GPS ni registramos identificadores de antenas.");
    button(
        c,
        "Permitir información de red",
        () -> {
          if (!has(Manifest.permission.READ_PHONE_STATE))
            askPermission(Manifest.permission.READ_PHONE_STATE, 102);
          else
            open(
                new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        });
  }

  void askPermission(String permission, int code) {
    String key = "requested:" + permission;
    if (prefs.getBoolean(key, false) && !shouldShowRequestPermissionRationale(permission)) {
      open(
          new Intent(
              Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.parse("package:" + getPackageName())));
      return;
    }
    prefs.edit().putBoolean(key, true).apply();
    requestPermissions(new String[] {permission}, code);
  }

  void open(Intent in) {
    try {
      startActivity(in);
    } catch (Exception e) {
      try {
        startActivity(new Intent(Settings.ACTION_SETTINGS));
      } catch (Exception x) {
        toast("No se pudo abrir Ajustes en este dispositivo.");
      }
    }
  }

  @Override
  public void onRequestPermissionsResult(int r, String[] p, int[] g) {
    super.onRequestPermissionsResult(r, p, g);
    if (r == 102) CollectorService.refresh(this);
    refresh();
  }

  void startMonitor() {
    try {
      CollectorService.start(this);
      handler.postDelayed(this::refresh, 700);
    } catch (Exception e) {
      toast("Android no ha permitido iniciar el registro: " + e.getClass().getSimpleName());
    }
    refresh();
  }

  void controls(LinearLayout c) {
    if (CollectorService.isRunning()) {
      button(
          c,
          "Pausar registro",
          () -> {
            CollectorService.pause(this);
            handler.postDelayed(this::refresh, 300);
          });
      button(
          c,
          "Detener registro",
          () -> {
            CollectorService.stop(this);
            handler.postDelayed(this::refresh, 300);
          });
    } else primary(c, "Activar / reanudar registro", this::startMonitor);
  }

  void dashboard() {
    Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    int lev = -1, plug = 0;
    double temp = Double.NaN;
    if (battery != null) {
      int raw = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
          scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
      if (raw >= 0 && scale > 0) lev = raw * 100 / scale;
      plug = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
      int t = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
      if (t != Integer.MIN_VALUE) temp = t / 10.0;
    }
    LinearLayout c = card(null);
    pill(c, plug > 0 ? "CONECTADO A CARGADOR" : "SIN CARGADOR");
    c.addView(bold((lev < 0 ? "—" : lev) + " %", 48));
    para(c, n(temp) + " °C · temperatura de batería actual");
    para(c, "Lectura actualizada: " + date(System.currentTimeMillis()));
    button(c, "Actualizar lectura", this::refresh);
    para(
        c,
        "Registro desde " + date(store.firstTime()) + ". " + all.size() + " muestras guardadas.");
    controls(c);
    periodPicker();
    if (advanced != null && advanced.recognized && !advanced.apps.isEmpty()) {
      c = card("Mayor consumo en el informe Android");
      AdvancedReport.Row top = advanced.apps.get(0);
      String[] packages = getPackageManager().getPackagesForUid(top.uid);
      String label = packages != null && packages.length > 0 ? appLabel(packages[0]) : top.label;
      para(
          c,
          label
              + " · "
              + n(top.mah)
              + " mAh estimados · "
              + n(advanced.percent(top.mah))
              + " % del total calculado por Android. Un UID puede agrupar varios paquetes.");
      if (!advanced.components.isEmpty()) {
        AdvancedReport.Row component = advanced.components.get(0);
        para(
            c,
            "Mayor componente global: "
                + component.label
                + " · "
                + n(component.mah)
                + " mAh. Se solapa con los UID; no sumar.");
      }
      pill(c, "ESTIMACIÓN DEL SISTEMA · NO DEMUESTRA CAUSA");
      para(
          c,
          "Periodo independiente del selector: "
              + advanced.period
              + (advanced.startClock.isEmpty() ? "" : " Inicio: " + advanced.startClock)
              + ". Importación: "
              + date(prefs.getLong("advancedImported", 0)));
      para(
          c,
          "Acción: revisa los detalles y compara una nueva sesión tras un único cambio. Figurar"
              + " primero no demuestra consumo anormal.");
      button(
          c,
          "Revisar ranking y evidencia",
          () -> {
            tab = 2;
            refresh();
          });
    }
    c = card("¿Qué está gastando más?");
    if (selected.size() < 2) {
      para(
          c,
          "Aún no hay suficientes mediciones para diagnosticar este periodo. Activa el registro y"
              + " recoge una primera sesión de al menos 60 minutos.");
      primary(
          c,
          "Preparar prueba de reposo",
          () -> {
            tab = 3;
            refresh();
          });
    } else {
      c.addView(
          bold(
              result.dischargeHours > 0
                  ? n(result.drop) + " puntos consumidos"
                  : "Sin tramos de descarga válidos",
              25));
      para(
          c,
          "En "
              + duration(result.dischargeHours)
              + " de intervalos de descarga observados. Media: "
              + rate(result.drop, result.dischargeHours)
              + " pp/h. El denominador es la escala de batería 0–100 %, no su capacidad en mAh.");
      para(
          c,
          "Ritmo del último tramo registrado ("
              + date(result.end)
              + "): "
              + n(result.recentRate)
              + " pp/h. Autonomía equivalente para ese ritmo: "
              + (Double.isFinite(result.recentRate) && result.recentRate > 0 && result.drop >= 5
                  ? duration(100 / result.recentRate)
                  : "datos insuficientes")
              + ". Extrapolación; no es una descarga medida desde el 100 %.");
      para(
          c,
          "Pantalla encendida: "
              + n(result.onDrop)
              + " pp / "
              + duration(result.onHours)
              + " · "
              + rate(result.onDrop, result.onHours)
              + " pp/h.\nPantalla apagada: "
              + n(result.offDrop)
              + " pp / "
              + duration(result.offHours)
              + " · "
              + rate(result.offDrop, result.offHours)
              + " pp/h.");
      para(
          c,
          "Pantalla se refiere al estado interactivo de Android; Always On Display puede quedar en"
              + " apagada. Son condiciones durante la descarga, no energía atribuida a la pantalla."
              + " No permiten repartir consumo entre apps, radio y componentes compartidos.");
      if (result.gaps > 0) pill(c, result.gaps + " huecos o discontinuidades excluidos");
      if (result.end > 0 && System.currentTimeMillis() - result.end > 900000)
        para(
            c,
            "Sin muestras recientes: han pasado "
                + duration((System.currentTimeMillis() - result.end) / 3600000.0)
                + " desde el final del periodo medido. El ritmo anterior no describe la descarga"
                + " actual.");
      if (advanced != null && advanced.recognized) {
        para(
            c,
            "Hay un informe avanzado importado con estimaciones por UID. Su periodo es"
                + " independiente del registro local.");
        button(
            c,
            "Ver energía importada",
            () -> {
              tab = 2;
              refresh();
            });
      } else {
        para(
            c,
            "Energía por app y por componente: no disponible con permisos básicos. Acceso a uso no"
                + " concede datos de energía.");
        button(c, "Obtener desglose avanzado con ADB", this::adbGuide);
      }
    }
    for (Analytics.Finding finding : result.findings) {
      c = card(finding.title);
      pill(c, "CONFIANZA: " + finding.confidence);
      para(c, "Periodo: " + date(result.start) + " → " + date(result.end));
      para(c, "Evidencia: " + finding.evidence);
      para(c, "Acción: " + finding.action);
    }
    c = card("Temperatura y posibles causas");
    para(
        c,
        "Máxima observada en el periodo: "
            + n(result.maxTemp)
            + " °C (batería). Estado térmico del sistema actual: "
            + thermalName(
                ((PowerManager) getSystemService(POWER_SERVICE)).getCurrentThermalStatus())
            + ".");
    para(
        c,
        "Si se mantiene caliente bloqueado y sin cargar, compara reposo con wifi y datos. Si"
            + " Android muestra una alerta térmica, deja de cargar o de exigir al móvil y sigue la"
            + " alerta. Ante hinchazón, olor o calor persistente en reposo, deja de usarlo y"
            + " consulta servicio técnico.");
  }

  String rate(double d, double h) {
    return h > 0 ? n(d / h) : "—";
  }

  String thermalName(int v) {
    String[] s = {
      "sin limitación térmica",
      "leve",
      "moderada",
      "grave",
      "crítica",
      "emergencia",
      "apagado inminente"
    };
    return v >= 0 && v < s.length ? s[v] : "no disponible";
  }

  void history() {
    periodPicker();
    LinearLayout c = card("Evolución del nivel de batería");
    c.addView(new TrendView(this, selected, false), new LinearLayout.LayoutParams(-1, dp(190)));
    para(
        c,
        "Puntos medidos. Si hay un hueco, los porcentajes a ambos lados siguen siendo lecturas"
            + " reales; no sabemos qué ocurrió entre ellas. Las líneas se interrumpen ante huecos"
            + " de más de 15 min, reinicios o cambios de reloj. La precisión del nivel es de 1"
            + " punto porcentual.");
    c = card("Temperatura de batería");
    c.addView(new TrendView(this, selected, true), new LinearLayout.LayoutParams(-1, dp(170)));
    para(c, "No es la temperatura de CPU ni GPU. No se inventan sensores adicionales.");
    c = card("Ritmo observado");
    para(
        c,
        "Total "
            + n(result.drop)
            + " pp en "
            + duration(result.dischargeHours)
            + " de descarga; media ponderada "
            + rate(result.drop, result.dischargeHours)
            + " pp/h. "
            + result.gaps
            + " huecos. Tiempo sin atribución: "
            + duration(result.unknownHours)
            + ".");
    long observedDays = result.days.stream().filter(day -> day.hours > 0).count();
    if (observedDays > 0)
      para(
          c,
          "Promedio por día con datos: "
              + n(result.drop / observedDays)
              + " pp/día observado. Días parciales; no equivale a consumo de 24 horas.");
    Analytics.Bucket max = null;
    for (Analytics.Bucket b : result.hours)
      if (b.hours > 0 && (max == null || b.drop > max.drop)) max = b;
    if (max != null)
      para(
          c,
          "Franja con mayor pérdida: "
              + max.label
              + ", "
              + n(max.drop)
              + " pp durante "
              + duration(max.hours)
              + " observada.");
    c = card("Por hora");
    if (result.hours.isEmpty()) para(c, "Aún no hay intervalos de descarga válidos.");
    int shown = 0;
    for (int i = result.hours.size() - 1; i >= 0 && shown < 48; i--, shown++) {
      Analytics.Bucket b = result.hours.get(i);
      para(
          c,
          b.hours <= 0
              ? b.label + " · sin medición utilizable"
              : b.label
                  + "\n"
                  + n(b.drop)
                  + " pp · "
                  + rate(b.drop, b.hours)
                  + " pp/h · "
                  + duration(b.hours)
                  + (b.interpolated ? " · reparto temporal estimado" : "")
                  + (b.gaps > 0 ? " · huecos" : ""));
    }
    if (result.hours.size() > 48)
      para(c, "Se muestran las últimas 48 franjas. Exporta CSV para el registro completo.");
    c = card("Por día");
    for (Analytics.Bucket b : result.days)
      para(
          c,
          b.hours <= 0
              ? b.label + " · sin medición utilizable · hueco"
              : b.label
                  + " · "
                  + n(b.drop)
                  + " pp · "
                  + duration(b.hours)
                  + " · "
                  + rate(b.drop, b.hours)
                  + " pp/h"
                  + (b.gaps > 0 ? " · parcial, huecos excluidos" : ""));
    c = card("Sesiones de descarga");
    if (result.sessions.isEmpty())
      para(c, "La primera sesión aparecerá al recoger muestras sin cargador.");
    for (int i = result.sessions.size() - 1; i >= Math.max(0, result.sessions.size() - 20); i--) {
      Analytics.Session s = result.sessions.get(i);
      para(
          c,
          date(s.start)
              + " → "
              + date(s.end)
              + "\n"
              + s.startLevel
              + " → "
              + s.endLevel
              + " % · "
              + n(s.drop)
              + " pp · "
              + duration(s.hours)
              + "\n"
              + (s.from100 ? "Inicio al 100 % observado" : "Inicio parcial observado")
              + " · "
              + ("En curso".equals(s.endReason) ? "abierta hasta la última muestra" : s.endReason)
              + "\nEquivalente carga completa: "
              + duration(s.equivalentFullHours)
              + " (extrapolación, mínimo 5 pp y 30 min).");
    }
    c = card("Red y cobertura durante la descarga");
    para(
        c,
        "Cada grupo es una vista de los mismos intervalos: no sumes red, pantalla y señal. Son"
            + " correlaciones, no energía exacta de la antena.");
    for (Map.Entry<String, Analytics.Condition> e : result.conditions.entrySet()) {
      if (!e.getKey().startsWith("NETWORK:") && !e.getKey().startsWith("SIGNAL:")) continue;
      Analytics.Condition v = e.getValue();
      para(
          c,
          conditionName(e.getKey())
              + ": "
              + n(v.drop)
              + " pp / "
              + duration(v.hours)
              + " · "
              + rate(v.drop, v.hours)
              + " pp/h · temp. media de muestras "
              + n(v.tempCount > 0 ? v.tempSum / v.tempCount : Double.NaN)
              + " °C");
    }
    if (!selected.isEmpty()) {
      Sample s = selected.get(selected.size() - 1);
      para(
          c,
          "Última muestra: "
              + date(s.wallMs)
              + " · "
              + s.network
              + " · datos "
              + s.radio
              + " · señal "
              + (s.signalDbm == Integer.MIN_VALUE ? "no disponible" : s.signalDbm + " dBm")
              + " · nivel "
              + (s.signalLevel == Integer.MIN_VALUE ? "no disponible" : s.signalLevel + "/4")
              + ".");
    }
    para(
        c,
        "5G NSA puede presentarse como LTE. No se usa la tecnología de voz para inferir la de"
            + " datos. Señal puede ser una lectura en caché y depender de la SIM activa. Sin"
            + " muestreo continuo no se detectan todos los cambios.");
    c = card("Interrupciones del registro");
    String events = store.eventsText();
    para(c, events.isEmpty() ? "Sin eventos guardados." : events);
  }

  String conditionName(String k) {
    return k.replace("NETWORK:", "Conexión ")
        .replace("SIGNAL:", "Señal ")
        .replace("WIFI", "wifi")
        .replace("MOBILE", "datos móviles")
        .replace("NONE", "sin conexión")
        .replace("UNKNOWN", "desconocida")
        .replace("NO_SERVICE", "sin servicio móvil (sistema)")
        .replace("POOR", "mala o nula")
        .replace("GOOD", "aceptable")
        .replace("OTHER", "otra");
  }

  void apps() {
    periodPicker();
    LinearLayout c = card("Energía estimada por Android · ADB");
    if (advanced == null || !advanced.recognized) {
      para(
          c,
          "No hay un informe energético reconocido. Android no facilita una API pública para leer"
              + " energía por aplicación. Importa una captura ADB sin root para ver lo que el"
              + " sistema realmente exponga.");
    } else {
      para(c, advanced.textSummary);
      para(
          c,
          "Importado: "
              + date(prefs.getLong("advancedImported", 0))
              + ". Este periodo no cambia con el selector de arriba.");
      for (String warn : advanced.warnings) para(c, "• " + warn);
      if (advanced.apps.isEmpty())
        para(c, "Este informe no contiene filas de energía por UID reconocibles.");
      for (AdvancedReport.Row row : advanced.apps) {
        String[] pkgs = getPackageManager().getPackagesForUid(row.uid);
        String pkg = pkgs != null && pkgs.length > 0 ? pkgs[0] : null;
        String name = pkg == null ? row.label : appLabel(pkg);
        LinearLayout r =
            appRow(c, pkg, name + (pkgs != null && pkgs.length > 1 ? " · UID compartido" : ""));
        para(
            r,
            n(row.mah)
                + " mAh · "
                + n(advanced.percent(row.mah))
                + " % del drenaje total calculado por Android ("
                + n(advanced.computedDrainMah)
                + " mAh)");
        if (pkgs != null && pkgs.length > 1)
          para(r, "Incluye: " + android.text.TextUtils.join(", ", pkgs));
        if (row.details != null && !row.details.isEmpty()) para(r, row.details);
      }
      para(
          c,
          "El UID importado se resuelve con las apps instaladas ahora. Desinstalar o reinstalar"
              + " puede cambiar su asociación; conserva el paquete del informe cuando exista.");
      if (!advanced.components.isEmpty()) {
        para(c, "Componentes globales (se solapan con los UID; NO sumar a las aplicaciones):");
        for (AdvancedReport.Row row : advanced.components)
          para(c, row.label + " · " + n(row.mah) + " mAh");
      }
    }
    button(c, "Guía ADB y comandos copiables", this::adbGuide);
    button(
        c,
        "Importar estadísticas de batería (.txt)",
        () -> {
          Intent i =
              new Intent(Intent.ACTION_OPEN_DOCUMENT)
                  .addCategory(Intent.CATEGORY_OPENABLE)
                  .setType("text/*");
          startActivityForResult(i, 203);
        });
    c = card("Ranking de uso · NO es consumo energético");
    para(
        c,
        "Periodo de uso: "
            + date(from)
            + " → "
            + date(to)
            + ". El tiempo en primer plano no permite repartir la batería entre apps. La retención"
            + " de Android puede aportar uso previo a la instalación, con disponibilidad"
            + " limitada.");
    if (!usageAccess()) {
      para(c, "Falta el acceso a datos de uso. No hay cifras por app disponibles en este modo.");
      button(
          c,
          "Conceder acceso a uso",
          () -> open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
      return;
    }
    LinearLayout list = c;
    TextView loading = text("Consultando datos locales de Android…", 14, MUTED);
    c.addView(loading);
    final int generation = renderVersion;
    final long a = from, b = to;
    worker.execute(
        () -> {
          UsageReader.Result usage = UsageReader.read(this, a, b);
          UsageReader.Result previous = UsageReader.read(this, a - Math.max(0, b - a), a);
          runOnUiThread(
              () -> {
                if (isDestroyed() || renderVersion != generation) return;
                list.removeView(loading);
                para(list, usage.note);
                para(
                    list,
                    "Referencia de uso anterior: "
                        + date(a - Math.max(0, b - a))
                        + " → "
                        + date(a)
                        + ". Puede abarcar otras horas del día; no equivale a comportamiento"
                        + " habitual.");
                Map<String, Long> prev = new HashMap<>();
                for (UsageReader.AppUsage u : previous.apps)
                  prev.put(u.packageName, u.foregroundMs);
                int count = 0;
                for (UsageReader.AppUsage u : usage.apps) {
                  if (count++ >= 40) break;
                  LinearLayout row =
                      appRow(
                          list,
                          u.packageName,
                          u.packageName.startsWith("uid:") ? u.name : appLabel(u.packageName));
                  para(
                      row,
                      u.foregroundMs > 0
                          ? duration(u.foregroundMs / 3600000.0) + " en primer plano observado"
                          : "Sin tiempo de primer plano observado; no demuestra ausencia de uso");
                  if (u.note != null && !u.note.isEmpty()) para(row, u.note);
                  if (u.rxBytes != null && u.txBytes != null)
                    para(
                        row,
                        "Tráfico UID: ↓ "
                            + bytes(u.rxBytes)
                            + " · ↑ "
                            + bytes(u.txBytes)
                            + " (no energía)");
                  else para(row, "Tráfico por UID: no disponible");
                  Long old = prev.get(u.packageName);
                  if (old != null)
                    para(
                        row,
                        "Periodo anterior de igual duración: "
                            + duration(old / 3600000.0)
                            + ". Diferencia de uso: "
                            + String.format(es, "%+.0f", (u.foregroundMs - old) / 60000.0)
                            + " min; no establece una causa ni un hábito.");
                  if (!u.packageName.startsWith("uid:"))
                    button(
                        row,
                        "Ver ajustes de esta app",
                        () ->
                            open(
                                new Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + u.packageName))));
                }
                if (usage.apps.isEmpty())
                  para(
                      list,
                      "Android no ha devuelto uso para este periodo. Esto no demuestra ausencia de"
                          + " actividad en segundo plano.");
                para(
                    list,
                    "Actividad en segundo plano y bloqueos de reposo: no disponibles en el modo"
                        + " básico. El informe ADB puede aportar indicios acumulados; no se asigna"
                        + " energía por tiempo de uso.");
              });
        });
  }

  LinearLayout appRow(LinearLayout target, String pkg, String name) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(1);
    row.setPadding(0, dp(15), 0, dp(10));
    target.addView(row);
    LinearLayout title = new LinearLayout(this);
    title.setGravity(Gravity.CENTER_VERTICAL);
    row.addView(title);
    ImageView image = new ImageView(this);
    try {
      image.setImageDrawable(
          pkg == null
              ? getDrawable(R.drawable.ic_battery)
              : getPackageManager().getApplicationIcon(pkg));
    } catch (Exception e) {
      image.setImageResource(R.drawable.ic_battery);
    }
    image.setContentDescription("");
    title.addView(image, new LinearLayout.LayoutParams(dp(32), dp(32)));
    TextView text = bold(name, 15);
    text.setPadding(dp(10), 0, 0, 0);
    title.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
    return row;
  }

  String appLabel(String pkg) {
    try {
      return getPackageManager()
          .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
          .toString();
    } catch (Exception e) {
      return pkg;
    }
  }

  String bytes(long n) {
    if (n < 1024) return n + " B";
    if (n < 1048576) return this.n(n / 1024.0) + " KB";
    return this.n(n / 1048576.0) + " MB";
  }

  JSONArray savedTests() {
    try {
      return new JSONArray(prefs.getString("tests", "[]"));
    } catch (Exception e) {
      return new JSONArray();
    }
  }

  void saveTests(JSONArray a) {
    prefs.edit().putString("tests", a.toString()).apply();
  }

  Analytics.Result testStats(JSONObject t) {
    long start = t.optLong("start"), end = t.optLong("end", System.currentTimeMillis());
    List<Sample> values = new ArrayList<>();
    boolean skipped = false;
    for (Sample s : all) {
      if (s.wallMs >= start && s.wallMs <= end) {
        if (skipped && !values.isEmpty()) values.add(null);
        values.add(s);
        skipped = false;
      } else skipped = true;
    }
    return Analytics.analyze(values);
  }

  double conditionHours(Analytics.Result a, String name) {
    Analytics.Condition c = a.conditions.get(name);
    return c == null ? 0 : c.hours;
  }

  void tests() {
    LinearLayout c = card("Pruebas para confirmar una sospecha");
    para(
        c,
        "Cambia una sola condición y repite en horarios, carga inicial y temperatura parecidos."
            + " Guarda una referencia A y después una prueba B. Los resultados indican"
            + " asociaciones, no demuestran una causa por sí solos.");
    JSONArray tests = savedTests();
    JSONObject active = null;
    for (int i = 0; i < tests.length(); i++)
      if (!tests.optJSONObject(i).has("end")) active = tests.optJSONObject(i);
    if (active != null) {
      JSONObject current = active;
      c = card("Prueba en curso · " + active.optString("type"));
      para(
          c,
          "Inicio: "
              + date(active.optLong("start"))
              + "\nCondiciones anotadas: "
              + active.optString("note"));
      para(
          c,
          "Deja el teléfono bloqueado para evaluar reposo. La app no necesita estar en pantalla."
              + " Puedes usarlo normalmente si estudias uso o carga.");
      if (!CollectorService.isRunning()) {
        para(c, "El registro está detenido; este tramo quedará sin mediciones.");
        primary(c, "Reanudar registro", this::startMonitor);
      }
      primary(
          c,
          "Finalizar y guardar mediciones",
          () -> {
            try {
              CollectorService.snapshot(this);
              current.put("end", System.currentTimeMillis());
              saveTests(tests);
              store.event("PRUEBA", "Fin de " + current.optString("type"));
              handler.postDelayed(this::refresh, 300);
            } catch (Exception ignored) {
            }
          });
    } else {
      c = card("1 · Reposo sin cargador");
      para(
          c,
          "60 minutos bloqueado, en un lugar fijo. Comprueba si sigue caliente. Repite después con"
              + " una intervención concreta; no silencies notificaciones necesarias por defecto.");
      button(c, "Iniciar prueba de reposo", () -> newTest("Reposo"));
      c = card("2 · Wifi frente a datos móviles");
      para(
          c,
          "Dos periodos de al menos 60 minutos, ambos bloqueado y sin cargador, con temperatura y"
              + " batería inicial similares. Cambia tú la conexión. No actives modo avión si"
              + " necesitas recibir llamadas.");
      button(c, "Registrar referencia wifi", () -> newTest("Red: wifi"));
      button(c, "Registrar comparación datos", () -> newTest("Red: datos"));
      c = card("3 · Calor y actividad persistente");
      para(
          c,
          "Carga: anota cargador, funda y temperatura ambiente; compara sesiones de carga"
              + " parecidas. Apps: consulta el informe ADB, revisa sus ajustes y repite una sesión"
              + " comparable. No se cierran apps ni se borran datos automáticamente.");
      button(c, "Registrar calentamiento durante carga", () -> newTest("Carga y calor"));
      button(c, "Registrar cambio en una app", () -> newTest("Intervención en app"));
    }
    c = card("Resultados guardados");
    List<JSONObject> finished = new ArrayList<>();
    for (int i = tests.length() - 1; i >= 0; i--) {
      JSONObject t = tests.optJSONObject(i);
      if (!t.has("end")) continue;
      finished.add(t);
      Analytics.Result a = testStats(t);
      para(
          c,
          t.optString("type")
              + " · "
              + date(t.optLong("start"))
              + " → "
              + date(t.optLong("end"))
              + "\n"
              + t.optString("note")
              + "\n"
              + n(a.drop)
              + " pp / "
              + duration(a.dischargeHours)
              + " · "
              + rate(a.drop, a.dischargeHours)
              + " pp/h · máxima "
              + n(a.maxTemp)
              + " °C · "
              + a.gaps
              + " huecos.");
      Sample first = firstIn(t.optLong("start"), t.optLong("end")),
          last = lastIn(t.optLong("start"), t.optLong("end"));
      if (first != null && last != null)
        para(
            c,
            "Extremos observados: "
                + (first.level >= 0 && first.level <= 100 ? first.level : "no disponible")
                + " → "
                + (last.level >= 0 && last.level <= 100 ? last.level : "no disponible")
                + " %; batería "
                + n(first.tempC)
                + " → "
                + n(last.tempC)
                + " °C. Diferencia neta: "
                + (first.level >= 0 && first.level <= 100 && last.level >= 0 && last.level <= 100
                    ? String.valueOf(first.level - last.level)
                    : "no disponible")
                + " pp. No equivale a descarga total si hubo recargas o huecos, ni demuestra que"
                + " permaneciera en reposo todo el intervalo.");
      para(
          c,
          "Pantalla apagada: "
              + duration(a.offHours)
              + "; wifi: "
              + duration(conditionHours(a, "NETWORK:WIFI"))
              + "; datos: "
              + duration(conditionHours(a, "NETWORK:MOBILE"))
              + ". Intervalo efectivo: "
              + date(a.start)
              + " → "
              + date(a.end)
              + ".");
    }
    if (finished.isEmpty())
      para(c, "Todavía no has finalizado pruebas. No hay resultados precargados.");
    if (finished.size() >= 2) {
      JSONObject b = finished.get(0), a = finished.get(1);
      LinearLayout compare = card("Comparar las dos últimas pruebas");
      Analytics.Result ar = testStats(a), br = testStats(b);
      double arate = ar.dischargeHours > 0 ? ar.drop / ar.dischargeHours : Double.NaN,
          brate = br.dischargeHours > 0 ? br.drop / br.dischargeHours : Double.NaN;
      para(
          compare,
          "A: "
              + a.optString("type")
              + " · "
              + date(a.optLong("start"))
              + "\nB: "
              + b.optString("type")
              + " · "
              + date(b.optLong("start")));
      para(
          compare,
          "A: "
              + n(arate)
              + " pp/h. B: "
              + n(brate)
              + " pp/h. Diferencia: "
              + n(brate - arate)
              + " pp/h.");
      List<String> limits = new ArrayList<>();
      if (!a.optString("type").equals(b.optString("type"))
          && !(a.optString("type").startsWith("Red:") && b.optString("type").startsWith("Red:")))
        limits.add("tipos de prueba diferentes");
      double awall = (a.optLong("end") - a.optLong("start")) / 3600000.0,
          bwall = (b.optLong("end") - b.optLong("start")) / 3600000.0;
      if (awall <= 0
          || bwall <= 0
          || ar.dischargeHours / awall < 0.8
          || br.dischargeHours / bwall < 0.8)
        limits.add(
            "cobertura útil de descarga menor del 80 % del tiempo de prueba; carga, huecos o falta"
                + " de muestras");
      if (ar.dischargeHours < 0.5 || br.dischargeHours < 0.5)
        limits.add("menos de 30 minutos válidos en una de las pruebas");
      if (ar.drop < 2 || br.drop < 2)
        limits.add("menos de 2 puntos de descarga: resolución insuficiente");
      if (ar.gaps > 0 || br.gaps > 0) limits.add("huecos de registro");
      if (ar.dischargeHours > 0
          && br.dischargeHours > 0
          && Math.abs(ar.onHours / ar.dischargeHours - br.onHours / br.dischargeHours) > 0.15)
        limits.add("proporciones de pantalla distintas");
      if (ar.dischargeHours > 0
          && br.dischargeHours > 0
          && (ar.dischargeHours / br.dischargeHours < 0.5
              || ar.dischargeHours / br.dischargeHours > 2)) limits.add("duraciones muy distintas");
      Sample as = firstIn(a.optLong("start"), a.optLong("end")),
          bs = firstIn(b.optLong("start"), b.optLong("end"));
      if (as != null && bs != null) {
        if (Math.abs(as.level - bs.level) > 15)
          limits.add("carga inicial difiere más de 15 puntos");
        if (Double.isFinite(as.tempC)
            && Double.isFinite(bs.tempC)
            && Math.abs(as.tempC - bs.tempC) > 3)
          limits.add("temperaturas iniciales difieren más de 3 °C");
      } else limits.add("sin muestras iniciales");
      pill(
          compare,
          limits.isEmpty()
              ? "COMPARACIÓN ORIENTATIVA · CONFIANZA BAJA"
              : "COMPARACIÓN NO CONCLUYENTE");
      para(
          compare,
          limits.isEmpty()
              ? "Las condiciones registradas superan los filtros básicos. Repite al menos otra"
                  + " pareja de pruebas; una diferencia aislada no confirma que la intervención"
                  + " sea la causa."
              : "Limitaciones: "
                  + android.text.TextUtils.join("; ", limits)
                  + ". Repite con condiciones más parecidas.");
      para(
          compare,
          "No se controla automáticamente temperatura ambiente, brillo, apps, calidad de señal ni"
              + " todas las tareas del sistema. La prueba de carga requiere comparar las"
              + " temperaturas observadas, no pp/h de descarga.");
    }
  }

  Sample lastIn(long start, long end) {
    Sample last = null;
    for (Sample s : all) if (s.wallMs >= start && s.wallMs <= end) last = s;
    return last;
  }

  Sample firstIn(long start, long end) {
    for (Sample s : all) if (s.wallMs >= start && s.wallMs <= end) return s;
    return null;
  }

  void newTest(String type) {
    LinearLayout wrap = new LinearLayout(this);
    wrap.setOrientation(1);
    wrap.setPadding(dp(20), dp(8), dp(20), 0);
    para(
        wrap,
        "Anota A o B, conexión, cargador, funda, temperatura ambiente y el único cambio que vas a"
            + " probar. Se registrarán las condiciones medidas disponibles.");
    EditText note = new EditText(this);
    note.setHint("Ej.: A · wifi · bloqueado · sin funda");
    note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    wrap.addView(note);
    new AlertDialog.Builder(this)
        .setTitle(type)
        .setView(wrap)
        .setNegativeButton("Cancelar", null)
        .setPositiveButton(
            "Iniciar",
            (d, w) -> {
              try {
                JSONObject t = new JSONObject();
                t.put("type", type);
                t.put("start", System.currentTimeMillis());
                t.put("note", note.getText().toString());
                JSONArray a = savedTests();
                a.put(t);
                saveTests(a);
                store.event("PRUEBA", "Inicio de " + type);
                if (!CollectorService.isRunning()) startMonitor();
                else CollectorService.snapshot(this);
                refresh();
              } catch (Exception e) {
                toast("No se pudo guardar la prueba.");
              }
            })
        .show();
  }

  void settings() {
    LinearLayout c = card("Registro y permisos");
    para(c, monitorLabel() + " · última muestra: " + date(prefs.getLong("lastSample", 0)));
    String error = prefs.getString("lastError", "");
    if (!error.isEmpty()) para(c, error);
    controls(c);
    button(
        c,
        "Repetir asistente",
        () -> {
          wizard = 0;
          prefs.edit().putBoolean("onboarded", false).apply();
          render();
        });
    permissionCards();
    c = card("Salud de batería · fuentes separadas");
    int health = readSystemHealth();
    para(
        c,
        "Sistema (sondeo experimental): "
            + (health > 0
                ? health
                    + " % · propiedad 10 de BatteryManager, relación estimada de capacidad completa"
                    + " / nominal según AOSP. Compatibilidad Samsung sin verificar."
                : "no disponible o no interpretable en este firmware/permisos."));
    int manual = prefs.getInt("manualHealth", -1);
    para(
        c,
        "Dato manual: "
            + (manual > 0
                ? manual + " % · " + prefs.getString("manualHealthNote", "")
                : "no disponible"));
    para(
        c,
        "Estimación propia de capacidad: no disponible en esta versión. La carga restante en µAh no"
            + " es la capacidad total y una sola lectura no basta. Una estimación requeriría varias"
            + " cargas amplias comparables, sensores fiables y control de temperatura y pérdidas.");
    Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    int categorical =
        bi == null
            ? BatteryManager.BATTERY_HEALTH_UNKNOWN
            : bi.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
    para(
        c,
        "Estado Android: "
            + healthCategory(categorical)
            + ". Este estado no es un porcentaje de salud.");
    button(c, "Editar dato manual de salud", this::editHealth);
    button(
        c,
        "Alternativas al menú *#9900#",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("Salud: qué se puede comprobar")
                .setMessage(
                    "Se consulta de forma conservadora la propiedad SOH conocida en AOSP (10), que"
                        + " puede estar oculta o no soportada en Samsung. Sólo se admite un valor"
                        + " 1–100; no hay un 100 % por defecto.\n\n"
                        + "Samsung Members puede ofrecer un diagnóstico cualitativo, no"
                        + " necesariamente un porcentaje. ADB ‘dumpsys battery’ puede mostrar"
                        + " campos del fabricante. ASOC u otros valores crudos no se convierten"
                        + " aquí a salud porque no hemos verificado su significado en tu firmware."
                        + " No usamos root ni el menú de diagnóstico.\n\n"
                        + "Cualquier dato que introduzcas manualmente se identifica como no"
                        + " verificado. No es una lectura actual de la app.")
                .setPositiveButton("Entendido", null)
                .show());
    c = card("Datos y privacidad");
    para(
        c,
        "Almacenamiento local, sin permiso de Internet. El CSV contiene horas, batería, pantalla,"
            + " red y señal; el informe resume el diagnóstico. Un log ADB puede contener nombres de"
            + " apps y actividad: revísalo antes de compartirlo.");
    button(
        c,
        "Exportar mediciones CSV",
        () -> export("inhouse-mediciones.csv", "text/csv", csv(), false));
    button(
        c,
        "Exportar informe de diagnóstico",
        () -> export("inhouse-informe.txt", "text/plain", report(), false));
    button(
        c,
        "Guardar y compartir informe",
        () -> export("inhouse-informe.txt", "text/plain", report(), true));
    button(
        c,
        "Exportar informe ADB original",
        () -> {
          if (advancedText.isEmpty()) toast("No hay un informe ADB importado.");
          else export("inhouse-batterystats-original.txt", "text/plain", advancedText, false);
        });
    button(
        c,
        "Borrar historial e informes",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("¿Borrar los datos locales?")
                .setMessage(
                    "Se detendrá el registro y se borrarán mediciones, eventos, pruebas e informes"
                        + " importados. Los permisos y el dato manual de salud se conservan.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton(
                    "Borrar",
                    (d, w) -> {
                      CollectorService.stop(this);
                      handler.postDelayed(
                          () -> {
                            store.clear();
                            prefs
                                .edit()
                                .remove("tests")
                                .remove("lastSample")
                                .remove("advancedImported")
                                .apply();
                            new File(getFilesDir(), "advanced.txt").delete();
                            new File(getFilesDir(), "pending-export").delete();
                            advanced = null;
                            advancedText = "";
                            refresh();
                          },
                          350);
                    })
                .show());
    c = card("Sobre esta versión");
    para(
        c,
        "Inhouse battery check 1.0 · Android 10–16\nDispositivo: "
            + Build.MANUFACTURER
            + " "
            + Build.MODEL
            + " · Android "
            + Build.VERSION.RELEASE
            + " (API "
            + Build.VERSION.SDK_INT
            + ")\nOne UI: no verificada; consulta Información de software en Ajustes.");
    para(
        c,
        "Implementación original. Fuentes revisadas: Android/AOSP, BBS Reloaded,"
            + " BetterBatteryStats, ABattery y Beam. No se incorpora código ni iconos de esos"
            + " proyectos. Métodos y límites documentados con el código fuente.");
    button(c, "Guía ADB", this::adbGuide);
    button(
        c,
        "Información del dispositivo",
        () -> open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)));
  }

  int readSystemHealth() {
    if (Build.VERSION.SDK_INT < 34) return -1;
    try {
      int value = ((BatteryManager) getSystemService(BATTERY_SERVICE)).getIntProperty(10);
      return value >= 1 && value <= 100 ? value : -1;
    } catch (Exception e) {
      return -1;
    }
  }

  String healthCategory(int n) {
    switch (n) {
      case BatteryManager.BATTERY_HEALTH_GOOD:
        return "GOOD (estado normal)";
      case BatteryManager.BATTERY_HEALTH_OVERHEAT:
        return "sobrecalentamiento";
      case BatteryManager.BATTERY_HEALTH_COLD:
        return "fría";
      case BatteryManager.BATTERY_HEALTH_DEAD:
        return "fallo";
      case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
        return "sobretensión";
      case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
        return "fallo no especificado";
      default:
        return "desconocido";
    }
  }

  void editHealth() {
    EditText input = new EditText(this);
    input.setInputType(InputType.TYPE_CLASS_NUMBER);
    input.setHint("1–100, o vacío para eliminar");
    int old = prefs.getInt("manualHealth", -1);
    if (old > 0) input.setText("" + old);
    new AlertDialog.Builder(this)
        .setTitle("Salud introducida manualmente")
        .setView(input)
        .setNegativeButton("Cancelar", null)
        .setPositiveButton(
            "Guardar",
            (d, w) -> {
              String s = input.getText().toString().trim();
              if (s.isEmpty()) {
                prefs.edit().remove("manualHealth").remove("manualHealthNote").apply();
                refresh();
                return;
              }
              try {
                int v = Integer.parseInt(s);
                if (v < 1 || v > 100) throw new Exception();
                prefs
                    .edit()
                    .putInt("manualHealth", v)
                    .putString(
                        "manualHealthNote",
                        "Introducido manualmente el "
                            + date(System.currentTimeMillis())
                            + "; no verificado")
                    .apply();
                refresh();
              } catch (Exception e) {
                toast("Introduce un entero entre 1 y 100.");
              }
            })
        .show();
  }

  void adbGuide() {
    LinearLayout old = body;
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(1);
    content.setPadding(dp(20), dp(8), dp(20), dp(15));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(content);
    para(
        content,
        "Esta versión usa una importación ADB sin root. No necesita Shizuku ni concesiones de"
            + " permisos internos. Funciona aunque el registro básico esté activo. La app comprueba"
            + " el formato y el periodo al importar; no garantiza que Samsung exponga todas las"
            + " métricas.");
    para(
        content,
        "1. En un ordenador instala Android SDK Platform-Tools oficial. En el móvil, Ajustes →"
            + " Acerca del teléfono → Información de software → pulsa 7 veces Número de"
            + " compilación. Activa Depuración USB en Opciones de desarrollador.");
    para(
        content,
        "2. Conecta por USB y acepta la clave del ordenador sólo si confías en él. En la terminal"
            + " ejecuta (Windows: adb.exe o .\\adb.exe desde su carpeta):");
    command(content, "adb devices");
    para(
        content,
        "La fila de tu teléfono debe terminar en ‘device’, no ‘unauthorized’. Si Samsung bloquea"
            + " depuración, revisa sus indicaciones de seguridad; no cambies protecciones"
            + " automáticamente.");
    para(content, "3. Guarda la captura. Linux, macOS o cmd.exe de Windows:");
    command(content, "adb shell dumpsys batterystats > inhouse-batterystats.txt");
    para(content, "PowerShell (para garantizar texto UTF-8):");
    command(
        content,
        "adb shell dumpsys batterystats | Out-File -Encoding utf8 inhouse-batterystats.txt");
    para(content, "4. Copia el archivo al móvil y selecciona ‘Importar estadísticas’ en Apps:");
    command(content, "adb push inhouse-batterystats.txt /sdcard/Download/inhouse-batterystats.txt");
    para(
        content,
        "5. Comprueba en la app que el informe está reconocido, su periodo y el drenaje calculado."
            + " Si aparece Permission Denial, falta el bloque Estimated power use o Android cambia"
            + " el formato, se indica que no puede interpretarse. No se muestran porcentajes"
            + " inventados.");
    para(
        content,
        "Opcional: diagnóstico bruto de batería para revisarlo en el ordenador. No se interpreta"
            + " automáticamente ASOC:");
    command(content, "adb shell dumpsys battery > inhouse-battery.txt");
    para(
        content,
        "No ejecutamos ‘batterystats --reset’: eliminaría la referencia del sistema. Captura tras"
            + " una sesión conocida y conserva el periodo indicado por Android. El informe"
            + " importado no genera energía de días anteriores ni de la sesión local.");
    para(
        content,
        "Al terminar, desconecta USB y desactiva Depuración USB si no la necesitas. Los logs"
            + " permanecen locales hasta que tú los exportes o compartas. BBS Reloaded y Shizuku se"
            + " han investigado; esta versión no los integra.");
    new AlertDialog.Builder(this)
        .setTitle("Estadísticas avanzadas con ADB")
        .setView(scroll)
        .setPositiveButton("Cerrar", null)
        .show();
  }

  void command(LinearLayout c, String s) {
    TextView t = text(s, 13, INK);
    t.setTypeface(Typeface.MONOSPACE);
    t.setTextIsSelectable(true);
    t.setPadding(dp(9), dp(9), dp(9), dp(9));
    t.setBackground(bg(BG, 8));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(10);
    c.addView(t, lp);
    button(
        c,
        "Copiar comando",
        () -> {
          ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
              .setPrimaryClip(ClipData.newPlainText("ADB", s));
          toast("Comando copiado");
        });
  }

  String csvCell(Object s) {
    if (s == null) return "";
    String t = String.valueOf(s);
    return "\"" + t.replace("\"", "\"\"") + "\"";
  }

  String finite(double d) {
    return Double.isFinite(d) ? String.valueOf(d) : "";
  }

  String known(int v) {
    return v == Integer.MIN_VALUE ? "" : String.valueOf(v);
  }

  String csv() {
    StringBuilder b =
        new StringBuilder(
            "id,epoch_ms,local_time,zone,elapsed_ms,boot,continuity,reason,level_percent,plugged,screen_on,battery_temp_c,thermal_status,connection,data_technology,signal_dbm,signal_level,charge_uah,current_ua,voltage_mv\r\n");
    for (Sample s : all) {
      b.append(s.id)
          .append(',')
          .append(s.wallMs)
          .append(',')
          .append(
              csvCell(
                  java.time.Instant.ofEpochMilli(s.wallMs)
                      .atZone(
                          java.time.ZoneId.of(
                              s.zone == null ? java.time.ZoneId.systemDefault().getId() : s.zone))
                      .toString()))
          .append(',')
          .append(csvCell(s.zone))
          .append(',')
          .append(s.elapsedMs)
          .append(',')
          .append(known(s.boot))
          .append(',')
          .append(csvCell(s.continuity))
          .append(',')
          .append(csvCell(s.reason))
          .append(',')
          .append(s.level < 0 ? "" : s.level)
          .append(',')
          .append(s.plugged)
          .append(',')
          .append(s.screen)
          .append(',')
          .append(finite(s.tempC))
          .append(',')
          .append(known(s.thermal))
          .append(',')
          .append(csvCell(s.network))
          .append(',')
          .append(csvCell(s.radio))
          .append(',')
          .append(known(s.signalDbm))
          .append(',')
          .append(known(s.signalLevel))
          .append(',')
          .append(known(s.chargeUah))
          .append(',')
          .append(known(s.currentUa))
          .append(',')
          .append(known(s.voltageMv))
          .append("\r\n");
    }
    return b.toString();
  }

  String report() {
    StringBuilder b = new StringBuilder("INHOUSE BATTERY CHECK · INFORME LOCAL\n");
    b.append("Generado: ")
        .append(date(System.currentTimeMillis()))
        .append("\nDispositivo: ")
        .append(Build.MANUFACTURER)
        .append(' ')
        .append(Build.MODEL)
        .append(" · Android ")
        .append(Build.VERSION.RELEASE)
        .append(" · One UI sin verificar\nRegistro desde: ")
        .append(date(store.firstTime()))
        .append("\nPeriodo: ")
        .append(date(from))
        .append(" → ")
        .append(date(to))
        .append("\nMuestras: ")
        .append(selected.size())
        .append("\n\n");
    b.append("Descarga observada: ")
        .append(n(result.drop))
        .append(" pp en ")
        .append(duration(result.dischargeHours))
        .append("; ")
        .append(rate(result.drop, result.dischargeHours))
        .append(" pp/h.\nPantalla encendida: ")
        .append(n(result.onDrop))
        .append(" pp en ")
        .append(duration(result.onHours))
        .append("\nPantalla apagada: ")
        .append(n(result.offDrop))
        .append(" pp en ")
        .append(duration(result.offHours))
        .append("\nHuecos excluidos: ")
        .append(result.gaps)
        .append(" · duración sin atribuir ")
        .append(duration(result.unknownHours))
        .append("\nTemperatura máxima batería: ")
        .append(n(result.maxTemp))
        .append(" °C\n");
    for (Analytics.Finding f : result.findings)
      b.append("\n")
          .append(f.title)
          .append("\nConfianza: ")
          .append(f.confidence)
          .append("\nEvidencia: ")
          .append(f.evidence)
          .append("\nAcción: ")
          .append(f.action)
          .append("\n");
    b.append("\nSalud manual: ")
        .append(
            prefs.getInt("manualHealth", -1) > 0
                ? prefs.getInt("manualHealth", -1) + " %"
                : "no disponible")
        .append(" · ")
        .append(prefs.getString("manualHealthNote", ""))
        .append("\nSalud sistema: ");
    int health = readSystemHealth();
    b.append(
            health > 0
                ? health + " % (propiedad 10 experimental, compatibilidad OEM sin verificar)"
                : "no disponible")
        .append("\nNo hay estimación propia de capacidad. GOOD no es un porcentaje.\n");
    if (advanced != null && advanced.recognized)
      b.append("\nInforme energético importado (periodo independiente):\n")
          .append(advanced.textSummary)
          .append("\n");
    b.append("\nPruebas guiadas guardadas:\n");
    JSONArray t = savedTests();
    for (int i = 0; i < t.length(); i++) {
      JSONObject j = t.optJSONObject(i);
      Analytics.Result a = testStats(j);
      b.append(j.optString("type"))
          .append(" · ")
          .append(date(j.optLong("start")))
          .append(" → ")
          .append(j.has("end") ? date(j.optLong("end")) : "en curso")
          .append("\n")
          .append(j.optString("note"))
          .append("\n")
          .append(n(a.drop))
          .append(" pp / ")
          .append(duration(a.dischargeHours))
          .append(" · ")
          .append(rate(a.drop, a.dischargeHours))
          .append(" pp/h · ")
          .append(a.gaps)
          .append(" huecos\n");
    }
    b.append(
        "\n"
            + "Limitaciones: registro desde activación; no es historial reconstruido. Puntos"
            + " porcentuales de batería redondeados por Android; recargas no son consumo negativo."
            + " Comparaciones de red/pantalla son correlaciones, no atribuciones energéticas. No se"
            + " suman componentes globales con UID. No se ha validado en un Samsung real.\n"
            + "Los datos manuales de salud no son mediciones verificadas.\n");
    return b.toString();
  }

  void export(String name, String mime, String contents, boolean share) {
    exportText = contents;
    exportMime = mime;
    shareAfter = share;
    try (FileOutputStream pending = openFileOutput("pending-export", MODE_PRIVATE)) {
      pending.write(contents.getBytes(StandardCharsets.UTF_8));
      prefs.edit().putString("exportMime", mime).putBoolean("exportShare", share).apply();
    } catch (Exception e) {
      toast("No se pudo preparar la exportación.");
      return;
    }
    Intent i =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, name);
    try {
      startActivityForResult(i, 201);
    } catch (Exception e) {
      toast("No hay selector de archivos disponible.");
    }
  }

  static String read(InputStream in, int limit) throws IOException {
    try (InputStream stream = in;
        ByteArrayOutputStream b = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count, total = 0;
      while ((count = stream.read(buffer)) != -1) {
        total += count;
        if (total > limit) throw new IOException("El archivo supera 5 MB");
        b.write(buffer, 0, count);
      }
      byte[] bytes = b.toByteArray();
      if (bytes.length >= 2 && ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 254))
        return new String(bytes, StandardCharsets.UTF_16LE);
      if (bytes.length >= 2 && ((bytes[0] & 255) == 254 && (bytes[1] & 255) == 255))
        return new String(bytes, StandardCharsets.UTF_16BE);
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  @Override
  protected void onActivityResult(int request, int code, Intent data) {
    super.onActivityResult(request, code, data);
    if (code != RESULT_OK || data == null || data.getData() == null) {
      if (request == 201) new File(getFilesDir(), "pending-export").delete();
      return;
    }
    Uri uri = data.getData();
    if (request == 201) {
      try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
        if (stream == null) throw new IOException();
        try (InputStream pending = new FileInputStream(new File(getFilesDir(), "pending-export"))) {
          byte[] buffer = new byte[8192];
          int len;
          while ((len = pending.read(buffer)) != -1) stream.write(buffer, 0, len);
        }
        stream.flush();
        new File(getFilesDir(), "pending-export").delete();
        toast("Archivo guardado");
        if (prefs.getBoolean("exportShare", false)) {
          Intent share =
              new Intent(Intent.ACTION_SEND)
                  .setType(prefs.getString("exportMime", "text/plain"))
                  .putExtra(Intent.EXTRA_STREAM, uri)
                  .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          share.setClipData(ClipData.newUri(getContentResolver(), "Informe Inhouse", uri));
          startActivity(Intent.createChooser(share, "Compartir informe"));
        }
      } catch (Exception e) {
        toast("No se pudo guardar o compartir el archivo. Vuelve a exportarlo.");
      }
    }
    if (request == 203) {
      worker.execute(
          () -> {
            try {
              String contents = read(getContentResolver().openInputStream(uri), 5 * 1024 * 1024);
              AdvancedReport.Report parsed = AdvancedReport.parse(contents);
              if (!parsed.recognized) {
                runOnUiThread(
                    () -> {
                      if (alive())
                        new AlertDialog.Builder(this)
                            .setTitle("Informe no reconocido")
                            .setMessage(
                                parsed.textSummary
                                    + "\n\n"
                                    + "No se ha sustituido el informe anterior. Selecciona la"
                                    + " salida completa de adb shell dumpsys batterystats, en"
                                    + " formato de texto.")
                            .setPositiveButton("Entendido", null)
                            .show();
                    });
                return;
              }
              try (FileOutputStream out = openFileOutput("advanced.txt", MODE_PRIVATE)) {
                out.write(contents.getBytes(StandardCharsets.UTF_8));
              }
              advanced = parsed;
              advancedText = contents;
              prefs.edit().putLong("advancedImported", System.currentTimeMillis()).apply();
              runOnUiThread(
                  () -> {
                    if (!alive()) return;
                    toast("Informe reconocido. Comprueba su periodo antes de compararlo.");
                    refresh();
                  });
            } catch (Exception e) {
              runOnUiThread(() -> toast("No se pudo importar: " + e.getMessage()));
            }
          });
    }
  }
}
