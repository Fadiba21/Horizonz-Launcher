# HorizonZ - Launcher (v1.2)

**Real Minecraft: Java Edition 26.3 Rust Engine for Android — Material You (M3) UI, Live Asset Downloader, Process Logger & Auto-Extract `Update/` Folder System.**

[![Build HorizonZ Launcher APK](https://github.com/Fadiba21/Horizonz-Launcher/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Fadiba21/Horizonz-Launcher/actions/workflows/build-apk.yml)

## Cara Update Cepat via Folder `Update/` (Mulai v1.2)

Mulai versi `1.2`, Anda **tidak perlu lagi mengekstrak file `.zip` secara manual**:
1. Buka folder **[`Update/`](Update/)** di repositori GitHub Anda.
2. Klik **Add file $ightarrow$ Upload files**, lalu pilih file `.zip` update terbaru (misalnya `HorizonZ1.3.zip`).
3. Klik **Commit changes**.
4. GitHub Actions (`apply-update-zip`) akan **otomatis mengekstrak seluruh isi file `.zip` tersebut dan menimpanya ke kode sumber utama (*main source*)**, menghapus file `.zip` yang sudah diekstrak dari folder `Update/`, menyimpan komit pembaruan ke branch `main`, lalu langsung merakit APK terbaru ke halaman **Releases**!
