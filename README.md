# HorizonZ - Launcher

**Minecraft: Java Edition 26.3 Rewritten in 100% Rust for Android — with a Native Material 3 (Material You) Zalith Launcher 2+ UI.**

[![Build HorizonZ Launcher APK](https://img.shields.io/badge/GitHub_Actions-Build_APK_Ready-26A69A?logo=githubactions&logoColor=white)](.github/workflows/build-apk.yml)
[![Minecraft Target](https://img.shields.io/badge/Minecraft_Java-26.3_(100%25_Rust)-FF7043?logo=rust&logoColor=white)](https://github.com/horizon-rust/horizon)
[![Android API](https://img.shields.io/badge/Android-SDK_28--35_(Material_3)-42A5F5?logo=android&logoColor=white)](https://github.com/Vasysik/horizon-android)

---

## Arsitektur & Gabungan 3 Repositori Utama

**HorizonZ - Launcher** menggabungkan tiga proyek open-source ke dalam satu aplikasi Android native:

1. **Core Engine ([horizon-rust/horizon](https://github.com/horizon-rust/horizon) — `v0.2.97`)**:
   - Reimplementasi **Minecraft: Java Edition 26.3** yang ditulis ulang 100% menggunakan **Rust** (tanpa JVM di latar belakang), diverifikasi terhadap vanilla menggunakan **281 differential harnesses** (skor paritas 100% di 14 subsistem: Worldgen, Lighting, Section Meshing, Redstone, Mob AI, Villagers, Raids, Anvil Saves 1.8–26.3, dan Protocol 777 Multiplayer).
2. **Android Native Backend ([Vasysik/horizon-android](https://github.com/Vasysik/horizon-android) — `v0.7.3` / `v0.7.4`)**:
   - Porting native Android melalui crate `crates/horizon-android` (`cdylib` $ightarrow$ `libhorizon_android.so`).
   - `dev.horizon.android.HorizonActivity` (`extends android.app.NativeActivity`) dengan dukungan penuh **Storage Access Framework (SAF)** request code `4101` (`nativeDocumentResult`).
   - `dev.horizon.android.HorizonDocumentsProvider` (`extends android.provider.DocumentsProvider`) dengan metode JNI `nativeCall` dan `nativeOpen` agar folder `saves`, `resourcepacks`, `datapacks`, `mods` (`.wasm`), dan `logs` langsung muncul di aplikasi **Files** bawaan Android.
   - Sistem input layar sentuh (`bedrock-controls.json` / `crates/horizon-client/src/touch*`) dan gamepad fisik.
3. **Material 3 Frontend UI ([Star1xr/ZalithLauncher2Plus](https://github.com/Star1xr/ZalithLauncher2Plus) — `2.4.10 Plus 1.3`)**:
   - Dibangun menggunakan **Jetpack Compose + Material Design 3 (Material You)** (`dev.horizon.android.ui.ZalithMainActivity`).
   - **Top Bar & Collapsible Navigation Rail**: Tombol cepat **In-Launcher File Manager** di Top Bar, **Game Screen Recorder** dengan timer berjalan (`PLAYBACK_CAPTURE` + fallback video), **Live Log Viewer** (`logs/latest.log` & `android-error.txt`), dan **Theme Palette Switcher** (*Zalith Embermire*, *Horizon Rust Orange*, *Material Ocean*, *Redstone Melter Emerald*, serta *Android 12+ Dynamic Wallpaper Color*).
   - **Accounts & Cape Wardrobe (ZL2+ 1.3)**: Dukungan akun **Offline**, **Microsoft OAuth (Online-mode 26.3)**, dan **Ely.by / Custom Yggdrasil**, fitur **Chroma Name** (nama pemain berwarna gradasi), pilihan model skin **Slim (Alex)** / **Classic (Steve)**, serta **Cape Selector & Gallery Upload**.
   - **26.3 Asset Preparation Manager**: Mendukung mode `--public` (`HORIZON_ANDROID_PUBLIC=1`) untuk mengunduh/menyiapkan aset langsung di perangkat Android maupun impor manual berkas `server.jar` (Minecraft 26.3) dan `engine-data.zip`.
   - **Renderer & Extras Config**: Konfigurasi backend `wgpu` (**Vulkan** / **OpenGL ES 3.0**), skala resolusi default **70%** (seperti ZL2+ 1.2), **Distant Terrain hingga 1024 chunks**, opsi **Hide Controller While Game is Loading**, **Minimap**, **Zoom**, dan **Subtitles / Closed Captions**.

---

## Struktur Direktori

```text
HorizonZ-Launcher/
├── .github/workflows/
│   └── build-apk.yml                # GitHub Actions CI/CD otomatis menghasilkan 4 ABI APK + Release
├── crates/
│   └── horizon-android/             # Crate Rust cdylib (libhorizon_android.so) & jembatan JNI
│       ├── Cargo.toml
│       └── src/lib.rs
├── packaging/android/               # Proyek Android Gradle (Jetpack Compose Material 3 + NativeActivity)
│   ├── AndroidManifest.xml
│   ├── build.gradle
│   ├── settings.gradle
│   ├── bedrock-controls.json
│   ├── java/dev/horizon/android/
│   │   ├── HorizonActivity.java             # Subclass NativeActivity (kompatibel penuh dengan Vasysik/horizon-android)
│   │   ├── HorizonDocumentsProvider.java    # SAF DocumentsProvider (nativeCall & nativeOpen)
│   │   ├── bridge/HorizonNativeBridge.kt    # Sinkronisasi options.txt, horizon-extras.txt & peluncuran NativeActivity
│   │   ├── model/ZalithModels.kt            # State model akun, cape, kontrol & pengaturan
│   │   └── ui/
│   │       ├── ZalithMainActivity.kt        # Tampilan utama Material 3 bergaya ZalithLauncher2Plus
│   │       └── theme/ZalithTheme.kt         # Tema Material You & palet warna Zalith
│   └── res/
├── scripts/
│   └── sync_upstream.sh             # Skrip sinkronisasi penuh dengan Vasysik/horizon-android (branch android)
├── tools/
│   └── android_build.py             # Pembangun otomatis NDK + Rust + Gradle untuk 4 ABI Android
└── Cargo.toml
```

---

## Cara Build APK di GitHub Actions (Otomatis)

1. Buat repositori publik baru di GitHub dengan nama **`HorizonZ-Launcher`** (atau `HorizonZ - Launcher`).
2. Ekstrak seluruh isi berkas `HorizonZ-Launcher.zip` ini lalu push ke branch `main`:
   ```bash
   git init
   git add .
   git commit -m "feat: initial HorizonZ - Launcher (Rust 26.3 + ZalithLauncher2Plus M3 UI)"
   git branch -M main
   git remote add origin https://github.com/<USERNAME_ANDA>/HorizonZ-Launcher.git
   git push -u origin main
   ```
3. Buka tab **Actions** di repositori GitHub Anda:
   - Workflow **Build HorizonZ Launcher APK** akan otomatis berjalan dan menghasilkan 4 berkas APK siap pasang (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) beserta **GitHub Release** otomatis!
   - Anda juga dapat menjalankan workflow secara manual (**Run workflow**) dengan memilih mode:
     - `hybrid` *(Default, ~4 menit)*: Mengambil `libhorizon_android.so` & `engine-data.zip` resmi dari rilis `Vasysik/horizon-android` `v0.7.3`, memverifikasi jembatan Rust JNI, dan mengompilasi aplikasi Material 3 `HorizonZ-Launcher`.
     - `full_source` *(~35–60 menit)*: Mengkloning seluruh pohon sumber Rust `Vasysik/horizon-android` (`branch: android`) dan mengompilasi ulang seluruh crate engine dari awal menggunakan Android NDK 28.
