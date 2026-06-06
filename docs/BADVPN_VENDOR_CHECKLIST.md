# BadVPN vendor checklist + patch plan

Bu belge, `shadowsocks/badvpn` tabanlı gerçek Android `tun2socks` entegrasyonu için pratik kontrol listesidir.

## Hedef upstream
- `third_party/badvpn` → `shadowsocks/badvpn` (`shadowsocks-android` branch önerilir)
- `third_party/libancillary` → `shadowsocks/libancillary` (`shadowsocks-android` branch önerilir)

## Vendor checklist

### A. Kaynakları yerleştir
- [ ] `third_party/badvpn/` klasörünü oluştur
- [ ] `third_party/libancillary/` klasörünü oluştur
- [ ] upstream kaynakları bu klasörlere koy
- [ ] gerekirse commit hash notunu bu dosyanın altına ekle

Önerilen kayıt formatı:

```text
badvpn: <commit>
libancillary: <commit>
source date: <yyyy-mm-dd>
```

### B. Android NDK yardımcı dosyalarını hazırla
Bu projede şu template'ler hazırdır:
- `scripts/ndk/Android.mk`
- `scripts/ndk/Application.mk`
- `scripts/ndk/build-shared-executable.mk`

- [ ] `scripts/prepare_badvpn_vendor.sh` çalıştır
- [ ] `native-build/tun2socks/` altında stage dizinin oluştuğunu kontrol et

### C. Build al
- [ ] `ANDROID_NDK_HOME` tanımlı olsun
- [ ] `scripts/build_badvpn_android.sh` çalıştır
- [ ] `app/src/main/jniLibs/<abi>/libtun2socks.so` dosyalarının oluştuğunu kontrol et

Beklenen ABI'ler:
- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`

### D. Uygulama doğrulama
- [ ] Settings ekranında `badvpn tun2socks dene` aç
- [ ] `SOCKS host` ve `SOCKS port` ayarla
- [ ] capture başlat
- [ ] status kartında gerçek badvpn engine mesajını doğrula
- [ ] TCP isteklerinin artık sadece Java UDP fallback'a düşmediğini test et

## Patch plan

### Patch 1 — Vendor staging
Amaç: upstream ağaçlarını doğrudan app modülü içine gömmek yerine kontrollü bir stage dizini üretmek.

İşlem:
- `third_party/badvpn`
- `third_party/libancillary`
- `scripts/prepare_badvpn_vendor.sh`
- `native-build/tun2socks/`

### Patch 2 — NDK build layout
Amaç: `shadowsocks-android` yaklaşımındaki ndk-build yapısını bu projeye uyarlamak.

İşlem:
- template `Android.mk`
- template `Application.mk`
- template `build-shared-executable.mk`
- çıktı hedefi `app/src/main/jniLibs/<abi>/libtun2socks.so`

### Patch 3 — Process adapter
Amaç: gerçek `libtun2socks.so` binary'sini Android içinde process olarak başlatmak.

İşlem:
- `BadvpnTun2SocksRunner.kt`
- `--sock-path` üzerinden fd gönderimi
- binary stdout/stderr drain etme
- start/stop lifecycle yönetimi

### Patch 4 — Service fallback policy
Amaç: engine başlatma sırasında kontrollü düşüş akışı.

Sıra:
1. gerçek badvpn process
2. JNI scaffold
3. Java UDP fallback

### Patch 5 — Debug ve bakım
Öneri:
- native çıktılarını logcat'e bridge et
- build sonrası `sha256` raporu üret
- kullanılan upstream commit hash'ini `docs/VENDORED_VERSIONS.md` içinde sakla

## Sık görülen problemler

### 1. `libtun2socks.so` hiç oluşmuyor
- stage dizinindeki `Android.mk` yolunu kontrol et
- `third_party/libancillary` eksik olabilir
- NDK yolu yanlış olabilir

### 2. Process başlıyor ama TUN fd gönderilemiyor
- `LocalSocket` yolu oluşmuş mu kontrol et
- SELinux / dosya yolu izinlerini kontrol et
- socket timeout'u artırmayı düşün

### 3. Engine çalışıyor ama trafik yok
- SOCKS endpoint gerçekten çalışıyor mu kontrol et
- `VpnService.protect()` gereken başka soketler var mı bak
- route/DNS davranışını doğrula

## Sonraki patchler
- native log forwarding
- udpgw desteğini settings üzerinden aç/kapat
- session bazlı engine health kayıtları
