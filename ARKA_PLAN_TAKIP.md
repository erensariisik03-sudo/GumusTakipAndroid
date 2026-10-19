# Arka plan takibi notları

Bu sürüm, kullanıcı takip düğmesiyle etkinleştirdikten sonra Android ön plan hizmeti (foreground service) kullanır; durum bildirimi görünür kalır. Kısmi CPU uyanık kilidi, cihaz ekranı kapalıyken fiyat sorgulama ve dosyaya yazma döngüsünün Doze nedeniyle askıya alınma ihtimalini azaltır. Bu yöntem pil tüketimini artırır.

- Android 6+ için pil optimizasyonu dışında tutma izni istenir.
- Bildirim izni Android 13+ üzerinde istenir.
- `tracking_enabled=true` ise cihaz yeniden başlatıldıktan veya uygulama güncellendikten sonra `BootReceiver` takibi başlatmayı dener.
- Kullanıcı uygulamayı Ayarlar'dan **Zorla durdur** yaparsa Android yeniden başlatma alıcısını ve servis başlangıçlarını engelleyebilir; uygulamayı yeniden açıp takibi başlatmak gerekir.
- Bazı üreticiler (özellikle agresif arka plan yönetimi olan cihazlar) ayrıca uygulama pil kullanımını **Kısıtlanmamış** yapmayı, otomatik başlatma iznini ve son uygulamalar ekranında kilitlemeyi gerektirebilir. Bunlar üreticiye özel ayarlardır; uygulama bunları sessizce açamaz.
- Sürekli 40–60 saniyelik kontrol pil tüketir ve internet/sunucu kesintilerini ortadan kaldırmaz. Android hiçbir üçüncü taraf uygulama için kesintisiz çalışma garantisi vermez.

Derleme: GitHub Actions üzerinden `.github/workflows/build-apk.yml` çalıştırılabilir. Bu ortamda Gradle komutu bulunmadığından yerel APK derlemesi doğrulanamamıştır.
