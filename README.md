# Gümüş Takip — Android

Bu proje Java + Android Gradle Plugin tabanlı bağımsız bir Android uygulamasıdır.

## İşlevler
- GetirFinans sayfasından XAG/gümüş alış-satış fiyatını HTTPS üzerinden almaya çalışır.
- 60 saniyede bir fiyat kontrolü yapar.
- Gram ve alış maliyetine göre portföy değeri ile kâr/zararı hesaplar.
- Satış fiyatının tam TL seviyesi değiştiğinde bildirim üretir.
- Arka planda foreground service ile çalışır.
- Termux veya Python gerektirmez.

## APK oluşturma
1. Android Studio'da bu klasörü açın.
2. Android SDK'nın kurulu olduğundan emin olun (compileSdk 33).
3. Gradle senkronizasyonunu çalıştırın.
4. `Build > Build APK(s)` seçin.
5. APK genellikle `app/build/outputs/apk/debug/app-debug.apk` altında oluşur.

`local.properties` makineye özel SDK yolu içermeyecek şekilde temizlenmiştir.
