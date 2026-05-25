FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY hubitat_sensor_bridge.py .

CMD ["python", "hubitat_sensor_bridge.py"]
