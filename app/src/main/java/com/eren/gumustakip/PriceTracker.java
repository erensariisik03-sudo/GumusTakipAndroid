package com.eren.gumustakip;

import android.util.Log;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PriceTracker {
    private static final String URL = "https://www.getirfinans.com/doviz-islemleri/";
    private static final String TAG = "PriceTracker";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // Alış ve Satış fiyatını tutmak için model
    public static class GumusFiyat {
        public double alis;
        public double satis;

        public GumusFiyat(double alis, double satis) {
            this.alis = alis;
            this.satis = satis;
        }
    }

    public static GumusFiyat fiyatiCek() {
        try {
            // Siteye bağlanma ve başlıkları (Headers) ayarlama
            Document doc = Jsoup.connect(URL)
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                    .timeout(15000)
                    .get();

            // Sitedeki tr, div ve li etiketlerini tarama
            Elements rows = doc.select("div, tr, li");
            for (Element row : rows) {
                String rowText = row.text().toLowerCase();
                
                // Eğer satırda XAG veya Gümüş geçiyorsa
                if (rowText.contains("xag") || rowText.contains("gümüş")) {
                    Elements spans = row.select("span.text-b2, span.font-semibold");
                    List<Double> fiyatlar = new ArrayList<>();
                    
                    // Regex ile sayıyı yakalama (Örn: 28,45 veya 28.45)
                    Pattern pattern = Pattern.compile("\\d{2,3}[.,]\\d{2,4}");

                    for (Element span : spans) {
                        Matcher matcher = pattern.matcher(span.text());
                        if (matcher.find()) {
                            String temizSayi = matcher.group(0).replace(",", ".");
                            try {
                                double fiyatFloat = Double.parseDouble(temizSayi);
                                // Sadece 50 ile 300 TL arasındaki mantıklı gümüş fiyatlarını al
                                if (fiyatFloat > 50.0 && fiyatFloat < 300.0) {
                                    fiyatlar.add(fiyatFloat);
                                }
                            } catch (NumberFormatException e) {
                                Log.e(TAG, "Sayıya çevirme hatası: " + temizSayi);
                            }
                        }
                    }

                    // Fiyatlar bulunduysa nesneyi döndür (0 = Alış, 1 = Satış)
                    if (fiyatlar.size() >= 2) {
                        return new GumusFiyat(fiyatlar.get(0), fiyatlar.get(1));
                    } else if (fiyatlar.size() == 1) {
                        return new GumusFiyat(fiyatlar.get(0), 0.0);
                    }
                }
            }
            Log.e(TAG, "Hata: Sayfada Gümüş Fiyatı Bulunamadı.");
        } catch (Exception e) {
            Log.e(TAG, "Bağlantı Hatası: " + e.getMessage());
        }
        return null;
    }
}
