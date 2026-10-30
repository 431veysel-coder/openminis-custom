# MinisPool (OpenMinis Custom) - Master Development Roadmap

> **Hedef:** Telefonun içinde tam otonom, kendi kendini geliştiren, derleyen, test eden, dağıtan bir AI ajan ekosistemi.
> **Marka:** MinisPool (paket: `com.minispool.app`)

---

## 📊 Genel Durum Özeti

| Bileşen | Durum | Not |
|---------|-------|-----|
| **Temel Uygulama (OpenMinis Fork)** | ✅ Çalışıyor | Commit `9d4f16b` - Son başarılı build |
| **GitHub Actions CI/CD** | ⚠️ Altyapı Sorunları | NDK indirme hataları, runner bozulmaları |
| **Linux Sandbox (PRoot/Alpine)** | 🔴 Kritik Hata | "Shell sandbox is dead" - AŞAMA 0 |
| **ML Kit Çeviri (Phase 1.2)** | 🟡 Geliştirme Aşamasında | Derleme hataları devam ediyor |
| **GeckoView / Browser** | ❌ Yok | AŞAMA 2 |
| **Multi-Agent Orchestration** | ❌ Yok | AŞAMA 3 |
| **Build Station (Self-Compiling)** | ❌ Yok | AŞAMA 4 |

---

## 🚨 AŞAMA 0: Acil Müdahale - Sandbox Canlandırma (Kritik)

### Sorun
> "Shell sandbox is dead" - Alpine minirootfs yüklenemiyor, PROOT bağlanamıyor.

### Kök Neden Analizi
1. `src/android/app/src/main/cpp/pty_bridge.c` - PTY köprüsü
2. `src/android/app/src/main/java/com/openminis/app/sandbox/` - Sandbox başlatma
3. `deps/build_rclone_android.sh` ve `deps/proot` - Native bağımlılıklar
4. `assets/alpine-minirootfs.tar.gz` - Eksik veya bozuk

### Yapılacaklar
| # | Görev | Dosya/Klasör | Durum |
|---|-------|--------------|-------|
| 0.1 | Alpine rootfs indirme/doğrulama scripti | `scripts/fetch_alpine_rootfs.sh` | ⏳ |
| 0.2 | PRoot binary'sini assets'tan çıkarma ve çalıştırma izni | `src/android/app/src/main/jniLibs/` | ⏳ |
| 0.3 | PTY bridge (pty_bridge.c) - terminal akışı düzeltme | `src/android/app/src/main/cpp/pty_bridge.c` | ⏳ |
| 0.4 | Sandbox başlatma sırası: rootfs → proot → shell → health check | `SandboxManager.kt` | ⏳ |
| 0.5 | Timeout/retry mekanizması + kullanıcıya hata bildirimi | `SandboxHealthMonitor.kt` | ⏳ |
| 0.6 | **Test:** `adb shell` ile sandbox'a girip `apk add python3` çalıştırma | - | ⏳ |

### Kabul Kriterleri
- [ ] Uygulama açıldığında "Linux sandbox hazır" mesajı gelir
- [ ] Terminal sekmesinde `apk add python3` çalışır
- [ ] `python3 -c "print('hello')"` çıktısı alınır

---

## 🎨 AŞAMA 1: Arayüz (UI) ve Kullanıcı Deneyimi

### 1.1 Sağ Üst "3 Nokta" → Görev Paneli (Dashboard)
| # | Özellik | Açıklama |
|---|---------|----------|
| 1.1.1 | Subagent listesi | Çalışan/ bekleyen/ hata veren alt ajanlar |
| 1.1.2 | Görev durumu | Aktif görev, ilerleme %, token kullanımı |
| 1.1.3 | Sistem metrikleri | RAM, CPU, disk, sandbox durumu |
| 1.1.4 | Canlı log akışı | `tail -f` tarzı anlık log görüntüleme |

### 1.2 Derin Düşünme (Deep Thinking) Kutusu - Çeviri & Özet
| # | Özellik | Teknik Detay |
|---|---------|--------------|
| 1.2.1 | [TR Çevir] butonu | ML Kit on-device çeviri, kod bloklarını koruyarak |
| 1.2.2 | [Özetle] butonu | 3-5 maddelik özet, karakter/cümle istatistiği |
| 1.2.3 | Otomatik mod | Ayarlar: "Streaming bitince otomatik çevir/özetle" |
| 1.2.4 | Ayarlar entegrasyonu | `SettingsScreen` → "Düşünce Geliştirmeleri" bölümü |

### 1.3 Sohbet Bazlı Liste Panelleri (Yan Panel)
| Sekme | İçerik |
|-------|--------|
| 📋 Yapılandırma | Model ayarları, sistem promptu, araç izinleri |
| ✅ Görevler | O sohbette oluşturulan/bitirilen görev listesi |
| 👷 Çalışanlar | O sohbette aktif/pasif subagent'lar |
| 📁 Projeler | O sohbette oluşturulan dosyalar, klasörler, çıktılar |

---

## 🌐 AŞAMA 2: Tam Otonom Tarayıcı (GeckoView Entegrasyonu)

### Mimari
```
BrowserTool (Skill) → GeckoView Engine → BrowserSession → DOM/JS API
```

### Bileşenler
| # | Bileşen | Sorumluluk |
|---|---------|------------|
| 2.1 | `GeckoViewWrapper` | Motor başlatma, profil yönetimi, headless mod |
| 2.2 | `BrowserSession` | Sekme yönetimi, navigation, history |
| 2.3 | `DOMController` | `evaluateJavaScript`, `querySelector`, `click`, `type` |
| 2.4 | `NetworkInterceptor` | Request/response loglama, cookie yönetimi |
| 2.5 | `ScreenshotCapture` | Viewport/full-page screenshot → base64/file |
| 2.6 | `BrowserSkill` | Agent'a sunulan araç: `browse(url)`, `click(selector)`, `extract(selector)` |

### Agent API (Skill Interface)
```kotlin
// Agent çağırır:
val result = browserSkill.execute("browse", mapOf("url" to "https://example.com"))
val data = browserSkill.execute("extract", mapOf("selector" to ".price"))
```

---

## 🧠 AŞAMA 3: Görev Orkestrasyonu - Çoklu Ajan Mimarisi

### Hiyerarşi
```
Orchestrator (Beyin)
├── Planner (Planlama)
│   ├── TaskDecomposer (Görev bölme)
│   ├── DependencyGraph (Bağımlılık grafiği)
│   └── Scheduler (Zamanlama)
├── Dispatcher (Dağıtıcı)
│   ├── WorkerPool (Çalışan havuzu)
│   ├── LoadBalancer (Yük dengeleme)
│   └── PriorityQueue (Öncelik kuyruğu)
├── Workers (Alt Ajanlar)
│   ├── CoderAgent (Kod yazma/derleme)
│   ├── ResearcherAgent (Web araştırması)
│   ├── TesterAgent (Test yazma/çalıştırma)
│   ├── ReviewerAgent (Kod inceleme)
│   └── BuilderAgent (APK üretme)
├── Guard (Bekçi)
│   ├── SecurityScanner (Güvenlik tarama)
│   ├── PolicyEnforcer (Kural zorlayıcı)
│   └── QualityGate (Kalite kapısı)
└── Memory (Hafıza)
    ├── ShortTerm (Oturum içi)
    ├── LongTerm (Kalıcı - GLOBAL.md)
    └── Episodic (Görev geçmişi)
```

### Görev Akışı
```
User Request → Orchestrator
    → Planner: TaskGraph oluşturur
    → Dispatcher: Worker'lara dağıtır (bağımlılık sırasına göre)
    → Workers: Paralel/seri çalışır, sonuçları Memory'ye yazar
    → Guard: Çıktıları doğrular (test geçti mi? güvenli mi?)
    → Orchestrator: Sonuçları birleştirir, kullanıcıya sunar
```

---

## 🛠️ AŞAMA 4: Dahili Derleme İstasyonu (Build Station)

### Hedef
> Agent, kendi kendine Android uygulaması üretebilsin.

### Bileşenler
| # | Bileşen | Araçlar |
|---|---------|---------|
| 4.1 | Paket Yöneticisi | `apk` (Alpine), `apt` (proot-distro), `npm`, `pip`, `cargo` |
| 4.2 | Derleme Dilleri | OpenJDK 17/21, Python 3.11, Node.js 20, Go 1.22, Rust |
| 4.3 | Build Sistemleri | Gradle 8.x, Maven, Make, CMake, Ninja, Bazel |
| 4.4 | Android SDK | cmdline-tools, platform-tools, build-tools 34, NDK 27 |
| 4.5 | Terminal Çoklu Oturum | `tmux` / `screen` session yönetimi |
| 4.6 | Log & Hata Analizi | `logcat`, derleme hatası parsing, auto-fix önerisi |

### Self-Correction Döngüsü
```
Agent yazıyor → ./gradlew assembleDebug
    → Hata mı?
        → Evet: Hata logunu oku → Analiz et → Düzelt → Tekrar dene (max 5 iterasyon)
        → Hayır: APK hazır → Test et → İmzala → Dağıt
```

---

## 📅 Uygulama Sırası ve Milestones

| Sprint | Aşama | Teslim Edilebilirler | Süre |
|--------|-------|----------------------|------|
| **0** | AŞAMA 0 | Sandbox canlı, terminal çalışıyor | 1-2 gün |
| **1** | AŞAMA 1.2 | Deep Thinking: Çeviri + Özet butonları | 2-3 gün |
| **2** | AŞAMA 1.1 + 1.3 | Dashboard + Yan Paneller | 3-4 gün |
| **3** | AŞAMA 2 | GeckoView BrowserSkill çalışıyor | 5-7 gün |
| **4** | AŞAMA 3 | Orchestrator + 2 Worker (Coder, Researcher) | 7-10 gün |
| **5** | AŞAMA 4 | Self-compiling APK demo | 5-7 gün |

---

## 🔧 Teknik Borçlanma ve Riskler

| Risk | Etki | Azaltma |
|------|------|---------|
| GitHub Actions NDK bozulması | CI/CD kesintisi | Local build + cache, self-hosted runner |
| GeckoView boyutu (~50MB) | APK büyümesi | Dynamic feature module, on-demand indirme |
| PRoot/Alpine ARM64 uyumu | Sandbox çökmesi | QEMU-user fallback, rootfs doğrulama |
| Multi-agent token maliyeti | Yüksek maliyet | Local model (Ollama/llama.cpp) öncelikli |
| Sandbox güvenliği | Sistem sızdırma | Seccomp profil, capability drop, namespace隔离 |

---

## 📝 Notlar

- **Mevcut Çalışan Baz:** Commit `9d4f16b` (Build #40928727479)
- **Paket Adı Değişikliği:** `com.openminis.app.custom` → `com.minispool.app` (Rebranding Phase 5)
- **Marka:** "MinisPool" - Tüm UI, dokümantasyon, release notlarında kullanılacak
- **Öncelik Sırası:** 0 → 1.2 → 1.1/1.3 → 2 → 3 → 4

---

*Son Güncelleme: 2026-10-30*
*Yazar: Minis AI Agent*