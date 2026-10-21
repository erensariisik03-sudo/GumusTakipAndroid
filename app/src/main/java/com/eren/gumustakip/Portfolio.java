package com.eren.gumustakip;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Eldeki gümüş, ortalama maliyet, nakit, ilk girilen başlangıç ve işlem kayıtları.
 * Sayılar SharedPreferences'ta String olarak (double hassasiyetiyle) tutulur.
 * Eski sürümde float olarak yazılmış değerler de okunabilir.
 */
public final class Portfolio {
    public static final String PREFS = "gumus";
    private final SharedPreferences p;

    public Portfolio(Context c) { p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ---------- tür-toleranslı okuma/yazma ----------
    public static double getD(SharedPreferences p, String key, double def) {
        try {
            Object o = p.getAll().get(key);
            if (o == null) return def;
            if (o instanceof Number) return ((Number) o).doubleValue();
            return Double.parseDouble(o.toString().trim().replace(",", "."));
        } catch (Exception e) { return def; }
    }
    public static void putD(SharedPreferences.Editor e, String key, double v) { e.putString(key, Double.toString(v)); }

    // ---------- okuma ----------
    public double grams() { return getD(p, "grams", 0); }
    public double cost() { return getD(p, "cost", 0); }
    public double cash() { return getD(p, "cash", 0); }
    public double initialGrams() { return getD(p, "initial_grams", 0); }
    public double initialCost() { return getD(p, "initial_cost", 0); }
    public double realized() { return getD(p, "realized_profit", 0); }
    public double spent() { return getD(p, "total_spent", 0); }
    public double proceeds() { return getD(p, "total_proceeds", 0); }
    public boolean initialized() { return p.getBoolean("portfolio_initialized", false); }
    public double initialInvestment() { return initialGrams() * initialCost(); }

    /** Eldeki gümüşün satış fiyatından değeri. */
    public double value(double sell) { return grams() * sell; }
    /** Eldeki miktarın (henüz satılmamış) K/Z'si. */
    public double unrealized(double sell) { return grams() * (sell - cost()); }
    /** İlk girilen miktar + sonraki tüm al/satlar dahil, başlangıçtan beri toplam K/Z. */
    public double totalPnl(double sell) { return value(sell) + proceeds() - spent() - initialInvestment(); }
    /** Hiç işlem yapmasaydın ilk girdiğin miktarın K/Z'si. */
    public double holdPnl(double sell) { return initialGrams() * (sell - initialCost()); }
    public double totalPnlPct(double sell) {
        double base = initialInvestment() > 0 ? initialInvestment() : spent();
        return base > 0 ? totalPnl(sell) / base * 100.0 : 0;
    }

    // ---------- yazma ----------
    /** Kullanıcının kutulara yazdığı değerleri kaydeder. Miktar elle değiştiyse true döner. */
    public boolean saveInputs(double grams, double cost, double cash) {
        boolean manualChange = initialized() && Math.abs(grams - grams()) > 0.0005;
        SharedPreferences.Editor e = p.edit();
        putD(e, "grams", grams); putD(e, "cost", cost); putD(e, "cash", cash);
        e.apply();
        return manualChange;
    }

    /** İlk kez takip başlatılırken başlangıç miktarı ve maliyeti sabitlenir. */
    public void initializeIfNeeded(double grams, double cost) {
        if (initialized()) return;
        SharedPreferences.Editor e = p.edit();
        putD(e, "initial_grams", grams); putD(e, "initial_cost", cost);
        putD(e, "realized_profit", 0); putD(e, "total_spent", 0); putD(e, "total_proceeds", 0);
        e.putBoolean("portfolio_initialized", true).apply();
    }

    /** Başlangıcı şimdiki miktar ve maliyete çeker; işlem geçmişini ve K/Z'yi sıfırlar. */
    public void resetBaseline() {
        SharedPreferences.Editor e = p.edit();
        putD(e, "initial_grams", grams()); putD(e, "initial_cost", cost());
        putD(e, "realized_profit", 0); putD(e, "total_spent", 0); putD(e, "total_proceeds", 0);
        e.putString("trade_log", "[]").putBoolean("portfolio_initialized", true)
                .putBoolean("signal_pending", false).apply();
    }

    /**
     * Gerçekleşen işlemi kaydeder. Hata varsa mesajı, başarılıysa null döner.
     * SELL: gram azalır, nakit artar. BUY: ağırlıklı ortalama maliyet yeniden hesaplanır, nakit azalır.
     */
    public String recordTrade(String type, double qty, double price) {
        if (qty <= 0 || price <= 0) return "Miktar/fiyat geçersiz.";
        double grams = grams(), cost = cost(), cash = cash();
        double realizedNow = 0;
        SharedPreferences.Editor e = p.edit();
        if ("SELL".equals(type)) {
            if (qty > grams + 1e-9) return "Elindeki miktardan fazla satış kaydedilemez.";
            realizedNow = (price - cost) * qty;
            grams = Math.max(0, grams - qty);
            cash += qty * price;
            putD(e, "total_proceeds", proceeds() + qty * price);
            putD(e, "realized_profit", realized() + realizedNow);
        } else {
            double oldValue = grams * cost;
            grams += qty;
            cost = grams > 0 ? (oldValue + qty * price) / grams : price;
            cash = Math.max(0, cash - qty * price);
            putD(e, "total_spent", spent() + qty * price);
        }
        JSONArray log;
        try { log = new JSONArray(p.getString("trade_log", "[]")); } catch (Exception ex) { log = new JSONArray(); }
        try {
            JSONObject o = new JSONObject();
            o.put("type", type); o.put("grams", qty); o.put("price", price); o.put("realized", realizedNow);
            o.put("ts", System.currentTimeMillis());
            o.put("time", new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date()));
            log.put(o);
        } catch (Exception ignored) { }
        putD(e, "grams", grams); putD(e, "cost", cost); putD(e, "cash", cash);
        e.putString("trade_log", log.toString()).apply();
        return null;
    }

    /** Algoritma için pozisyon özeti (son işlem bilgisi dahil). */
    public TradingAlgorithm.Position toPosition() {
        TradingAlgorithm.Position pos = new TradingAlgorithm.Position();
        pos.grams = grams(); pos.avgCost = cost(); pos.cash = cash();
        try {
            JSONArray log = new JSONArray(p.getString("trade_log", "[]"));
            if (log.length() > 0) {
                JSONObject o = log.getJSONObject(log.length() - 1);
                pos.lastTradeType = o.optString("type", null);
                pos.lastTradePrice = o.optDouble("price", 0);
                pos.lastTradeTs = o.optLong("ts", 0);
            }
        } catch (Exception ignored) { }
        return pos;
    }

    /** Ana ekrandaki kısa özet. */
    public String summary(double sell) {
        return "Eldeki: " + Fmt.gram(grams()) + " g · ortalama maliyet " + Fmt.n2(cost()) + " TL/g\n"
                + "Değeri: " + Fmt.tl(value(sell)) + "\n"
                + "Başlangıçtan beri K/Z: " + Fmt.stl(totalPnl(sell)) + " (" + Fmt.pct(totalPnlPct(sell)) + ")\n"
                + "Hiç satmasaydın: " + Fmt.stl(holdPnl(sell));
    }

    /** İşlem geçmişi penceresindeki ayrıntılı döküm. */
    public String details(double sell) {
        return summary(sell) + "\n\nEldeki miktarın K/Z: " + Fmt.stl(unrealized(sell))
                + "\nGerçekleşmiş K/Z: " + Fmt.stl(realized())
                + "\nBaşlangıç: " + Fmt.gram(initialGrams()) + " g × " + Fmt.n2(initialCost()) + " TL/g";
    }
}
