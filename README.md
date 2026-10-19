# Gümüş Takip

Android uygulaması GetirFinans XAG fiyatlarını izler ve geçmiş fiyat örneklerine dayalı açıklanabilir, kural tabanlı sinyaller üretir. Gemini/AI analizi ve API anahtarı kaldırılmıştır.

## Özellikler
- Site kontrol aralığı saniye cinsinden; en az 40 saniye.
- Fiyat geçmişi ve günlük TXT kaydı.
- 1 TL ve üzeri yönlü hareketler ile kısa/orta dönem teyidine göre bekle/alım/satış değerlendirmesi.
- Parçalı satış önerisi en fazla yarım veya dörtte bir; otomatik işlem yok.
- Alım önerisi portföy değerinin en fazla %25'i kadar bütçe sınırıyla sunulur. Bu, gerçek nakit bakiyesini doğrulamaz; kullanıcı işlem öncesi nakdini kontrol etmelidir.
- Sinyal cevapsız kalırsa 15 dakikada bir hatırlatma; cevap verildiğinde tekrar bildirim için bekleme süresi.
- Kullanıcı gerçekten yaptığı işlemi miktar ve fiyatla kaydeder. Ortalama maliyet, elde kalan miktar, gerçekleşmiş ve eldeki pozisyon kâr/zararı izlenir.
- Fiyat sağlayıcısının alış/satış değerleri kullanılır; gerçek kuyumcu/banka makası ve ücretler sonucu değiştirebilir.

## Not
Bu algoritma sezgisel bir kural sistemidir; geçmiş fiyatlar geleceği garanti etmez ve kâr garantisi vermez. Sinyaller yatırım tavsiyesi veya otomatik emir değildir.
