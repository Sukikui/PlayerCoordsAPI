<div align="center">

# PlayerCoordsAPI

A lightweight Fabric mod that exposes your Minecraft player coordinates via a local HTTP API.

</div>

## 📋 Overview

PlayerCoordsAPI provides real-time access to your Minecraft player coordinates through a simple HTTP endpoint. This enables external applications to track your position without needing to read Minecraft's memory or capture the screen.

## ✨ Features

- Lightweight HTTP server running only on localhost providing your coordinates
- Client-side only - no server-side components needed
- Works in singleplayer and multiplayer
- Mod menu integration allowing you to enable/disable the API

## 🚀 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.4
2. Download the latest `playercoordsapi-x.x.x+mcx.x.x.jar` from the [releases page](https://github.com/Sukikui/PlayerCoordsAPI/releases)
3. Place the jar in your `.minecraft/mods` folder
4. Launch Minecraft with the Fabric profile

## 🔌 API Usage

| Endpoint      | Method | Description                                              |
|---------------|--------|----------------------------------------------------------|
| `/api/coords` | `GET`  | Returns the player's current coordinates and world infos |

### Response Format

```json
{
  "x": 123.45,
  "y": 64.00,
  "z": -789.12,
  "world": "overworld",
  "biome": "plains"
}
```

### Response Fields

| Field   | Type     | Description     |
|---------|----------|-----------------|
| `x`     | `number` | East-West       |
| `y`     | `number` | Height          |
| `z`     | `number` | North-South     |
| `world` | `string` | Minecraft world |
| `biome` | `string` | Minecraft biome |

### Error Responses

| Status | Message             |
|--------|---------------------|
| `403`  | Access denied       |
| `404`  | Player not in world |

## 🔒 Security

For security reasons, the API server:
- Only accepts connections from localhost `127.0.0.1`
- Runs on port `25565` by default
- Provides read-only access to player position data

## 🛠️ Examples

### cURL
```bash
curl http://localhost:25565/api/coords
```

### Python
```python
import requests

response = requests.get("http://localhost:25565/api/coords")
data = response.json()
print(f"Player at X: {data['x']}, Y: {data['y']}, Z: {data['z']}")
```

### JavaScript
```javascript
fetch("http://localhost:25565/api/coords")
  .then(response => response.json())
  .then(data => console.log(`Player at X: ${data.x}, Y: ${data.y}, Z: ${data.z}`));
```

<div align="center">
Made with ❤️ by 
<img src="https://crafatar.com/avatars/7d2159e810514c3eb504c279cadd4273?size=100&overlay" width="20" height="20" style="vertical-align: -3px;"> 
Sukikui
</div>