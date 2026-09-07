package com.eren.gumustakip;

import android.app.*;
import android.content.*;
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
    public static final String ACTION_SETTINGS_CHANGED = "SETTINGS_CHANGED";

    private static final String CHANNEL_STATUS_ID = "gumus_status_channel";
    private static final String CHANNEL_ALERT_ID = "gumus_alert_channel";
    private static final String CHANNEL_AI_ID = "gumus_ai_channel";
    private static final int NOTIFICATION_ID = 2201;
    private static final int LEVEL_NOTIFICATION_ID = 2202;
    private static final int AI_NOTIFICATION_ID = 2203;
    private static final long DEFAULT_CONTROL_MS = 60_000L;
    private static final long DEFAULT_AI_MS = 3_600_000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private Double lastSell;
    private long lastAiTimeMs;
    private SharedPreferences prefs;
    private final List<String> priceHistory = new ArrayList<>();

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("gumus", MODE_PRIVATE);
        createChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTracking();
            return START_NOT_STICKY;
        }
        long controlMin = getPositiveLong("control_interval_min", 1);
        startForeground(NOTIFICATION_ID, buildNotification(CHANNEL_STATUS_ID,
                "Gümüş takibi çalışıyor", controlMin + " dakikada bir fiyat kontrol ediliyor."));
        if (!running) {
            running = true;
            lastAiTimeMs = System.currentTimeMillis();
            priceHistory.clear();
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
                if (!Double.isNaN(r.sell) && r.buy >= r.sell) {
                    double grams = parse(prefs.getString("grams", "0"));
                    double cost = parse(prefs.getString("cost", "0"));
                    String fullTs = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(new Date());
                    String timeOnly = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

                    saveLiveData(r, timeOnly, fullTs);
                    String record = String.format(Locale.US,
                            "[%s] Alış: %.4f TL | Satış: %.4f TL", fullTs, r.buy, r.sell);
                    appendDailyHistory(record);

                    double portfolioValue = grams * r.sell;
                    double totalCost = grams * cost;
                    double profit = portfolioValue - totalCost;
                    double pct = totalCost > 0 ? (profit / totalCost * 100.0) : 0.0;
                    System.out.println("📌 " + record);
                    System.out.println(String.format(Locale.US, "💰 Portföy: %.2f TL | K/Z: %+.2f TL (%%%.2f)", portfolioValue, profit, pct));

                    updateForeground(String.format(Locale.US, "Alış %.4f · Satış %.4f · %s", r.buy, r.sell, timeOnly));
                    sendUiUpdate(r, grams, cost, timeOnly);
                    notifyLevelChange(r.sell, r.buy, grams, cost);
                    priceHistory.add(String.format(Locale.US, "%s -> %.4f TL", timeOnly, r.sell));

                    long aiInterval = getPositiveLong("ai_interval_min", 60) * 60_000L;
                    if (System.currentTimeMillis() - lastAiTimeMs >= aiInterval) {
                        runAiAnalysis(grams, cost);
                        priceHistory.clear();
                        lastAiTimeMs = System.currentTimeMillis();
                    }
                }
            } catch (Exception e) {
                System.out.println("Bağlantı Hatası: " + e.getMessage());
                updateForeground("Fiyat alınamadı · tekrar denenecek");
            }

            long intervalMs = getPositiveLong("control_interval_min", 1) * 60_000L;
            long sleepMs = Math.max(1000L, intervalMs - (System.currentTimeMillis() - started));
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void saveLiveData(PriceTracker.Result r, String timeOnly, String fullTs) {
        SharedPreferences.Editor e = prefs.edit()
                .putFloat("last_sell", (float) r.sell)
                .putFloat("last_buy", (float) r.buy)
                .putString("last_ts", fullTs);

        JSONArray rows;
        try { rows = new JSONArray(prefs.getString("live_rows", "[]")); } catch (Exception ex) { rows = new JSONArray(); }
        JSONArray next = new JSONArray();
        try {
            JSONObject obj = new JSONObject();
            obj.put("time", timeOnly);
            obj.put("buy", r.buy);
            obj.put("sell", r.sell);
            for (int i = Math.max(0, rows.length() - 29); i < rows.length(); i++) next.put(rows.get(i));
            next.put(obj);
        } catch (Exception ignored) {}
        e.putString("live_rows", next.toString()).apply();
    }

    private void sendUiUpdate(PriceTracker.Result r, double grams, double cost, String ts) {
        Intent i = new Intent("com.eren.gumustakip.UPDATE_UI");
        i.setPackage(getPackageName());
        i.putExtra("sell", r.sell); i.putExtra("buy", r.buy);
        i.putExtra("grams", grams); i.putExtra("cost", cost); i.putExtra("ts", ts);
        sendBroadcast(i);
    }

    private void notifyLevelChange(double sell, double buy, double grams, double cost) {
        int level = (int) Math.floor(sell);
        if (lastSell != null && (int) Math.floor(lastSell) != level) {
            double diff = sell - lastSell;
            String direction = diff > 0 ? "Yükseliş 📈" : "Düşüş 📉";
            double value = grams * sell, totalCost = grams * cost;
            double profit = value - totalCost, pct = totalCost > 0 ? profit / totalCost * 100.0 : 0;
            String msg = String.format(Locale.US, "Satış: %.4f TL (Eski: %.4f TL)\nPortföy: %.2f TL\nNet K/Z: %+.2f TL (%+.2f%%)", sell, lastSell, value, profit, pct);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(LEVEL_NOTIFICATION_ID, buildNotification(CHANNEL_ALERT_ID,
                    "Gümüş " + level + " TL Seviyesinde! (" + direction + ")", msg));
        }
        lastSell = sell;
    }

    private void runAiAnalysis(double grams, double cost) {
        String tableText = priceHistory.isEmpty() ? "Veri yok." : joinPriceHistory();
        long aiMin = getPositiveLong("ai_interval_min", 60);
        System.out.println("🧠 " + aiMin + " dakika doldu. Yapay zekaya son veriler gönderiliyor...");
        String answer = GeminiAnalyzer.ask(grams, cost, tableText, aiMin);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(AI_NOTIFICATION_ID, buildNotification(CHANNEL_AI_ID, "🤖 Gemini Finansal Tavsiye", answer));
        prefs.edit().putString("last_ai_advice", answer).putString("last_ai_time", new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(new Date())).apply();
        Intent i = new Intent("com.eren.gumustakip.UPDATE_AI"); i.setPackage(getPackageName()); i.putExtra("advice", answer); sendBroadcast(i);
    }

    private String joinPriceHistory() {
        StringBuilder sb = new StringBuilder();
        for (String item : priceHistory) { if (sb.length() > 0) sb.append(", "); sb.append(item); }
        return sb.toString();
    }

    private synchronized void appendDailyHistory(String line) {
        File file = new File(getFilesDir(), "gumus_fiyat_gecmisi.txt");
        String today = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(new Date());
        boolean reset = !file.exists();
        if (!reset) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String first = r.readLine(); reset = first == null || !first.contains(today);
            } catch (Exception ex) { reset = true; }
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file, !reset), StandardCharsets.UTF_8)) {
            if (reset) w.write("=== TARİH: " + today + " ===\n");
            w.write(line); w.write("\n");
        } catch (Exception e) { System.out.println("TXT kayıt hatası: " + e.getMessage()); }
    }

    private long getPositiveLong(String key, long fallback) {
        long v = prefs.getLong(key, fallback);
        return v > 0 ? v : fallback;
    }

    private double parse(String s) { try { return Double.parseDouble(s == null ? "0" : s.trim().replace(",", ".")); } catch (Exception e) { return 0; } }

    private Notification buildNotification(String channelId, String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return new Notification.Builder(this, channelId).setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).setOngoing(channelId.equals(CHANNEL_STATUS_ID)).build();
    }

    private void updateForeground(String text) { NotificationManager nm = getSystemService(NotificationManager.class); if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(CHANNEL_STATUS_ID, "Gümüş takibi çalışıyor", text)); }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_STATUS_ID, "Takip durumu", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT_ID, "Fiyat bildirimleri", NotificationManager.IMPORTANCE_HIGH));
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_AI_ID, "Gemini tavsiyeleri", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    private void stopTracking() { running = false; stopForeground(true); stopSelf(); }
    @Override public void onDestroy() { running = false; executor.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
