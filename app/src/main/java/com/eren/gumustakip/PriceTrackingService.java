package com.eren.gumustakip;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PriceTrackingService extends Service {
    public static final String ACTION_START = "START";
    public static final String ACTION_STOP = "STOP";

    private static final String CHANNEL_STATUS_ID = "gumus_status_channel";
    private static final String CHANNEL_UP_ID = "gumus_up_channel";
    private static final String CHANNEL_DOWN_ID = "gumus_down_channel";
    private static final int NOTIFICATION_ID = 2201;
    private static final int LEVEL_NOTIFICATION_ID = 2202;
    private long lastSignalReminderMs;
    private String lastSignalKey = "";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private Double lastSell;
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("gumus", MODE_PRIVATE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":PriceTracking");
            wakeLock.setReferenceCounted(false);
        }
        DailyStorage.ensureToday(this);
        createChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.edit().putBoolean("tracking_enabled", false).apply();
            stopTracking();
            return START_NOT_STICKY;
        }

        prefs.edit().putBoolean("tracking_enabled", true).apply();
        if (wakeLock != null && !wakeLock.isHeld()) {
            try { wakeLock.acquire(); } catch (SecurityException ignored) { }
        }
        long controlSec = getPositiveLong("control_interval_sec", 60);
        startForeground(NOTIFICATION_ID, buildNotification(CHANNEL_STATUS_ID,
                "Gümüş takibi çalışıyor", controlSec + " saniyede bir fiyat kontrol ediliyor.", Color.GRAY));

        if (!running) {
            running = true;
            DailyStorage.ensureToday(this);
            lastSell = null;
            executor.execute(this::loop);
        }
        return START_STICKY;
    }

    private void loop() {
        while (running) {
            long started = System.currentTimeMillis();
            try {
                PriceTracker.Result r = PriceTracker.fetch();

                // Savunma: uygulama içinde de kuralı bir kez daha garanti et.
                double buy = Math.max(r.buy, r.sell);
                double sell = Math.min(r.buy, r.sell);
                r = new PriceTracker.Result(buy, sell);

                double grams = parse(prefs.getString("grams", "0"));
                double cost = parse(prefs.getString("cost", "0"));
                Date now = new Date();
                String fullTs = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(now);
                String timeOnly = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now);

                saveLiveData(r, timeOnly, fullTs);
                String record = String.format(Locale.US,
                        "[%s] Alış: %.4f TL | Satış: %.4f TL", fullTs, r.buy, r.sell);
                appendDailyHistory(record);

                double portfolioValue = grams * r.sell;
                double totalCost = grams * cost;
                double profit = portfolioValue - totalCost;
                double pct = totalCost > 0 ? (profit / totalCost * 100.0) : 0.0;

                System.out.println("📌 " + record);
                System.out.println(String.format(Locale.US,
                        "💰 Portföy: %.2f TL | K/Z: %+.2f TL (%%%.2f)", portfolioValue, profit, pct));

                updateForeground(String.format(Locale.US,
                        "Alış %.4f · Satış %.4f · %s", r.buy, r.sell, timeOnly));
                sendUiUpdate(r, grams, cost, timeOnly);
                notifyLevelChange(r.sell, grams, cost);

                evaluateSignal(r, grams);
            } catch (Exception e) {
                System.out.println("Bağlantı Hatası: " + e.getMessage());
                updateForeground("Fiyat alınamadı · tekrar denenecek");
            }

            long intervalMs = getPositiveLong("control_interval_sec", 60) * 1000L;
            long sleepMs = Math.max(1000L,
                    intervalMs - (System.currentTimeMillis() - started));
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void saveLiveData(PriceTracker.Result r, String timeOnly, String fullTs) {
        SharedPreferences.Editor e = prefs.edit()
                .putFloat("last_sell", (float) r.sell)
                .putFloat("last_buy", (float) r.buy)
                .putString("last_ts", fullTs);

        JSONArray rows;
        try {
            rows = new JSONArray(prefs.getString("live_rows", "[]"));
        } catch (Exception ex) {
            rows = new JSONArray();
        }

        JSONArray next = new JSONArray();
        try {
            for (int i = Math.max(0, rows.length() - 99); i < rows.length(); i++) {
                next.put(rows.get(i));
            }

            JSONObject obj = new JSONObject();
            obj.put("time", timeOnly);
            obj.put("timestamp", System.currentTimeMillis());
            obj.put("buy", r.buy);
            obj.put("sell", r.sell);
            next.put(obj);
        } catch (Exception ignored) {
        }
        e.putString("live_rows", next.toString()).apply();
    }

    private void sendUiUpdate(PriceTracker.Result r, double grams, double cost, String ts) {
        Intent i = new Intent("com.eren.gumustakip.UPDATE_UI");
        i.setPackage(getPackageName());
        i.putExtra("sell", r.sell);
        i.putExtra("buy", r.buy);
        i.putExtra("grams", grams);
        i.putExtra("cost", cost);
        i.putExtra("ts", ts);
        sendBroadcast(i);
    }

    private void notifyLevelChange(double sell, double grams, double cost) {
        int level = (int) Math.floor(sell);
        if (lastSell != null && (int) Math.floor(lastSell) != level) {
            double diff = sell - lastSell;
            String direction = diff > 0 ? "Yükseliş 📈" : "Düşüş 📉";
            double value = grams * sell;
            double totalCost = grams * cost;
            double profit = value - totalCost;
            double pct = totalCost > 0 ? profit / totalCost * 100.0 : 0;
            String msg = String.format(Locale.US,
                    "Satış: %.4f TL (Eski: %.4f TL)\nPortföy: %.2f TL\nNet K/Z: %+.2f TL (%+.2f%%)",
                    sell, lastSell, value, profit, pct);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                String channel = diff > 0 ? CHANNEL_UP_ID : CHANNEL_DOWN_ID;
                int ledColor = diff > 0 ? Color.GREEN : Color.RED;
                nm.notify(LEVEL_NOTIFICATION_ID,
                        buildNotification(channel,
                                "Gümüş " + level + " TL Seviyesinde! (" + direction + ")",
                                msg, ledColor));
            }
        }
        lastSell = sell;
    }

    private void evaluateSignal(PriceTracker.Result r, double grams) {
        TradingAlgorithm.Signal signal = TradingAlgorithm.evaluate(this, r.sell, r.buy, grams);
        if (signal.action.equals("HOLD")) return;
        String key = signal.action + ":" + (int)Math.floor(r.sell) + ":" + Math.round(signal.fraction*100);
        long now = System.currentTimeMillis();
        // First notification, then repeat a reminder every 15 minutes until the user records a response.
        boolean pending = prefs.getBoolean("signal_pending", false);
        long answeredAt=prefs.getLong("signal_answered_at",0);
        if (!pending && now-answeredAt < 60*60_000L) { lastSignalKey=key; return; }
        if (!key.equals(lastSignalKey) || !pending || now-lastSignalReminderMs >= 15*60_000L) {
            String message = signal.message(parse(prefs.getString("grams","0")),r.sell,r.buy);
            prefs.edit().putBoolean("signal_pending",true).putString("signal_key",key)
                    .putString("signal_action",signal.action).putString("signal_message",message)
                    .putFloat("signal_price",(float)(signal.action.equals("SELL")?r.sell:r.buy))
                    .putLong("signal_time",now).apply();
            NotificationManager nm=getSystemService(NotificationManager.class);
            if(nm!=null) nm.notify(2204,buildNotification(signal.action.equals("SELL")?CHANNEL_DOWN_ID:CHANNEL_UP_ID,
                    "Gümüş algoritma önerisi",message+"\nUygulamayı açıp işlemi yaptığını/yapmadığını kaydet.",signal.action.equals("SELL")?Color.RED:Color.GREEN));
            lastSignalKey=key; lastSignalReminderMs=now;
        }
    }

    private synchronized void appendDailyHistory(String line) {
        DailyStorage.ensureToday(this);
        File file = new File(getFilesDir(), "gumus_fiyat_gecmisi.txt");
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            if (file.length() == 0) {
                String today = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(new Date());
                w.write("=== TARİH: " + today + " ===\n");
            }
            w.write(line);
            w.write("\n");
        } catch (Exception e) {
            System.out.println("TXT kayıt hatası: " + e.getMessage());
        }
    }

    private long getPositiveLong(String key, long fallback) {
        long value = prefs.getLong(key, fallback);
        return value > 0 ? value : fallback;
    }

    private double parse(String s) {
        try {
            return Double.parseDouble(s == null ? "0" : s.trim().replace(",", "."));
        } catch (Exception e) {
            return 0;
        }
    }

    private Notification buildNotification(String channelId, String title, String text, int ledColor) {
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channelId)
                : new Notification.Builder(this);

        builder.setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(!channelId.equals(CHANNEL_STATUS_ID))
                .setOnlyAlertOnce(channelId.equals(CHANNEL_STATUS_ID));

        if (Build.VERSION.SDK_INT < 26) {
            builder.setLights(ledColor, 500, 1500);
        }
        if (channelId.equals(CHANNEL_STATUS_ID)) builder.setOngoing(true);
        return builder.build();
    }

    private void updateForeground(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID,
                    buildNotification(CHANNEL_STATUS_ID, "Gümüş takibi çalışıyor", text, Color.GRAY));
        }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            // Eski tek renkli kanalın kullanılmasını engelle.
            nm.deleteNotificationChannel("gumus_alert_channel");
            createLightChannel(nm, CHANNEL_STATUS_ID, "Takip durumu", NotificationManager.IMPORTANCE_LOW, Color.GRAY, false);
            createLightChannel(nm, CHANNEL_UP_ID, "Fiyat artışı", NotificationManager.IMPORTANCE_HIGH, Color.GREEN, true);
            createLightChannel(nm, CHANNEL_DOWN_ID, "Fiyat düşüşü", NotificationManager.IMPORTANCE_HIGH, Color.RED, true);
        }
    }

    private void createLightChannel(NotificationManager nm, String id, String name, int importance, int color, boolean lights) {
        NotificationChannel channel = new NotificationChannel(id, name, importance);
        channel.enableLights(lights);
        if (lights) channel.setLightColor(color);
        nm.createNotificationChannel(channel);
    }

    private void stopTracking() {
        running = false;
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        catch (RuntimeException ignored) { }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Foreground service is deliberately independent of the activity/task UI.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        running = false;
        releaseWakeLock();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
