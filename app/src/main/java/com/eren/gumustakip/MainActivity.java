package com.eren.gumustakip;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int NOTIFICATION_REQUEST = 1001;
    private static final String ACTION_UPDATE_UI = "com.eren.gumustakip.UPDATE_UI";

    private SharedPreferences prefs;
    private Portfolio pf;
    private EditText gramInput, costInput, cashInput, controlIntervalInput;
    private TextView priceView, portfolioView, updateView, statusView, aiView, liveTableView, pathView, scheduleView;
    private Button startButton, stopButton;
    private AlertDialog signalDialog;
    private long shownSignalTime = -1;
    private boolean showDetail = false;

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_UPDATE_UI.equals(intent.getAction())) {
                refreshLive();
                loadLiveTable();
                // Uygulama açıkken yeni bir öneri geldiyse bir kez sor (aynı öneri için tekrar tekrar sormaz).
                showPendingSignalIfNeeded(false);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Portfolio.PREFS, MODE_PRIVATE);
        pf = new Portfolio(this);
        DailyStorage.ensureToday(this);
        buildUi();
        loadSavedInputs();
        requestNotificationPermission();
        refreshLive();
        loadLiveTable();
        updateScheduleLabel();
        // Öneri penceresi yalnızca onResume'da açılır (eskiden onCreate+onResume ikisi birden açıyordu).
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        DailyStorage.ensureToday(this);
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_UPDATE_UI);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updateReceiver, f);
        refreshLive();
        loadLiveTable();
        updateScheduleLabel();
        showPendingSignalIfNeeded(true);
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(updateReceiver); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ arayüz

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.setBackgroundColor(Color.rgb(11, 16, 24));
        scroll.addView(root);

        ImageView cover = new ImageView(this);
        cover.setImageResource(R.drawable.gumus_cover);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams cp = matchWrap(0);
        cp.height = dp(210);
        cp.setMargins(0, 0, 0, dp(14));
        root.addView(cover, cp);
        root.addView(text("GÜMÜŞ TAKİP", 28, Color.WHITE, true), matchWrap(0));
        root.addView(text("GetirFinans · XAG · hız tabanlı al/sat algoritması", 14, Color.LTGRAY, false), matchWrap(0));

        // Portföy
        LinearLayout card = card();
        addLabel(card, "Portföy");
        gramInput = input("Gümüş miktarı (gram) · örn. 250,50");
        card.addView(gramInput, matchWrap(0));
        costInput = input("Alış maliyeti (TL/gram) · örn. 78,25");
        card.addView(costInput, matchWrap(0));
        cashInput = input("Alım için ayırdığın nakit (TL) · örn. 5000");
        card.addView(cashInput, matchWrap(0));
        card.addView(text("Nakit, AL önerisinde ne kadar alacağını hesaplamak için kullanılır. Satışlarda otomatik artar, alışlarda azalır.", 12, Color.LTGRAY, false), matchWrap(0));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = button("TAKİBİ BAŞLAT");
        stopButton = button("DURDUR");
        buttons.addView(startButton, weightWrap(1));
        buttons.addView(stopButton, weightWrap(1));
        card.addView(buttons, matchWrap(0));
        root.addView(card, matchWrap(0));

        // Ayarlar
        LinearLayout settings = card();
        addLabel(settings, "Zamanlama Ayarları");
        controlIntervalInput = input("Site kontrol aralığı (saniye) · 40+ tavsiye edilir");
        settings.addView(controlIntervalInput, matchWrap(0));
        settings.addView(text("Site kontrolü için 40 saniyenin altı önerilmez.", 12, Color.LTGRAY, false), matchWrap(0));
        scheduleView = text("Site kontrolü: 60 sn", 13, Color.LTGRAY, false);
        settings.addView(scheduleView, matchWrap(0));
        Button saveSettings = button("AYARLARI VE PORTFÖYÜ KAYDET");
        settings.addView(saveSettings, matchWrap(0));
        root.addView(settings, matchWrap(0));

        // Anlık veri
        LinearLayout live = card();
        addLabel(live, "ANLIK VERİ");
        priceView = text("Alış: —\nSatış: —", 21, Color.WHITE, true);
        live.addView(priceView, matchWrap(0));
        portfolioView = text("Portföy: —", 15, Color.LTGRAY, false);
        live.addView(portfolioView, matchWrap(0));
        updateView = text("Son kontrol: —", 13, Color.GRAY, false);
        live.addView(updateView, matchWrap(0));
        statusView = text("Takip durumu: kapalı", 14, Color.LTGRAY, false);
        live.addView(statusView, matchWrap(0));
        aiView = text("Algoritma: veri toplanıyor.", 14, Color.LTGRAY, false);
        live.addView(aiView, matchWrap(0));
        Button answerSignal = button("ALGORİTMA BİLDİRİMİNİ YANITLA");
        live.addView(answerSignal, matchWrap(0));
        Button detailButton = button("ALGORİTMA DETAYINI GÖSTER / GİZLE");
        live.addView(detailButton, matchWrap(0));
        Button historyButton = button("İŞLEM GEÇMİŞİ / KÂR-ZARAR");
        live.addView(historyButton, matchWrap(0));
        Button resetButton = button("BAŞLANGICI SIFIRLA (K/Z'yi yeniden başlat)");
        live.addView(resetButton, matchWrap(0));
        root.addView(live, matchWrap(0));

        // Tablo
        LinearLayout table = card();
        addLabel(table, "SON ANLIK VERİLER (30 KAYIT)");
        liveTableView = text("Henüz veri yok.", 13, Color.LTGRAY, false);
        liveTableView.setTypeface(Typeface.MONOSPACE);
        table.addView(liveTableView, matchWrap(0));
        root.addView(table, matchWrap(0));

        // Veri
        LinearLayout data = card();
        addLabel(data, "VERİLER NEREDE KAYDEDİLİYOR?");
        pathView = text(getFilesDir().getAbsolutePath(), 12, Color.WHITE, false);
        data.addView(pathView, matchWrap(0));
        data.addView(text("Günlük dosya: gumus_fiyat_gecmisi.txt (gün değişince tarihli arşive taşınır, 14 gün saklanır)\nAlgoritma verisi: gumus_ticks.csv (son 48 saat, gün değişiminde silinmez)", 12, Color.LTGRAY, false), matchWrap(0));
        Button share = button("GÜNLÜK TXT DOSYASINI PAYLAŞ");
        data.addView(share, matchWrap(0));
        Button shareCsv = button("FİYAT VERİSİ (CSV, 48 SAAT) PAYLAŞ");
        data.addView(shareCsv, matchWrap(0));
        root.addView(data, matchWrap(0));

        root.addView(text("Algoritma fiyatın yükseliş/düşüş hızına ve alış-satış farkına bakar. Parçalı işlemlerde miktar 1/4 ile 1/2 arasında önerilir. İşlem otomatik yapılmaz, kâr garantisi yoktur. Hafta sonu ve gece takip yoğunluğu otomatik düşer. Alım-satımı yalnızca gerçekten yaptıysan kaydet.", 13, Color.GRAY, false), matchWrap(0));

        startButton.setOnClickListener(v -> startTracking());
        stopButton.setOnClickListener(v -> stopTracking());
        saveSettings.setOnClickListener(v -> saveAll(true));
        share.setOnClickListener(v -> shareFile(DailyStorage.getHistoryFile(this), "Günlük TXT dosyasını paylaş"));
        shareCsv.setOnClickListener(v -> shareFile(TickStore.file(this), "Fiyat verisini (CSV) paylaş"));
        answerSignal.setOnClickListener(v -> {
            if (!prefs.getBoolean("signal_pending", false)) Toast.makeText(this, "Yanıt bekleyen öneri yok.", Toast.LENGTH_SHORT).show();
            else showPendingSignalIfNeeded(true);
        });
        detailButton.setOnClickListener(v -> { showDetail = !showDetail; refreshLive(); });
        historyButton.setOnClickListener(v -> showTradeHistory());
        resetButton.setOnClickListener(v -> confirmResetBaseline());
        setContentView(scroll);
    }

    // ------------------------------------------------------------------ takip

    private void startTracking() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission();
            Toast.makeText(this, "Sürekli takip için bildirim iznini verip tekrar başlat.", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent in = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                    startActivity(in);
                    Toast.makeText(this, "Pil kısıtlaması ekranından izin ver; sonra TAKİBİ BAŞLAT'a yeniden bas.", Toast.LENGTH_LONG).show();
                } catch (Exception e) { showBackgroundReliabilityHelp(); }
                return;
            }
        }
        double grams = parse(gramInput.getText().toString()), cost = parse(costInput.getText().toString());
        if (grams < 0 || cost < 0) { Toast.makeText(this, "Değerleri kontrol et.", Toast.LENGTH_SHORT).show(); return; }
        saveAll(false);
        pf.initializeIfNeeded(grams, cost); // ilk girilen miktar ve maliyet K/Z başlangıcı olarak sabitlenir
        prefs.edit().putBoolean("tracking_enabled", true).apply();
        Intent i = new Intent(this, PriceTrackingService.class);
        i.setAction(PriceTrackingService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        statusView.setText("Takip durumu: aktif");
        if (!prefs.getBoolean("background_help_shown", false)) {
            prefs.edit().putBoolean("background_help_shown", true).apply();
            showBackgroundReliabilityHelp();
        }
    }

    private void stopTracking() {
        prefs.edit().putBoolean("tracking_enabled", false).apply();
        Intent i = new Intent(this, PriceTrackingService.class);
        i.setAction(PriceTrackingService.ACTION_STOP);
        startService(i);
        statusView.setText("Takip durumu: kapalı");
    }

    private void showBackgroundReliabilityHelp() {
        new AlertDialog.Builder(this).setTitle("Arka plan takibi için izinler")
                .setMessage("Takibin daha uzun süre çalışması için:\n\n1. Pil kullanımını 'Kısıtlanmamış' yap.\n2. Telefon ayarlarında uygulamanın arka plan kullanımına izin ver.\n3. Varsa 'Otomatik başlat' iznini aç.\n4. Son uygulamalar ekranında uygulamayı kilitle.\n5. Bildirim iznini açık tut.\n\nÜreticiye göre ayar isimleri değişebilir. Android, kullanıcı zorla durdurursa veya üretici sistemi kapatırsa kesintisiz çalışmayı garanti etmez. Sürekli takip pil tüketimini artırır.")
                .setPositiveButton("Uygulama ayarları", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
                })
                .setNeutralButton("Pil ayarları", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) { }
                })
                .setNegativeButton("Kapat", null).show();
    }

    // ------------------------------------------------------------------ ayarlar / girişler

    private void saveAll(boolean toast) {
        long control = Math.max(40, (long) parse(controlIntervalInput.getText().toString()));
        prefs.edit().putLong("control_interval_sec", control).apply();
        controlIntervalInput.setText(String.valueOf(control));
        boolean manualChange = pf.saveInputs(parse(gramInput.getText().toString()), parse(costInput.getText().toString()), parse(cashInput.getText().toString()));
        updateScheduleLabel();
        refreshLive();
        if (manualChange)
            Toast.makeText(this, "Gram miktarını elle değiştirdin. K/Z hesabı karışmasın diye gerekirse BAŞLANGICI SIFIRLA'yı kullan.", Toast.LENGTH_LONG).show();
        else if (toast)
            Toast.makeText(this, "Kaydedildi: site " + control + " sn", Toast.LENGTH_SHORT).show();
    }

    private void loadSavedInputs() {
        setInput(gramInput, pf.grams(), 4);
        setInput(costInput, pf.cost(), 4);
        setInput(cashInput, pf.cash(), 2);
        long seconds = prefs.getLong("control_interval_sec", 0);
        if (seconds <= 0) {
            long oldMin = prefs.getLong("control_interval_min", 1);
            seconds = Math.max(40, oldMin * 60);
            prefs.edit().putLong("control_interval_sec", seconds).remove("control_interval_min").apply();
        }
        controlIntervalInput.setText(String.valueOf(seconds));
    }

    private void setInput(EditText e, double v, int decimals) {
        if (v == 0) { e.setText(""); return; }
        String s = String.format(Locale.US, "%." + decimals + "f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        e.setText(s);
    }

    private void updateScheduleLabel() {
        if (scheduleView != null)
            scheduleView.setText("Site kontrolü: " + prefs.getLong("control_interval_sec", 60) + " sn · Algoritma: aktif");
    }

    // ------------------------------------------------------------------ canlı ekran

    private void refreshLive() {
        if (priceView == null) return;
        double sell = Portfolio.getD(prefs, "last_sell", 0), buy = Portfolio.getD(prefs, "last_buy", 0);
        String ts = prefs.getString("last_ts", "—");
        if (sell > 0) {
            priceView.setText("Alış: " + Fmt.n2(buy) + " TL\nSatış: " + Fmt.n2(sell) + " TL");
            portfolioView.setText(pf.summary(sell) + (pf.cash() > 0 ? "\nAlım için nakit: " + Fmt.tl(pf.cash()) : ""));
            updateView.setText("Son kontrol: " + ts);
        }
        String note = prefs.getString("market_note", "");
        statusView.setText("Takip durumu: " + (prefs.getBoolean("tracking_enabled", false) ? "aktif" : "kapalı")
                + (prefs.getBoolean("tracking_enabled", false) && !note.isEmpty() ? "\n" + note : ""));

        StringBuilder sb = new StringBuilder();
        String status = prefs.getString("algo_status", "");
        boolean closedOrNight = !"OPEN".equals(prefs.getString("market_state", "OPEN"));
        if (!status.isEmpty() && !(closedOrNight && status.equals(note))) {
            sb.append(status);
        } else if (status.isEmpty()) {
            sb.append("Algoritma: veri toplanıyor. İşlem otomatik yapılmaz.");
        }
        if (sb.length() == 0) sb.append("Algoritma: beklemede.");
        if (prefs.getBoolean("signal_pending", false))
            sb.append("\n\n⚠ Yanıt bekleyen öneri var (").append("SELL".equals(prefs.getString("signal_action", "")) ? "SAT" : "AL").append(").");
        String detail = prefs.getString("algo_detail", "");
        if (showDetail && !detail.isEmpty()) sb.append("\n\nDetay:\n").append(detail);
        aiView.setText(sb.toString());
    }

    private void loadLiveTable() {
        if (liveTableView == null) return;
        try {
            JSONArray a = new JSONArray(prefs.getString("live_rows", "[]"));
            if (a.length() == 0) { liveTableView.setText("Henüz veri yok."); return; }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "%-9s %-10s %-10s\n", "Saat", "Alış", "Satış"));
            sb.append("───────────────────────────\n");
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject o = a.getJSONObject(i);
                sb.append(String.format(Locale.US, "%-9s %-10.4f %-10.4f\n", o.optString("time", ""), o.optDouble("buy", 0), o.optDouble("sell", 0)));
            }
            liveTableView.setText(sb.toString());
        } catch (Exception e) {
            liveTableView.setText("Anlık tablo okunamadı.");
        }
    }

    private void shareFile(File f, String chooserTitle) {
        if (f == null || !f.exists() || f.length() == 0) { Toast.makeText(this, "Henüz kayıt oluşmadı.", Toast.LENGTH_SHORT).show(); return; }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_STREAM, uri);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, chooserTitle));
    }

    // ------------------------------------------------------------------ algoritma önerisi / işlem kaydı

    private void showPendingSignalIfNeeded(boolean force) {
        if (!prefs.getBoolean("signal_pending", false)) return;
        if (signalDialog != null && signalDialog.isShowing()) return;
        long signalTime = prefs.getLong("signal_time", 0);
        if (!force && signalTime == shownSignalTime) return;
        shownSignalTime = signalTime;
        String message = prefs.getString("signal_message", "Gümüş işlem önerisi");
        signalDialog = new AlertDialog.Builder(this).setTitle("Algoritma önerisi")
                .setMessage(message + "\n\nBu öneriye göre işlem yaptın mı?")
                .setPositiveButton("Evet, yaptım", (d, w) -> askTradeDetails())
                .setNegativeButton("Hayır, yapmadım", (d, w) -> {
                    // Hayır: öneri kapanır, 20 dk sonra güncel duruma göre yeniden değerlendirilir.
                    prefs.edit().putBoolean("signal_pending", false)
                            .putLong("signal_answered_at", System.currentTimeMillis())
                            .putLong("signal_cooldown_ms", 20 * 60_000L).apply();
                    refreshLive();
                    Toast.makeText(this, "Yanıt kaydedildi. Durum 20 dk sonra yeniden değerlendirilecek.", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Daha sonra", (d, w) -> Toast.makeText(this, "Hatırlatma devam edecek.", Toast.LENGTH_SHORT).show())
                .create();
        signalDialog.show();
    }

    private void askTradeDetails() {
        final String action = prefs.getString("signal_action", "SELL");
        final boolean sell = "SELL".equals(action);
        double suggestedGrams = Portfolio.getD(prefs, "signal_grams", 0);
        final double marketPrice = Portfolio.getD(prefs, sell ? "last_sell" : "last_buy", 0);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText qty = input("Kaç gram?");
        final EditText price = input(marketPrice > 0 ? "Gram başına TL (şu an: " + Fmt.n2(marketPrice) + ")" : "Gram başına TL");
        setInput(qty, suggestedGrams, 3); // önerilen miktar hazır yazılı; fiyat bilerek BOŞ bırakılır
        box.addView(text(sell ? "Kaç gram sattın?" : "Kaç gram aldın?", 14, Color.WHITE, true), matchWrap(0));
        box.addView(qty, matchWrap(0));
        box.addView(text(sell ? "Gram başına kaç TL'ye sattın?" : "Gram başına kaç TL'ye aldın?", 14, Color.WHITE, true), matchWrap(0));
        box.addView(price, matchWrap(0));
        box.addView(text("Bildirimle işlem arasında fiyat/fark değişmiş olabilir; gerçekleşen fiyatı yaz.", 12, Color.LTGRAY, false), matchWrap(0));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(sell ? "Satış kaydı" : "Alım kaydı")
                .setView(box)
                .setNegativeButton("İptal", null)
                .setPositiveButton("İşlemi kaydet", null)
                .create();
        dialog.show();
        // Hata olursa pencere kapanmasın, girilenler kaybolmasın.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double q = parse(qty.getText().toString());
            double pr = parse(price.getText().toString());
            if (pr <= 0) { Toast.makeText(this, sell ? "Kaç TL'ye sattığını yaz." : "Kaç TL'ye aldığını yaz.", Toast.LENGTH_LONG).show(); return; }
            // Toplam tutar yazılmış olabilir; gram başına fiyat güncel fiyattan çok uzaksa uyar.
            if (marketPrice > 0 && Math.abs(pr - marketPrice) / marketPrice > 0.15) {
                Toast.makeText(this, "Bu fiyat güncel fiyattan (" + Fmt.n2(marketPrice) + ") çok farklı. Gram başına TL yaz.", Toast.LENGTH_LONG).show();
                return;
            }
            String err = pf.recordTrade(action, q, pr);
            if (err != null) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); return; }
            // Evet: yeni miktar üzerinden algoritma kısa bir soğumadan sonra yeniden devreye girer.
            prefs.edit().putBoolean("signal_pending", false)
                    .putLong("signal_answered_at", System.currentTimeMillis())
                    .putLong("signal_cooldown_ms", 5 * 60_000L).apply();
            loadSavedInputs();
            refreshLive();
            Toast.makeText(this, "Gerçekleşen işlem kaydedildi.", Toast.LENGTH_LONG).show();
            dialog.dismiss();
        });
    }

    private void showTradeHistory() {
        JSONArray log;
        try { log = new JSONArray(prefs.getString("trade_log", "[]")); } catch (Exception e) { log = new JSONArray(); }
        double sell = Portfolio.getD(prefs, "last_sell", 0);
        StringBuilder b = new StringBuilder();
        if (sell > 0) b.append(pf.details(sell)).append("\n\n");
        else b.append("Gerçekleşmiş K/Z: ").append(Fmt.stl(pf.realized())).append("\n\n");
        if (log.length() == 0) b.append("Henüz kaydedilmiş işlem yok.");
        for (int i = log.length() - 1; i >= 0; i--) {
            JSONObject o = log.optJSONObject(i);
            if (o == null) continue;
            boolean isSell = "SELL".equals(o.optString("type"));
            b.append(o.optString("time")).append(" · ").append(isSell ? "SATIŞ" : "ALIŞ")
                    .append(" · ").append(Fmt.gram(o.optDouble("grams", 0))).append(" g · ").append(Fmt.n2(o.optDouble("price", 0))).append(" TL/g");
            if (isSell) b.append(" · K/Z ").append(Fmt.stl(o.optDouble("realized", 0)));
            b.append('\n');
        }
        new AlertDialog.Builder(this).setTitle("İşlem geçmişi / K-Z").setMessage(b.toString()).setPositiveButton("Kapat", null).show();
    }

    private void confirmResetBaseline() {
        new AlertDialog.Builder(this).setTitle("Başlangıcı sıfırla?")
                .setMessage("Kutulardaki gram ve maliyet yeni başlangıç olur; işlem geçmişi ve gerçekleşmiş K/Z silinir. Gümüşü başka yerden eklediysen/çıkardıysan K/Z'nin doğru kalması için bunu kullan.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sıfırla", (d, w) -> {
                    saveAll(false);
                    pf.resetBaseline();
                    refreshLive();
                    Toast.makeText(this, "Başlangıç sıfırlandı.", Toast.LENGTH_SHORT).show();
                }).show();
    }

    // ------------------------------------------------------------------ yardımcılar

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.GRAY);
        e.setTextColor(Color.WHITE);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setPadding(dp(12), dp(8), dp(12), dp(8));
        return e;
    }

    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setTextSize(12); return b; }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(24, 31, 42));
        bg.setCornerRadius(dp(14));
        l.setBackground(bg);
        LinearLayout.LayoutParams p = matchWrap(0);
        p.setMargins(0, dp(14), 0, 0);
        l.setLayoutParams(p);
        return l;
    }

    private void addLabel(LinearLayout parent, String s) { parent.addView(text(s, 13, Color.LTGRAY, true), matchWrap(0)); }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private LinearLayout.LayoutParams matchWrap(int h) { return new LinearLayout.LayoutParams(-1, h == 0 ? -2 : h); }

    private LinearLayout.LayoutParams weightWrap(float w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2);
        p.weight = w;
        return p;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    private double parse(String s) {
        try { return Double.parseDouble(s.trim().replace(",", ".")); } catch (Exception e) { return 0; }
    }
}
