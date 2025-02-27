<div align="center">

# PlayerCoordsAPI

![Version](https://img.shields.io/badge/version-1.0.0-blue)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.4-green)
![Fabric](https://img.shields.io/badge/Fabric-0.16.10-orange)
![License](https://img.shields.io/badge/license-MIT-yellow)

A lightweight Fabric mod that exposes your Minecraft player coordinates via a local HTTP API.

</div>

## 📋 Overview

PlayerCoordsAPI provides real-time access to your Minecraft player coordinates through a simple HTTP endpoint. This enables external applications to track your position without needing to read Minecraft's memory or capture the screen.

## ✨ Features

- ✅ Lightweight HTTP server running only on localhost
- ✅ JSON-formatted coordinate data (X, Y, Z)
- ✅ Current dimension information
- ✅ Client-side only - no server-side components needed
- ✅ Works in singleplayer and multiplayer

## 🚀 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.4
2. Download the latest `playercoordsapi-x.x.x.jar` from the [releases page](https://github.com/Sukikui/PlayerCoordsAPI/releases)
3. Place the jar in your `.minecraft/mods` folder
4. Launch Minecraft with the Fabric profile

## 🔌 API Usage

| Endpoint  | Method | Description                                            |
|-----------|--------|--------------------------------------------------------|
| `/coords` | `GET`  | Returns the player's current coordinates and dimension |

### Response Format

```json
{
  "x": 123.45,
  "y": 64.00,
  "z": -789.12,
  "dimension": "minecraft:overworld"
}
```

### Response Fields

| Field       | Type     | Description                                                             |
|-------------|----------|-------------------------------------------------------------------------|
| `x`         | `number` | X-coordinate (East/West position)                                       |
| `y`         | `number` | Y-coordinate (Height/Depth)                                             |
| `z`         | `number` | Z-coordinate (North/South position)                                     |
| `dimension` | `string` | Current dimension (e.g., "minecraft:overworld", "minecraft:the_nether") |

### Error Responses

| Status  | Description                                                      |
|---------|------------------------------------------------------------------|
| `403`   | Access denied (only localhost connections are allowed)           |
| `404`   | Player not in world (returned when player data is not available) |

## 🔒 Security

For security reasons, the API server:
- Only accepts connections from localhost `127.0.0.1`
- Runs on port `25565` by default
- Provides read-only access to player position data

## 🛠️ Examples

### cURL
```bash
curl http://localhost:25565/coords
```

### Python
```python
import requests

response = requests.get("http://localhost:25565/coords")
data = response.json()
print(f"Player at X: {data['x']}, Y: {data['y']}, Z: {data['z']} in {data['dimension']}")
```

### JavaScript/Node.js
```javascript
fetch("http://localhost:25565/coords")
  .then(response => response.json())
  .then(data => console.log(`Player at X: ${data.x}, Y: ${data.y}, Z: ${data.z} in ${data.dimension}`));
```

<div align="center">
Made with ❤️ by Sukikui
</div>