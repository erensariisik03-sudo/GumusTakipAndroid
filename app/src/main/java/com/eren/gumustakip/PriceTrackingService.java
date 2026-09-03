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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PriceTrackingService extends Service {
    public static final String ACTION_START = "START";
    public static final String ACTION_STOP = "STOP";
    
    private static final String CHANNEL_STATUS_ID = "gumus_status_channel";
    private static final String CHANNEL_ALERT_ID = "gumus_alert_channel";
    
    private static final int NOTIFICATION_ID = 2201;
    private static final int LEVEL_NOTIFICATION_ID = 2202;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;
    private Double lastSell = null;
    private SharedPreferences prefs;

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("gumus", MODE_PRIVATE);
        createChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTracking(); return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(CHANNEL_STATUS_ID, "Gümüş takibi çalışıyor", "60 saniyede bir fiyat kontrol ediliyor."));
        if (!running) { running = true; executor.execute(this::loop); }
        return START_STICKY;
    }

    private void loop() {
        while (running) {
            try {
                PriceTracker.Result r = PriceTracker.fetch();
                double grams = parse(prefs.getString("grams", "0"));
                double cost = parse(prefs.getString("cost", "0"));
                String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                
                // Anlık verileri kalıcı hafızaya kaydediyoruz (Uygulama kapansa bile silinmez)[cite: 3]
                prefs.edit()
                    .putFloat("last_sell", (float) r.sell)
                    .putFloat("last_buy", (float) r.buy)
                    .putString("last_ts", ts)
                    .apply();

                updateForeground(String.format(Locale.US, "Satış %.4f TL · Alış %.4f TL · %s", r.sell, r.buy, ts));
                
                Intent updateIntent = new Intent("com.eren.gumustakip.UPDATE_UI");
                updateIntent.putExtra("sell", r.sell);
                updateIntent.putExtra("buy", r.buy);
                updateIntent.putExtra("grams", grams);
                updateIntent.putExtra("cost", cost);
                updateIntent.putExtra("ts", ts);
                sendBroadcast(updateIntent);

                if (!Double.isNaN(r.sell)) {
                    notifyLevelChange(r.sell, r.buy, grams, cost);
                }
            } catch (Exception e) {
                updateForeground("Fiyat alınamadı · tekrar denenecek");
            }
            sleep60();
        }
    }

    private void notifyLevelChange(double sell, double buy, double grams, double cost) {
        int level = (int) sell;
        if (lastSell != null && (int)(double)lastSell != level) {
            double diff = sell - lastSell;
            String direction = diff > 0 ? "Yükseliş 📈" : "Düşüş 📉";
            double value = grams * sell;
            double totalCost = grams * cost;
            double profit = value - totalCost;
            double pct = totalCost > 0 ? (profit / totalCost * 100.0) : 0;
            String msg = String.format(Locale.US, "Satış: %.4f TL (Eski: %.4f TL)", sell, lastSell);
            if (grams > 0) {
                msg += String.format(Locale.US, "\nPortföy Değeri: %.2f TL\nNet Kar/Zarar: %+.2f TL (%+.2f%%)", value, profit, pct).replace("+ ", "+");
            }
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(LEVEL_NOTIFICATION_ID, buildNotification(CHANNEL_ALERT_ID, "Gümüş Satış " + level + " TL Seviyesine Geçti! (" + direction + ")", msg));
        }
        lastSell = sell;
    }

    private void sleep60() { try { Thread.sleep(60_000L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }

    private double parse(String s) { try { return Double.parseDouble(s.replace(",", ".")); } catch (Exception e) { return 0; } }

    private Notification buildNotification(String channelId, String title, String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channelId) : new Notification.Builder(this);
        
        Notification.Builder builder = b.setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setOngoing(channelId.equals(CHANNEL_STATUS_ID))
                .setOnlyAlertOnce(true);

        if (channelId.equals(CHANNEL_ALERT_ID)) {
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
            
            NotificationChannel statusChannel = new NotificationChannel(CHANNEL_STATUS_ID, "Gümüş Takip Durumu", NotificationManager.IMPORTANCE_LOW);
            statusChannel.setDescription("Arka plan servis durumunu gösterir");
            nm.createNotificationChannel(statusChannel);

            NotificationChannel alertChannel = new NotificationChannel(CHANNEL_ALERT_ID, "Gümüş Seviye Alarmları", NotificationManager.IMPORTANCE_HIGH);
            alertChannel.setDescription("Fiyat seviye değişim bildirimleri");
            alertChannel.enableVibration(true);
            alertChannel.setVibrationPattern(new long[]{0, 500, 250, 500});
            alertChannel.enableLights(true);
            alertChannel.setLightColor(android.graphics.Color.GREEN);
            nm.createNotificationChannel(alertChannel);
        }
    }

    private void stopTracking() {
        running = false;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() { running = false; executor.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
