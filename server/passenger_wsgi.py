import os
import sys

# Tambahkan direktori saat ini ke path pencarian modul Python
sys.path.insert(0, os.path.dirname(__file__))

# Import aplikasi FastAPI dari main.py
from main import app

# Bungkus aplikasi ASGI (FastAPI) menjadi WSGI agar kompatibel dengan Passenger di cPanel
from a2wsgi import ASGIMiddleware
application = ASGIMiddleware(app)
