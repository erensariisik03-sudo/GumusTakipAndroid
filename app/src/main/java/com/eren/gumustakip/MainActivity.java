package com.eren.gumustakip;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

public class MainActivity extends Activity {
private static final int NOTIFICATION_REQUEST = 1001;
private static final int PAD = 16;

```
private SharedPreferences prefs;
private EditText gramInput, costInput, controlIntervalInput, aiIntervalInput;
private TextView priceView, buyView, sellView, portfolioView, updateView,
        statusView, aiView, liveTableView, apiStatusView, scheduleView;
private Button startButton, stopButton;
private MaterialButton apiKeyButton;

private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
        if ("com.eren.gumustakip.UPDATE_UI".equals(intent.getAction())) {
            double sell = intent.getDoubleExtra("sell", 0);
            double buy = intent.getDoubleExtra("buy", 0);
            double grams = intent.getDoubleExtra("grams", 0);
            double cost = intent.getDoubleExtra("cost", 0);

            showLive(
                    buy,
                    sell,
                    grams,
                    cost,
                    intent.getStringExtra("ts")
            );

            loadLiveTable();

        } else if ("com.eren.gumustakip.UPDATE_AI".equals(intent.getAction())) {
            String advice = intent.getStringExtra("advice");

            if (advice != null && aiView != null) {
                aiView.setText(advice);
            }
        }
    }
};

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    getWindow().setStatusBarColor(Color.rgb(11, 15, 20));
    getWindow().setNavigationBarColor(Color.rgb(11, 15, 20));

    prefs = getSharedPreferences("gumus", MODE_PRIVATE);

    DailyStorage.ensureToday(this);

    buildUi();
    loadSavedInputs();
    requestNotificationPermission();
    loadSavedLiveData();
    loadLiveTable();
    updateScheduleLabel();
    refreshApiStatus();
}

@Override
protected void onResume() {
    super.onResume();

    DailyStorage.ensureToday(this);

    IntentFilter f = new IntentFilter();
    f.addAction("com.eren.gumustakip.UPDATE_UI");
    f.addAction("com.eren.gumustakip.UPDATE_AI");

    if (Build.VERSION.SDK_INT >= 33) {
        registerReceiver(updateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
    } else {
        registerReceiver(updateReceiver, f);
    }

    loadSavedLiveData();
    loadLiveTable();
    updateScheduleLabel();
    refreshApiStatus();
}

@Override
protected void onPause() {
    super.onPause();

    try {
        unregisterReceiver(updateReceiver);
    } catch (Exception ignored) {
    }
}

private void buildUi() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(PAD), dp(12), dp(PAD), dp(28));
    root.setBackgroundColor(Color.rgb(11, 15, 20));

    scroll.addView(root);

    // Modern app bar
    LinearLayout appBar = new LinearLayout(this);
    appBar.setGravity(Gravity.CENTER_VERTICAL);
    appBar.setPadding(dp(2), dp(8), dp(2), dp(14));

    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.ic_launcher);

    LinearLayout.LayoutParams ip =
            new LinearLayout.LayoutParams(dp(52), dp(52));

    appBar.addView(icon, ip);

    LinearLayout titleBox = new LinearLayout(this);
    titleBox.setOrientation(LinearLayout.VERTICAL);
    titleBox.setPadding(dp(12), 0, 0, 0);

    TextView title =
            text("Gümüş Takip", 26, Color.WHITE, true);

    titleBox.addView(title, wrap());

    TextView subtitle =
            text("XAG · canlı piyasa takibi",
                    13,
                    Color.rgb(154, 167, 184),
                    false);

    titleBox.addView(subtitle, wrap());

    appBar.addView(titleBox, weight(1));

    TextView liveDot =
            text("● CANLI",
                    12,
                    Color.rgb(61, 220, 132),
                    true);

    appBar.addView(liveDot, wrap());

    root.addView(appBar, wrap());

    // Hero price card
    MaterialCardView hero = cardView();

    LinearLayout heroBox = vertical();
    heroBox.setPadding(dp(18), dp(18), dp(18), dp(18));

    hero.addView(
            heroBox,
            new MaterialCardView.LayoutParams(-1, -2)
    );

    TextView heroLabel =
            text("GÜMÜŞ · XAG",
                    12,
                    Color.rgb(154, 167, 184),
                    true);

    heroBox.addView(heroLabel, wrap());

    priceView =
            text("—",
                    36,
                    Color.WHITE,
                    true);

    priceView.setPadding(0, dp(2), 0, dp(8));
    heroBox.addView(priceView, wrap());

    LinearLayout mini = new LinearLayout(this);
    mini.setGravity(Gravity.CENTER_VERTICAL);

    buyView =
            pill("ALIŞ  —",
                    Color.rgb(61, 220, 132));

    sellView =
            pill("SATIŞ  —",
                    Color.rgb(255, 92, 92));

    mini.addView(buyView, weight(1));
    mini.addView(sellView, weight(1));

    heroBox.addView(mini, wrap());

    updateView =
            text("Son kontrol: —",
                    12,
                    Color.rgb(154, 167, 184),
                    false);

    heroBox.addView(updateView, wrap());

    root.addView(hero, wrap());

    // Portfolio
    MaterialCardView portfolioCard = cardView();

    LinearLayout portfolio = vertical();
    portfolio.setPadding(dp(18), dp(16), dp(18), dp(16));

    portfolioCard.addView(
            portfolio,
            new MaterialCardView.LayoutParams(-1, -2)
    );

    addSectionTitle(portfolio, "PORTFÖY");

    gramInput =
            input("Gümüş miktarı · gram");

    portfolio.addView(
            gramInput,
            fieldParams()
    );

    costInput =
            input("Ortalama alış maliyeti · TL / gram");

    portfolio.addView(
            costInput,
            fieldParams()
    );

    portfolioView =
            text("Portföy değeri: —\nKar/Zarar: —",
                    16,
                    Color.WHITE,
                    true);

    portfolioView.setPadding(0, dp(8), 0, 0);

    portfolio.addView(
            portfolioView,
            wrap()
    );

    LinearLayout actions = new LinearLayout(this);

    actions.setGravity(Gravity.CENTER_VERTICAL);
    actions.setPadding(0, dp(10), 0, 0);

    startButton =
            button("TAKİBİ BAŞLAT",
                    Color.rgb(61, 220, 132));

    stopButton =
            button("DURDUR",
                    Color.rgb(85, 98, 115));

    actions.addView(
            startButton,
            weight(1)
    );

    actions.addView(
            stopButton,
            weight(1)
    );

    portfolio.addView(actions, wrap());

    root.addView(portfolioCard, wrap());

    // Settings
    MaterialCardView settings = cardView();

    LinearLayout box = vertical();
    box.setPadding(dp(18), dp(16), dp(18), dp(16));

    settings.addView(
            box,
            new MaterialCardView.LayoutParams(-1, -2)
    );

    addSectionTitle(box, "AYARLAR");

    controlIntervalInput =
            input("Site kontrol aralığı · saniye");

    box.addView(
            controlIntervalInput,
            fieldParams()
    );

    aiIntervalInput =
            input("Yapay zekâ analiz aralığı · dakika");

    box.addView(
            aiIntervalInput,
            fieldParams()
    );

    TextView secInfo =
            text("Site kontrolü için 40 saniye ve üzeri önerilir.",
                    12,
                    Color.rgb(154, 167, 184),
                    false);

    box.addView(secInfo, wrap());

    scheduleView =
            text("Site: 60 sn · AI: 60 dk",
                    13,
                    Color.rgb(220, 229, 239),
                    true);

    box.addView(scheduleView, wrap());

    apiKeyButton = new MaterialButton(this);
    apiKeyButton.setText("GEMINI API KEY EKLE / DEĞİŞTİR");
    apiKeyButton.setAllCaps(false);
    apiKeyButton.setTextSize(14);

    box.addView(
            apiKeyButton,
            buttonParams()
    );

    apiStatusView =
            text("Yapay zekâ: devre dışı",
                    12,
                    Color.rgb(154, 167, 184),
                    false);

    box.addView(
            apiStatusView,
            wrap()
    );

    MaterialButton saveSettings =
            new MaterialButton(this);

    saveSettings.setText("Ayarları kaydet");
    saveSettings.setAllCaps(false);

    box.addView(
            saveSettings,
            buttonParams()
    );

    root.addView(settings, wrap());

    // AI card
    MaterialCardView aiCard = cardView();

    LinearLayout aiBox = vertical();
    aiBox.setPadding(dp(18), dp(16), dp(18), dp(16));

    aiCard.addView(
            aiBox,
            new MaterialCardView.LayoutParams(-1, -2)
    );

    addSectionTitle(aiBox, "YAPAY ZEKA ANALİZİ");

    TextView aiCaption =
            text("Bugünün kaydedilmiş fiyat hareketlerinden kısa analiz.",
                    13,
                    Color.rgb(154, 167, 184),
                    false);

    aiBox.addView(aiCaption, wrap());

    aiView =
            text("AI anahtarı eklenmediği için analiz kullanılamıyor. Fiyat takibi yine çalışır.",
                    14,
                    Color.rgb(255, 213, 74),
                    false);

    aiView.setPadding(0, dp(8), 0, dp(4));

    aiBox.addView(aiView, wrap());

    root.addView(aiCard, wrap());

    // Live table
    MaterialCardView table = cardView();

    LinearLayout tb = vertical();
    tb.setPadding(dp(18), dp(16), dp(18), dp(16));

    table.addView(
            tb,
            new MaterialCardView.LayoutParams(-1, -2)
    );

    addSectionTitle(tb, "SON 30 KAYIT");

    liveTableView =
            text("Henüz veri yok.",
                    13,
                    Color.rgb(220, 229, 239),
                    false);

    tb.addView(liveTableView, wrap());

    root.addView(table, wrap());

    // Data
    MaterialCardView data = cardView();

    LinearLayout db = vertical();
    db.setPadding(dp(18), dp(16), dp(18), dp(16));

    data.addView(
            db,
            new MaterialCardView.LayoutParams(-1, -2)
    );

    addSectionTitle(db, "GÜNLÜK VERİ");

    TextView path =
            text(new File(
                            getFilesDir(),
                            "gumus_fiyat_gecmisi.txt"
                    ).getAbsolutePath(),
                    11,
                    Color.rgb(154, 167, 184),
                    false);

    db.addView(path, wrap());

    MaterialButton share =
            new MaterialButton(this);

    share.setText("Günlük TXT'yi paylaş");
    share.setAllCaps(false);

    db.addView(
            share,
            buttonParams()
    );

    root.addView(data, wrap());

    startButton.setOnClickListener(
            v -> startTracking()
    );

    stopButton.setOnClickListener(
            v -> stopTracking()
    );

    saveSettings.setOnClickListener(
            v -> saveSettings()
    );

    apiKeyButton.setOnClickListener(
            v -> showApiKeyDialog(false)
    );

    share.setOnClickListener(
            v -> shareTxt()
    );

    setContentView(scroll);
}

private MaterialCardView cardView() {
    MaterialCardView c =
            new MaterialCardView(this);

    c.setRadius(dp(20));

    c.setCardBackgroundColor(
            Color.rgb(20, 26, 34)
    );

    c.setStrokeColor(
            Color.rgb(42, 52, 67)
    );

    c.setStrokeWidth(dp(1));
    c.setCardElevation(dp(1));

    LinearLayout.LayoutParams p = wrap();
    p.setMargins(0, 0, 0, dp(12));

    c.setLayoutParams(p);

    return c;
}

private LinearLayout vertical() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
}

private void addSectionTitle(
        LinearLayout p,
        String s
) {
    TextView t =
            text(
                    s,
                    12,
                    Color.rgb(154, 167, 184),
                    true
            );

    t.setLetterSpacing(.08f);
    t.setPadding(0, 0, 0, dp(8));

    p.addView(t, wrap());
}

private TextView pill(
        String s,
        int color
) {
    TextView t =
            text(s, 14, color, true);

    GradientDrawable g =
            new GradientDrawable();

    g.setColor(
            Color.argb(
                    28,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
            )
    );

    g.setCornerRadius(dp(14));

    g.setStroke(
            dp(1),
            Color.argb(
                    90,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
            )
    );

    t.setGravity(Gravity.CENTER);
    t.setPadding(0, dp(10), 0, dp(10));
    t.setBackground(g);

    LinearLayout.LayoutParams p =
            weight(1);

    p.setMargins(0, 0, dp(8), 0);
    t.setLayoutParams(p);

    return t;
}

private void startTracking() {
    saveSettings();

    double grams =
            parse(gramInput.getText().toString());

    double cost =
            parse(costInput.getText().toString());

    if (grams < 0 || cost < 0) {
        Toast.makeText(
                this,
                "Değerleri kontrol et.",
                Toast.LENGTH_SHORT
        ).show();

        return;
    }

    prefs.edit()
            .putString(
                    "grams",
                    String.valueOf(grams)
            )
            .putString(
                    "cost",
                    String.valueOf(cost)
            )
            .apply();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        android.os.PowerManager pm =
                (android.os.PowerManager)
                        getSystemService(POWER_SERVICE);

        if (pm != null &&
                !pm.isIgnoringBatteryOptimizations(
                        getPackageName())) {

            try {
                Intent in =
                        new Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse(
                                        "package:" +
                                                getPackageName()
                                )
                        );

                startActivity(in);

            } catch (Exception ignored) {
            }
        }
    }

    Intent i =
            new Intent(
                    this,
                    PriceTrackingService.class
            );

    i.setAction(
            PriceTrackingService.ACTION_START
    );

    if (Build.VERSION.SDK_INT >= 26) {
        startForegroundService(i);
    } else {
        startService(i);
    }

    statusView.setText(
            "Takip durumu: aktif"
    );
}

private void stopTracking() {
    Intent i =
            new Intent(
                    this,
                    PriceTrackingService.class
            );

    i.setAction(
            PriceTrackingService.ACTION_STOP
    );

    startService(i);

    if (statusView != null) {
        statusView.setText(
                "Takip durumu: kapalı"
        );
    }
}

private void saveSettings() {
    long control =
            Math.max(
                    40,
                    (long) parse(
                            controlIntervalInput
                                    .getText()
                                    .toString()
                    )
            );

    long ai =
            Math.max(
                    1,
                    (long) parse(
                            aiIntervalInput
                                    .getText()
                                    .toString()
                    )
            );

    prefs.edit()
            .putLong(
                    "control_interval_sec",
                    control
            )
            .putLong(
                    "ai_interval_min",
                    ai
            )
            .apply();

    controlIntervalInput.setText(
            String.valueOf(control)
    );

    updateScheduleLabel();

    Toast.makeText(
            this,
            "Ayarlar kaydedildi",
            Toast.LENGTH_SHORT
    ).show();
}

private void loadSavedInputs() {
    gramInput.setText(
            prefs.getString("grams", "")
    );

    costInput.setText(
            prefs.getString("cost", "")
    );

    long seconds =
            prefs.getLong(
                    "control_interval_sec",
                    0
            );

    if (seconds <= 0) {
        long oldMin =
                prefs.getLong(
                        "control_interval_min",
                        1
                );

        seconds =
                Math.max(
                        40,
                        oldMin * 60
                );

        prefs.edit()
                .putLong(
                        "control_interval_sec",
                        seconds
                )
                .remove("control_interval_min")
                .apply();
    }

    controlIntervalInput.setText(
            String.valueOf(seconds)
    );

    aiIntervalInput.setText(
            String.valueOf(
                    prefs.getLong(
                            "ai_interval_min",
                            60
                    )
            )
    );
}

private void updateScheduleLabel() {
    if (scheduleView != null) {
        scheduleView.setText(
                "Site: " +
                        prefs.getLong(
                                "control_interval_sec",
                                60
                        ) +
                        " sn · AI: " +
                        prefs.getLong(
                                "ai_interval_min",
                                60
                        ) +
                        " dk"
        );
    }
}

private void loadSavedLiveData() {
    double sell =
            prefs.getFloat(
                    "last_sell",
                    0
            );

    double buy =
            prefs.getFloat(
                    "last_buy",
                    0
            );

    String ts =
            prefs.getString(
                    "last_ts",
                    "—"
            );

    if (sell > 0) {
        showLive(
                buy,
                sell,
                parse(
                        gramInput
                                .getText()
                                .toString()
                ),
                parse(
                        costInput
                                .getText()
                                .toString()
                ),
                ts
        );
    }

    String advice =
            prefs.getString(
                    "last_ai_advice",
                    ""
            );

    if (!advice.isEmpty() &&
            aiView != null) {

        aiView.setText(advice);
    }
}

private void loadLiveTable() {
    if (liveTableView == null) {
        return;
    }

    try {
        JSONArray a =
                new JSONArray(
                        prefs.getString(
                                "live_rows",
                                "[]"
                        )
                );

        StringBuilder sb =
                new StringBuilder();

        sb.append(
                String.format(
                        Locale.US,
                        "%-10s %-11s %-11s\n",
                        "Saat",
                        "Alış",
                        "Satış"
                )
        );

        sb.append(
                "────────────────────────\n"
        );

        for (
                int i = a.length() - 1;
                i >= 0;
                i--
        ) {
            JSONObject o =
                    a.getJSONObject(i);

            sb.append(
                    String.format(
                            Locale.US,
                            "%-10s %-11.4f %-11.4f\n",
                            o.optString("time", ""),
                            o.optDouble("buy", 0),
                            o.optDouble("sell", 0)
                    )
            );
        }

        liveTableView.setText(
                sb.length() > 40
                        ? sb.toString()
                        : "Henüz veri yok."
        );

    } catch (Exception e) {
        liveTableView.setText(
                "Anlık tablo okunamadı."
        );
    }
}

private void shareTxt() {
    File f =
            new File(
                    getFilesDir(),
                    "gumus_fiyat_gecmisi.txt"
            );

    if (!f.exists()) {
        Toast.makeText(
                this,
                "Henüz TXT kaydı oluşmadı.",
                Toast.LENGTH_SHORT
        ).show();

        return;
    }

    Uri uri =
            FileProvider.getUriForFile(
                    this,
                    getPackageName() +
                            ".fileprovider",
                    f
            );

    Intent s =
            new Intent(
                    Intent.ACTION_SEND
            );

    s.setType("text/plain");
    s.putExtra(Intent.EXTRA_STREAM, uri);
    s.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
    );

    startActivity(
            Intent.createChooser(
                    s,
                    "Günlük TXT'yi paylaş"
            )
    );
}

private void refreshApiStatus() {
    if (apiStatusView == null) {
        return;
    }

    if (GeminiAnalyzer.hasApiKey(this)) {

        apiStatusView.setText(
                "Yapay zekâ: hazır · " +
                        GeminiAnalyzer
                                .getMaskedApiKey(this)
        );

        apiStatusView.setTextColor(
                Color.rgb(
                        61,
                        220,
                        132
                )
        );

    } else {

        apiStatusView.setText(
                "Yapay zekâ: devre dışı · API key eklenmedi"
        );

        apiStatusView.setTextColor(
                Color.rgb(
                        255,
                        213,
                        74
                )
        );
    }
}

private void showApiKeyDialog(boolean ignored) {
    final EditText keyInput =
            new EditText(this);

    keyInput.setHint("AIza...");
    keyInput.setSingleLine(true);
    keyInput.setTextColor(Color.WHITE);
    keyInput.setHintTextColor(Color.GRAY);

    keyInput.setInputType(
            InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
    );

    keyInput.setText(
            prefs.getString(
                    "gemini_api_key",
                    ""
            )
    );

    LinearLayout box =
            vertical();

    box.setPadding(
            dp(24),
            0,
            dp(24),
            0
    );

    box.addView(
            keyInput,
            wrap()
    );

    AlertDialog dialog =
            new AlertDialog.Builder(this)
                    .setTitle("Gemini API Key")
                    .setMessage(
                            "Yapay zekâ kullanmak için anahtar ekleyebilirsin. " +
                                    "Anahtar eklenmezse fiyat takibi normal şekilde devam eder."
                    )
                    .setView(box)
                    .setNeutralButton(
                            "API KEY AL",
                            (d, w) -> {
                                try {
                                    startActivity(
                                            new Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(
                                                            "https://aistudio.google.com/apikey"
                                                    )
                                            )
                                    );
                                } catch (Exception ignored2) {
                                }
                            }
                    )
                    .setNegativeButton(
                            "İPTAL",
                            null
                    )
                    .setPositiveButton(
                            "KAYDET",
                            null
                    )
                    .create();

    // FIX: İç içe lambda yapısı ayrı bloklara ayrıldı.
    dialog.setOnShowListener(d -> {

        Button positiveButton =
                dialog.getButton(
                        AlertDialog.BUTTON_POSITIVE
                );

        positiveButton.setOnClickListener(v -> {

            String key =
                    keyInput
                            .getText()
                            .toString()
                            .trim();

            if (key.length() > 0 &&
                    key.length() < 10) {

                keyInput.setError(
                        "API key çok kısa."
                );

                return;
            }

            if (key.isEmpty()) {
                GeminiAnalyzer.clearApiKey(this);
            } else {
                GeminiAnalyzer.saveApiKey(
                        this,
                        key
                );
            }

            refreshApiStatus();

            dialog.dismiss();
        });
    });

    dialog.show();
}

private void requestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {

        requestPermissions(
                new String[]{
                        Manifest.permission.POST_NOTIFICATIONS
                },
                NOTIFICATION_REQUEST
        );
    }
}

private EditText input(String hint) {
    EditText e =
            new EditText(this);

    e.setHint(hint);
    e.setHintTextColor(
            Color.rgb(
                    130,
                    145,
                    162
            )
    );

    e.setTextColor(Color.WHITE);
    e.setTextSize(15);

    e.setInputType(
            InputType.TYPE_CLASS_NUMBER |
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
    );

    e.setPadding(
            dp(14),
            dp(6),
            dp(14),
            dp(6)
    );

    GradientDrawable g =
            new GradientDrawable();

    g.setColor(
            Color.rgb(
                    26,
                    34,
                    48
            )
    );

    g.setCornerRadius(
            dp(12)
    );

    g.setStroke(
            dp(1),
            Color.rgb(
                    52,
                    65,
                    82
            )
    );

    e.setBackground(g);

    return e;
}

private Button button(
        String s,
        int bgColor
) {
    Button b =
            new Button(this);

    b.setText(s);
    b.setTextSize(12);
    b.setTextColor(Color.WHITE);
    b.setAllCaps(false);

    b.setBackground(
            round(
                    bgColor,
                    dp(13)
            )
    );

    return b;
}

private GradientDrawable round(
        int color,
        float radius
) {
    GradientDrawable g =
            new GradientDrawable();

    g.setColor(color);
    g.setCornerRadius(radius);

    return g;
}

private TextView text(
        String s,
        float size,
        int color,
        boolean bold
) {
    TextView t =
            new TextView(this);

    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);

    if (bold) {
        t.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );
    }

    return t;
}

private LinearLayout.LayoutParams wrap() {
    LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(
                    -1,
                    -2
            );

    p.setMargins(
            0,
            0,
            0,
            0
    );

    return p;
}

private LinearLayout.LayoutParams weight(
        float w
) {
    LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(
                    0,
                    -2
            );

    p.weight = w;

    return p;
}

private LinearLayout.LayoutParams fieldParams() {
    LinearLayout.LayoutParams p =
            wrap();

    p.setMargins(
            0,
            0,
            0,
            dp(10)
    );

    return p;
}

private LinearLayout.LayoutParams buttonParams() {
    LinearLayout.LayoutParams p =
            wrap();

    p.setMargins(
            0,
            dp(8),
            0,
            0
    );

    return p;
}

private int dp(int v) {
    return (int) (
            v *
                    getResources()
                            .getDisplayMetrics()
                            .density +
                    0.5f
    );
}

private double parse(String s) {
    try {
        return Double.parseDouble(
                s.trim()
                        .replace(",", ".")
        );
    } catch (Exception e) {
        return 0;
    }
}

public void showLive(
        double buy,
        double sell,
        double grams,
        double cost,
        String ts
) {
    runOnUiThread(() -> {

        priceView.setText(
                String.format(
                        Locale.US,
                        "%d TL",
                        (int) Math.floor(sell)
                )
        );

        buyView.setText(
                String.format(
                        Locale.US,
                        "ALIŞ  %.4f",
                        buy
                )
        );

        sellView.setText(
                String.format(
                        Locale.US,
                        "SATIŞ  %.4f",
                        sell
                )
        );

        double total =
                grams * cost;

        double value =
                grams * sell;

        double profit =
                value - total;

        double pct =
                total > 0
                        ? profit / total * 100
                        : 0;

        portfolioView.setText(
                String.format(
                        Locale.US,
                        "Portföy değeri  %.2f TL\n" +
                                "Kar/Zarar  %+.2f TL  (%+.2f%%)",
                        value,
                        profit,
                        pct
                )
        );

        updateView.setText(
                "Son kontrol: " + ts
        );

        if (statusView != null) {
            statusView.setText(
                    "Takip durumu: aktif"
            );
        }
    });
}
```

}
