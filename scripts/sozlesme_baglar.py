"""Sözleşmeler arası bağımlılık grafiği — tek kaynak.

24 sözleşme bağımsız değil. Bazıları onsuz **anlamsız**, bazıları onsuz
**yanlış sonuç** üretir, bazıları yalnız birlikte anlam kazanır.

Üç bağ türü:

  ZORUNLU (→)   A açıldıysa B de açık olmalı. Yoksa A'nın kaydı eksiktir.
  TAMAMLAYICI (⇄) A ve B birbirini tamamlar; biri olmadan diğeri yarım kalır.
  ÇELİŞKİ (✗)   A ve B aynı işte açıksa bir karar verilmesi ZORUNLU; aksi
                halde ikisi zıt şey söyler.

Neden gerekli: `S` (sürüm) kaydı `V` (veri) olmadan açılırsa göç test edilmemiş
bir APK yayınlanır. `A` (ayar) `Y`ye bağlıdır: anahtar açıldıysa "ayar satırı"
işi yapılacaklar listesine girer, yoksa özellik kullanıcıdan saklanmış olur.
Bugün bu bağlar kafada; yazılı olduğunda `denetle` sorabilir.
"""

#: A → B: A açıksa B de açık olmalı (B olmadan A eksik)
ZORUNLU = {
    "S": ["K", "V", "R", "IZN"],   # sürüm: karar + veri + risk + izin
    "V": ["T", "K"],             # veri: göç testi + karar (yıkıcıysa)
    "A": ["Y"],                  # ayar: satır/eksik kaydı
    "F": ["T"],                  # geri bildirim: kapıyı sınayan test
    "P": ["C"],                  # kod: yazma kilidi
    "D": ["P"],                  # derleme: kod kaydı olmadan iddia havada
    "T": ["P"],                  # test: hangi kod değiştiği belli olmalı
    "UY": ["OLC"],                  # arayüz: ölçülebilir metrik
    "IZN": ["E"],                  # izin: artifact ömrü/saklama
    "N": ["Z"],                  # nakil: bütçe
    "M": ["G"],                  # mesaj: görev sınırı olmadan kapsam belirsiz
}

#: A ⇄ B: biri olmadan diğeri yarım
TAMAMLAYICI = {
    "G": ["M", "P"],
    "K": ["R"],
    "OLC": ["UY"],
    "E": ["D"],
    "B": ["O"],
    "Z": ["N"],
    "SH": ["L"],
}

#: A ✗ B: aynı işte çelişki — karar (K) zorunlu
CELISKI = {
    "R": ["Z"],   # "yapmayacağız" ile "bütçeyi artır" çelişebilir
    "S": ["E"],   # yayın kalıcı, artifact budaması geçici
    "H": ["IZN"],   # hatırlama sır taşımamalı; izin sır taşır
}

#: Bir iş kademesi açıldığında otomatik açılan asgari küme
KADEME_ASGARI = {
    # C, K0'a bilerek girdi: P→C zorunlu bağı var ve kilit ucuz,
    # çakışma pahalı. İki oturum aynı ağaçta yazıyor.
    "K0": ["G", "P", "C", "M"],
    "K1": ["G", "P", "C", "T", "D", "M"],
    "K2": ["G", "P", "C", "T", "D", "M", "K", "S", "V", "IZN", "R", "Z"],
}

#: Bağımlılık zincirinin neden var olduğu — her satır bir gerçek kayıp
GEREKCE = {
    "S→K": "Onaysız yayın: 1 kez oldu, imza/anahtar hatasıyla geri dönüşsüz",
    "S→V": "Göç test edilmeden yayın: kullanıcı verisi sessizce bozulur",
    "S→R": "Yayının freni yazılı olmalı; 'onaysız yayın' kırmızı çizgi",
    "S→IZN": "Yayın izni (contents: write) en dar işte olmalı",
    "V→K": "Yıkıcı göç (kolon düşürme/veri dönüşümü) karar kaydı ister",
    "G⇄P": "Görev sınırı kodun kapsamını, kod görevin sınırını sınar",
    "V→T": "14→15 göçü test edilmedi; şema dosyası 3 sürüm boyunca yoktu",
    "A→Y": "COUNCIL anahtarı vardı, ayar satırı yoktu → kullanıcı kurtaramadı",
    "F→T": "Hata düzeltildi ama kapı eklenmedi → aynı hata tekrar eder",
    "P→C": "İki oturum aynı ağaçta; kilit olmadan yazma birbirini ezer",
    "D→P": "'Derlendi' iddiası hangi kod kaydına ait belli değilse doğrulanamaz",
    "T→P": "Test hangi davranışı sabitlediği bilinmeden sayı olmaktan çıkmaz",
    "UY→OLC": "Chip 96dp/10sp sınırı ölçülmeden 'küçük oldu' denemez",
    "IZN→E": "Artifact süresi belirsizse sır ne kadar yayında kalacağı bilinmez",
    "N→Z": "Bütçesiz devir: 16 dk timeout, çıktı yok, iş yeniden",
    "M→G": "Görev sınırı yoksa cevabın kapsamı da belirsiz",
    "G⇄M": "Görev sınırı cevabın dürüstlüğünü, cevap görevin kapsamını sınar",
    "K⇄R": "Karar riski, risk kararı gerektirir",
    "R✗Z": "'Yapmayacağız' ile 'bütçeyi artır' çelişirse K kaydı zorunlu",
    "S✗E": "Yayın kalıcı, budama geçici — aynı işte ikisi açıksa karar gerekir",
    "H✗IZN": "Hatırlamaya sır yazılmamalı; İzin sır taşır → çelişki kontrolü",
    "OLC⇄UY": "Ölçüm göstergeyi, gösterge ölçümü haklı çıkarır",
    "E⇄D": "Artifact ömrü derleme çıktısına bağlı",
    "B⇄O": "Bağımlılık ortama, ortam bağımlılığa bağlı (araç sürümü)",
    "Z⇄N": "Bütçe devri, devir bütçeyi sınar",
    "SH⇄L": "Kabuk komutu terminal oturumunda yaşar",
}
