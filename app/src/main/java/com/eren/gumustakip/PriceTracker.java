package com.eren.gumustakip;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PriceTracker {
    private static final String URL_STR = "https://www.getirfinans.com/doviz-islemleri/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Pattern PRICE_PATTERN = Pattern.compile("\\d{2,3}[.,]\\d{2,4}");

    private PriceTracker() {}

    public static Result fetch() throws Exception {
        Document doc = Jsoup.connect(URL_STR)
                .userAgent(USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .timeout(15000)
                .get();

        // Öncelik: Alış/Satış etiketlerinin yanındaki fiyatları bul.
        Elements rows = doc.select("div, tr, li");
        for (Element row : rows) {
            String text = row.text();
            String lower = text.toLowerCase(Locale.ROOT);
            if (!(text.contains("XAG") || lower.contains("gümüş"))) continue;

            Double labelledBuy = findLabelledPrice(row, "alış");
            Double labelledSell = findLabelledPrice(row, "satış");
            if (labelledBuy != null && labelledSell != null) {
                return normalize(labelledBuy, labelledSell);
            }
        }

        // Yedek: önce aday fiyatları çıkar, sonra en yüksek olanı ALIŞ, en düşük olanı SATIŞ kabul et.
        // Böylece web sayfasındaki görsel/sıralama değişse bile ters yazma engellenir.
        for (Element row : rows) {
            String text = row.text();
            String lower = text.toLowerCase(Locale.ROOT);
            if (!(text.contains("XAG") || lower.contains("gümüş"))) continue;

            List<Double> prices = extractPrices(row.select("span[class*=text-b2], span[class*=font-semibold]"));
            Result result = fromCandidates(prices);
            if (result != null) return result;
        }

        String fullText = doc.body() != null ? doc.body().text() : doc.text();
        String[] tokens = fullText.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].toLowerCase(Locale.ROOT);
            if (!token.equals("gümüş") && !token.equals("xag")) continue;
            List<Double> prices = new ArrayList<>();
            for (int j = 1; j <= 16 && i + j < tokens.length; j++) {
                Matcher matcher = PRICE_PATTERN.matcher(tokens[i + j]);
                if (matcher.find()) {
                    Double d = parsePrice(matcher.group(0));
                    if (d != null) prices.add(d);
                    if (prices.size() >= 4) break;
                }
            }
            Result result = fromCandidates(prices);
            if (result != null) return result;
        }

        throw new Exception("Sayfada Gümüş/XAG fiyatı bulunamadı.");
    }

    private static Double findLabelledPrice(Element row, String label) {
        String text = row.text();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?i)" + label + "\\s*[:\\-]?\\s*(\\d{2,3}(?:[.,]\\d{2,4}))");
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) return parsePrice(m.group(1));

        // Bazı tasarımlarda etiket ve sayı ayrı HTML düğümlerindedir.
        Elements spans = row.select("span[class*=text-b2], span[class*=font-semibold], span");
        for (int i = 0; i < spans.size(); i++) {
            String current = spans.get(i).text().trim().toLowerCase(Locale.ROOT);
            if (!current.equals(label)) continue;
            for (int j = i + 1; j < Math.min(spans.size(), i + 4); j++) {
                Double d = firstValidPrice(spans.get(j).text());
                if (d != null) return d;
            }
        }
        return null;
    }

    private static Result normalize(double a, double s) {
        // Kullanıcının istediği kural: Alış her zaman daha yüksek, Satış daha düşük.
        if (a >= s) return new Result(a, s);
        return new Result(s, a);
    }

    private static Result fromCandidates(List<Double> prices) {
        if (prices.size() < 2) return null;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double p : prices) {
            min = Math.min(min, p);
            max = Math.max(max, p);
        }
        if (max == min) return null;
        return normalize(max, min);
    }

    private static List<Double> extractPrices(Elements elements) {
        List<Double> out = new ArrayList<>();
        for (Element e : elements) {
            Matcher m = PRICE_PATTERN.matcher(e.text());
            if (m.find()) {
                Double d = parsePrice(m.group(0));
                if (d != null) out.add(d);
            }
        }
        return out;
    }

    private static Double firstValidPrice(String s) {
        Matcher m = PRICE_PATTERN.matcher(s);
        if (!m.find()) return null;
        return parsePrice(m.group(0));
    }

    private static Double parsePrice(String raw) {
        try {
            double d;
            if (raw.contains(",")) d = Double.parseDouble(raw.replace(".", "").replace(",", "."));
            else d = Double.parseDouble(raw);
            // Gümüş TL/gram için mevcut scraper'ın güvenlik aralığını koru.
            if (d > 50.0 && d < 300.0) return d;
        } catch (Exception ignored) {}
        return null;
    }

    public static final class Result {
        public final double buy;  // Alış: yüksek olan
        public final double sell; // Satış: düşük olan

        public Result(double buy, double sell) {
            this.buy = buy;
            this.sell = sell;
        }
    }
}
