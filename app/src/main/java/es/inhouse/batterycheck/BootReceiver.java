package es.inhouse.batterycheck;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Documents interruption; deliberately does not start a foreground service after boot. */
public final class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    String action = intent.getAction();
    if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
        && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
    SharedPreferences p = context.getSharedPreferences("inhouse", Context.MODE_PRIVATE);
    boolean wasRunning = "RUNNING".equals(p.getString("monitorState", "STOPPED"));
    if (wasRunning)
      p.edit()
          .putString("monitorState", "PAUSED")
          .putString(
              "lastError",
              "Registro interrumpido por reinicio o actualización. Abre la app y reanuda"
                  + " manualmente.")
          .apply();
    try (Store store = new Store(context)) {
      if (p.getLong("lastSample", 0) > 0)
        store.event(
            "REINICIO",
            wasRunning
                ? "El registro estaba activo. Se requiere reanudación manual; el hueco no se"
                      + " interpreta como consumo cero."
                : "Reinicio o actualización detectados. El registro sigue detenido.");
    } catch (RuntimeException ignored) {
    }
  }
}
