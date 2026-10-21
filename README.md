# Gümüş Takip

Android uygulaması GetirFinans XAG fiyatlarını izler ve fiyat **hızına** dayalı, açıklanabilir, kural tabanlı al/sat önerileri üretir. Yapay zekâ / API anahtarı yoktur. İşlem otomatik yapılmaz.

## Nasıl çalışır
- **1 TL bildirimi:** Son bildirimden bu yana fiyat 1 TL artar/azalırsa bildirim gelir (son 15 dk hızı + başlangıçtan K/Z ile).
- **Fiyat geçmişi:** `gumus_ticks.csv` içinde son 48 saat tutulur (gün değişince silinmez). Günlük TXT, gün değişince tarihli arşive taşınır (14 gün).
- **Algoritma** (`TradingAlgorithm.java`): 10/30/60 dk eğimlerin (TL/saat) aynı yönde olmasını ister. Alış-satış farkını (spread) hesaba katar; hareket spread'i aşmıyorsa öneri üretmez.
  - **SAT:** teyitli düşüş + zirveden geri çekilme. Kârdayken daha erken çıkar, zarardayken yalnızca güçlü düşüşte önerir.
  - **AL:** teyitli yükseliş veya büyük düşüş sonrası toparlanma. Son 4 saatte sattığından pahalıya geri alım önermez.
  - **Miktar:** hiçbir zaman 1/4'ten küçük, 1/2'den büyük değil. Hız yüksekse 1/2, değilse 1/4. AL miktarı girilen nakdin 1/4 veya 1/2'si üzerinden gram olarak yazılır.
  - İki işlem arasında en az 60 dk beklenir (spread kaybını sınırlamak için).
- **Öneri akışı:** Bildirim gelir → uygulamada "işlemi yaptın mı?" sorulur.
  - *Evet:* gerçek miktar ve fiyat girilir, 5 dk sonra yeni miktarla algoritma yeniden devreye girer.
  - *Hayır:* 20 dk sonra güncel duruma göre yeniden değerlendirilir.
  - *Cevap yok:* öneri hâlâ geçerliyse 15 dk'da bir hatırlatılır; 10 dk boyunca geçersizse iptal edilir.
- **K/Z:** Eldeki miktarın K/Z'si, gerçekleşmiş K/Z, **başlangıçtan beri toplam K/Z** (ilk girilen miktar ve maliyet + tüm al/satlar) ve "hiç satmasaydın" karşılaştırması gösterilir.

## Ayarlar
E�ikler `TradingAlgorithm.java` en üstündeki sabitlerdedir (`SPREAD_FACTOR`, `DIST_*`, `MIN_TRADE_GAP_MIN`, `FRACTION_*`).

## Test / simülasyon
`tools/Sim.java` algoritmayı sentetik fiyat serilerinde dener (Android gerekmez):
```
javac -d out app/src/main/java/com/eren/gumustakip/TradingAlgorithm.java tools/Sim.java
java -cp out Sim
```
Gerçek veriyle denemek için uygulamadan paylaşılan `gumus_ticks.csv` kullanılabilir.

## Not
Bu bir sezgisel kural sistemidir; geçmiş fiyatlar geleceği garanti etmez ve **kâr garantisi vermez**. Önerilerin yatırım tavsiyesi niteliği yoktur. Fiyat sağlayıcının alış/satış değerleri kullanılır; gerçek kuyumcu/banka makası ve ücretler sonucu değiştirebilir.
