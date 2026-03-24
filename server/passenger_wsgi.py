import os
import sys

# Pastikan path aplikasi terbaca
sys.path.insert(0, os.path.dirname(__file__))

# Import aplikasi dari main.py
from main import app

# Jembatan ASGI ke WSGI untuk cPanel
from a2wsgi import ASGIMiddleware
application = ASGIMiddleware(app)
