# 🧪 RAC-JS Comprehensive Testing Record

Dokumen ini digunakan untuk melacak status kecocokan fitur *Command and Control* (C2) dari aplikasi Android target. Diuji coba menggunakan beberapa sistem operasi untuk mengawasi limitasi latar belakang (*Background Limits*) Android yang semakin ketat bertumbuh menurut versinya.

### 📱 Perangkat Uji:
- **[A15]** TECNO POVA 6 (Android 15)
- **[A14]** TECNO MEGAPAD 11 (Android 14) 
- **[A12]** OPPO A53 (Android 12)

---

### 📋 Tabel Status Command/Fitur

Gunakan emoji berikut saat Anda mengisi hasil tes:
*   ✅ **Success** : Fitur berjalan sempurna dan merespon dari latar belakang (Background).
*   ⚠️ **Partial** : Berjalan tetapi dengan anomali (Cth: perlu aplikasi dibuka *Foreground*, atau data terpotong).
*   ❌ **Fail** : Tidak berjalan sama sekali / Dicekal habis oleh OS / *Crash*.
*   ➖ **Not Tested** : Belum diuji coba.

| Kategori | Nama Command | Parameter (Input) | Android 15 (Pova 6) | Android 14 (Megapad 11) | Android 12 (Oppo A53) | Catatan Khusus |
| :--- | :--- | :--- | :---: | :---: | :---: | :--- |
| **Utilitas Dasar** | `ping` | - | ➖ | ✅ | ✅ | Harus merespon "pong" dalam detik yang sama (Cek Long-Polling). |
| | `get_device_info` | - | ➖ | ✅ | ✅ | Tarik Merk, OS Version. |
| | `get_battery` | - | ➖ | ✅ | ✅ | Cek persentasi baterai tanpa nyalakan layar ponsel. |
| | `show_toast` | `[text]` | ➖ | ➖ | ➖ | Menampilkan Toast pop-up meski aplikasi ditutup. |
| | `get_volume` | - | ➖ | ✅ | ✅ | Laporan volume Ring, Alarm, Notif. |
| | `set_volume` | `[type] [level]` | ➖ | ✅ | ⚠️ | Contoh: *music 15*. |
| | `vibrate` | `[durasi detik]`| ➖ | ✅ | ✅ | Getar paksa (Bypass DND Do Not Disturb). |
| | `hide_app` | - | ➖ | ➖ | ➖ | Meniadakan *Launcher Icon* di menu utama (Berjalan di Latar Belakang). |
| **Hardware & Sensor** | `torch` | `on` / `off` | ➖ | ✅ | ✅ | Matikan/Nyalakan Flashlight (Jika HP ditutup). |
| | `sensors` | - | ➖ | ✅ | ✅ | Tarik daftar seluruh sensor manufaktur. |
| **Mata & Telinga**| `photo front` | `front` | ➖ | ✅ | ✅ | Uji Kamera Selfie: Apakah berjalan mulus ke Web Admin / Base64? |
| | `photo back` | `back` | ➖ | ✅ | ✅ | Uji Kamera Belakang resolusi penuh. |
| | `record_sound` | `[durasi detik]`| ➖ | ✅ | ✅ | Uji Rekam Mikrofon; Audio harus di-render MP4 ke `/uploads`. |
| **Jaringan & Lokasi** | `location` | - | ➖ | ❌ | ❌ | Tangkap Lat/Lon GPS. |
| | `wifi_scan` | - | ➖ | ⚠️ | ⚠️ | List Jaringan WiFi di sekitar target. (Wajib A14 FGS Bypass test). |
| **Data Pribadi** | `contacts` | - | ➖ | ✅ | ✅ | Ekstrak daftar seluruh Data Kontak HP target. |
| | `sms_list` | - | ➖ | ✅ | ✅ | Tarik SMS *Inbox* masuk. |
| | `sms_send` | `[nomor]\|[pesan]`| ➖ | ➖ | ➖ | **Background SMS** Kirim pesan uji coba tanpa memicu antarmuka SMS utama. |
| | `clipboard` | - | ➖ | ❌ | ❌ | (Uji Ketat Android 10+): Apakah OS memblokir kita baca Teks Copy/Paste. |
| | `get_call_logs`| `[limit max]` | ➖ | ➖ | ➖ | Data Riwayat Panggilan Telepon. |
| | `get_installed_apps`| - | ➖ | ✅ | ✅ | Skema nama *Package Name* yang terinstall. |
| **Operasi File I/O** | `ls` | `[path]` | ➖ | ✅ | ✅ | Buka dan jelajahi `/storage/emulated/0` (File Explorer). |
| | `download` | *Auto-Click* | ➖ | ✅ | ✅ | Klik nama file (via *ls explorer*). Apakah payload file sampai utuh ke Telegram? |
| | `upload` | *Payload String* | ➖ | ✅ | ✅ | Test File/Script Dropper (Remote Upload). |
|| `shell` | `[command]` | ➖ | ✅ | ✅ | Tulis perintah BASH Terminal. Ex: `ls -la /sdcard/` atau `cat /proc/cpuinfo`. |
| **Visual/Gagguan** | `notify`| `[Title]\|[Body]`| ➖ | ✅ | ✅ | Uji integrasi notifikasi (*Push Notification*) pada sistem target. |
| | `open_url` | `[link]` | ➖ | ⚠️ | ❌ | Memaksa OS memindahkan layar/buka link. |
| | `set_wallpaper` | `[link jpg/png]`| ➖ | ✅ | ➖ | Ganti Background layar hp saat itu jua. |
| | `dial_number` | `[no HP / USSD]`| ➖ | ➖ | ❌ | Paksa Panggil / Call MMI USSD. |
| | `play_alarm` | - | ➖ | ➖ | ➖ | Putar Nada Alarm *default* sekeras besarnya menembus fitur DND. |
| | `tts` | `[pesan text]` | ➖ | ✅ | ❌ | Google Text-To-Speech bersuara dari Speaker Handphone. |
| | `play_sound` | `[link audio]` | ➖ | ✅ | ✅ | Putar Audio dari tautan .mp3 langsung di latar handphone. |

---

### 🔥 Catatan Keamanan / OS Check 
Android Versi 12+ mulai melakukan banyak pembatasan keras terhadap sistem operasi *Background Service*. Saat Anda melakukan tes, sangat disarankan untuk memberikan `Battery Optimization: Unrestricted` serta mencabut izin `Pause app activity if unused` demi mencegah Bot lumpuh saat Device ditinggal tidur.
