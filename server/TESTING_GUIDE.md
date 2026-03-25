# Panduan Lengkap Pengujian RAC-JS Server (Lokal)

Dokumen ini berisi langkah-langkah terperinci untuk menguji server **RAC-JS** yang telah dimigrasi ke **SQLite** dan memiliki antarmuka **Web Dashboard**. Menggunakan arsitektur SQLite menjamin server tidak kehilangan memori antrean saat di-deploy di lingkungan _Shared Hosting_ / cPanel (Passenger).

---

## 🛠️ Fase 1: Persiapan Awal
1. Buka terminal VS Code atau terminal bawaan Ubuntu Anda.
2. Arahkan direktori terminal ke dalam folder server proyek:
   ```bash
   cd /home/rjw/Destop/Projects/rac-js/server
   ```
3. Aktifkan *Virtual Environment* Python Anda:
   ```bash
   source .venv/bin/activate
   ```
4. Install paket dependensi baru (seperti FastAPI, SQLAlchemy, Jinja2, dll.):
   ```bash
   pip install -r requirements.txt
   ```
5. Pastikan Anda punya konfigurasi minimal di dalam file `.env` (berada di dalam `/server/.env`):
   ```env
   AUTH_TOKEN=my-secret-token
   ADMIN_PASSWORD=admin123
   ```

---

## 🚀 Fase 2: Menjalankan Server & Mengakses Dashboard
1. Jalankan layanan server dengan Uvicorn:
   ```bash
   uvicorn main:app --reload --port 8000
   ```
2. Buka browser dan kunjungi: **[http://localhost:8000/admin](http://localhost:8000/admin)**
3. Anda akan diarahkan ke halaman **Login**:
   - Masukkan kata sandi sesuai file `.env` (default: **`admin123`**).
   - Klik **Masuk Dashboard**.
4. Di *dashboard*, Anda akan melihat dua panel besar:
   - **Perangkat Tersambung**: Kosong ("Belum ada perangkat terhubung").
   - **Log Sistem & Respons**: Kosong.
   
   *(Catatan: Setelah URL di atas dibuka pertama kali, database `data.db` secara otomatis langsung dibentuk di folder `server/`)*

---

## 📡 Fase 3: Simulasi Perangkat & Polling (Mock Client)
Server Anda bekerja berdasar prinsip *Long Polling* atau sistem *Pull*. Alat mendatangi server untuk bertanya apakah ada perintah. Mari simulasikan alat ini dengan Terminal.

1. Buka sambungan terminal **baru** di VS Code.
2. Jalankan sintaks *curl* di bawah ini:
   ```bash
   curl -X GET "http://127.0.0.1:8000/poll?client_id=DEVICE_001&auth=my-secret-token"
   ```
3. Hasil di terminal akan menampilkan *(karena belum ada perintah darimanapun)*:
   `{"command":"none"}`

**➜ Silakan Cek Dashboard:** 
Pada dashboard browser, **DEVICE_001** sekarang muncul di daftar "Perangkat Tersambung" dan indikatornya menyala **Hijau (ONLINE)** karena barusan aktif. 

---

## 💻 Fase 4: Mengirim Perintah via Web
Alih-alih repot menyuruh lewat Telegram, gunakan saja Web Admin.

1. Di Dashboard, amati list di menu kiri: klik tepat pada kotak perangkat **`DEVICE_001`**.
2. Kotak "Kirim Perintah" akan muncul di bawahnya.
3. Isikan:
   - **Perintah (Command)**: `restart`
   - **Payload Ekstra**: `force=true`
4. Tekan **"Kirim Eksekusi"**.
5. *Toast notification* keren di pojokan browser mencatat keberhasilan masuknya pesan ke dalam *database queue*. *(Pesan saat ini di antrean bersatus `pending` dalam DB).*

---

## ⚙️ Fase 5: Memproses Perintah & Kirim Log
1. Kembali ke terminal simulasi yang baru. Perangkat melakukan *polling* lagi:
   ```bash
   curl -X GET "http://127.0.0.1:8000/poll?client_id=DEVICE_001&auth=my-secret-token"
   ```
2. Kini, perangkat *harus* mendapatkan respons berisi *command* yang Anda inject dari dashboard tadi:
   ```json
   {"command":"restart","text":"force=true","id":"8a4f9b2c"}
   ```
   *(Catat/salin **UUID `id`** tersebut, misal: `8a4f9b2c`)*

3. Alat menyimulasikan kelar memproses instruksi `restart`, lalu alat mau melapor balik (*Callback/Response*) dengan menyertakan log ke server:
   ```bash
   curl -X POST "http://127.0.0.1:8000/response?client_id=DEVICE_001&auth=my-secret-token" \
        -H "Content-Type: application/json" \
        -d '{"id": "8a4f9b2c", "level": "INFO", "message": "Proses restart paksa (force=true) sukses dikerjakan."}'
   ```
   *(Harap ganti `8a4f9b2c` di atas dengan ID yang Anda catat sendiri).*
4. Tanggapan server akan tertulis:
   `{"status":"received"}`

---

## ✅ Fase 6: Validasi Dashboard
1. Buka kembali browser Dashboard Admin.
2. Periksa panel sisi kanan: **Log Sistem & Respons**.
3. Paling atas, Anda sudah bisa melihat catatan `INFO` masuk untuk target `DEVICE_001` dengan pesan *"Proses restart paksa (force=true) sukses dikerjakan."*
4. Jika proses ini ditunggu lebih dari 60 detik (60.000 ms), perangkat di panel kiri akan merubah sinyal indikatornya dari Hijau (*ONLINE*) menjadi Merah (*OFFLINE*) karena *timeout* (belum lapor `poll` lagi).

---
**Pengujian telah Selesai 100%!** Logika *stateful* dengan SQLite ini dirancang tahan terhadap lingkungan *Thread Pooling Shared Hosting* milik CPanel / Passenger WSGI. 🚀
