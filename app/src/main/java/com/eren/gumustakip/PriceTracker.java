package com.eren.gumustakip;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PriceTracker {
    private static final String URL_STR = "https://www.getirfinans.com/doviz-islemleri/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Pattern PRICE_PATTERN = Pattern.compile("\\d{2,3}[.,]\\d{2,4}");

    private PriceTracker() {}

    public static Result fetch() throws Exception {
        // ag.py içindeki HEADERS ve SESSION yapısının birebir aynısı
        Document doc = Jsoup.connect(URL_STR)
                .userAgent(USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .timeout(15000)
                .get();

        // 1. YÖNTEM: XAG / Gümüş satırındaki Alış ve Satış Fiyatlarını Bulma (ag.py Yöntem 1)
        Elements rows = doc.select("div, tr, li");
        for (Element row : rows) {
            String rowText = row.text();
            String rowTextLower = rowText.toLowerCase();

            if (rowText.contains("XAG") || rowTextLower.contains("gümüş")) {
                // text-b2 veya font-semibold içeren span elemanlarını filtreleme
                Elements spans = row.select("span[class*=text-b2], span[class*=font-semibold]");
                List<Double> fiyatlar = new ArrayList<>();

                for (Element span : spans) {
                    String valStr = span.text().trim();
                    Matcher matcher = PRICE_PATTERN.matcher(valStr);
                    if (matcher.find()) {
                        String temizSayi = matcher.group(0).replace(",", ".");
                        try {
                            double fiyat = Double.parseDouble(temizSayi);
                            if (fiyat > 50.0 && fiyat < 300.0) {
                                fiyatlar.add(fiyat);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }

                if (fiyatlar.size() >= 2) {
                    return new Result(fiyatlar.get(0), fiyatlar.get(1)); // alis, satis
                } else if (fiyatlar.size() == 1) {
                    return new Result(fiyatlar.get(0), Double.NaN);
                }
            }
        }

        // 2. YÖNTEM: Genel Metin Taraması - Yedek (ag.py Yöntem 2)
        String fullText = doc.body() != null ? doc.body().text() : doc.text();
        String[] tokens = fullText.split("\\s+");

        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].toUpperCase();
            if (token.equals("GÜMÜŞ") || token.equals("XAG")) {
                List<Double> fiyatlar = new ArrayList<>();
                for (int j = 1; j <= 14 && (i + j) < tokens.length; j++) {
                    Matcher matcher = PRICE_PATTERN.matcher(tokens[i + j]);
                    if (matcher.find()) {
                        String temizSayi = matcher.group(0).replace(",", ".");
                        try {
                            double fiyat = Double.parseDouble(temizSayi);
                            if (fiyat > 50.0 && fiyat < 300.0) {
                                fiyatlar.add(fiyat);
                                if (fiyatlar.size() == 2) break;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }

                if (fiyatlar.size() >= 2) {
                    return new Result(fiyatlar.get(0), fiyatlar.get(1));
                } else if (fiyatlar.size() == 1) {
                    return new Result(fiyatlar.get(0), Double.NaN);
                }
            }
        }

        throw new Exception("Sayfada Gümüş/XAG fiyatı bulunamadı.");
    }

    public static final class Result {
        public final double buy;
        public final double sell;

        public Result(double buy, double sell) {
            this.buy = buy;
            this.sell = sell;
        }
    }
}
