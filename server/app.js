require('dotenv').config();
const express = require('express');
const { Telegraf } = require('telegraf');
const sqlite3 = require('sqlite3');
const { open } = require('sqlite');
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
    db = await open({
        filename: path.resolve(__dirname, 'data.db'),
        driver: sqlite3.Database
    });

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
app.use(bodyParser.json());
app.use(bodyParser.urlencoded({ extended: true }));
app.use(cookieParser());
app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'templates'));

// Helper function
const updateDeviceSeen = async (deviceId) => {
    const timestamp = Date.now() / 1000;
    await db.run('INSERT OR REPLACE INTO devices (id, last_seen) VALUES (?, ?)', [deviceId, timestamp]);
};

// --- TELEGRAM BOT ---
let bot;
if (TELEGRAM_TOKEN && !TELEGRAM_TOKEN.includes('YOUR_BOT')) {
    bot = new Telegraf(TELEGRAM_TOKEN);

    bot.start((ctx) => ctx.reply('Bot Active (Node.js Migrated).\n/list - Devices\n/cmd [id] [cmd]'));

    bot.command('list', async (ctx) => {
        const devices = await db.all('SELECT * FROM devices');
        if (devices.length === 0) return ctx.reply('No devices seen yet.');

        let msg = "Devices:\n";
        devices.forEach(d => {
            const isOnline = (Date.now() / 1000 - d.last_seen) < 60;
            msg += `- \`${d.id}\` ${isOnline ? '🟢' : '🔴'}\n`;
        });
        ctx.replyWithMarkdown(msg);
    });

    bot.command('cmd', async (ctx) => {
        const args = ctx.message.text.split(' ');
        if (args.length < 3) return ctx.reply('Usage: /cmd [id] [command] [text]');

        const devId = args[1];
        const cmdName = args[2];
        const extraText = args.slice(3).join(' ');

        const cmdId = uuidv4().slice(0, 8);
        const chatId = ctx.chat.id.toString();

        await db.run('INSERT INTO commands (id, device_id, command, text, status, chat_id) VALUES (?, ?, ?, ?, ?, ?)',
            [cmdId, devId, cmdName, extraText, 'pending', chatId]);

        ctx.reply(`Queued: ${cmdName} -> ${devId}`);
    });

    if (WEBHOOK_URL) {
        bot.createWebhook({ domain: WEBHOOK_URL.replace('https://', '').replace('/webhook', '') })
            .then(() => console.log('Webhook set successfully!'));
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
                const jsonString = JSON.stringify(deviceResponse, null, 2);

                // Jika ukuran pesan melebihi limit Telegram (4096 char), kirim sebagai file JSON
                if (jsonString.length > 3500) {
                    const buffer = Buffer.from(jsonString, 'utf-8');
                    await bot.telegram.sendDocument(cmd.chat_id, {
                        source: buffer,
                        filename: `response_${data.id}.json`
                    }, { caption: `✅ <b>Respon dari alat (${client_id}):</b> Payload terlalu besar, dikirim sebagai file.`, parse_mode: 'HTML' });
                } else {
                    const replyMessage = `✅ <b>Respon dari alat (${client_id}):</b>\n<pre><code class="language-json">${jsonString}</code></pre>`;
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

app.listen(PORT, () => {
    console.log(`Server Node.js berjalan di port ${PORT}`);
});
