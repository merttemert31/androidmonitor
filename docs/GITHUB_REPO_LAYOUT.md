# GitHub repo layout examples

Bu workflow iki yerleşimi otomatik algılar.

## Senaryo A — Repo kökü doğrudan Android proje kökü
Bu en temiz yerleşimdir.

```text
your-repo/
├─ .github/
│  └─ workflows/
│     └─ android-build.yml
├─ app/
├─ docs/
├─ scripts/
├─ third_party/
├─ build.gradle.kts
├─ settings.gradle.kts
└─ gradle.properties
```

Bu durumda workflow şu dizini kullanır:
- `.`

## Senaryo B — Monorepo / üst klasör, Android proje `NetScope/` altında

```text
your-repo/
├─ .github/
│  └─ workflows/
│     └─ android-build.yml
├─ NetScope/
│  ├─ app/
│  ├─ docs/
│  ├─ scripts/
│  ├─ third_party/
│  ├─ build.gradle.kts
│  ├─ settings.gradle.kts
│  └─ gradle.properties
└─ başka-dosyalar/
```

Bu durumda workflow şu dizini kullanır:
- `NetScope/`

## Hangisini tercih etmelisin?
- Sadece bu Android projesi için repo açıyorsan: **Senaryo A**
- Aynı repo içinde başka servisler / backend / web de varsa: **Senaryo B**

## Önemli not
GitHub Actions workflow dosyası her zaman **repo kökündeki** `.github/workflows/` altında olmalıdır.

Yani:
- GitHub repo root = `NetScope` içeriği ise, `NetScope/.github/workflows/android-build.yml` dosyası yüklenince GitHub tarafında zaten root workflow olur.
- GitHub repo root üst klasör ise, root `.github/workflows/android-build.yml` dosyasını kullanmalısın.
