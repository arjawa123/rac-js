import os
import json
import logging
import asyncio
from typing import Set
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import PlainTextResponse
from telegram.ext import ApplicationBuilder, CommandHandler
from contextlib import asynccontextmanager
from dotenv import load_dotenv

load_dotenv()

# --- KONFIGURASI ---
TELEGRAM_TOKEN = os.getenv('TELEGRAM_TOKEN', '')
# Cek apakah token valid (tidak kosong dan bukan placeholder)
IS_TOKEN_VALID = TELEGRAM_TOKEN and "YOUR_BOT" not in TELEGRAM_TOKEN

logging.basicConfig(format='%(asctime)s - %(name)s - %(levelname)s - %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

connected_devices: Set[WebSocket] = set()
last_chat_id = None
bot_app = None

async def run_bot_in_background():
    """Fungsi untuk menjalankan bot tanpa memblokir web server"""
    global bot_app
    if not IS_TOKEN_VALID:
        logger.warning("TELEGRAM_TOKEN tidak valid. Bot tidak dijalankan.")
        return

    try:
        bot_app = ApplicationBuilder().token(TELEGRAM_TOKEN).build()
        # Tambahkan handler di sini jika perlu
        await bot_app.initialize()
        await bot_app.start()
        if bot_app.updater:
            await bot_app.updater.start_polling()
            logger.info("Bot Telegram berhasil berjalan di background.")
    except Exception as e:
        logger.error(f"Gagal menjalankan bot: {e}")

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Jalankan bot di background tanpa 'await' agar startup FastAPI instan
    asyncio.create_task(run_bot_in_background())
    yield
    if bot_app:
        try:
            if bot_app.updater:
                await bot_app.updater.stop()
            await bot_app.stop()
            await bot_app.shutdown()
        except:
            pass

app = FastAPI(lifespan=lifespan)

@app.get("/")
async def root():
    return {
        "status": "running",
        "bot_active": bot_app is not None,
        "token_configured": IS_TOKEN_VALID,
        "connected_devices": len(connected_devices)
    }

@app.get("/health")
async def health():
    return "OK"

@app.websocket("/ws")
@app.websocket("/")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    connected_devices.add(websocket)
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        pass
    finally:
        connected_devices.remove(websocket)
