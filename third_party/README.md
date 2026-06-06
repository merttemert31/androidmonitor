# third_party

Bu klasör, gerçek tun2socks engine kaynaklarını vendoring için ayrılmıştır.

Önerilen kaynak:
- `shadowsocks/badvpn`

Beklenen yerleşim:
- `third_party/badvpn/`

Sonra Android ABI build için şu script kullanılabilir:
- `scripts/build_badvpn_android.sh`

Çıktı hedefi:
- `app/src/main/jniLibs/<abi>/libtun2socks.so`

Not:
Bu workspace içinde upstream kaynağı otomatik indirmedim; çünkü üçüncü taraf repo boyutu büyük ve lisans/versiyon seçimi kullanıcı kontrolünde olmalı.
