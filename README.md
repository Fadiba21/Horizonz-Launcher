# HorizonZ - Launcher (v0.8.0)

**Native Android Rust Runtime (`libhorizon_android.so`) + Clean Minimalist Zalith Launcher 2 Material 3 UI.**

[![Build HorizonZ Launcher APK](https://github.com/Fadiba21/Horizonz-Launcher/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Fadiba21/Horizonz-Launcher/actions/workflows/build-apk.yml)

## Pembaruan Utama v0.8.0

1. **Native Rust `.so` Runtime (Tanpa Launcher Bawaan Repo Lain)**:
   - Workflow tidak lagi mengunduh/mengekstrak `libhorizon_android.so` dari APK rilis `Vasysik/horizon-android` (yang sebelumnya memunculkan *launcher* bawaan `launcher::Launcher`).
   - Sebaliknya, `crates/horizon-android` kini dikompilasi **100% dari kode sumber Rust** menggunakan `android-activity` (`native-activity`), NDK 28, dan `libEGL.so` / `libGLESv3.so` untuk langsung menjalankan *window* permainan native saat tombol **PLAY** ditekan.
2. **Desain UI Minimalis Bergaya Zalith Launcher 2**:
   - **Left Icon-Only Navigation Rail**: Hanya 4 ikon bersih (*Home*, *Versions*, *Files*, *Settings*) tanpa teks panjang.
   - **Clean Center Viewport**: Tampilan tengah kaca transparan (*glassmorphic*) yang bersih dan simpel.
   - **Right Launch Sidebar**: Kartu profil akun di atas, pemilih versi `26.3 Rust` di tengah, dan tombol **PLAY** utama di bawah.
