# ScreenFlow Relay Server

WebSocket relay server for ScreenFlow remote screen mirroring when direct P2P connections are not possible.

## Features

- WebSocket-based relay for screen mirroring data
- Session management with encryption
- Automatic connection cleanup and timeout handling
- Support for PC-to-Android and Android-to-PC message forwarding
- Health monitoring and statistics
- Graceful shutdown handling

## Installation

```bash
npm install
```

## Configuration

Create a `.env` file in the project root:

```env
# Server configuration
PORT=8081
LOG_LEVEL=info
NODE_ENV=production

# Security
ALLOWED_ORIGINS=http://localhost:3000,https://screenflow.app

# Session settings
SESSION_TIMEOUT=600000
HEARTBEAT_INTERVAL=30000
MAX_CONNECTIONS_PER_SESSION=2
```

## Usage

### Development

```bash
npm run dev
```

### Production

```bash
npm start
```

### Health Check

```bash
curl http://localhost:8081/health
```

## API Endpoints

### Health Check
`GET /health`

Returns server status and statistics:
```json
{
  "status": "healthy",
  "timestamp": "2024-01-15T10:30:00.000Z",
  "connections": 2,
  "sessions": 1
}
```

### Session Info
`GET /session/:sessionId`

Returns session information:
```json
{
  "sessionId": "abc123",
  "createdAt": "2024-01-15T10:25:00.000Z",
  "expiresAt": "2024-01-15T10:35:00.000Z",
  "status": "active",
  "participants": ["conn1", "conn2"]
}
```

## WebSocket Protocol

### Connection
- WebSocket server runs on port 8082 (HTTP server on 8081)
- Supports CORS for cross-origin requests
- Client verification based on origin

### Message Types

#### Pairing Request
```json
{
  "type": "pairing_request",
  "sessionId": "abc123",
  "deviceId": "device123",
  "deviceName": "Samsung Galaxy S21",
  "encryptionKey": "hex_key_here"
}
```

#### Video Frame
```json
{
  "type": "video_frame",
  "data": {
    "size": 1024,
    "timestamp": 1642248600000
  }
}
```

#### Input Event
```json
{
  "type": "input_event",
  "data": {
    "type": "touch_down",
    "x": 500,
    "y": 300,
    "button": 1
  }
}
```

#### Ping/Pong
```json
{
  "type": "ping",
  "timestamp": 1642248600000
}
```

## Architecture

```
┌─────────────┐     WebSocket     ┌─────────────┐     WebSocket     ┌─────────────┐
│   PC App    │ ◄──────────────► │ Relay Server│ ◄──────────────► │Android App  │
└─────────────┘                 └─────────────┘                 └─────────────┘
```

### Components

1. **Express Server**: HTTP API for health checks and session info
2. **WebSocket Server**: Real-time message relay between clients
3. **Session Manager**: Handles session creation, validation, and cleanup
4. **Connection Manager**: Manages client connections and message forwarding
5. **Health Monitor**: Tracks server performance and connection health

### Session Management

- Each session can have maximum 2 participants (PC + Android)
- Sessions automatically expire after 10 minutes of inactivity
- Encrypted session keys for secure communication
- Automatic cleanup of expired sessions and inactive connections

### Message Forwarding

- Video frames: Android → PC
- Input events: PC → Android
- Quality changes: PC → Android
- Control messages: Bidirectional

## Security

- CORS protection for HTTP endpoints
- Client origin verification
- Session-based authentication
- Encrypted communication keys
- Automatic session timeout

## Monitoring

### Logs
- Winston-based logging with multiple transports
- Error logs to `logs/error.log`
- Combined logs to `logs/combined.log`
- Console output in development mode

### Statistics
- Connection count
- Active sessions
- Server uptime
- Memory usage

## Deployment

### Docker
```dockerfile
FROM node:18-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production
COPY . .
EXPOSE 8081 8082
CMD ["npm", "start"]
```

### Systemd Service
```ini
[Unit]
Description=ScreenFlow Relay Server
After=network.target

[Service]
Type=simple
User=screenflow
WorkingDirectory=/opt/screenflow-relay
ExecStart=/usr/bin/npm start
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

## Performance

- Handles multiple concurrent sessions
- Message buffering and queue management
- Connection heartbeat and health monitoring
- Memory-efficient message forwarding
- Automatic cleanup of inactive resources

## Troubleshooting

### Common Issues

1. **Connection refused**: Check if ports 8081/8082 are available
2. **CORS errors**: Verify `ALLOWED_ORIGINS` configuration
3. **Session timeout**: Increase `SESSION_TIMEOUT` if needed
4. **Memory usage**: Monitor and adjust connection limits

### Debug Mode
```bash
LOG_LEVEL=debug npm start
```

### Health Monitoring
```bash
curl -s http://localhost:8081/health | jq .
```

## License

MIT License