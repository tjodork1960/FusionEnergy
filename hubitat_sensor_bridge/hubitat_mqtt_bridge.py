import time
import json
import logging
import threading
import queue
import uuid
import socket
import os
from datetime import datetime
from zoneinfo import ZoneInfo

import paho.mqtt.client as mqtt
from influxdb_client import InfluxDBClient, Point
from influxdb_client.client.write_api import SYNCHRONOUS
from prometheus_client import start_http_server, Gauge

# --- Prometheus setup ---
start_http_server(8001, addr="0.0.0.0")
power_metric = Gauge('hubitat_power_watts', 'Power usage in watts', ['device'])
energy_metric = Gauge('hubitat_energy_kwh', 'Energy usage in kWh', ['device'])

# --- Logging ---
logging.basicConfig(level=logging.INFO)
print("Starting hubitat-mqtt-bridge...")

# --- MQTT config ---
MQTT_BROKER = os.getenv("MQTT_BROKER", "mosquitto")
MQTT_PORT = 1883
MQTT_SUB_TOPIC = "hubitat/tim-home-hub-000d/+/+"

# --- InfluxDB config ---
INFLUX_URL = "http://192.168.1.62:8086"
INFLUX_TOKEN = "PTcss1WGatG7VGj3BJF7lUFgacwSICDVga4fTIdCoadJFMBUeeeL-k-pJDtpkXLlY8d7qO3pCE8dNBBZg5NhIw=="
INFLUX_ORG = "tjo"
INFLUX_BUCKET = "Power"

# --- Thresholds ---
POWER_THRESHOLD = 0.10
ENERGY_THRESHOLD = 0.05
MIN_DELTA_POWER = 10
MIN_DELTA_ENERGY = 5
BACKUP_INTERVAL = 180

# --- State ---
last_sent_values = {}
last_sent_time = {}
send_counter = 0
msg_queue = queue.Queue()

# --- InfluxDB client ---
influx = InfluxDBClient(url=INFLUX_URL, token=INFLUX_TOKEN, org=INFLUX_ORG)
write_api = influx.write_api(write_options=SYNCHRONOUS)

# --- Helper ---
def should_write(metric_key, value, threshold, min_delta):
    now = time.time()
    last_value = last_sent_values.get(metric_key)
    last_time = last_sent_time.get(metric_key, 0)

    if last_value is None:
        reason = "initial"
        trigger = True
    else:
        delta = abs(value - last_value)
        pct = (delta / max(abs(last_value), 1e-6)) * 100
        trigger = (delta >= min_delta and pct >= threshold * 100) or (now - last_time >= BACKUP_INTERVAL)
        reason = "delta+percent" if trigger else "skip"

    if trigger:
        last_sent_values[metric_key] = value
        last_sent_time[metric_key] = now
        logging.info(f"[{metric_key}] WRITE: {value:.2f} ({reason})")
        return True
    return False

# --- Worker ---
def process_queue():
    while True:
        topic, payload = msg_queue.get()
        try:
            parts = topic.split("/")
            if len(parts) != 4 or parts[0] != "hubitat":
                logging.warning(f"Unexpected topic format: {topic}")
                return
            _, hub_id, device_name, metric = parts
            value = float(payload.strip())
            metric_key = f"{device_name}_{metric}"

            if metric == "power":
                if should_write(metric_key, value, POWER_THRESHOLD, MIN_DELTA_POWER):
                    power_metric.labels(device=device_name).set(value)
                    point = Point("hubitat_power").tag("device", device_name).field("power", round(value, 3))
                    write_api.write(bucket=INFLUX_BUCKET, record=point)
            elif metric == "energy":
                if should_write(metric_key, value, ENERGY_THRESHOLD, MIN_DELTA_ENERGY):
                    energy_metric.labels(device=device_name).set(value)
                    point = Point("hubitat_power").tag("device", device_name).field("energy", round(value, 3))
                    write_api.write(bucket=INFLUX_BUCKET, record=point)

        except Exception as e:
            logging.error("Error processing Hubitat message:", exc_info=e)
        finally:
            msg_queue.task_done()

# --- MQTT callbacks ---
def on_connect(client, userdata, flags, rc):
    logging.info(f"Connected to MQTT broker {MQTT_BROKER} with code {rc}")
    client.subscribe(MQTT_SUB_TOPIC, qos=1)

def on_message(client, userdata, msg):
    msg_queue.put((msg.topic, msg.payload.decode()))

# --- MQTT setup ---
client_id = f"hubitat_bridge_{uuid.uuid4().hex[:8]}"
client = mqtt.Client(client_id=client_id, clean_session=True)
client.on_connect = on_connect
client.on_message = on_message

try:
    resolved_ip = socket.gethostbyname(MQTT_BROKER)
    logging.info(f"Resolved {MQTT_BROKER} to {resolved_ip}")
except Exception as e:
    logging.warning("DNS resolution failed:", exc_info=e)

client.connect(MQTT_BROKER, MQTT_PORT, 60)
client.reconnect_delay_set(min_delay=1, max_delay=30)
client.loop_start()

# --- Start worker thread ---
threading.Thread(target=process_queue, daemon=True).start()

# --- Keep alive ---
try:
    while True:
        time.sleep(1)
except KeyboardInterrupt:
    logging.info("Stopping hubitat-mqtt-bridge...")