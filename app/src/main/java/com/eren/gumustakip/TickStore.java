package com.eren.gumustakip;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fiyat geçmişi (ts,buy,sell) diske yazılır; gün değişince SİLİNMEZ.
 * Diskte son 48 saat tutulur, algoritma için belleğe son 8 saat yüklenir.
 */
public final class TickStore {
    public static final String FILE_NAME = "gumus_ticks.csv";
    private static final long KEEP_FILE_MS = 48L * 3600_000L;
    public static final long KEEP_MEMORY_MS = 8L * 3600_000L;

    private TickStore() {}

    public static File file(Context c) { return new File(c.getFilesDir(), FILE_NAME); }

    /** Dosyayı okur, 48 saatten eskileri atıp yeniden yazar, son 8 saati döndürür. */
    public static synchronized List<TradingAlgorithm.Tick> load(Context c, long now) {
        List<TradingAlgorithm.Tick> keep = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] a = line.split(",");
                if (a.length < 3) continue;
                try {
                    long ts = Long.parseLong(a[0].trim());
                    double buy = Double.parseDouble(a[1].trim()), sell = Double.parseDouble(a[2].trim());
                    if (ts >= now - KEEP_FILE_MS && ts <= now + 60_000L) keep.add(new TradingAlgorithm.Tick(ts, buy, sell));
                } catch (NumberFormatException ignored) { }
            }
        } catch (Exception ignored) { }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8)) {
            for (TradingAlgorithm.Tick t : keep) w.write(line(t));
        } catch (Exception ignored) { }
        List<TradingAlgorithm.Tick> mem = new ArrayList<>();
        for (TradingAlgorithm.Tick t : keep) if (t.ts >= now - KEEP_MEMORY_MS) mem.add(t);
        return mem;
    }

    public static synchronized void append(Context c, TradingAlgorithm.Tick t) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file(c), true), StandardCharsets.UTF_8)) {
            w.write(line(t));
        } catch (Exception ignored) { }
    }

    private static String line(TradingAlgorithm.Tick t) {
        return String.format(Locale.US, "%d,%.4f,%.4f\n", t.ts, t.buy, t.sell);
    }
}
