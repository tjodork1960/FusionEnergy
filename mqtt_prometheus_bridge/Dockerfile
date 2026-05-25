FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
RUN pip install prometheus_client
RUN mkdir -p /app/logs

COPY mqtt_prometheus_bridge.py .

CMD ["python", "mqtt_prometheus_bridge.py"]