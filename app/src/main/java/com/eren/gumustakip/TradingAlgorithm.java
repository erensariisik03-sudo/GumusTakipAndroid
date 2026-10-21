package com.eren.gumustakip;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gümüş al/sat algoritması. Android'e bağımlı DEĞİLDİR (kolay test için).
 *
 * Temel fikirler:
 *  1) Alış-satış farkı (spread) her al-sat turunun maliyetidir. Hareket spread'i
 *     aşmıyorsa işlem önerilmez.
 *  2) Yön; 10 / 30 / 60 dk'lık eğimlerin (hız, TL/saat) AYNI yönde olmasıyla teyit edilir.
 *  3) Satış: teyitli düşüş + zirveden en az D kadar geri çekilme (iz süren stop).
 *     Kârdayken daha erken (10 ve 30 dk negatif) çıkılır; zarardayken yalnız güçlü düşüşte.
 *  4) Alım: teyitli yükseliş VEYA büyük düşüş sonrası toparlanma. Yakın zamanda sattığın
 *     fiyattan daha pahalıya geri alım önerilmez.
 *  5) Miktar: ASLA 1/4'ten küçük, 1/2'den büyük değil. Hız yüksekse 1/2, değilse 1/4.
 *
 * Kâr garantisi yoktur; bu sezgisel bir kural sistemidir.
 */
public final class TradingAlgorithm {
    private TradingAlgorithm() {}

    // ===================== AYARLAR (buradan oynayabilirsin) =====================
    /** Kullanıcının istediği en küçük anlamlı hareket (TL). */
    public static final double MIN_MOVE_TL = 1.0;
    /** Hareket eşiği en az spread'in bu katı kadar olsun. */
    public static final double SPREAD_FACTOR = 1.0;
    /** Zirveden geri çekilme mesafesi: max(1 TL, DIST_SPREAD_FACTOR*spread, DIST_RANGE_FACTOR*60dk aralık). */
    public static final double DIST_SPREAD_FACTOR = 0.8;
    public static final double DIST_RANGE_FACTOR = 0.5;
    /** İki işlem arasında en az bu kadar dk geçmeden yeni sinyal üretilmez (spread kaybını sınırlar). */
    public static final long MIN_TRADE_GAP_MIN = 60;
    /** Normal ve güçlü sinyaldeki parça oranları. 0.25'ten küçük/0.5'ten büyük kullanılmaz. */
    public static final double FRACTION_NORMAL = 0.25;
    public static final double FRACTION_STRONG = 0.50;
    /** Bundan küçük miktar önerilmez (gram). */
    public static final double MIN_TRADE_GRAMS = 0.01;
    /** Zirve/dip referansı için geriye bakış (dk), son işlemden sonrası ile sınırlı. */
    public static final long REF_LOOKBACK_MIN = 180;
    /** Satıştan sonra, sattığından pahalıya geri alımı engelleme süresi (dk). */
    public static final long REBUY_GUARD_MIN = 240;
    /** Bu kadar eski veriyle sinyal üretilmez (ms). */
    public static final long MAX_DATA_AGE_MS = 5 * 60_000L;
    // ===========================================================================

    public static final class Tick {
        public final long ts;
        public final double buy;  // senin alırken ödediğin (yüksek)
        public final double sell; // senin satarken aldığın (düşük)
        public Tick(long ts, double buy, double sell) { this.ts = ts; this.buy = buy; this.sell = sell; }
        public double mid() { return (buy + sell) / 2.0; }
    }

    public static final class Position {
        public double grams;       // eldeki gram
        public double avgCost;     // ortalama maliyet TL/g
        public double cash;        // alım için ayrılan nakit TL (0 = bilinmiyor)
        public String lastTradeType; // "BUY" / "SELL" / null
        public double lastTradePrice;
        public long lastTradeTs;
    }

    public static final class Signal {
        public final String action;   // "BUY" | "SELL" | "HOLD"
        public final double fraction; // 0.25 / 0.50 (HOLD'da 0)
        public final double grams;    // önerilen miktar (0 = hesaplanamadı)
        public final double budgetTl; // alımda harcanacak TL
        public final double price;    // işlem fiyatı TL/g
        public final boolean strong;
        public final String message;  // bildirim/ekran metni (kısa, sade)
        public final String detail;   // teknik sayılar (isteğe bağlı gösterilir)
        Signal(String action, double fraction, double grams, double budgetTl, double price, boolean strong, String message, String detail) {
            this.action = action; this.fraction = fraction; this.grams = grams;
            this.budgetTl = budgetTl; this.price = price; this.strong = strong; this.message = message; this.detail = detail;
        }
        public boolean isHold() { return "HOLD".equals(action); }
    }

    // ---------------------------------------------------------------------------

    public static Signal evaluate(List<Tick> ticks, long now, Position pos) {
        if (ticks == null || ticks.size() < 5)
            return hold("Veri toplanıyor, birazdan başlayacak.", "En az ~40 dk fiyat geçmişi gerekir.");
        Tick cur = ticks.get(ticks.size() - 1);
        if (now - cur.ts > MAX_DATA_AGE_MS) return hold("Güncel fiyat alınamıyor, öneri üretilmiyor.", "Son veri 5 dk'dan eski.");

        double v10 = slopePerHour(ticks, now, 10), v30 = slopePerHour(ticks, now, 30), v60 = slopePerHour(ticks, now, 60);
        double c10 = change(ticks, now, 10), c15 = change(ticks, now, 15), c30 = change(ticks, now, 30), c60 = change(ticks, now, 60);
        if (nan(v10) || nan(v30) || nan(v60) || nan(c30))
            return hold("Veri toplanıyor, birazdan başlayacak.", "10/30/60 dk pencereleri için ~40 dk geçmiş gerekir.");

        double spread = medianSpread(ticks, now, 60, cur);
        double range60 = range(ticks, now, 60);
        double moveReq = Math.max(MIN_MOVE_TL, SPREAD_FACTOR * spread);                 // anlamlı hareket
        double dist = Math.max(Math.max(MIN_MOVE_TL, DIST_SPREAD_FACTOR * spread), DIST_RANGE_FACTOR * range60); // geri çekilme mesafesi

        long since = now - REF_LOOKBACK_MIN * 60_000L;
        if (pos.lastTradeTs > since) since = pos.lastTradeTs;
        double refHigh = cur.sell, refLow = cur.buy;
        for (Tick k : ticks) {
            if (k.ts < since) continue;
            if (k.sell > refHigh) refHigh = k.sell;
            if (k.buy < refLow) refLow = k.buy;
        }
        double drop = refHigh - cur.sell;   // zirveden düşüş
        double bounce = cur.buy - refLow;   // dipten toparlanma

        // Teknik detay (ekranda isteğe bağlı gösterilir)
        String detail = "10 dk " + fmtChg(c10) + " · 30 dk " + fmtChg(c30) + " · 60 dk " + fmtChg(c60) + " TL"
                + "\nHız: " + Fmt.signed2(v30) + " TL/saat"
                + "\nAlış-satış farkı: " + Fmt.n2(spread) + " TL"
                + "\nZirveden düşüş: " + Fmt.n2(drop) + " TL · dipten çıkış: " + Fmt.n2(bounce) + " TL";
        String last30 = nan(c30) ? "" : "\nSon 30 dk fiyat: " + Fmt.signed2(c30) + " TL";

        if (pos.lastTradeTs > 0 && now - pos.lastTradeTs < MIN_TRADE_GAP_MIN * 60_000L)
            return hold("Son işleminden sonra biraz bekleniyor, yeni öneri sonra gelecek.", detail);

        boolean down = v10 < 0 && v30 < 0 && v60 < 0 && c30 <= -moveReq;
        boolean up = v10 > 0 && v30 > 0 && v60 > 0 && c30 >= moveReq;

        // ------------------------------ SATIŞ ------------------------------
        if (pos.grams >= MIN_TRADE_GRAMS) {
            double gain = cur.sell - pos.avgCost; // TL/g, satış fiyatından
            boolean profitReversal = gain >= moveReq && v10 < 0 && v30 < 0 && c30 < 0 && drop >= dist;
            boolean trendExit = down && drop >= dist;
            if (trendExit || profitReversal) {
                boolean strong = (!nan(c15) && c15 <= -2 * moveReq) || drop >= 2.5 * dist;
                if (gain < 0 && !strong)
                    return hold("Fiyat düşüyor ama şu an zarardasın; satmak zararı kesinleştirir. Düşüş hızlanırsa haber veririm.", detail);
                double fraction = strong ? FRACTION_STRONG : FRACTION_NORMAL;
                double g = pos.grams * fraction;
                if (g < MIN_TRADE_GRAMS) return hold("Fiyat düşüyor ama satılacak miktar çok küçük.", detail);
                String why = profitReversal && !trendExit
                        ? "Kârdasın ve fiyat geri dönmeye başladı; kârını korumak için."
                        : "Fiyat düşüyor ve düşüş sürüyor.";
                if (strong) why += " Düşüş hızlı olduğu için miktar yarısı.";
                double est = g * gain;
                StringBuilder m = new StringBuilder();
                m.append("SAT: ").append(Fmt.gram(g)).append(" gram sat (elindekinin ").append(fracWords(fraction)).append(")\n");
                m.append("Fiyat: ").append(Fmt.n2(cur.sell)).append(" TL/g · toplam ≈ ").append(Fmt.tl(g * cur.sell)).append('\n');
                m.append("Neden: ").append(why).append('\n');
                m.append(est >= 0 ? "Bu satıştan tahmini kâr: " : "Bu satıştan tahmini zarar: ").append(Fmt.stl(est));
                m.append(last30);
                return new Signal("SELL", fraction, g, 0, cur.sell, strong, m.toString(), detail);
            }
        }

        // ------------------------------ ALIM ------------------------------
        boolean trendBuy = up;
        boolean rebound = !nan(c10) && drop >= 2 * dist && v10 > 0 && c10 >= 0.5 * moveReq && bounce >= 0.5 * dist;
        if (trendBuy || rebound) {
            if ("SELL".equals(pos.lastTradeType) && now - pos.lastTradeTs <= REBUY_GUARD_MIN * 60_000L
                    && cur.buy > pos.lastTradePrice) {
                return hold("Alım fırsatı var ama az önce sattığından (" + Fmt.n2(pos.lastTradePrice) + " TL) daha pahalı; beklemek daha iyi.", detail);
            }
            boolean strong = trendBuy && !nan(c15) && c15 >= 2 * moveReq && v10 >= v30;
            double fraction = strong ? FRACTION_STRONG : FRACTION_NORMAL;
            boolean cashKnown = pos.cash > 0;
            double basis = cashKnown ? pos.cash : pos.grams * cur.sell;
            double budget = basis * fraction;
            double g = budget > 0 ? budget / cur.buy : 0;
            if (budget > 0 && g < MIN_TRADE_GRAMS) return hold("Alım fırsatı var ama ayırdığın nakit çok az.", detail);
            String why = trendBuy ? "Fiyat düzenli şekilde yükseliyor." : "Sert düşüşten sonra fiyat toparlanıyor.";
            if (strong) why += " Yükseliş hızlı olduğu için miktar yarısı.";
            StringBuilder m = new StringBuilder();
            if (budget > 0) {
                m.append("AL: ").append(Fmt.gram(g)).append(" gram al (").append(cashKnown ? "nakdinin " : "portföy değerinin ")
                        .append(fracWords(fraction)).append(" ≈ ").append(Fmt.tl(budget)).append(")\n");
            } else {
                m.append("AL: miktarı hesaplayamadım, Portföy kartına nakit tutarını yaz.\n");
            }
            m.append("Fiyat: ").append(Fmt.n2(cur.buy)).append(" TL/g\n");
            m.append("Neden: ").append(why);
            if (budget > 0 && !cashKnown) m.append("\n(Nakit girilmediği için portföy değeri baz alındı.)");
            m.append(last30);
            return new Signal("BUY", fraction, g, budget, cur.buy, strong, m.toString(), detail);
        }

        return hold("Şimdilik bekle: net bir yön yok.", detail);
    }

    // ---------------------------- yardımcılar ----------------------------

    private static Signal hold(String why, String detail) { return new Signal("HOLD", 0, 0, 0, 0, false, why, detail); }
    private static boolean nan(double d) { return Double.isNaN(d); }
    private static String fmtChg(double d) { return nan(d) ? "—" : Fmt.signed2(d); }
    private static String fracWords(double f) { return f <= 0.26 ? "1/4'ü" : f <= 0.51 ? "yarısı" : String.format(Locale.US, "%%%.0f'i", f * 100); }

    /** Pencere içindeki orta fiyatın en küçük kareler eğimi (TL/saat). Yetersiz veri → NaN. */
    public static double slopePerHour(List<Tick> t, long now, int minutes) {
        long from = now - minutes * 60_000L;
        int n = 0; double sx = 0, sy = 0, sxx = 0, sxy = 0; long first = Long.MAX_VALUE, last = 0;
        for (Tick k : t) {
            if (k.ts < from || k.ts > now) continue;
            double x = (k.ts - from) / 3_600_000.0, y = k.mid();
            n++; sx += x; sy += y; sxx += x * x; sxy += x * y;
            if (k.ts < first) first = k.ts;
            if (k.ts > last) last = k.ts;
        }
        if (n < 3 || (last - first) < 0.6 * minutes * 60_000L) return Double.NaN;
        double den = n * sxx - sx * sx;
        if (Math.abs(den) < 1e-12) return Double.NaN;
        return (n * sxy - sx * sy) / den;
    }

    /** Şimdiki orta fiyat − 'minutes' dk önceki orta fiyat. Yakın kayıt yoksa NaN. */
    public static double change(List<Tick> t, long now, int minutes) {
        if (t == null || t.isEmpty()) return Double.NaN;
        Tick cur = t.get(t.size() - 1);
        long target = now - minutes * 60_000L, tol = Math.max(90_000L, minutes * 60_000L / 4);
        Tick best = null; long bd = Long.MAX_VALUE;
        for (Tick k : t) { long d = Math.abs(k.ts - target); if (d < bd) { bd = d; best = k; } }
        if (best == null || bd > tol || best == cur) return Double.NaN;
        return cur.mid() - best.mid();
    }

    private static double range(List<Tick> t, long now, int minutes) {
        long from = now - minutes * 60_000L; double hi = -1e18, lo = 1e18; boolean any = false;
        for (Tick k : t) { if (k.ts < from) continue; any = true; if (k.sell > hi) hi = k.sell; if (k.sell < lo) lo = k.sell; }
        return any ? hi - lo : 0;
    }

    private static double medianSpread(List<Tick> t, long now, int minutes, Tick cur) {
        long from = now - minutes * 60_000L; List<Double> a = new ArrayList<>();
        for (Tick k : t) if (k.ts >= from) a.add(k.buy - k.sell);
        if (a.isEmpty()) return cur.buy - cur.sell;
        java.util.Collections.sort(a);
        return a.get(a.size() / 2);
    }
}
