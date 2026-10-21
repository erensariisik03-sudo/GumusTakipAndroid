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

/**
 * GetirFinans XAG fiyat çekicisi.
 * Python scraper ile aynı temel yaklaşımı kullanır:
 * XAG/Gümüş içeren satır -> belirli span'lerdeki ilk iki fiyat -> büyük ALIŞ, küçük SATIŞ.
 */
public final class PriceTracker {
    private static final String URL_STR = "https://www.getirfinans.com/doviz-islemleri/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Pattern PRICE_PATTERN = Pattern.compile("\\d{2,3}[\\.,]\\d{2,4}");
    private static final Pattern PRICE_RANGE_PATTERN = Pattern.compile("(?i)^([5-9]\\d(?:[\\.,]\\d{2,4})?|1\\d{2}(?:[\\.,]\\d{2,4})?|2\\d{2}(?:[\\.,]\\d{2,4})?)$");

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

        // XAG/Gümüş geçen ELEMENTLERİN İÇİNDEN EN KÜÇÜĞÜNÜ (satırın kendisini) seç.
        // Eski sürüm belge sırasındaki ilk (en dıştaki) div'i alıyordu; o div tüm sayfayı kapsadığı için
        // sayfadaki ilk iki fiyat (başka bir döviz/emtia) yanlışlıkla gümüş sanılabiliyordu.
        Result bestResult = null;
        int bestLen = Integer.MAX_VALUE;
        for (Element row : doc.select("div, tr, li, a, section, article")) {
            String rowText = row.text();
            int len = rowText.length();
            if (len >= bestLen) continue;
            String lower = rowText.toLowerCase(Locale.ROOT);
            if (!(rowText.contains("XAG") || lower.contains("gümüş"))) continue;

            Elements spans = row.select("span[class*=text-b2], span[class*=font-semibold]");
            List<Double> prices = extractValidPrices(spans, 2);
            Result result = normalizeFirstTwo(prices);
            if (result != null) { bestResult = result; bestLen = len; }
        }
        if (bestResult != null) return bestResult;

        // Geçerli fiyat çifti yok: piyasa kapalıyken site anormal bir çift gösterebilir (ör. 150 / 50).
        // Gümüş satırındaki iki sayı birbirinden %10'dan fazla farklıysa bunu hata değil "piyasa kapalı" say.
        int closedLen = Integer.MAX_VALUE;
        double closedLo = 0, closedHi = 0;
        for (Element row : doc.select("div, tr, li, a, section, article")) {
            String rowText = row.text();
            int len = rowText.length();
            if (len >= closedLen) continue;
            if (!(rowText.contains("XAG") || rowText.toLowerCase(Locale.ROOT).contains("gümüş"))) continue;
            List<Double> any = extractAnyNumbers(row.select("span[class*=text-b2], span[class*=font-semibold]"), 2);
            if (any.size() == 2 && any.get(0) > 0 && any.get(1) > 0) {
                closedLen = len;
                closedHi = Math.max(any.get(0), any.get(1));
                closedLo = Math.min(any.get(0), any.get(1));
            }
        }
        if (closedLen != Integer.MAX_VALUE && closedHi / closedLo > 1.10) {
            throw new MarketClosedException("Piyasa kapalı görünüyor (site " + closedHi + " / " + closedLo + " gösteriyor).");
        }

        // Yedek: Python kodundaki genel metin taramasına benzer şekilde Gümüş/XAG'den sonra gelen ilk iki sayı.
        String bodyText = doc.body() != null ? doc.body().text() : doc.text();
        List<String> textList = new ArrayList<>();
        for (String token : bodyText.split("\\s+")) {
            if (token != null && !token.trim().isEmpty()) textList.add(token.trim());
        }
        for (int i = 0; i < textList.size(); i++) {
            String token = textList.get(i) == null ? "" : textList.get(i).trim();
            String upper = token.toUpperCase(Locale.ROOT);
            if (!(upper.equals("GÜMÜŞ") || upper.equals("XAG"))) continue;

            List<Double> prices = new ArrayList<>();
            for (int j = 1; j <= 14 && i + j < textList.size(); j++) {
                Matcher matcher = PRICE_PATTERN.matcher(textList.get(i + j) == null ? "" : textList.get(i + j));
                if (matcher.find()) {
                    Double value = parsePrice(matcher.group());
                    if (value != null) {
                        prices.add(value);
                        if (prices.size() == 2) break;
                    }
                }
            }
            Result result = normalizeFirstTwo(prices);
            if (result != null) return result;
        }

        throw new Exception("Sayfada Gümüş/XAG için iki geçerli fiyat bulunamadı.");
    }

    private static List<Double> extractValidPrices(Elements elements, int maxCount) {
        List<Double> prices = new ArrayList<>();
        for (Element element : elements) {
            Matcher matcher = PRICE_PATTERN.matcher(element.text());
            while (matcher.find()) {
                Double value = parsePrice(matcher.group());
                if (value != null) {
                    prices.add(value);
                    if (prices.size() >= maxCount) return prices;
                }
            }
        }
        return prices;
    }

    /** Aralık kontrolü yapmadan ilk sayıları okur (yalnızca piyasa-kapalı tespiti için). */
    private static List<Double> extractAnyNumbers(Elements elements, int maxCount) {
        List<Double> out = new ArrayList<>();
        for (Element element : elements) {
            Matcher matcher = PRICE_PATTERN.matcher(element.text());
            while (matcher.find()) {
                try {
                    String n = matcher.group();
                    if (n.contains(",")) n = n.replace(".", "").replace(',', '.');
                    out.add(Double.parseDouble(n));
                    if (out.size() >= maxCount) return out;
                } catch (NumberFormatException ignored) { }
            }
        }
        return out;
    }

    /** Büyük sayı ALIŞ, küçük sayı SATIŞ. Sıralama kaynağın sırasından bağımsızdır. */
    private static Result normalizeFirstTwo(List<Double> prices) {
        if (prices == null || prices.size() < 2) return null;
        double first = prices.get(0);
        double second = prices.get(1);
        if (first == second) return null;
        return first >= second ? new Result(first, second) : new Result(second, first);
    }

    private static Double parsePrice(String raw) {
        try {
            String normalized = raw.trim();
            // Türkçe biçim: 123,4567 -> 123.4567. Binlik ayraçlı format da güvenli şekilde ele alınır.
            if (normalized.contains(",")) {
                normalized = normalized.replace(".", "").replace(',', '.');
            }
            double value = Double.parseDouble(normalized);
            if (value > 50.0 && value < 300.0 && PRICE_RANGE_PATTERN.matcher(raw.trim()).find()) {
                return value;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Site piyasa kapalıyken gösterdiği anormal fiyat çiftini verdi. Hata değildir. */
    public static final class MarketClosedException extends Exception {
        public MarketClosedException(String message) { super(message); }
    }

    public static final class Result {
        public final double buy;  // Her zaman yüksek olan fiyat.
        public final double sell; // Her zaman düşük olan fiyat.

        public Result(double buy, double sell) {
            this.buy = buy;
            this.sell = sell;
        }
    }
}
