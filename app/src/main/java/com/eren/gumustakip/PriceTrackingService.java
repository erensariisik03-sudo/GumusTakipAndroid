package com.eren.gumustakip;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.util.Log;

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
    private static final String TAG = "GumusTakip";

    private static final String CHANNEL_STATUS_ID = "gumus_status_channel";
    private static final String CHANNEL_UP_ID = "gumus_up_channel";
    private static final String CHANNEL_DOWN_ID = "gumus_down_channel";
    private static final String CHANNEL_INFO_ID = "gumus_info_channel";
    private static final int NOTIFICATION_ID = 2201;
    private static final int LEVEL_NOTIFICATION_ID = 2202;
    private static final int SIGNAL_NOTIFICATION_ID = 2204;
    private static final int INFO_NOTIFICATION_ID = 2205;

    /** Fiyat bu kadar TL oynayınca "artış/azalış" bildirimi gelir. */
    private static final double LEVEL_STEP_TL = 1.0;
    /** Cevapsız öneri için hatırlatma aralığı. */
    private static final long REMIND_MS = 15 * 60_000L;
    /** Öneri bu kadar süredir artık geçerli değilse iptal edilir. */
    private static final long EXPIRE_MS = 10 * 60_000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<TradingAlgorithm.Tick> ticks = new ArrayList<>();
    private volatile boolean running;
    private int failCount;
    private int suspectCount;
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
        try {
            startForeground(NOTIFICATION_ID, buildNotification(CHANNEL_STATUS_ID,
                    "Gümüş takibi çalışıyor", controlSec + " saniyede bir fiyat kontrol ediliyor.", Color.GRAY));
        } catch (Exception e) {
            Log.e(TAG, "Ön plan servisi başlatılamadı", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!running) {
            running = true;
            DailyStorage.ensureToday(this);
            executor.execute(this::loop);
        }
        return START_STICKY;
    }

    // ------------------------------------------------------------------ ana döngü

    private void loop() {
        try {
            ticks.clear();
            ticks.addAll(TickStore.load(this, System.currentTimeMillis()));
        } catch (Exception e) {
            Log.e(TAG, "Geçmiş yüklenemedi", e);
        }
        while (running) {
            long started = System.currentTimeMillis();
            MarketSchedule.Mode mode = MarketSchedule.modeAt(started);
            boolean night = mode == MarketSchedule.Mode.NIGHT;
            long normalMs = getPositiveLong("control_interval_sec", 60) * 1000L;
            long activeMs = night ? Math.max(normalMs, MarketSchedule.NIGHT_POLL_MS) : normalMs; // gece seyrek kontrol
            long waitMs;

            if (mode == MarketSchedule.Mode.WEEKEND) {
                // Hafta sonu: hiç istek yapılmaz, yalnızca saat kontrol edilir.
                enterClosed("Piyasa kapalı (hafta sonu). Pazartesi 00:00'dan sonra kendiliğinden devam eder.");
                waitMs = MarketSchedule.CLOSED_RECHECK_MS;
            } else {
                try {
                    boolean ok = handlePrice(PriceTracker.fetch(), started, night);
                    // Başarısız/şüpheli okumada daha kısa aralıkla tekrar dene (gece 1 dk, gündüz 20 sn).
                    waitMs = ok ? activeMs : Math.min(activeMs, night ? 60_000L : 20_000L);
                } catch (PriceTracker.MarketClosedException e) {
                    enterClosed("Piyasa kapalı görünüyor (fiyatlar anormal). Açılınca kendiliğinden devam eder.");
                    waitMs = MarketSchedule.CLOSED_RECHECK_MS;
                } catch (Exception e) {
                    handleFailure(e.getMessage());
                    waitMs = Math.min(activeMs, night ? 60_000L : 20_000L);
                }
            }

            long sleepMs = Math.max(1000L, waitMs - (System.currentTimeMillis() - started));
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Piyasa kapalı: öneri/bildirim durur, bekleyen öneri iptal edilir, fiyat kaydedilmez. */
    private void enterClosed(String note) {
        boolean wasClosed = "CLOSED".equals(prefs.getString("market_state", "OPEN"));
        SharedPreferences.Editor e = prefs.edit()
                .putString("market_state", "CLOSED")
                .putString("market_note", note)
                .putString("algo_status", note)
                .putString("algo_detail", "")
                .putLong("algo_status_ts", System.currentTimeMillis());
        if (!wasClosed) {
            // Açılışta eski referanslarla yanlış "büyük hareket" bildirimi gelmesin.
            e.putBoolean("signal_pending", false).remove("level_anchor").remove("signal_mismatch_since");
        }
        e.apply();
        failCount = 0;
        suspectCount = 0;
        updateForeground(note);
        if (!wasClosed) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) { nm.cancel(SIGNAL_NOTIFICATION_ID); nm.cancel(LEVEL_NOTIFICATION_ID); }
            sendUiUpdate();
        }
    }

    /** Fiyatı doğrular, kaydeder, bildirim ve algoritmayı çalıştırır. Başarılıysa true. */
    private boolean handlePrice(PriceTracker.Result raw, long now, boolean night) throws PriceTracker.MarketClosedException {
        double buy = Math.max(raw.buy, raw.sell);
        double sell = Math.min(raw.buy, raw.sell);
        // Alış-satış farkı %10'dan büyükse bu gerçek bir fiyat değil, piyasa kapalıyken görünen anormal değerdir.
        if (sell > 0 && (buy - sell) / sell > 0.10)
            throw new PriceTracker.MarketClosedException("Anormal fiyat farkı: " + buy + " / " + sell);
        if (!acceptPrice(buy, sell, now)) {
            handleFailure("Şüpheli fiyat verisi atlandı (" + buy + " / " + sell + ")");
            return false;
        }
        failCount = 0;

        DailyStorage.ensureToday(this);
        TradingAlgorithm.Tick tick = new TradingAlgorithm.Tick(now, buy, sell);
        ticks.add(tick);
        long cutoff = now - TickStore.KEEP_MEMORY_MS;
        while (!ticks.isEmpty() && ticks.get(0).ts < cutoff) ticks.remove(0);
        TickStore.append(this, tick);

        Date d = new Date(now);
        String fullTs = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(d);
        String timeOnly = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(d);
        saveLiveData(buy, sell, timeOnly, fullTs);
        appendDailyHistory(String.format(Locale.US, "[%s] Alış: %.4f TL | Satış: %.4f TL", fullTs, buy, sell));

        String nightNote = "Gece modu (00:00–07:00): kontrol " + (MarketSchedule.NIGHT_POLL_MS / 60_000L) + " dakikada bir, öneri ve bildirim yok. Sabah kendiliğinden normale döner.";
        prefs.edit().putString("market_state", night ? "NIGHT" : "OPEN").putString("market_note", night ? nightNote : "").apply();

        if (night && !MarketSchedule.NIGHT_SIGNALS) {
            prefs.edit().putString("algo_status", nightNote).putString("algo_detail", "").putLong("algo_status_ts", now).apply();
            updateForeground(String.format(Locale.US, "Gece modu · Satış %s · %s", Fmt.n2(sell), timeOnly));
            sendUiUpdate();
            return true;
        }

        updateForeground(String.format(Locale.US, "Alış %s · Satış %s · %s", Fmt.n2(buy), Fmt.n2(sell), timeOnly));
        sendUiUpdate();

        Portfolio pf = new Portfolio(this);
        try { notifyLevelChange(now, sell, pf); } catch (Exception e) { Log.e(TAG, "Seviye bildirimi hatası", e); }
        try { evaluateSignal(now, pf); } catch (Exception e) { Log.e(TAG, "Algoritma hatası", e); }
        return true;
    }

    /** Çöp/yanlış okunmuş veriyi eler: alış>satış ve tek seferde %5'ten büyük sıçrama. */
    private boolean acceptPrice(double buy, double sell, long now) {
        if (sell <= 0 || buy <= sell) return false;
        if (!ticks.isEmpty()) {
            TradingAlgorithm.Tick lastTick = ticks.get(ticks.size() - 1);
            boolean recent = now - lastTick.ts < 30 * 60_000L; // uzun ara sonrası (piyasa açılışı) gerçek fark olabilir
            if (recent && lastTick.sell > 0 && Math.abs(sell - lastTick.sell) / lastTick.sell > 0.05) {
                // Gerçek bir sıçrama ise 3 ardışık okumada kabul edilir.
                if (++suspectCount < 3) return false;
            }
        }
        suspectCount = 0;
        return true;
    }

    private void handleFailure(String msg) {
        failCount++;
        Log.w(TAG, "Fiyat alınamadı (" + failCount + "): " + msg);
        updateForeground("Fiyat alınamadı (" + failCount + ". deneme) · tekrar denenecek");
        if (failCount % 30 == 10) {
            postInfo("Fiyat verisi alınamıyor",
                    failCount + " denemedir fiyat okunamadı. İnternet bağlantını ve sitenin açık olduğunu kontrol et. "
                            + "Veri gelmediği sürece algoritma sinyal üretmez.");
        }
    }

    private void saveLiveData(double buy, double sell, String timeOnly, String fullTs) {
        SharedPreferences.Editor e = prefs.edit();
        Portfolio.putD(e, "last_sell", sell);
        Portfolio.putD(e, "last_buy", buy);
        e.putString("last_ts", fullTs);

        JSONArray rows;
        try { rows = new JSONArray(prefs.getString("live_rows", "[]")); } catch (Exception ex) { rows = new JSONArray(); }
        JSONArray next = new JSONArray();
        try {
            for (int i = Math.max(0, rows.length() - 29); i < rows.length(); i++) next.put(rows.get(i));
            JSONObject obj = new JSONObject();
            obj.put("time", timeOnly);
            obj.put("timestamp", System.currentTimeMillis());
            obj.put("buy", buy);
            obj.put("sell", sell);
            next.put(obj);
        } catch (Exception ignored) { }
        e.putString("live_rows", next.toString()).apply();
    }

    private void sendUiUpdate() {
        Intent i = new Intent("com.eren.gumustakip.UPDATE_UI");
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    // ------------------------------------------------------------------ 1 TL bildirimi

    /** Son bildirimden bu yana fiyat en az 1 TL oynadıysa bildirir; sonra referans yeni fiyata çekilir. */
    private void notifyLevelChange(long now, double sell, Portfolio pf) {
        double anchor = Portfolio.getD(prefs, "level_anchor", 0);
        if (anchor <= 0) { saveAnchor(sell); return; }
        double diff = sell - anchor;
        if (Math.abs(diff) < LEVEL_STEP_TL) return;
        saveAnchor(sell);

        boolean up = diff > 0;
        double c15 = TradingAlgorithm.change(ticks, now, 15);
        StringBuilder msg = new StringBuilder();
        msg.append("Satış fiyatı: ").append(Fmt.n2(sell)).append(" TL/g");
        if (!Double.isNaN(c15)) msg.append("\nSon 15 dk: ").append(Fmt.signed2(c15)).append(" TL");
        msg.append("\nEldeki: ").append(Fmt.gram(pf.grams())).append(" g ≈ ").append(Fmt.tl(pf.value(sell)));
        msg.append("\nBaşlangıçtan beri: ").append(Fmt.stl(pf.totalPnl(sell))).append(" (").append(Fmt.pct(pf.totalPnlPct(sell))).append(")");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(LEVEL_NOTIFICATION_ID, buildNotification(up ? CHANNEL_UP_ID : CHANNEL_DOWN_ID,
                    (up ? "Gümüş yükseldi 📈 " : "Gümüş düştü 📉 ") + Fmt.signed2(diff) + " TL",
                    msg.toString(), up ? Color.GREEN : Color.RED));
        }
    }

    private void saveAnchor(double v) {
        SharedPreferences.Editor e = prefs.edit();
        Portfolio.putD(e, "level_anchor", v);
        e.apply();
    }

    // ------------------------------------------------------------------ algoritma önerisi

    private void evaluateSignal(long now, Portfolio pf) {
        TradingAlgorithm.Signal s = TradingAlgorithm.evaluate(ticks, now, pf.toPosition());
        prefs.edit().putString("algo_status", s.message).putString("algo_detail", s.detail == null ? "" : s.detail)
                .putLong("algo_status_ts", now).apply();

        if (prefs.getBoolean("signal_pending", false)) {
            handlePending(s, now);
            return;
        }
        if (s.isHold()) return;

        // Kullanıcı yanıt verdiyse kısa bir soğuma süresinden sonra güncel miktarla yeniden değerlendirilir.
        long answeredAt = prefs.getLong("signal_answered_at", 0);
        long cooldown = prefs.getLong("signal_cooldown_ms", 5 * 60_000L);
        if (now - answeredAt < cooldown) return;

        storeSignal(s, now, true);
        postSignalNotification(s, now, false);
    }

    /** Cevapsız öneri: hâlâ geçerliyse hatırlat, uzun süredir geçersizse iptal et. */
    private void handlePending(TradingAlgorithm.Signal s, long now) {
        String pendingAction = prefs.getString("signal_action", "");
        if (s.action.equals(pendingAction)) {
            double oldFraction = Portfolio.getD(prefs, "signal_fraction", 0);
            boolean escalated = Math.abs(s.fraction - oldFraction) > 0.01;
            storeSignal(s, now, false);
            prefs.edit().remove("signal_mismatch_since").apply();
            long notifiedAt = prefs.getLong("signal_notified_at", 0);
            if (escalated || now - notifiedAt >= REMIND_MS) postSignalNotification(s, now, true);
            return;
        }
        long since = prefs.getLong("signal_mismatch_since", 0);
        if (since == 0) {
            prefs.edit().putLong("signal_mismatch_since", now).apply();
        } else if (now - since >= EXPIRE_MS) {
            prefs.edit().putBoolean("signal_pending", false).remove("signal_mismatch_since").apply();
            postInfo("Önceki öneri geçersiz oldu", "Piyasa yönü değişti, önceki al/sat önerisi artık geçerli değil. Yeni bir öneri olursa haber verilecek.");
        }
    }

    private void storeSignal(TradingAlgorithm.Signal s, long now, boolean isNew) {
        SharedPreferences.Editor e = prefs.edit()
                .putBoolean("signal_pending", true)
                .putString("signal_action", s.action)
                .putString("signal_message", s.message);
        Portfolio.putD(e, "signal_price", s.price);
        Portfolio.putD(e, "signal_grams", s.grams);
        Portfolio.putD(e, "signal_fraction", s.fraction);
        if (isNew) e.putLong("signal_time", now).putLong("signal_notified_at", 0).remove("signal_mismatch_since");
        e.apply();
    }

    private void postSignalNotification(TradingAlgorithm.Signal s, long now, boolean reminder) {
        prefs.edit().putLong("signal_notified_at", now).apply();
        boolean sell = "SELL".equals(s.action);
        String title = (sell ? "SAT önerisi" : "AL önerisi") + (reminder ? " (hatırlatma)" : "");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(SIGNAL_NOTIFICATION_ID, buildNotification(sell ? CHANNEL_DOWN_ID : CHANNEL_UP_ID, title,
                    s.message + "\n\nİşlemi yaptıysan uygulamayı açıp yanıtla. Otomatik işlem yapılmaz.",
                    sell ? Color.RED : Color.GREEN));
        }
    }

    private void postInfo(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(INFO_NOTIFICATION_ID, buildNotification(CHANNEL_INFO_ID, title, text, Color.YELLOW));
    }

    // ------------------------------------------------------------------ dosya / yardımcılar

    private synchronized void appendDailyHistory(String line) {
        DailyStorage.ensureToday(this);
        File file = DailyStorage.getHistoryFile(this);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            if (file.length() == 0) {
                String today = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(new Date());
                w.write("=== TARİH: " + today + " ===\n");
            }
            w.write(line);
            w.write("\n");
        } catch (Exception e) {
            Log.e(TAG, "TXT kayıt hatası", e);
        }
    }

    private long getPositiveLong(String key, long fallback) {
        long value = prefs.getLong(key, fallback);
        return value > 0 ? value : fallback;
    }

    private Notification buildNotification(String channelId, String title, String text, int ledColor) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean status = channelId.equals(CHANNEL_STATUS_ID);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channelId)
                : new Notification.Builder(this);

        builder.setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(!status)
                .setOnlyAlertOnce(status);

        if (Build.VERSION.SDK_INT < 26) {
            builder.setLights(ledColor, 500, 1500);
            if (!status) {
                builder.setPriority(Notification.PRIORITY_HIGH);
                builder.setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
            }
        }
        if (status) builder.setOngoing(true);
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
            nm.deleteNotificationChannel("gumus_alert_channel");
            createLightChannel(nm, CHANNEL_STATUS_ID, "Takip durumu", NotificationManager.IMPORTANCE_LOW, Color.GRAY, false);
            createLightChannel(nm, CHANNEL_UP_ID, "Fiyat artışı / AL önerisi", NotificationManager.IMPORTANCE_HIGH, Color.GREEN, true);
            createLightChannel(nm, CHANNEL_DOWN_ID, "Fiyat düşüşü / SAT önerisi", NotificationManager.IMPORTANCE_HIGH, Color.RED, true);
            createLightChannel(nm, CHANNEL_INFO_ID, "Bilgi / uyarılar", NotificationManager.IMPORTANCE_DEFAULT, Color.YELLOW, false);
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
        // Ön plan servisi aktiviteden bağımsız çalışmaya devam eder.
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
