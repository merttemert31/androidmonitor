# NetScope

Android için Kotlin + Jetpack Compose ile hazırlanmış, **Wireshark-benzeri ağ analiz uygulaması** prototipi.

## Son güncellemeler
- GitHub Actions ile debug + release APK/AAB build workflow'u
- opsiyonel signed release build desteği
- repo kökü `.` veya `NetScope/` olduğunda otomatik proje algılama
- `shadowsocks/badvpn` tabanlı vendor planı ve build checklist dokümanı
- ndk-build uyumlu `badvpn` stage scriptleri
- gelişmiş arama:
  - `ip:`
  - `src:`
  - `dst:`
  - `port:`
  - `proto:`
  - `dir:`
  - `from:`
  - `to:`
- session tarih filtreleme
- session tarih seçici UI
- session export / paylaşım
- packet detail ekranında daha ayrıntılı header decode
- TLS certificate ve DNS answer decode iyileştirmeleri
- Room tarafında session arşivi ve indeksler

## Yeni dokümanlar ve scriptler
- `.github/workflows/android-build.yml`
- `docs/GITHUB_REPO_LAYOUT.md`
- `docs/GITHUB_ACTIONS_SIGNING.md`
- `docs/GITHUB_TAG_RELEASES.md`
- `docs/KESTORE_QUICKSTART.md`
- `docs/GITHUB_RELEASE_CHECKLIST.md`
- `docs/BADVPN_VENDOR_CHECKLIST.md`
- `docs/BADVPN_EXPECTED_TREE.md`
- `scripts/vendor_badvpn_pinned.sh`
- `scripts/prepare_badvpn_vendor.sh`
- `scripts/build_badvpn_android.sh`
- `scripts/ndk/Android.mk`
- `scripts/ndk/Application.mk`
- `scripts/ndk/build-shared-executable.mk`

## Vendor yapısı
Beklenen third-party dizinleri:
- `third_party/badvpn/`
- `third_party/libancillary/`
- `third_party/VERSIONS.env`
- `third_party/VERSIONS.example.env`

Commit-pin’li vendor akışı:
1. `third_party/VERSIONS.example.env` dosyasını incele
2. `third_party/VERSIONS.env` içindeki commit hash’lerini doldur
3. `scripts/vendor_badvpn_pinned.sh` çalıştır
4. `scripts/build_badvpn_android.sh` çalıştır
5. oluşan ABI dosyaları `app/src/main/jniLibs/<abi>/libtun2socks.so` altına kopyalanır

## Arama örnekleri
- `ip:8.8.8.8`
- `port:53 proto:udp`
- `dir:out dns`
- `from:2026-06-01 to:2026-06-05`
- `src:192.168.1.10`

## Session arşivi
Room artık iki tablo kullanır:
- `captured_packets`
- `capture_sessions`

Her capture başlangıcında session kaydı açılır ve paketler session ile ilişkilendirilir.

## Packet detail decode
Detay ekranı artık temel olarak şu katmanları ayırır:
- IP header
- TCP / UDP / ICMP header
- DNS temel alanları
- HTTP first-line preview
- raw hex

## GitHub Actions
- Branch push ile debug/release build alınır
- `v*` tag push ile otomatik GitHub Release oluşturulur
- Workflow repo kökü `.` veya `NetScope/` olduğunda proje dizinini otomatik algılar

İlgili belgeler:
- `docs/GITHUB_REPO_LAYOUT.md`
- `docs/GITHUB_ACTIONS_SIGNING.md`
- `docs/GITHUB_TAG_RELEASES.md`
- `docs/KESTORE_QUICKSTART.md`
- `docs/GITHUB_RELEASE_CHECKLIST.md`

## Açma
Android Studio ile `NetScope` klasörünü açıp Gradle sync yap.
