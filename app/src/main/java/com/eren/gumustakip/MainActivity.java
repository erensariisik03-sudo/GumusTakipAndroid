package com.eren.gumustakip;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int NOTIFICATION_REQUEST = 1001;
    private SharedPreferences prefs;
    private EditText gramInput, costInput;
    private TextView priceView, portfolioView, updateView, statusView;
    private Button startButton, stopButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("gumus", MODE_PRIVATE);
        buildUi();
        loadSavedInputs();
        requestNotificationPermission();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(24));
        root.setBackgroundColor(Color.rgb(16, 19, 24));
        scroll.addView(root);

        TextView title = text("GÜMÜŞ TAKİP", 26, Color.WHITE, true);
        root.addView(title, matchWrap(0));
        TextView subtitle = text("GetirFinans · XAG · 60 saniyelik takip", 14, Color.LTGRAY, false);
        root.addView(subtitle, matchWrap(0));

        LinearLayout card = card();
        addLabel(card, "Gümüş miktarı (gram)");
        gramInput = input("Örn. 250,50");
        card.addView(gramInput, matchWrap(0));
        addLabel(card, "Alış maliyeti (TL/gram)");
        costInput = input("Örn. 78,25");
        card.addView(costInput, matchWrap(0));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        startButton = button("TAKİBİ BAŞLAT");
        stopButton = button("DURDUR");
        buttons.addView(startButton, weightWrap(1));
        buttons.addView(stopButton, weightWrap(1));
        card.addView(buttons, matchWrap(0));
        root.addView(card, matchWrap(0));

        LinearLayout live = card();
        addLabel(live, "ANLIK VERİ");
        priceView = text("Satış: —\nAlış: —", 21, Color.WHITE, true);
        live.addView(priceView, matchWrap(0));
        portfolioView = text("Portföy: —\nKar/Zarar: —", 16, Color.LTGRAY, false);
        live.addView(portfolioView, matchWrap(0));
        updateView = text("Son kontrol: —", 13, Color.GRAY, false);
        live.addView(updateView, matchWrap(0));
        statusView = text("Takip durumu: kapalı", 14, Color.LTGRAY, false);
        live.addView(statusView, matchWrap(0));
        root.addView(live, matchWrap(0));

        TextView info = text("Bildirim kuralı: Satış fiyatının tam TL seviyesi değiştiğinde bildirim gönderilir. Kontrol aralığı 60 saniyedir.", 13, Color.GRAY, false);
        root.addView(info, matchWrap(0));

        startButton.setOnClickListener(v -> startTracking());
        stopButton.setOnClickListener(v -> stopTracking());

        setContentView(scroll);
    }

    private void startTracking() {
        double grams = parse(gramInput.getText().toString());
        double cost = parse(costInput.getText().toString());
        if (grams < 0 || cost < 0) {
            Toast.makeText(this, "Değerleri kontrol et.", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString("grams", String.valueOf(grams)).putString("cost", String.valueOf(cost)).apply();
        Intent i = new Intent(this, PriceTrackingService.class);
        i.setAction(PriceTrackingService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        statusView.setText("Takip durumu: aktif");
        Toast.makeText(this, "Gümüş takibi başlatıldı.", Toast.LENGTH_SHORT).show();
    }

    private void stopTracking() {
        Intent i = new Intent(this, PriceTrackingService.class);
        i.setAction(PriceTrackingService.ACTION_STOP);
        startService(i);
        statusView.setText("Takip durumu: kapalı");
        Toast.makeText(this, "Gümüş takibi durduruldu.", Toast.LENGTH_SHORT).show();
    }

    private void loadSavedInputs() {
        gramInput.setText(prefs.getString("grams", ""));
        costInput.setText(prefs.getString("cost", ""));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
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

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(12);
        return b;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        l.setBackgroundColor(Color.rgb(26, 31, 39));
        LinearLayout.LayoutParams p = matchWrap(0);
        p.setMargins(0, dp(16), 0, 0);
        return l;
    }

    private void addLabel(LinearLayout parent, String s) { parent.addView(text(s, 13, Color.LTGRAY, true), matchWrap(0)); }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private LinearLayout.LayoutParams matchWrap(int h) { return new LinearLayout.LayoutParams(-1, h == 0 ? -2 : h); }
    private LinearLayout.LayoutParams weightWrap(float w) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2); p.weight = w; return p; }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private double parse(String s) {
        try { return Double.parseDouble(s.trim().replace(",", ".")); } catch (Exception e) { return 0; }
    }

    public void showLive(double buy, double sell, double grams, double cost, String ts) {
        runOnUiThread(() -> {
            priceView.setText(String.format(Locale.US, "Satış: %.4f TL\nAlış: %.4f TL\nSeviye: %d TL", sell, buy, (int)sell));
            double totalCost = grams * cost;
            double value = grams * sell;
            double profit = value - totalCost;
            double pct = totalCost > 0 ? (profit / totalCost * 100.0) : 0.0;
            portfolioView.setText(String.format(Locale.US, "Portföy Değeri: %.2f TL\nKar/Zarar: %+.2f TL (%+.2f%%)", value, profit, pct).replace("+ ", "+"));
            updateView.setText("Son kontrol: " + ts);
            statusView.setText("Takip durumu: aktif");
        });
    }
}
