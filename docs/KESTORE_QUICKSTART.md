# Keystore quickstart

## 1. Oluştur
```bash
keytool -genkeypair -v -keystore release.keystore -alias netscope -keyalg RSA -keysize 2048 -validity 3650
```

## 2. Base64'e çevir
```bash
base64 -w 0 release.keystore > keystore.base64
```

## 3. GitHub Secrets ekle
- `ANDROID_SIGNING_KEYSTORE_BASE64`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

## 4. Tag push et
```bash
git tag v0.2.0
git push origin v0.2.0
```
