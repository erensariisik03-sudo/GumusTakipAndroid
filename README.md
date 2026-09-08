# Gümüş Takip

Standalone Android uygulaması. GetirFinans XAG fiyatlarını doğrudan çeker; iki fiyatın büyüğü **ALIŞ**, küçüğü **SATIŞ** kabul edilir.

## Özellikler
- Site kontrol aralığı saniye cinsinden. 40 saniye ve üzeri önerilir.
- Gemini API key uygulama açılışında istenir ve uygulamanın kendi özel depolamasında tutulur.
- `API KEY YOK` düğmesi Google AI Studio API key sayfasını açar.
- `bot.py`, Flask, Termux veya localhost bağımlılığı yoktur.
- Günlük fiyat geçmişi uygulama sandboxındaki `gumus_fiyat_gecmisi.txt` dosyasına kaydedilir.
- Takvim günü değişince günlük fiyat/AI verileri sıfırlanır; portföy ayarları korunur.
- Gemini bildirimleri genişletilebilir (BigTextStyle).
- Fiyat artışı bildirim LED'i yeşil, düşüş kırmızı, Gemini analizi sarıdır.
