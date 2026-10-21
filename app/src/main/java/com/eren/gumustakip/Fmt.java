package com.eren.gumustakip;

import java.util.Locale;

/** Ekranda ve bildirimlerde Türkçe sayı biçimi: 104,23 · 10.420 · +131. */
public final class Fmt {
    private static final Locale TR = new Locale("tr", "TR");
    private Fmt() {}

    /** 104,23 */
    public static String n2(double v) { return String.format(TR, "%.2f", v); }

    /** 12,14 (1'den küçükse 3 hane: 0,250) */
    public static String gram(double g) { return String.format(TR, g < 1 ? "%.3f" : "%.2f", g); }

    /** 10.420 TL (büyük tutarlarda küsuratsız) */
    public static String tl(double v) {
        return Math.abs(v) >= 100 ? String.format(TR, "%,.0f TL", v) : String.format(TR, "%.2f TL", v);
    }

    /** +131 TL / -45,20 TL */
    public static String stl(double v) {
        return Math.abs(v) >= 100 ? String.format(TR, "%+,.0f TL", v) : String.format(TR, "%+.2f TL", v);
    }

    /** +1,6% */
    public static String pct(double v) { return String.format(TR, "%+.1f%%", v); }

    /** +1,55 */
    public static String signed2(double v) { return String.format(TR, "%+.2f", v); }
}
