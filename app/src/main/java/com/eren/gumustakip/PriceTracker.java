package com.eren.gumustakip;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PriceTracker {
    private static final String URL_STR = "https://www.getirfinans.com/doviz-islemleri/";
    private static final Pattern PRICE = Pattern.compile("\\d{2,3}[\\.,]\\d{2,4}");
    private static final Pattern SPAN = Pattern.compile("<span[^>]*class=[\\\"']([^\\\"']*(?:text-b2|font-semibold)[^\\\"']*)[\\\"'][^>]*>(.*?)</span>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private PriceTracker() {}

    public static Result fetch() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_STR).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000); c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/124 Safari/537.36");
        c.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7");
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        String html = read(c.getInputStream());
        Result r = method1(html);
        if (r != null) return r;
        r = method2(html);
        if (r != null) return r;
        throw new Exception("Gümüş/XAG fiyatı bulunamadı");
    }

    private static Result method1(String html) {
        Matcher sm = SPAN.matcher(html);
        while (sm.find()) {
            int start = Math.max(0, sm.start() - 1200);
            int end = Math.min(html.length(), sm.end() + 1200);
            String block = stripTags(html.substring(start, end)).toLowerCase();
            if (block.contains("xag") || block.contains("gümüş")) {
                List<Double> prices = pricesIn(sm.group(2) + " " + stripTags(html.substring(start, end)));
                if (prices.size() >= 2) return new Result(prices.get(0), prices.get(1));
            }
        }
        return null;
    }

    private static Result method2(String html) {
        String[] parts = stripTags(html).split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i].trim();
            if (t.equalsIgnoreCase("gümüş") || t.equalsIgnoreCase("xag")) {
                List<Double> prices = new ArrayList<>();
                for (int j = 1; j <= 14 && i + j < parts.length; j++) {
                    Double v = parsePrice(parts[i + j]);
                    if (v != null) { prices.add(v); if (prices.size() == 2) break; }
                }
                if (prices.size() >= 2) return new Result(prices.get(0), prices.get(1));
                if (prices.size() == 1) return new Result(prices.get(0), Double.NaN);
            }
        }
        return null;
    }

    private static List<Double> pricesIn(String s) {
        List<Double> out = new ArrayList<>();
        Matcher m = PRICE.matcher(s);
        while (m.find()) {
            Double v = parsePrice(m.group());
            if (v != null) out.add(v);
        }
        return out;
    }

    private static Double parsePrice(String s) {
        Matcher m = PRICE.matcher(s);
        if (!m.find()) return null;
        try {
            double v = Double.parseDouble(m.group().replace(',', '.'));
            return (v > 50 && v < 300) ? v : null;
        } catch (Exception e) { return null; }
    }

    private static String stripTags(String s) {
        return s.replaceAll("<script[\\s\\S]*?</script>", " ")
                .replaceAll("<style[\\s\\S]*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\\\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String read(InputStream in) throws Exception {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        return b.toString();
    }

    public static final class Result {
        public final double buy, sell;
        public Result(double buy, double sell) { this.buy = buy; this.sell = sell; }
    }
}
