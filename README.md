# Gümüş Takip Android

GetirFinans XAG sayfasından gümüşün iki fiyatını okur. Uygulamada **iki geçerli fiyat arasındaki büyük değer ALIŞ, küçük değer SATIŞ** kabul edilir.

## Mimari
- Android uygulaması GetirFinans'a doğrudan bağlanır.
- Gemini API'ye doğrudan bağlanır; `bot.py`, Flask, Termux veya localhost gerekmez.
- Gemini modeli API'nin `models.list` çıktısından dinamik keşfedilir; önce Flash modelleri denenir.
- 429 ve geçici 5xx hatalarında yeniden deneme/backoff yapılır; model değişse bile aynı günün veri bağlamı korunur.

## Günlük veri
Fiyat geçmişi `context.getFilesDir()` altındaki Android uygulama sandboxında `gumus_fiyat_gecmisi.txt` olarak tutulur. `SharedPreferences` içindeki anlık kayıtlar da aynı uygulama sandboxındaki uygulama verisidir.

Takvim günü değiştiğinde:
- anlık fiyat tablosu temizlenir,
- günlük fiyat TXT dosyası silinip yeniden oluşturulur,
- son Gemini cevabı temizlenir.

Portföy miktarı/maliyet ve zamanlama ayarları korunur.

## Paylaşma
TXT dosyası uygulama dışına yalnızca Android `FileProvider` üzerinden kullanıcı paylaşımıyla çıkarılabilir.

## Gerekli izinler
Sadece ağ ve uygulamanın ihtiyaç duyduğu Android servis/bildirim izinleri kullanılır. Ortak depolama için `READ/WRITE_EXTERNAL_STORAGE` veya Termux depolama izni gerekmez.

## API anahtarı
`GeminiAnalyzer.java` içinde mevcut bot.py'deki API anahtarı kullanılmıştır. Kaynak kodu paylaşacaksan bu anahtarı yenilemen ve güvenli bir yapılandırmaya taşıman önerilir.
