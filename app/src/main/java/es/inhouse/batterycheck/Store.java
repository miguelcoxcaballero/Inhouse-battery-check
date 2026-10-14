package es.inhouse.batterycheck;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Local database only; insertion order preserves real chronology across clock changes. */
public final class Store extends SQLiteOpenHelper {
  private static final String[] COLS = {
    "id",
    "wall",
    "elapsed",
    "boot",
    "level",
    "plugged",
    "screen",
    "thermal",
    "signal_dbm",
    "signal_level",
    "charge_uah",
    "current_ua",
    "voltage_mv",
    "temp_c",
    "network",
    "radio",
    "reason",
    "continuity",
    "zone"
  };

  public Store(Context context) {
    super(context.getApplicationContext(), "inhouse-local.db", null, 1);
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE samples (id INTEGER PRIMARY KEY AUTOINCREMENT, wall INTEGER NOT NULL, elapsed"
            + " INTEGER NOT NULL, boot INTEGER, level INTEGER, plugged INTEGER, screen INTEGER,"
            + " thermal INTEGER, signal_dbm INTEGER, signal_level INTEGER, charge_uah INTEGER,"
            + " current_ua INTEGER, voltage_mv INTEGER, temp_c REAL, network TEXT, radio TEXT,"
            + " reason TEXT, continuity TEXT, zone TEXT)");
    db.execSQL("CREATE INDEX samples_wall ON samples(wall)");
    db.execSQL(
        "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, wall INTEGER, kind TEXT, note"
            + " TEXT)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    // Version 1: never silently discard a user's observations.
  }

  public synchronized void insert(Sample s) {
    ContentValues v = new ContentValues();
    v.put("wall", s.wallMs);
    v.put("elapsed", s.elapsedMs);
    v.put("boot", s.boot);
    v.put("level", s.level);
    v.put("plugged", s.plugged);
    v.put("screen", s.screen);
    v.put("thermal", s.thermal);
    v.put("signal_dbm", s.signalDbm);
    v.put("signal_level", s.signalLevel);
    v.put("charge_uah", s.chargeUah);
    v.put("current_ua", s.currentUa);
    v.put("voltage_mv", s.voltageMv);
    if (Double.isNaN(s.tempC) || Double.isInfinite(s.tempC)) v.putNull("temp_c");
    else v.put("temp_c", s.tempC);
    v.put("network", s.network);
    v.put("radio", s.radio);
    v.put("reason", s.reason);
    v.put("continuity", s.continuity);
    v.put("zone", s.zone);
    s.id = getWritableDatabase().insertOrThrow("samples", null, v);
  }

  public synchronized List<Sample> samples(long startMs, long endMs) {
    return query(
        "wall >= ? AND wall <= ?", new String[] {Long.toString(startMs), Long.toString(endMs)});
  }

  public synchronized List<Sample> all() {
    return query(null, null);
  }

  private List<Sample> query(String where, String[] args) {
    List<Sample> list = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase().query("samples", COLS, where, args, null, null, "id ASC")) {
      while (c.moveToNext()) {
        Sample s = new Sample();
        s.id = c.getLong(0);
        s.wallMs = c.getLong(1);
        s.elapsedMs = c.getLong(2);
        s.boot = c.getInt(3);
        s.level = c.getInt(4);
        s.plugged = c.getInt(5);
        s.screen = c.getInt(6);
        s.thermal = c.getInt(7);
        s.signalDbm = c.getInt(8);
        s.signalLevel = c.getInt(9);
        s.chargeUah = c.getInt(10);
        s.currentUa = c.getInt(11);
        s.voltageMv = c.getInt(12);
        s.tempC = c.isNull(13) ? Double.NaN : c.getDouble(13);
        s.network = c.getString(14);
        s.radio = c.getString(15);
        s.reason = c.getString(16);
        s.continuity = c.getString(17);
        s.zone = c.getString(18);
        list.add(s);
      }
    }
    return list;
  }

  public synchronized void clear() {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      db.delete("samples", null, null);
      db.delete("events", null, null);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public synchronized long firstTime() {
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT wall FROM samples ORDER BY id ASC LIMIT 1", null)) {
      return c.moveToFirst() ? c.getLong(0) : 0L;
    }
  }

  public synchronized void event(String kind, String note) {
    ContentValues v = new ContentValues();
    v.put("wall", System.currentTimeMillis());
    v.put("kind", kind);
    v.put("note", note);
    SQLiteDatabase db = getWritableDatabase();
    db.insertOrThrow("events", null, v);
    db.execSQL("DELETE FROM events WHERE id < (SELECT MAX(id)-999 FROM events)");
  }

  public synchronized String eventsText() {
    StringBuilder out = new StringBuilder();
    DateTimeFormatter f =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss XXX").withZone(ZoneId.systemDefault());
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT wall,kind,note FROM events ORDER BY id DESC LIMIT 200", null)) {
      while (c.moveToNext())
        out.append(f.format(Instant.ofEpochMilli(c.getLong(0))))
            .append(" · ")
            .append(c.getString(1))
            .append(" · ")
            .append(c.getString(2))
            .append('\n');
    }
    return out.toString();
  }
}
