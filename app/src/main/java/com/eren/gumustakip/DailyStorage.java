package com.eren.gumustakip;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Günlük fiyat ve AI verilerinin uygulama sandboxında tutulmasını yönetir. */
public final class DailyStorage {
    private static final String PREFS = "gumus";
    private static final String DAY_KEY = "data_day";
    private static final String HISTORY_FILE = "gumus_fiyat_gecmisi.txt";

    private DailyStorage() {}

    public static synchronized void ensureToday(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String savedDay = prefs.getString(DAY_KEY, "");

        if (today.equals(savedDay)) return;

        prefs.edit()
                .putString(DAY_KEY, today)
                // Sadece günlük fiyat/AI verilerini temizle.
                // Portföy ve zamanlama ayarları korunur.
                .remove("live_rows")
                .remove("last_buy")
                .remove("last_sell")
                .remove("last_ts")
                .remove("last_ai_advice")
                .remove("last_ai_time")
                .apply();

        File history = new File(context.getFilesDir(), HISTORY_FILE);
        if (history.exists() && !history.delete()) {
            System.out.println("⚠️ Eski günlük dosya silinemedi: " + history.getAbsolutePath());
        }

        System.out.println("🗓️ Gün değişti. Günlük fiyat ve AI verileri sıfırlandı.");
    }

    public static File getHistoryFile(Context context) {
        return new File(context.getFilesDir(), HISTORY_FILE);
    }
}
