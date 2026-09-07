package com.eren.gumustakip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PriceTrackingService extends Service {
    public static final String ACTION_START = "START";
    public static final String ACTION_STOP = "STOP";

    private static final String CHANNEL_STATUS_ID = "gumus_status_channel";
    private static final String CHANNEL_ALERT_ID = "gumus_alert_channel";
    private static final String CHANNEL_AI_ID = "gumus_ai_channel";

    private static final int NOTIFICATION_ID = 2201;
    private static final int LEVEL_NOTIFICATION_ID = 2202;
    private static final int AI_NOTIFICATION_ID = 2203;

    // Yeni_Metin_Belgesi[1](1).txt ile aynı zamanlama.
    private static final long KONTROL_SURESI_MS = 40_000L;
    private static final long YZ_ANALIZ_SURESI_MS = 1_800_000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;
    private Double lastSell = null;
    private long lastAiTimeMs = 0L;
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

        startForeground(NOTIFICATION_ID, buildNotification(
                CHANNEL_STATUS_ID,
                "Gümüş takibi çalışıyor",
                "40 saniyede bir fiyat kontrol ediliyor."
        ));

        if (!running) {
            running = true;
            lastAiTimeMs = System.currentTimeMillis();
            priceHistory.clear();
            executor.execute(this::loop);
        }
        return START_STICKY;
    }

    private void loop() {
        while (running) {
            try {
                PriceTracker.Result r = PriceTracker.fetch();
                if (!Double.isNaN(r.sell)) {
                    double grams = parse(prefs.getString("grams", "0"));
                    double cost = parse(prefs.getString("cost", "0"));
                    String fullTs = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(new Date());
                    String timeOnly = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

                    saveLiveData(r, timeOnly);
                    String record = String.format(Locale.US,
                            "[%s] Satış: %.4f TL | Alış: %.4f TL", fullTs, r.sell, r.buy);
                    appendDailyHistory(record);

                    double portfolioValue = grams * r.sell;
                    double totalCost = grams * cost;
                    double profit = portfolioValue - totalCost;
                    double pct = totalCost > 0 ? (profit / totalCost * 100.0) : 0.0;
                    String status = profit >= 0 ? "📈" : "📉";

                    System.out.println("📌 " + record);
                    System.out.println(String.format(Locale.US,
                            "💰 Portföy: %.2f TL | K/Z: %+.2f TL (%%%.2f) %s",
                            portfolioValue, profit, pct, status));

                    updateForeground(String.format(Locale.US,
                            "Satış %.4f TL · Alış %.4f TL · %s", r.sell, r.buy, timeOnly));
                    sendUiUpdate(r, grams, cost, timeOnly);
                    notifyLevelChange(r.sell, r.buy, grams, cost);

                    priceHistory.add(String.format(Locale.US, "%s -> %.4f TL", timeOnly, r.sell));

                    long elapsed = System.currentTimeMillis() - lastAiTimeMs;
                    if (elapsed >= YZ_ANALIZ_SURESI_MS) {
                        runAiAnalysis(grams, cost);
                        priceHistory.clear();
                        lastAiTimeMs = System.currentTimeMillis();
                    }
                }
            } catch (Exception e) {
                System.out.println("Bağlantı Hatası: " + e.getMessage());
                updateForeground("Fiyat alınamadı · tekrar denenecek");
            }

            sleepControlInterval();
        }
    }

    private void saveLiveData(PriceTracker.Result r, String timeOnly) {
        prefs.edit()
                .putFloat("last_sell", (float) r.sell)
                .putFloat("last_buy", (float) r.buy)
                .putString("last_ts", timeOnly)
                .apply();
    }

    private void sendUiUpdate(PriceTracker.Result r, double grams, double cost, String ts) {
        Intent updateIntent = new Intent("com.eren.gumustakip.UPDATE_UI");
        updateIntent.setPackage(getPackageName());
        updateIntent.putExtra("sell", r.sell);
        updateIntent.putExtra("buy", r.buy);
        updateIntent.putExtra("grams", grams);
        updateIntent.putExtra("cost", cost);
        updateIntent.putExtra("ts", ts);
        sendBroadcast(updateIntent);
    }

    private void notifyLevelChange(double sell, double buy, double grams, double cost) {
        int level = (int) sell;
        if (lastSell != null && (int) Math.floor(lastSell) != level) {
            double diff = sell - lastSell;
            String direction = diff > 0 ? "Yükseliş 📈" : "Düşüş 📉";
            double value = grams * sell;
            double totalCost = grams * cost;
            double profit = value - totalCost;
            double pct = totalCost > 0 ? (profit / totalCost * 100.0) : 0.0;

            String msg = String.format(Locale.US,
                    "Satış: %.4f TL (Eski: %.4f TL)", sell, lastSell);
            if (grams > 0) {
                msg += String.format(Locale.US,
                        "\nPortföy Değeri: %.2f TL\nNet Kar/Zarar: %+.2f TL (%+.2f%%)",
                        value, profit, pct);
            }

            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(LEVEL_NOTIFICATION_ID, buildNotification(
                    CHANNEL_ALERT_ID,
                    "Gümüş " + level + " TL Seviyesinde! (" + direction + ")",
                    msg
            ));
        }
        lastSell = sell;
    }

    private void runAiAnalysis(double grams, double cost) {
        String tableText = joinPriceHistory();
        if (tableText.isEmpty()) tableText = "Veri yok.";

        System.out.println("🧠 30 dakika doldu. Yapay zekaya son veriler gönderiliyor...");
        String answer = GeminiAnalyzer.ask(grams, cost, tableText);
        System.out.println("🤖 [YAPAY ZEKA TAVSİYESİ]: " + answer);

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(AI_NOTIFICATION_ID, buildNotification(
                CHANNEL_AI_ID,
                "🤖 Gemini Finansal Tavsiye",
                answer
        ));

        prefs.edit().putString("last_ai_advice", answer).apply();
        Intent aiIntent = new Intent("com.eren.gumustakip.UPDATE_AI");
        aiIntent.setPackage(getPackageName());
        aiIntent.putExtra("advice", answer);
        sendBroadcast(aiIntent);
    }

    private String joinPriceHistory() {
        StringBuilder sb = new StringBuilder();
        for (String item : priceHistory) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(item);
        }
        return sb.toString();
    }

    /** Python scriptteki txt_gunluk_kayit() karşılığı: gün değişince dosyayı sıfırlar. */
    private synchronized void appendDailyHistory(String line) {
        File file = new File(getFilesDir(), "gumus_fiyat_gecmisi.txt");
        String today = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(new Date());
        boolean reset = !file.exists();

        if (!reset) {
            try (Scanner scanner = new Scanner(file, StandardCharsets.UTF_8.name())) {
                String firstLine = scanner.hasNextLine() ? scanner.nextLine() : "";
                if (!firstLine.contains(today)) reset = true;
            } catch (Exception e) {
                reset = true;
            }
        }

        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file, !reset), StandardCharsets.UTF_8)) {
            if (reset) {
                writer.write("=== TARİH: " + today + " ===\n");
                System.out.println("🧹 Eski tarihli veriler temizlendi. Günlük dosya başlatıldı (" + today + ").");
            }
            writer.write(line);
            writer.write("\n");
        } catch (Exception e) {
            System.out.println("TXT kayıt hatası: " + e.getMessage());
        }
    }

    private void sleepControlInterval() {
        try {
            Thread.sleep(KONTROL_SURESI_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private double parse(String s) {
        try {
            return Double.parseDouble(s == null ? "0" : s.trim().replace(",", "."));
        } catch (Exception e) {
            return 0;
        }
    }

    private Notification buildNotification(String channelId, String title, String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channelId)
                : new Notification.Builder(this);

        Notification.Builder builder = b.setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setOngoing(channelId.equals(CHANNEL_STATUS_ID))
                .setOnlyAlertOnce(channelId.equals(CHANNEL_STATUS_ID));

        if (channelId.equals(CHANNEL_ALERT_ID) || channelId.equals(CHANNEL_AI_ID)) {
            builder.setDefaults(Notification.DEFAULT_VIBRATE | Notification.DEFAULT_SOUND | Notification.DEFAULT_LIGHTS);
            builder.setLights(android.graphics.Color.GREEN, 1000, 1000);
        }
        return builder.build();
    }

    private void updateForeground(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(CHANNEL_STATUS_ID, "Gümüş Takip", text));
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);

            NotificationChannel statusChannel = new NotificationChannel(
                    CHANNEL_STATUS_ID, "Gümüş Takip Durumu", NotificationManager.IMPORTANCE_LOW);
            statusChannel.setDescription("Arka plan servis durumunu gösterir");
            nm.createNotificationChannel(statusChannel);

            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ALERT_ID, "Gümüş Seviye Alarmları", NotificationManager.IMPORTANCE_HIGH);
            alertChannel.setDescription("Fiyat seviye değişim bildirimleri");
            alertChannel.enableVibration(true);
            alertChannel.setVibrationPattern(new long[]{0, 500, 250, 500});
            alertChannel.enableLights(true);
            alertChannel.setLightColor(android.graphics.Color.GREEN);
            nm.createNotificationChannel(alertChannel);

            NotificationChannel aiChannel = new NotificationChannel(
                    CHANNEL_AI_ID, "Gemini Yapay Zeka Tavsiyeleri", NotificationManager.IMPORTANCE_HIGH);
            aiChannel.setDescription("Her 30 dakikada Gemini analiz bildirimi");
            aiChannel.enableVibration(true);
            aiChannel.setVibrationPattern(new long[]{0, 500, 250, 500});
            nm.createNotificationChannel(aiChannel);
        }
    }

    private void stopTracking() {
        running = false;
        lastSell = null;
        priceHistory.clear();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
