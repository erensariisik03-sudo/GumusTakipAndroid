# Gümüş Takip Uygulaması (Android)

GetirFinans üzerinden anlık XAG (gümüş) fiyatlarını takip eden, belirli TL seviyesi değişimlerinde sesli, titreşimli ve LED destekli bildirim gönderen, arka planda kesintisiz çalışan bir Android uygulamasıdır.

## Özellikler
* **Arka Plan Takibi:** `ForegroundService` ve `WakeLock` desteğiyle uygulama ve cihaz uykudayken bile çalışmaya devam eder.
* **Seviye Alarmları:** Satış fiyatının tam TL seviyesi değiştiğinde yüksek öncelikli (`IMPORTANCE_HIGH`) bildirim tetikler. 60 saniyelik normal güncellemeler ise sessiz yürütülür.
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
