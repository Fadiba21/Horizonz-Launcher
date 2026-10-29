# HorizonZ - Launcher

**Minecraft: Java Edition 26.3 Rewritten in 100% Rust for Android — with a Native Material 3 (Material You) Zalith Launcher 2+ UI.**

[![Build HorizonZ Launcher APK](https://github.com/Fadiba21/Horizonz-Launcher/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Fadiba21/Horizonz-Launcher/actions/workflows/build-apk.yml)
[![Minecraft Target](https://img.shields.io/badge/Minecraft_Java-26.3_(100%25_Rust)-FF7043?logo=rust&logoColor=white)](https://github.com/horizon-rust/horizon)
[![Android API](https://img.shields.io/badge/Android-SDK_28--35_(Material_3)-42A5F5?logo=android&logoColor=white)](https://github.com/Vasysik/horizon-android)

## Arsitektur & Gabungan 3 Repositori Utama

1. **Core Engine ([horizon-rust/horizon](https://github.com/horizon-rust/horizon) — `v0.2.97`)**:
   - Reimplementasi **Minecraft: Java Edition 26.3** yang ditulis ulang 100% menggunakan **Rust** (tanpa JVM), diverifikasi terhadap vanilla menggunakan **281 differential harnesses** (paritas 100% di 14 subsistem).
2. **Android Native Backend ([Vasysik/horizon-android](https://github.com/Vasysik/horizon-android) — `v0.7.3` / `v0.7.4`)**:
   - Porting native Android melalui crate `crates/horizon-android` (`cdylib` $ightarrow$ `libhorizon_android.so`), `HorizonActivity.java` (`NativeActivity`), dan `HorizonDocumentsProvider.java` (SAF `DocumentsProvider`).
3. **Material 3 Frontend UI ([Star1xr/ZalithLauncher2Plus](https://github.com/Star1xr/ZalithLauncher2Plus) — `2.4.10 Plus 1.3`)**:
   - Dibangun menggunakan **Jetpack Compose + Material Design 3 (Material You)** (`dev.horizon.android.ui.ZalithMainActivity`).
