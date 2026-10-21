package com.eren.gumustakip;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Günlük TXT kaydını yönetir. Gün değişince eski dosya SİLİNMEZ, tarihli arşive taşınır
 * (gumus_fiyat_YYYY-MM-DD.txt) ve 14 günden eskiler temizlenir.
 * Fiyat geçmişi (TickStore) ve portföy ayarları gün değişiminden etkilenmez.
 */
public final class DailyStorage {
    private static final String PREFS = "gumus";
    private static final String DAY_KEY = "data_day";
    private static final String HISTORY_FILE = "gumus_fiyat_gecmisi.txt";
    private static final long KEEP_ARCHIVE_MS = 14L * 24 * 3600_000L;

    private DailyStorage() {}

    public static synchronized void ensureToday(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String savedDay = prefs.getString(DAY_KEY, "");
        if (today.equals(savedDay)) return;

        prefs.edit().putString(DAY_KEY, today).apply();

        File history = new File(context.getFilesDir(), HISTORY_FILE);
        if (history.exists()) {
            if (!savedDay.isEmpty() && history.length() > 0) {
                File archive = new File(context.getFilesDir(), "gumus_fiyat_" + savedDay + ".txt");
                if (!history.renameTo(archive)) history.delete();
            } else {
                history.delete();
            }
        }
        pruneArchives(context);
    }

    private static void pruneArchives(Context context) {
        File[] files = context.getFilesDir().listFiles();
        if (files == null) return;
        long limit = System.currentTimeMillis() - KEEP_ARCHIVE_MS;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("gumus_fiyat_") && n.endsWith(".txt") && !n.equals(HISTORY_FILE) && f.lastModified() < limit) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    public static File getHistoryFile(Context context) {
        return new File(context.getFilesDir(), HISTORY_FILE);
    }
}
