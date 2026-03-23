import os
import json
import logging
import asyncio
from aiohttp import web
from telegram import Update
from telegram.ext import ApplicationBuilder, CommandHandler, ContextTypes

# --- KONFIGURASI ---
TELEGRAM_TOKEN = os.environ.get('TELEGRAM_TOKEN', '8794005734:AAEaT_oKgX9mF2T8D0iT_2br1flpqsMLSi8')
PORT = int(os.environ.get('PORT', 8080))

# Setup Logging
logging.basicConfig(format='%(asctime)s - %(name)s - %(levelname)s - %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

# Simpan koneksi device
connected_devices = set()
last_chat_id = None
bot_app = None

# --- HANDLER HTTP & WEBSOCKET (AIOHTTP) ---

async def handle_health_check(request):
    """Menangani Health Check (GET, OPTIONS, HEAD) dari Back4app"""
    return web.Response(text="OK\n")

async def websocket_handler(request):
    """Menangani Koneksi WebSocket dari Android"""
    global last_chat_id
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    
    logger.info("New device connected via WebSocket")
    connected_devices.add(ws)
    
    try:
        async for msg in ws:
            if msg.type == web.WSMsgType.TEXT:
                logger.info(f"Received from device: {msg.data}")
                if last_chat_id:
                    data = json.loads(msg.data)
                    response_text = f"📱 *Response from Device:*\n`{json.dumps(data, indent=2)}`"
                    await bot_app.bot.send_message(chat_id=last_chat_id, text=response_text, parse_mode='Markdown')
            elif msg.type == web.WSMsgType.ERROR:
                logger.error(f"WS connection closed with exception {ws.exception()}")
    finally:
        connected_devices.remove(ws)
        logger.info("Device disconnected")
    
    return ws

# --- HANDLER PERINTAH TELEGRAM ---

async def send_command_to_all(command_payload, update: Update):
    global last_chat_id
    last_chat_id = update.effective_chat.id
    
    if not connected_devices:
        await update.message.reply_text("❌ No devices connected.")
        return False
    
    payload = json.dumps(command_payload)
    for ws in list(connected_devices):
        try:
            await ws.send_str(payload)
        except Exception as e:
            logger.error(f"Failed to send to device: {e}")
    return True

async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text("🤖 *Device Control Active!*", parse_mode='Markdown')

async def ping(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if await send_command_to_all({"command": "ping"}, update):
        await update.message.reply_text("⏳ Pinging device...")

async def battery(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if await send_command_to_all({"command": "get_battery"}, update):
        await update.message.reply_text("⏳ Getting battery level...")

async def toast(update: Update, context: ContextTypes.DEFAULT_TYPE):
    text = " ".join(context.args) if context.args else "Hello from Remote!"
    if await send_command_to_all({"command": "show_toast", "text": text}, update):
        await update.message.reply_text(f"⏳ Sending toast: {text}")

# --- MAIN RUNNER ---

async def main():
    global bot_app
    # 1. Setup Telegram Bot
    bot_app = ApplicationBuilder().token(TELEGRAM_TOKEN).build()
    bot_app.add_handler(CommandHandler("start", start))
    bot_app.add_handler(CommandHandler("ping", ping))
    bot_app.add_handler(CommandHandler("battery", battery))
    bot_app.add_handler(CommandHandler("toast", toast))
    
    await bot_app.initialize()
    await bot_app.start()
    await bot_app.updater.start_polling()
    logger.info("Telegram Bot started.")

    # 2. Setup AIOHTTP Server (WebSocket + Health Check)
    app = web.Application()
    # Route root (/) melayani WebSocket DAN Health Check
    app.router.add_get('/', websocket_handler)
    app.router.add_options('/', handle_health_check)
    app.router.add_get('/health', handle_health_check) # Extra route untuk health check

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, '0.0.0.0', PORT)
    await site.start()
    logger.info(f"AIOHTTP Server started on port {PORT}")

    # Biarkan keduanya berjalan selamanya
    while True:
        await asyncio.sleep(3600)

if __name__ == '__main__':
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Server stopped.")
