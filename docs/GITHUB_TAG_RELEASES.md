# Tag-based automatic releases

Bu workflow, `v*` ile başlayan tag push'larında otomatik GitHub Release oluşturur.

## Örnek sürüm akışı

### 1. Kodunu commit et
```bash
git add .
git commit -m "Prepare v0.2.0"
git push origin main
```

### 2. Tag oluştur
```bash
git tag v0.2.0
git push origin v0.2.0
```

### 3. GitHub Actions ne yapar?
- Android build çalışır
- release APK üretir
- release AAB üretir
- dosyaları bir GitHub Release içine ekler
- SHA256 checksum dosyası da üretir

## Release sayfasında beklenen dosyalar
- `v0.2.0-release.apk`
- `v0.2.0-release.aab`
- `SHA256SUMS.txt`

## İsimlendirme önerisi
Tag formatı:
- `v0.1.0`
- `v0.2.0`
- `v1.0.0`

Öneri: semver kullan.

## Ön koşullar
- workflow dosyası repo kökünde aktif olmalı
- release build başarılı olmalı
- signed release istiyorsan signing secret'ları girilmiş olmalı
