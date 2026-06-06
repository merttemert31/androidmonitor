# GitHub Actions release signing

Bu belge, NetScope için **keystore oluşturma**, **GitHub secret ekleme** ve **signed release APK/AAB** üretme adımlarını tek tek anlatır.

## 1) Keystore oluştur

### Linux / macOS / Git Bash / WSL
```bash
keytool -genkeypair \
  -v \
  -keystore release.keystore \
  -alias netscope \
  -keyalg RSA \
  -keysize 2048 \
  -validity 3650
```

### Windows PowerShell
```powershell
keytool -genkeypair `
  -v `
  -keystore release.keystore `
  -alias netscope `
  -keyalg RSA `
  -keysize 2048 `
  -validity 3650
```

Bu komut senden şunları ister:
- keystore password
- key password
- ad/organizasyon bilgileri

> Not: `keytool`, JDK ile birlikte gelir.

## 2) Keystore dosyasını Base64'e çevir

### Linux / macOS / Git Bash / WSL
```bash
base64 -w 0 release.keystore > keystore.base64
```

### macOS'ta `-w` yoksa
```bash
base64 release.keystore | tr -d '\n' > keystore.base64
```

### Windows PowerShell
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) | Set-Content keystore.base64
```

## 3) GitHub Secrets ekle
GitHub repo içinde:
- **Settings**
- **Secrets and variables**
- **Actions**
- **New repository secret**

Şu secret'ları oluştur:

### Zorunlu secret'lar
- `ANDROID_SIGNING_KEYSTORE_BASE64`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

### Değerler nasıl doldurulur?
- `ANDROID_SIGNING_KEYSTORE_BASE64` → `keystore.base64` dosyasının tek satırlık içeriği
- `ANDROID_SIGNING_STORE_PASSWORD` → keystore şifresi
- `ANDROID_SIGNING_KEY_ALIAS` → örn. `netscope`
- `ANDROID_SIGNING_KEY_PASSWORD` → alias anahtar şifresi

## 4) Workflow nasıl davranır?
Workflow dosyası:
- secret'lar varsa keystore'u geçici dosyaya decode eder
- Gradle'a şu env'leri verir:
  - `NETSCOPE_SIGNING_STORE_FILE`
  - `NETSCOPE_SIGNING_STORE_PASSWORD`
  - `NETSCOPE_SIGNING_KEY_ALIAS`
  - `NETSCOPE_SIGNING_KEY_PASSWORD`

Sonuç:
- `assembleRelease`
- `bundleRelease`

build'leri **signed** olur.

## 5) Tag ile otomatik release nasıl yapılır?
Workflow `v*` ile başlayan tag push'larında otomatik release üretir.

Örnek:
```bash
git tag v0.2.0
git push origin v0.2.0
```

Bundan sonra GitHub Actions:
- debug APK
- release APK
- release AAB

üretir ve ayrıca bir **GitHub Release** oluşturup şu dosyaları ekler:
- `<tag>-release.apk`
- `<tag>-release.aab`
- `SHA256SUMS.txt`

## 6) Release imzasını nasıl doğrularsın?
Yerelde şu komutlarla kontrol edebilirsin:

```bash
apksigner verify --print-certs app-release.apk
```

veya bundle için build sonrası Play Console yükleme testi yapabilirsin.

## 7) Sık yapılan hatalar

### `Keystore was tampered with, or password was incorrect`
- store password yanlış
- base64 bozuk kopyalandı

### `No key with alias`
- `ANDROID_SIGNING_KEY_ALIAS` yanlış

### release unsigned çıktı
- secret'ların biri eksik
- workflow log'unda signing step'ini kontrol et

## 8) Güvenlik notları
- Keystore'u repoya commit etme
- Secret değerlerini issue/comment içine yapıştırma
- Keystore'u güvenli yedekle; kaybedersen aynı imza ile update yapamazsın
