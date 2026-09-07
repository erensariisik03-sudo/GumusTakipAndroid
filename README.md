# Gümüş Takip Uygulaması (Android)

GetirFinans üzerinden anlık XAG (gümüş) fiyatlarını takip eden, belirli TL seviyesi değişimlerinde sesli, titreşimli ve LED destekli bildirim gönderen, arka planda kesintisiz çalışan bir Android uygulamasıdır.

## Özellikler
* **Arka Plan Takibi:** `ForegroundService` ile uygulama arka plandayken de fiyat takibi sürer.
* **40 Saniyelik Veri Toplama:** GetirFinans XAG alış/satış fiyatları her 40 saniyede bir çekilir.
* **Günlük TXT Geçmişi:** `gumus_fiyat_gecmisi.txt` dosyası gün değiştiğinde otomatik sıfırlanır ve yeni günün kayıtları `[dd.MM.yyyy HH:mm:ss] Satış: ... | Alış: ...` formatında eklenir.
* **30 Dakikalık Gemini Analizi:** Son 30 dakikadaki fiyatlar Gemini'ye gönderilir; sonuç yüksek öncelikli bildirim olarak gelir ve uygulama içinde son tavsiye gösterilir.
* **Yedekli Gemini Modelleri:** `gemini-2.5-flash` başarısız/503 olduğunda iki deneme sonrası `gemini-1.5-flash` modeline geçilir.
* **Seviye Alarmları:** Satış fiyatının tam TL seviyesi değiştiğinde yüksek öncelikli (`IMPORTANCE_HIGH`) bildirim tetikler.
* **Portföy Yönetimi:** Kullanıcının girdiği gram ve maliyet değerlerine göre anlık kar/zarar hesaplaması yapar.
* **Veri Kalıcılığı:** Girdiler ve son anlık veriler `SharedPreferences` ile dahili hafızada saklanır; uygulama kapatılıp açılsa bile kaybolmaz.

## Kullanılan İzinler
* `INTERNET`: Fiyat verilerini çekmek için.
* `POST_NOTIFICATIONS`: Bildirim göndermek için (Android 13+).
* `FOREGROUND_SERVICE` & `DATA_SYNC`: Arka planda kesintisiz servis çalıştırabilmek için.
* `RECEIVE_BOOT_COMPLETED` & `WAKE_LOCK`: Cihaz yeniden başladığında tetiklenme ve uykuda kalma yönetimi için.
* `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Pil kısıtlamalarına takılmadan arka planda çalışabilmek için.

## Kurulum ve Çalıştırma
1. Projeyi klonlayın veya indirin.
2. Android Studio içerisinden projeyi açın.
3. Uygulamayı derleyip cihazınıza kurun ve "Takibi Başlat" butonuna basarak pil optimizasyonu muafiyetine onay verin.


## Gemini API Notu
Kod içinde kullanıcı tarafından verilen test API anahtarı doğrudan `GeminiAnalyzer.java` içinde tanımlıdır. GitHub deposunu public yapıyorsanız gerçek API anahtarınızı kaynak koda koymamanız gerekir.


## Yeni ayarlar
- Site kontrol aralığı: varsayılan **1 dakika**, uygulama içinden değiştirilebilir.
- Gemini analiz aralığı: varsayılan **60 dakika**, uygulama içinden değiştirilebilir.
- Her başarılı fiyat okuması anlık tabloya (son 30 kayıt), günlük TXT dosyasına ve son değer hafızasına kaydedilir.
- Günlük TXT dosyası uygulamanın güvenli dosya alanında `gumus_fiyat_gecmisi.txt` olarak tutulur; uygulama içindeki **TXT DOSYASINI PAYLAŞ** düğmesiyle dışarı aktarılabilir.
- Fiyat parser'ı Alış/Satış etiketlerini öncelikli kullanır ve etiket bulunamazsa kullanıcı tarafından belirtilen kurala göre yüksek fiyatı Alış, düşük fiyatı Satış olarak normalize eder.
