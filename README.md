# HorizonZ - Launcher (v1.6)

**Real Minecraft: Java Edition 26.3 Rust Engine for Android — Material You (M3) UI, 12 HD Minecraft Wallpapers, Persistent Accounts & Settings, and Direct Game Boot/Exit.**

[![Build HorizonZ Launcher APK](https://github.com/Fadiba21/Horizonz-Launcher/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Fadiba21/Horizonz-Launcher/actions/workflows/build-apk.yml)

## Pembaruan Utama v1.6 (`HorizonZ1.6.zip`)

1. **Penghapusan Total UI Bawaan `Horizon Android 0.7.4` & Perbaikan Exit Game**:
   - Mematikan pemanggilan `self.draw_pages()` pada `crates/horizon-client/src/android/launcher.rs` sehingga layar `Horizon Android 0.7.4` tidak pernah digambar dan langsung menjalankan `self.action(events)` $
ightarrow$ `game::android_game()`.
   - Memperbaiki `restore_launcher` di `launcher.rs` serta menjalankan `HorizonActivity` di proses terpisah `android:process=":game"` agar saat keluar dari Minecraft (*Quit Game*), aplikasi langsung menutup proses game (`events.exit(); std::process::exit(0);`) dan kembali ke **HorizonZ Launcher** (tidak masuk ulang ke Minecraft).
2. **Perbaikan Sistem Simpan Akun & Setting Game (`android-launcher.json` & `accounts.json`)**:
   - Menyimpan daftar akun secara permanen di `zalith-accounts.json`, `accounts.json`, `android-launcher.json` (`"name"`), dan `horizon-player.txt` sehingga nama pemain tidak pernah ter-reset ke awal.
   - Menyimpan pengaturan Launcher ke `android-launcher.json` (mengikuti skema asli `catalog.rs`: *Renderer*, *Render Scale*, *Input Mode*, *Orientation*, *Profile Slot*, *Keypad Controls*, *Perf Telemetry*) tanpa menimpa berkas `options.txt` milik Minecraft, sehingga pengaturan di dalam game tetap tersimpan permanen.
3. **12 Wallpaper Minecraft HD (16:9) & Pembersihan Dashboard**:
   - Menghapus tulisan status teknis (*Vulkan*, *Scale*, *Assets Ready*) dari kartu utama Dashboard.
   - Menambahkan **12 Wallpaper Minecraft HD (1280×720, rasio 16:9 normal)** yang berganti otomatis setiap 5 detik dengan animasi *crossfade* halus di kartu utama Dashboard.
