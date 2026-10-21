package com.eren.gumustakip;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Takip yoğunluğunu saate göre ayarlar (Türkiye saati).
 *  - HAFTA SONU (Cumartesi-Pazar): piyasa kapalı, hiç fiyat çekilmez, algoritma/bildirim kapalı.
 *  - GECE (hafta içi 00:00-07:00): seyrek kontrol, bildirim/öneri yok, fiyatlar yine kaydedilir.
 *  - AÇIK: normal çalışma.
 * Resmî açılış/kapanış saatleri bilinmediği için Cuma akşamı / Pazar gecesi gibi sınır saatlerde
 * piyasanın kapalı olduğu ayrıca FİYATTAN anlaşılır (alış-satış farkı anormalse kapalı sayılır).
 */
public final class MarketSchedule {
    public enum Mode { OPEN, NIGHT, WEEKEND }

    // ===== Ayarlar =====
    public static final int NIGHT_START_HOUR = 0;   // dahil
    public static final int NIGHT_END_HOUR = 7;     // hariç
    public static final long NIGHT_POLL_MS = 5 * 60_000L;
    public static final long CLOSED_RECHECK_MS = 10 * 60_000L;
    /** Gece öneri/bildirim istenirse true yap. */
    public static final boolean NIGHT_SIGNALS = false;
    private static final String TZ = "Europe/Istanbul";

    private MarketSchedule() {}

    public static Mode modeAt(long ts) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(TZ));
        c.setTimeInMillis(ts);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return Mode.WEEKEND;
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (h >= NIGHT_START_HOUR && h < NIGHT_END_HOUR) return Mode.NIGHT;
        return Mode.OPEN;
    }
}
