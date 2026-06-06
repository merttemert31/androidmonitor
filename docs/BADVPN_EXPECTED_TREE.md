# Expected `third_party/badvpn` tree

Aşağıdaki ağaç, `scripts/prepare_badvpn_vendor.sh` ve `scripts/build_badvpn_android.sh` akışının beklediği vendor yerleşimini örnekler.

```text
NetScope/
├─ third_party/
│  ├─ VERSIONS.env
│  ├─ badvpn/
│  │  ├─ base/
│  │  │  ├─ BLog.c
│  │  │  ├─ BLog_syslog.c
│  │  │  ├─ BPending.c
│  │  │  └─ DebugObject.c
│  │  ├─ flow/
│  │  ├─ flowextra/
│  │  ├─ lwip/
│  │  │  ├─ custom/
│  │  │  └─ src/
│  │  │     ├─ core/
│  │  │     └─ include/
│  │  ├─ socks_udp_client/
│  │  ├─ socksclient/
│  │  ├─ system/
│  │  ├─ tun2socks/
│  │  │  ├─ tun2socks.c
│  │  │  └─ SocksUdpGwClient.c
│  │  ├─ tuntap/
│  │  └─ udpgw_client/
│  └─ libancillary/
│     ├─ fd_recv.c
│     └─ fd_send.c
├─ scripts/
│  ├─ vendor_badvpn_pinned.sh
│  ├─ prepare_badvpn_vendor.sh
│  ├─ build_badvpn_android.sh
│  └─ ndk/
│     ├─ Android.mk
│     ├─ Application.mk
│     └─ build-shared-executable.mk
└─ native-build/
   └─ tun2socks/
      ├─ Android.mk
      ├─ Application.mk
      ├─ build-shared-executable.mk
      ├─ badvpn -> ../../third_party/badvpn
      └─ libancillary -> ../../third_party/libancillary
```

## Minimum gerekli badvpn alt klasörleri
`Android.mk` içinde referans verilen kaynaklar nedeniyle en az şu dizinlerin upstream tree içinde bulunması gerekir:
- `base/`
- `flow/`
- `flowextra/`
- `lwip/`
- `socks_udp_client/`
- `socksclient/`
- `system/`
- `tun2socks/`
- `tuntap/`
- `udpgw_client/`

## Kontrol listesi
- [ ] `third_party/badvpn/tun2socks/tun2socks.c` var
- [ ] `third_party/badvpn/lwip/src/include` var
- [ ] `third_party/libancillary/fd_recv.c` var
- [ ] `third_party/libancillary/fd_send.c` var
- [ ] `third_party/VERSIONS.env` içinde commit pinleri dolu
- [ ] `scripts/vendor_badvpn_pinned.sh` çalıştı
- [ ] `scripts/build_badvpn_android.sh` sonunda `app/src/main/jniLibs/<abi>/libtun2socks.so` oluştu
```