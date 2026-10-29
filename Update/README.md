# Folder Auto-Update Source (`Update/`)

Cukup **upload file `.zip` source baru** (misalnya `HorizonZ1.3.zip`, `HorizonZ1.4.zip`, dll.) ke dalam folder **`Update/`** ini lalu klik **Commit changes**.

### Apa yang Akan Dilakukan GitHub Actions Secara Otomatis:
1. Mendeteksi adanya berkas `*.zip` di dalam folder `Update/`.
2. Mengekstrak seluruh isi berkas `.zip` tersebut dan **menimpa (overwrite) kode sumber utama** di *root* repositori.
3. Menghapus berkas `.zip` yang sudah diekstrak dari folder `Update/` (agar folder `Update/` tetap bersih dan siap untuk update berikutnya) lalu melakukan *auto-commit & push* ke branch utama (`main`).
4. Langsung menjalankan proses **Build APK HorizonZ** menggunakan kode sumber terbaru yang baru saja diekstrak dan menerbitkannya ke halaman **Releases**!
