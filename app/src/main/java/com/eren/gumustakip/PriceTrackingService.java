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
    private static final String CHANNEL_AI_ID = "gumus_ai_channel";
    private static final int NOTIFICATION_ID = 2201;
    private static final int LEVEL_NOTIFICATION_ID = 2202;
    private static final int AI_NOTIFICATION_ID = 2203;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private Double lastSell;
    private long lastAiTimeMs;
    private SharedPreferences prefs;

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("gumus", MODE_PRIVATE);
        DailyStorage.ensureToday(this);
        createChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTracking();
            return START_NOT_STICKY;
        }

        long controlSec = getPositiveLong("control_interval_sec", 60);
        startForeground(NOTIFICATION_ID, buildNotification(CHANNEL_STATUS_ID,
                "Gümüş takibi çalışıyor", controlSec + " saniyede bir fiyat kontrol ediliyor.", Color.GRAY));

        if (!running) {
            running = true;
            DailyStorage.ensureToday(this);
            lastAiTimeMs = System.currentTimeMillis();
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

                long aiInterval = getPositiveLong("ai_interval_min", 60) * 60_000L;
                if (GeminiAnalyzer.hasApiKey(this) && System.currentTimeMillis() - lastAiTimeMs >= aiInterval) {
                    runAiAnalysis(grams, cost);
                    lastAiTimeMs = System.currentTimeMillis();
                }
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
            for (int i = Math.max(0, rows.length() - 29); i < rows.length(); i++) {
                next.put(rows.get(i));
            }

            JSONObject obj = new JSONObject();
            obj.put("time", timeOnly);
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

    private void runAiAnalysis(double grams, double cost) {
        String aiContext = buildAiContext();
        long aiMin = getPositiveLong("ai_interval_min", 60);
        System.out.println("AI analiz zamanı geldi; günün kayıtları işleniyor...");

        String answer = GeminiAnalyzer.ask(this, grams, cost, aiContext, aiMin);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(AI_NOTIFICATION_ID,
                    buildNotification(CHANNEL_AI_ID, "🤖 Gemini Veri Analizi", answer, Color.YELLOW));
        }

        prefs.edit()
                .putString("last_ai_advice", answer)
                .putString("last_ai_time", new SimpleDateFormat(
                        "dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(new Date()))
                .apply();

        Intent i = new Intent("com.eren.gumustakip.UPDATE_AI");
        i.setPackage(getPackageName());
        i.putExtra("advice", answer);
        sendBroadcast(i);
    }

    /**
     * Son 30 kayıt ve günün geçmişi analiz için kullanılır.
     */
    private String buildAiContext() {
        DailyStorage.ensureToday(this);
        StringBuilder sb = new StringBuilder();
        sb.append("SON 30 KAYIT:\n");

        try {
            JSONArray rows = new JSONArray(prefs.getString("live_rows", "[]"));
            if (rows.length() == 0) {
                sb.append("Veri yok.\n");
            } else {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject o = rows.getJSONObject(i);
                    sb.append(String.format(Locale.US,
                            "%s | Alış: %.4f TL | Satış: %.4f TL\n",
                            o.optString("time", ""),
                            o.optDouble("buy", 0),
                            o.optDouble("sell", 0)));
                }
            }
        } catch (Exception e) {
            sb.append("Anlık tablo okunamadı.\n");
        }

        sb.append("\nBUGÜNÜN KAYIT DOSYASI:\n");
        File file = new File(getFilesDir(), "gumus_fiyat_gecmisi.txt");
        if (file.exists()) {
            try {
                List<String> lines = readLastLines(file, 80);
                for (String line : lines) {
                    sb.append(line).append('\n');
                }
            } catch (Exception e) {
                sb.append("TXT geçmişi okunamadı.\n");
            }
        } else {
            sb.append("TXT dosyası henüz oluşmadı.\n");
        }
        return sb.toString().trim();
    }

    private List<String> readLastLines(File file, int maxLines) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (lines.size() > maxLines) lines.remove(0);
            }
        }
        return lines;
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
                .setSmallIcon(R.drawable.ic_notification)
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
            createLightChannel(nm, CHANNEL_AI_ID, "Gemini veri analizleri", NotificationManager.IMPORTANCE_DEFAULT, Color.YELLOW, true);
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
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
