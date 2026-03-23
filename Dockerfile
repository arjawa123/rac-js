FROM python:3.11-slim

WORKDIR /app

# Salin requirements dan instal
COPY server/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Salin semua kode server
COPY server/ ./server/

# Port default yang akan diexpose
EXPOSE 8080

# Jalankan server
CMD ["python", "server/bot_server.py"]
