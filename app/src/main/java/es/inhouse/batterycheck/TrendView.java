package es.inhouse.batterycheck;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

/** Draws observed points only. Missing intervals are never bridged. */
public final class TrendView extends View {
  private final List<Sample> samples;
  private final boolean temperature;
  private final Paint p = new Paint(3);

  public TrendView(Context c, List<Sample> s, boolean t) {
    super(c);
    samples = s;
    temperature = t;
    setContentDescription(
        t
            ? "Evolución de temperatura de batería; los huecos se interrumpen"
            : "Evolución de nivel de batería; los huecos se interrumpen");
  }

  private float dp(float n) {
    return n * getResources().getDisplayMetrics().density;
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    float l = dp(35), r = getWidth() - dp(14), t = dp(18), b = getHeight() - dp(26);
    p.setTextSize(dp(10));
    double min = temperature ? 15 : 0, max = temperature ? 50 : 100;
    if (temperature)
      for (Sample s : samples)
        if (Double.isFinite(s.tempC)) {
          min = Math.min(min, Math.floor(s.tempC / 5) * 5);
          max = Math.max(max, Math.ceil(s.tempC / 5) * 5);
        }
    for (int i = 0; i <= 4; i++) {
      float y = t + (b - t) * i / 4;
      p.setColor(0xffe3e9eb);
      p.setStrokeWidth(dp(1));
      c.drawLine(l, y, r, y, p);
      p.setColor(0xff627077);
      c.drawText(String.format(Locale.US, "%.0f", max - (max - min) * i / 4), dp(3), y + dp(4), p);
    }
    if (samples.isEmpty()) {
      p.setColor(0xff627077);
      c.drawText("Aún no hay muestras", l + dp(15), (b + t) / 2, p);
      return;
    }
    // Wall-clock axis; discontinuities are not joined. Include corrected timestamps within bounds.
    long first = samples.get(0).wallMs, last = first;
    for (Sample s : samples) {
      first = Math.min(first, s.wallMs);
      last = Math.max(last, s.wallMs);
    }
    long range = Math.max(1, last - first);
    Sample prev = null;
    float px = 0, py = 0;
    for (Sample s : samples) {
      double v = temperature ? s.tempC : s.level;
      if (!Double.isFinite(v) || (!temperature && (v < 0 || v > 100))) {
        prev = null;
        continue;
      }
      float x = l + (r - l) * (float) ((s.wallMs - first) / (double) range),
          y = b - (b - t) * (float) ((v - min) / (max - min));
      p.setColor(temperature ? 0xffcb6744 : 0xff167d7f);
      p.setStrokeWidth(dp(2));
      if (prev != null
          && s.boot == prev.boot
          && Objects.equals(s.continuity, prev.continuity)
          && Objects.equals(s.zone, prev.zone)
          && !"STOP".equals(prev.reason)
          && !"PAUSE".equals(prev.reason)
          && s.elapsedMs > prev.elapsedMs
          && s.elapsedMs - prev.elapsedMs <= 900000
          && Math.abs((s.wallMs - prev.wallMs) - (s.elapsedMs - prev.elapsedMs)) <= 60000)
        c.drawLine(px, py, x, y, p);
      c.drawCircle(x, y, dp(2), p);
      prev = s;
      px = x;
      py = y;
    }
    p.setColor(0xff627077);
    p.setTextSize(dp(10));
    java.text.DateFormat f =
        new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.forLanguageTag("es-ES"));
    c.drawText(f.format(new Date(first)), l, b + dp(20), p);
    String end = f.format(new Date(last));
    c.drawText(end, r - p.measureText(end), b + dp(20), p);
  }
}
