# Diagram Logika RAC-JS (Logic Flowchart)

Diagram ini merinci setiap langkah logika mulai dari inisiasi perintah di Telegram hingga penampilan hasil multimedia di Web Admin.

```mermaid
flowchart TD
    Start([User Klik Tombol Bot]) --> CheckInput{Butuh Argument?}
    
    %% Alur Input Interaktif
    CheckInput -- Ya --> AskInput[Bot: Minta Input Teks]
    AskInput --> WaitInput[/User Balas Pesan/]
    WaitInput --> CancelCheck{Klik Batal?}
    CancelCheck -- Ya --> ClearState[Hapus ActiveInput & Chat]
    CancelCheck -- No --> StoreInput[Gabungkan CMD + Teks]
    StoreInput --> DB_Insert
    
    %% Alur Eksekusi Langsung
    CheckInput -- Tidak --> DB_Insert[INSERT ke Table 'commands' status: PENDING]
    
    DB_Insert --> BotWaiting[Bot: Tampilkan '⏳ Menunggu Respon...']
    BotWaiting --> DB_MsgID[Update MsgID & ChatID di DB]

    %% Alur Android Client
    AndroidPolling{{Android Polling GET 'api/command'}} -- Ada Tugas --> AndroidExec[Android Eksekusi Perintah]
    AndroidExec -- Hasil (JSON/Base64) --> SendResult[POST 'api/command/result']
    
    %% Alur Pemrosesan Hasil di Backend
    SendResult --> DB_UpdateStatus[Update 'commands' status: DONE]
    DB_UpdateStatus --> LogInsert[Simpan Baris Baru ke 'system_logs']
    
    LogInsert --> SizeCheck{Ukuran Data > 4KB?}
    
    %% Alur Pengiriman Balasan
    SizeCheck -- Ya --> SendDoc[Bot: Kirim Dokumen .json + Preview]
    SendDoc --> DelWaiting[Bot: Hapus Pesan 'Menunggu']
    
    SizeCheck -- Tidak --> EditWaiting[Bot: EDIT Pesan 'Menunggu' Jadi Hasil]
    
    %% Alur Web Admin
    WebLog[🌐 Web Admin] -- Fetch 'api/logs' --> TrimData[Potong Base64 ke 300 Karakter]
    TrimData --> LogTable[Render Dasbor Admin]
    
    UserClick[User Klik 'Detail' di Web] --> FetchFull[GET 'api/log/:id' Full Message]
    FetchFull --> TypeCheck{Cek 'Type' Payload?}
    
    TypeCheck -- photo_base64 --> RenderImg[Tampilkan 🖼️ Image Viewer]
    TypeCheck -- audio_base64 --> RenderAudio[Tampilkan 🎤 Audio Player]
    TypeCheck -- location_data --> RenderMap[Tampilkan 🗺️ Leaflet Map]
    TypeCheck -- Other --> RenderJSON[Tampilkan 📄 Neon Pretty JSON]

    ClearState --> Stop([Proses Selesai])
    DelWaiting --> Stop
    EditWaiting --> Stop
    RenderImg --> Stop
    RenderAudio --> Stop
    RenderMap --> Stop
    RenderJSON --> Stop
```

---

### Key Logic Features Implemented:
*   **Active Input Handler**: Menggunakan objek `activeInput` di memori NodeJS untuk mengaitkan balasan teks pengguna dengan perintah yang tertunda.
*   **Anti-Spam (Smart Edit)**: Sistem melacak `message_id` sejak awal. Jika hasil kecil, pesan "Menunggu" dipoles (diedit); jika besar, pesan "Menunggu" dibuang agar tidak menumpuk.
*   **Multimedia Stream (Base64 Isolation)**: Data gambar/suara besar tidak dimuat saat rendering tabel utama (SUBSTR 300) guna menjaga kinerja memori browser tetap stabil.
*   **SQLite Synchronization**: Menggunakan ID unik yang sama antara tabel `commands` dan `system_logs` untuk memastikan integritas data dari awal hingga akhir.
