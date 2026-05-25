# FusionEnergy Stack

A Docker-based energy monitoring stack that reads data from a **Fusion SEM** (Smart Energy Monitor), pushes it to **Grafana Cloud**, and forwards it to a **Hubitat** home automation hub.

## Architecture

```
SEM Power Monitor
       │  MQTT (SEMMETER/+/HA)
   mosquitto  ◄─────────────────────────── Hubitat Hub
       │                                        ▲
mqtt_prometheus_bridge                 hubitat/sem_meter/#
       │
       ├── Prometheus :8000 ──► grafana-agent ──► Grafana Cloud
       └── hubitat/sem_meter/#
                                   hubitat_sensor_bridge
                                   (Hubitat device sensors)
                                        │
                                   Prometheus :8001 ──► grafana-agent ──► Grafana Cloud
```

### Containers

| Container | Purpose | Port |
|-----------|---------|------|
| `mosquitto` | MQTT broker | 1883, 9001 |
| `mqtt-prometheus-bridge` | Reads SEM data, exposes Prometheus metrics, forwards to Hubitat | 8000 |
| `hubitat-sensor-bridge` | Reads Hubitat device sensors, exposes Prometheus metrics | 8001 |
| `grafana-agent` | Scrapes :8000 and :8001, pushes to Grafana Cloud | — |

---

## Getting Started

### Prerequisites
- Docker + Docker Compose
- A Fusion SEM publishing to MQTT
- A Grafana Cloud account (free tier works)
- A Hubitat hub with the MQTT app installed (optional)

### 1. Clone the repo

```bash
git clone https://github.com/tjodork1960/FusionEnergy.git
cd FusionEnergy
```

### 2. Create your config files from the examples

```bash
cp grafana-agent.yaml.example grafana-agent.yaml
cp mqtt_prometheus_bridge/mqtt_prometheus_bridge.py.example \
   mqtt_prometheus_bridge/mqtt_prometheus_bridge.py
cp hubitat_sensor_bridge/hubitat_sensor_bridge.py.example \
   hubitat_sensor_bridge/hubitat_sensor_bridge.py
```

### 3. Fill in your values

#### `grafana-agent.yaml`
- `username` — your Grafana Cloud Prometheus username (found in Grafana Cloud → My Account → Prometheus)
- `password` — your Grafana Cloud API key
- `url` — your Grafana Cloud remote_write URL

#### `mqtt_prometheus_bridge/mqtt_prometheus_bridge.py`
- Replace `YOUR_SEM_DEVICE_MAC_1` / `YOUR_SEM_DEVICE_MAC_2` with your SEM device MAC addresses
  (find these by watching MQTT traffic: `mosquitto_sub -t "SEMMETER/#" -v`)
- Fill in your circuit names for each CT channel (1–19). Use `"NA"` for unused channels.
- Name paired circuits with `-A` / `-B` suffix — they are automatically merged:
  `Main-A` + `Main-B` → `Main`

#### `hubitat_sensor_bridge/hubitat_sensor_bridge.py`
- Replace `YOUR-HUB-ID` with your Hubitat hub ID
  (visible in the MQTT topic Hubitat publishes to, e.g. `hubitat/your-hub-id/...`)

### 4. Create the meter seed files

```bash
echo "0" > meter.txt
echo "{}" > meter_state.json
```

`meter.txt` anchors the projected meter reading. Update it with your actual meter kWh reading whenever you want to re-sync:

**Windows:**
```powershell
Set-Content -Path ".\meter.txt" -Value "12345" -Encoding Ascii
```
**Linux:**
```bash
echo "12345" > meter.txt
```

### 5. Start the stack

```bash
docker compose up -d --build
```

---

## Verifying it works

**Check all containers are running:**
```bash
docker ps
```

**Check the SEM bridge logs:**
```bash
docker logs mqtt-prometheus-bridge --tail 50
```

**Check Prometheus metrics are populated:**
Open `http://localhost:8000/metrics` — you should see `ct_power_watts` and `ct_energy_kwh` entries with your circuit names.

**Check Hubitat sensor metrics:**
Open `http://localhost:8001/metrics` — you should see `hubitat_power_watts` and `hubitat_energy_kwh` entries.

---

## Runtime tuning

### Log flags (`log_config.json`)
Edit `log_config.json` to toggle verbose logging without restarting any container:

| Flag | What it logs |
|------|-------------|
| `log_hubitat` | Every message published to Hubitat |
| `log_grafana` | Every CT metric pushed to Prometheus |
| `log_projected` | Projected meter updates |
| `log_senseblock1` | Raw CT sense data from SEM device 1 |
| `log_senseblock2` | Raw CT sense data from SEM device 2 |
| `log_power_shouldwrite` | Throttle decisions for power metrics |
| `log_energy_shouldwrite` | Throttle decisions for energy metrics |

### Updating the meter reading
When you want to re-sync the projected meter to your actual meter reading:

```powershell
# Windows
Set-Content -Path ".\meter.txt" -Value "12345" -Encoding Ascii
```
The bridge watches this file and updates automatically — no restart needed.

---

## Hubitat Integration

The `HubitatDrivers/` folder contains Groovy drivers to install on your Hubitat hub:

- **Fusion Energy Main** — parent driver, connects to MQTT and creates child devices per CT
- **Fusion CT Child** — child driver, one per circuit, exposes power/energy attributes

Install via: Hubitat → Drivers Code → New Driver → paste the Groovy code.

---

## Grafana Cloud

Metrics available in Grafana Cloud:

| Metric | Labels | Description |
|--------|--------|-------------|
| `ct_power_watts` | `ct_name` | Current power draw per circuit (W) |
| `ct_energy_kwh` | `ct_name` | Cumulative energy per circuit (kWh) |
| `hubitat_power_watts` | `device` | Power from Hubitat-connected devices (W) |
| `hubitat_energy_kwh` | `device` | Energy from Hubitat-connected devices (kWh) |

Example PromQL for total home power:
```promql
ct_power_watts{ct_name="Main"}
```

---

## File structure

```
FusionEnergy/
├── docker-compose.yml
├── grafana-agent.yaml              ← created from .example (gitignored)
├── grafana-agent.yaml.example
├── log_config.json
├── meter.txt                       ← your meter reading (gitignored)
├── meter_state.json                ← auto-managed (gitignored)
├── mosquitto/
│   └── config/mosquitto.conf
├── mqtt_prometheus_bridge/
│   ├── mqtt_prometheus_bridge.py   ← created from .example (gitignored)
│   ├── mqtt_prometheus_bridge.py.example
│   ├── Dockerfile
│   └── requirements.txt
├── hubitat_sensor_bridge/
│   ├── hubitat_sensor_bridge.py    ← created from .example (gitignored)
│   ├── hubitat_sensor_bridge.py.example
│   ├── Dockerfile
│   └── requirements.txt
├── HubitatDrivers/
│   ├── Fusion Energy Main - Good.txt
│   └── Fusion CT Child Good.txt
└── wal/                            ← Grafana Agent buffer (gitignored)
```
