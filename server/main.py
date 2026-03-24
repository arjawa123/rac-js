import os
import json
import logging
import asyncio
import uuid
from typing import Dict
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Query
from fastapi.responses import PlainTextResponse
from telegram import Update
from telegram.ext import ApplicationBuilder, CommandHandler, ContextTypes
from contextlib import asynccontextmanager
from dotenv import load_dotenv

load_dotenv()

# --- KONFIGURASI ---
TELEGRAM_TOKEN = os.getenv('TELEGRAM_TOKEN', '')
AUTH_TOKEN = os.getenv('AUTH_TOKEN', 'my-secret-token')
IS_TOKEN_VALID = TELEGRAM_TOKEN and "YOUR_BOT" not in TELEGRAM_TOKEN

logging.basicConfig(format='%(asctime)s - %(name)s - %(levelname)s - %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

# Simpan koneksi dengan ID unik
connected_devices: Dict[str, WebSocket] = {}
bot_app = None

async def start_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text("Device Control Bot Active.\nCommands:\n/list - List connected devices\n/cmd [id] [command] - Send command")

async def list_devices_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not connected_devices:
        await update.message.reply_text("No devices connected.")
        return
    
    msg = "Connected Devices:\n"
    for dev_id in connected_devices.keys():
        msg += f"- `{dev_id}`\n"
    await update.message.reply_text(msg, parse_mode='Markdown')

async def send_cmd_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if len(context.args) < 2:
        await update.message.reply_text("Usage: /cmd [device_id] [command] [optional_text]")
        return
    
    dev_id = context.args[0]
    command = context.args[1]
    extra_text = " ".join(context.args[2:]) if len(context.args) > 2 else ""

    if dev_id not in connected_devices:
        await update.message.reply_text(f"Device {dev_id} not found.")
        return

    ws = connected_devices[dev_id]
    payload = {
        "command": command,
        "text": extra_text
    }
    
    try:
        await ws.send_text(json.dumps(payload))
        await update.message.reply_text(f"Command '{command}' sent to {dev_id}")
    except Exception as e:
        await update.message.reply_text(f"Error sending command: {e}")

async def run_bot_in_background():
    global bot_app
    if not IS_TOKEN_VALID:
        logger.warning("TELEGRAM_TOKEN tidak valid. Bot tidak dijalankan.")
        return

    try:
        bot_app = ApplicationBuilder().token(TELEGRAM_TOKEN).build()
        bot_app.add_handler(CommandHandler("start", start_handler))
        bot_app.add_handler(CommandHandler("list", list_devices_handler))
        bot_app.add_handler(CommandHandler("cmd", send_cmd_handler))
        
        await bot_app.initialize()
        await bot_app.start()
        if bot_app.updater:
            await bot_app.updater.start_polling()
            logger.info("Bot Telegram berhasil berjalan dengan handler.")
    except Exception as e:
        logger.error(f"Gagal menjalankan bot: {e}")

@asynccontextmanager
async def lifespan(app: FastAPI):
    asyncio.create_task(run_bot_in_background())
    yield
    if bot_app:
        try:
            if bot_app.updater: await bot_app.updater.stop()
            await bot_app.stop()
            await bot_app.shutdown()
        except: pass

app = FastAPI(lifespan=lifespan)

@app.get("/")
async def root():
    return {
        "status": "running",
        "bot_active": bot_app is not None,
        "connected_devices": list(connected_devices.keys())
    }

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket, client_id: str = Query(None), auth: str = Query(None)):
    if auth != AUTH_TOKEN:
        await websocket.accept() # Accept then close to send code if needed, or just close
        await websocket.close(code=4003) 
        logger.warning(f"Unauthorized connection attempt from {client_id}")
        return

    await websocket.accept()
    
    # Gunakan ID yang diberikan client atau generate baru
    dev_id = client_id or str(uuid.uuid4())[:8]
    connected_devices[dev_id] = websocket
    
    logger.info(f"Device connected: {dev_id}")
    
    try:
        while True:
            data = await websocket.receive_text()
            # Log response dari device (bisa diteruskan ke Telegram jika perlu)
            logger.info(f"Response from {dev_id}: {data}")
    except WebSocketDisconnect:
        logger.info(f"Device disconnected: {dev_id}")
    finally:
        if dev_id in connected_devices:
            del connected_devices[dev_id]
