import os
import json
import logging
import asyncio
from typing import Set
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import PlainTextResponse
from telegram import Update
from telegram.ext import ApplicationBuilder, CommandHandler, ContextTypes
from contextlib import asynccontextmanager
from dotenv import load_dotenv

# Muat variabel lingkungan dari .env
load_dotenv()

# --- KONFIGURASI ---
TELEGRAM_TOKEN = os.getenv('TELEGRAM_TOKEN', '8794005734:AAEaT_oKgX9mF2T8D0iT_2br1flpqsMLSi8')
PORT = int(os.getenv('PORT', 8080))

# Setup Logging
logging.basicConfig(format='%(asctime)s - %(name)s - %(levelname)s - %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

# Simpan koneksi device
connected_devices: Set[WebSocket] = set()
last_chat_id = None
bot_app = None

# --- HANDLER PERINTAH TELEGRAM ---

async def send_command_to_all(command_payload):
    if not connected_devices:
        return False
    
    payload = json.dumps(command_payload)
    for ws in list(connected_devices):
        try:
            await ws.send_text(payload)
        except Exception as e:
            logger.error(f"Gagal mengirim ke device: {e}")
    return True

async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text("🤖 *Remote Control Aktif! (FastAPI)*", parse_mode='Markdown')

async def ping(update: Update, context: ContextTypes.DEFAULT_TYPE):
    global last_chat_id
    last_chat_id = update.effective_chat.id
    if await send_command_to_all({"command": "ping"}):
        await update.message.reply_text("⏳ Menghubungi device (Ping)...")
    else:
        await update.message.reply_text("❌ Tidak ada device yang terhubung.")

async def battery(update: Update, context: ContextTypes.DEFAULT_TYPE):
    global last_chat_id
    last_chat_id = update.effective_chat.id
    if await send_command_to_all({"command": "get_battery"}):
        await update.message.reply_text("⏳ Mengambil status baterai...")
    else:
        await update.message.reply_text("❌ Tidak ada device yang terhubung.")

async def toast(update: Update, context: ContextTypes.DEFAULT_TYPE):
    global last_chat_id
    last_chat_id = update.effective_chat.id
    text = " ".join(context.args) if context.args else "Halo dari Remote!"
    if await send_command_to_all({"command": "show_toast", "text": text}):
        await update.message.reply_text(f"⏳ Mengirim pesan toast: {text}")
    else:
        await update.message.reply_text("❌ Tidak ada device yang terhubung.")

# --- LIFECYCLE APLIKASI ---

@asynccontextmanager
async def lifespan(app: FastAPI):
    global bot_app
    # 1. Setup Telegram Bot
    bot_app = ApplicationBuilder().token(TELEGRAM_TOKEN).build()
    bot_app.add_handler(CommandHandler("start", start))
    bot_app.add_handler(CommandHandler("ping", ping))
    bot_app.add_handler(CommandHandler("battery", battery))
    bot_app.add_handler(CommandHandler("toast", toast))
    
    await bot_app.initialize()
    await bot_app.start()
    
    # Jalankan Polling di background
    polling_task = asyncio.create_task(bot_app.updater.start_polling())
    logger.info("Bot Telegram dimulai dengan Polling.")
    
    yield
    
    # Shutdown (Dijalankan saat aplikasi berhenti)
    await bot_app.updater.stop()
    await bot_app.stop()
    await bot_app.shutdown()
    polling_task.cancel()
    logger.info("Bot dan Server dihentikan.")

app = FastAPI(lifespan=lifespan)

# --- ROUTES ---

@app.get("/")
async def root():
    return {"status": "running", "framework": "FastAPI", "connected_devices": len(connected_devices)}

@app.get("/health", response_class=PlainTextResponse)
async def health():
    return "OK\n"

@app.websocket("/ws")
@app.websocket("/")  # Dukungan untuk path root lama
async def websocket_endpoint(websocket: WebSocket):
    global last_chat_id
    await websocket.accept()
    logger.info("Device baru terhubung melalui WebSocket")
    connected_devices.add(websocket)
    
    try:
        while True:
            data = await websocket.receive_text()
            logger.info(f"Diterima dari device: {data}")
            if last_chat_id:
                try:
                    payload = json.loads(data)
                    response_text = f"📱 *Respon dari Device:*\n`{json.dumps(payload, indent=2)}`"
                    await bot_app.bot.send_message(chat_id=last_chat_id, text=response_text, parse_mode='Markdown')
                except Exception as e:
                    logger.error(f"Gagal mengirim pesan ke Telegram: {e}")
    except WebSocketDisconnect:
        logger.info("Device terputus")
    finally:
        connected_devices.remove(websocket)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=PORT)
