# OpenMinis Custom - Otomasyon & CI/CD Rehberi

Bu belge, **OpenMinis Custom** projesinin GitHub Actions, GitHub Codespaces, otomatik sürüm yönetimi ve CI/CD süreçlerini açıklar.

---

## 🚀 1. GitHub Actions Otomatik APK Derleme

Depoya her kod gönderildiğinde (`push` veya `pull_request`) ve yeni bir tag/sürüm oluşturulduğunda (`v1.0.0` vb.), GitHub Actions otomatik olarak çalışır.

### Yapılan İşlemler:
1. Repository ve submodule'leri klonlar.
2. Java 17 (Temurin) ortamını kurar.
3. Android Gradle bağımlılıklarını önbelleğe alır (cache).
4. **Debug APK** (`app-debug.apk`) ve **Release APK** (`app-release-unsigned.apk`) derler.
5. Derlenen APK dosyalarını GitHub Actions Artifacts bölümüne yükler.
6. Yeni bir `v*` tag'i gönderildiyse otomatik olarak **GitHub Release** oluşturur ve APK'ları release varlığı olarak ekler.

---

## 💻 2. GitHub Codespace Entegrasyonu

Projede `.devcontainer/devcontainer.json` yapılandırması mevcuttur. GitHub web arayüzünden tek tıkla tam donanımlı bir bulut geliştirme ortamı açabilirsiniz.

### Codespace Özellikleri:
- **Java 17 & Gradle** önceden kurulu gelir.
- **Node.js & Python 3** desteği.
- VS Code eklentileri (Android/Java Pack, Python, Go, YAML) hazır yüklenir.

---

## 🏷️ 3. Otomatik Versiyon & Sürüm Yönetimi

Sürüm numaralarını kolayca güncellemek için `scripts/bump_version.py` betiği mevcuttur.

### Kullanım:
```bash
# Patch sürümünü artırır (ör. 1.0.0 -> 1.0.1, versionCode += 1)
python3 scripts/bump_version.py patch

# Minor sürümünü artırır (ör. 1.0.1 -> 1.1.0)
python3 scripts/bump_version.py minor

# Major sürümünü artırır (ör. 1.1.0 -> 2.0.0)
python3 scripts/bump_version.py major
```

### Git Tag ve Otomatik Sürüm Yayınlama:
```bash
python3 scripts/bump_version.py patch
git add src/android/app/build.gradle.kts
git commit -m "chore: bump version to v1.0.1"
git tag v1.0.1
git push origin main --tags
```
Tag gönderildiğinde GitHub Actions devreye girer ve otomatik **Release** yayını yapar.

---

## 📁 4. Proje Yapısı ve Dosya Kontrolü

- `.github/workflows/android-build.yml`: CI/CD otomasyon dosyası.
- `.devcontainer/devcontainer.json`: Codespaces ortam yapılandırması.
- `scripts/bump_version.py`: Versiyon artırma betiği.
- `AUTOMATION.md`: Otomasyon rehberi (bu dosya).
