package es.inhouse.batterycheck;

/** A real observation. Integer.MIN_VALUE / NaN mean unavailable, never zero. */
public final class Sample {
  public long id, wallMs, elapsedMs;
  public int boot = Integer.MIN_VALUE;
  public int level = Integer.MIN_VALUE;
  public int plugged = Integer.MIN_VALUE;
  public int screen = -1;
  public int thermal = Integer.MIN_VALUE;
  public int signalDbm = Integer.MIN_VALUE;
  public int signalLevel = Integer.MIN_VALUE;
  public int chargeUah = Integer.MIN_VALUE;
  public int currentUa = Integer.MIN_VALUE;
  public int voltageMv = Integer.MIN_VALUE;
  public double tempC = Double.NaN;
  public String network = "UNKNOWN";
  public String radio = "Desconocida";
  public String reason = "";
  public String continuity = "";
  public String zone = "";

  public Sample() {}
}
