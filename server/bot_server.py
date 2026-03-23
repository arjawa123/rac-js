import asyncio
import websockets
import json
import logging
from telegram import Update
from telegram.ext import ApplicationBuilder, CommandHandler, ContextTypes

import os

# --- KONFIGURASI ---
# Token Telegram (Disarankan diset di Dashboard Back4app)
TELEGRAM_TOKEN = os.environ.get('TELEGRAM_TOKEN', '8794005734:AAEaT_oKgX9mF2T8D0iT_2br1flpqsMLSi8')
# Port dinamis dari Back4app
WEBSOCKET_PORT = int(os.environ.get('PORT', 8080))
WEBSOCKET_HOST = '0.0.0.0'

# Setup Logging
logging.basicConfig(format='%(asctime)s - %(name)s - %(levelname)s - %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

# Simpan koneksi device yang aktif
connected_devices = {}  # {websocket: "device_name"}
last_chat_id = None    # Simpan chat_id terakhir untuk membalas respon dari device

async def websocket_handler(websocket, path):
    global last_chat_id
    logger.info("New device attempting to connect...")
    try:
        # Pendaftaran device (opsional: bisa kirim info device saat connect)
        connected_devices[websocket] = "Android Device"
        logger.info(f"Device connected! Total: {len(connected_devices)}")
        
        async for message in websocket:
            logger.info(f"Received from device: {message}")
            data = json.loads(message)
            
            # Jika ada chat_id terakhir, kirim balik respon device ke Telegram
            if last_chat_id:
                response_text = f"📱 *Response from Device:*\n`{json.dumps(data, indent=2)}`"
                # Kita butuh bot instance untuk mengirim pesan di luar context Telegram
                await bot_app.bot.send_message(chat_id=last_chat_id, text=response_text, parse_mode='Markdown')

    except websockets.ConnectionClosed:
        logger.info("Device disconnected")
    finally:
        if websocket in connected_devices:
            del connected_devices[websocket]

async def send_command_to_all(command_payload, update: Update):
    global last_chat_id
    last_chat_id = update.effective_chat.id
    
    if not connected_devices:
        await update.message.reply_text("❌ No devices connected via WebSocket.")
        return False
    
    payload = json.dumps(command_payload)
    for ws in connected_devices.keys():
        await ws.send(payload)
    return True

# --- HANDLER COMMAND TELEGRAM ---

async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    welcome = (
        "🤖 *Device Control Bot Active!*\n\n"
        "Commands:\n"
        "/ping - Check connection\n"
        "/info - Get device details\n"
        "/battery - Check battery level\n"
        "/toast <text> - Show toast message"
    )
    await update.message.reply_text(welcome, parse_mode='Markdown')

async def ping(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if await send_command_to_all({"command": "ping"}, update):
        await update.message.reply_text("⏳ Sending PING to device...")

async def get_info(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if await send_command_to_all({"command": "get_device_info"}, update):
        await update.message.reply_text("⏳ Requesting device info...")

async def get_battery(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if await send_command_to_all({"command": "get_battery"}, update):
        await update.message.reply_text("⏳ Checking battery level...")

async def show_toast(update: Update, context: ContextTypes.DEFAULT_TYPE):
    text = " ".join(context.args) if context.args else "Hello from Telegram!"
    if await send_command_to_all({"command": "show_toast", "text": text}, update):
        await update.message.reply_text(f"⏳ Sending toast: '{text}'")

async def main():
    global bot_app
    # 1. Jalankan WebSocket Server di background
    ws_server = await websockets.serve(websocket_handler, WEBSOCKET_HOST, WEBSOCKET_PORT)
    logger.info(f"WebSocket Server started on {WEBSOCKET_HOST}:{WEBSOCKET_PORT}")

    # 2. Jalankan Telegram Bot
    bot_app = ApplicationBuilder().token(TELEGRAM_TOKEN).build()
    
    bot_app.add_handler(CommandHandler("start", start))
    bot_app.add_handler(CommandHandler("ping", ping))
    bot_app.add_handler(CommandHandler("info", get_info))
    bot_app.add_handler(CommandHandler("battery", get_battery))
    bot_app.add_handler(CommandHandler("toast", show_toast))

    logger.info("Telegram Bot starting...")
    async with bot_app:
        await bot_app.initialize()
        await bot_app.start()
        await bot_app.updater.start_polling()
        # Biarkan server tetap berjalan
        await asyncio.Future()

if __name__ == '__main__':
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Server stopped.")
