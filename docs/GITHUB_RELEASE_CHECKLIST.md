# GitHub release checklist

## Repo yerleşimi
- [ ] Repo kökünde `.github/workflows/android-build.yml` var
- [ ] Android proje kökü ya repo root, ya da `NetScope/`

## Signing
- [ ] `ANDROID_SIGNING_KEYSTORE_BASE64` eklendi
- [ ] `ANDROID_SIGNING_STORE_PASSWORD` eklendi
- [ ] `ANDROID_SIGNING_KEY_ALIAS` eklendi
- [ ] `ANDROID_SIGNING_KEY_PASSWORD` eklendi

## Vendor (opsiyonel ama tam tun2socks için gerekli)
- [ ] `third_party/VERSIONS.env` commit pinleri dolu
- [ ] `vendor_badvpn_pinned.sh` çalışıyor

## Release
- [ ] `git tag vX.Y.Z`
- [ ] `git push origin vX.Y.Z`
- [ ] GitHub Release sayfasında APK/AAB görünüyor
