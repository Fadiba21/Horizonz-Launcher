# HorizonZ - Launcher (v1.8)

**Real Minecraft: Java Edition 26.3 Rust Engine for Android — Instant Touch Controls, Background Download Notification, Verbose Speed/URL Logger, Full Runtime & `options.txt` Sync, and Real Web HD 16:9 Wallpapers.**

[![Build HorizonZ Launcher APK](https://github.com/Fadiba21/Horizonz-Launcher/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Fadiba21/Horizonz-Launcher/actions/workflows/build-apk.yml)

## Pembaruan Utama v1.8 (`HorizonZ1.8.zip`)

1. **Perbaikan Stuck `5/6 Preparing Touch Controls` (`17 / 60 (28%)`)**:
   - Menambahkan `scripts/prestage_controls_and_wallpapers.py` yang mengunduh dan menyertakan ke-60 ikon *Bedrock Touch Controls* langsung ke dalam `engine-data.zip` saat proses build CI, ditambah patch *multi-mirror* (`raw.githubusercontent.com` + `cdn.jsdelivr.net` + `fastly.jsdelivr.net`), *timeout* 6s/10s, dan *fallback* PNG pada `crates/horizon-client/src/android_resources.rs`.
2. **Background Download & Notifikasi Progres Android (`HorizonDownloadService.kt`)**:
   - Mendukung *background download* dengan `ForegroundService` (`dataSync`) + `WakeLock` sehingga saat aplikasi di-*minimize*, unduhan tetap berjalan di latar belakang dan menampilkan notifikasi progres (`%`, tahap, kecepatan `MB/s`, dan tautan) di panel notifikasi ponsel.
3. **Log Proses Download Lengkap (Nama File, Link/URL, MB, & Kecepatan Download)**:
   - Menampilkan tautan aktif (`piston-meta.mojang.com`, `resources.download.minecraft.net`, `raw.githubusercontent.com/Mojang/bedrock-samples`), ukuran terunduh (`MB / MB`), dan kecepatan unduh (`MB/s` atau `KB/s`) secara *real-time*.
4. **Sinkronisasi Penuh Menu Settings dengan Runtime, Port Android, & `options.txt` Minecraft**:
   - Memperbaiki kunci serde `"open-gl"` pada `catalog.rs`, menyinkronkan `horizon-keypad.json`, menambahkan sinkronisasi 2 arah `options.txt` (*Render Distance*, *Simulation Distance*, *Max FPS*, *GUI Scale*, *VSync*, *Fullscreen*), sakelar aktif/nonaktif Mod (`.hmod`), Shader, dan Resource Pack, serta tombol *Reset Keypad Layout*, *Export Performance*, *Repair Assets*, dan *Open System Files*.
5. **Perbaikan Hapus System Log & Perbaikan Exit Game Kembali ke Launcher**:
   - Tombol **Clear** pada System Log kini membersihkan `logcat -c` dan seluruh berkas log lintas proses (`zalith-system.log`).
   - Menambahkan *3-layer exit lock* (`GAME_LAUNCHED_ONCE` di `launcher.rs` + `zalith-game-exited.flag` watchdog + *one-time token* `zalith-launch-token.txt` di `HorizonActivity.java`) agar keluar dari Minecraft langsung kembali ke **HorizonZ Launcher** tanpa pernah masuk ulang ke Minecraft.
