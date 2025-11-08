#!/usr/bin/env node

const WebSocket = require('ws');
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const compression = require('compression');
const winston = require('winston');
const { v4: uuidv4 } = require('uuid');
const crypto = require('crypto');
require('dotenv').config();

// Configure logging
const logger = winston.createLogger({
    level: process.env.LOG_LEVEL || 'info',
    format: winston.format.combine(
        winston.format.timestamp(),
        winston.format.errors({ stack: true }),
        winston.format.json()
    ),
    defaultMeta: { service: 'screenflow-relay' },
    transports: [
        new winston.transports.File({ filename: 'logs/error.log', level: 'error' }),
        new winston.transports.File({ filename: 'logs/combined.log' }),
    ],
});

if (process.env.NODE_ENV !== 'production') {
    logger.add(new winston.transports.Console({
        format: winston.format.simple()
    }));
}

class ScreenFlowRelayServer {
    constructor() {
        this.port = process.env.PORT || 8081;
        this.clients = new Map(); // sessionId -> { pc, android, timestamp }
        this.connections = new Map(); // WebSocket -> connection info
        this.sessions = new Map(); // sessionId -> session data
        this.heartbeatInterval = 30000; // 30 seconds
        this.sessionTimeout = 600000; // 10 minutes
        this.maxConnectionsPerSession = 2; // PC + Android

        this.setupExpress();
        this.setupWebSocket();
        this.startCleanup();
    }

    setupExpress() {
        this.app = express();

        // Security middleware
        this.app.use(helmet({
            contentSecurityPolicy: false, // Disable CSP for WebSocket connections
            crossOriginEmbedderPolicy: false
        }));

        this.app.use(cors({
            origin: process.env.ALLOWED_ORIGINS ? process.env.ALLOWED_ORIGINS.split(',') : '*',
            methods: ['GET', 'POST'],
            allowedHeaders: ['Content-Type', 'Authorization', 'X-Session-ID']
        }));

        this.app.use(compression());
        this.app.use(express.json({ limit: '10mb' }));

        // Health check endpoint
        this.app.get('/health', (req, res) => {
            res.json({
                status: 'healthy',
                timestamp: new Date().toISOString(),
                connections: this.connections.size,
                sessions: this.clients.size
            });
        });

        // Session info endpoint
        this.app.get('/session/:sessionId', (req, res) => {
            const { sessionId } = req.params;
            const session = this.sessions.get(sessionId);

            if (!session) {
                return res.status(404).json({ error: 'Session not found' });
            }

            // Return session info without sensitive data
            res.json({
                sessionId: session.sessionId,
                createdAt: session.createdAt,
                expiresAt: session.expiresAt,
                status: session.status,
                participants: Object.keys(session.participants)
            });
        });

        // Static files for web interface (optional)
        this.app.use(express.static('public'));

        logger.info('Express server configured');
    }

    setupWebSocket() {
        this.wss = new WebSocket.Server({
            port: this.port + 1, // WebSocket on port 8082 if HTTP is on 8081
            verifyClient: (info) => this.verifyClient(info)
        });

        this.wss.on('connection', (ws, req) => {
            this.handleConnection(ws, req);
        });

        this.wss.on('error', (error) => {
            logger.error('WebSocket server error:', error);
        });

        logger.info(`WebSocket server listening on port ${this.port + 1}`);
    }

    verifyClient(info) {
        // Basic client verification - could add more sophisticated checks
        const origin = info.origin;
        const allowedOrigins = process.env.ALLOWED_ORIGINS
            ? process.env.ALLOWED_ORIGINS.split(',')
            : ['*'];

        return allowedOrigins.includes('*') || allowedOrigins.includes(origin);
    }

    handleConnection(ws, req) {
        const connectionId = uuidv4();
        const clientIP = req.socket.remoteAddress;

        logger.info(`New connection from ${clientIP}, connection ID: ${connectionId}`);

        // Store connection info
        this.connections.set(ws, {
            connectionId,
            clientIP,
            connectedAt: Date.now(),
            lastPing: Date.now(),
            type: 'unknown', // 'pc' or 'android'
            sessionId: null
        });

        ws.on('message', (message) => {
            this.handleMessage(ws, message);
        });

        ws.on('pong', () => {
            const connection = this.connections.get(ws);
            if (connection) {
                connection.lastPing = Date.now();
            }
        });

        ws.on('close', (code, reason) => {
            this.handleDisconnection(ws, code, reason);
        });

        ws.on('error', (error) => {
            logger.error(`WebSocket error for connection ${connectionId}:`, error);
            this.handleDisconnection(ws, 1006, 'Connection error');
        });

        // Send welcome message
        this.sendMessage(ws, {
            type: 'welcome',
            connectionId: connectionId,
            serverTime: Date.now()
        });
    }

    handleMessage(ws, message) {
        const connection = this.connections.get(ws);
        if (!connection) {
            logger.warn('Received message from unknown connection');
            return;
        }

        try {
            const data = JSON.parse(message.toString());
            logger.debug(`Received message type: ${data.type} from ${connection.connectionId}`);

            switch (data.type) {
                case 'pairing_request':
                    this.handlePairingRequest(ws, connection, data);
                    break;
                case 'pairing_response':
                    this.handlePairingResponse(ws, connection, data);
                    break;
                case 'video_frame':
                    this.handleVideoFrame(ws, connection, data);
                    break;
                case 'input_event':
                    this.handleInputEvent(ws, connection, data);
                    break;
                case 'ping':
                    this.handlePing(ws, connection);
                    break;
                case 'pong':
                    this.handlePong(ws, connection);
                    break;
                case 'disconnect':
                    this.handleDisconnectRequest(ws, connection, data);
                    break;
                case 'quality_change':
                    this.handleQualityChange(ws, connection, data);
                    break;
                default:
                    logger.warn(`Unknown message type: ${data.type}`);
            }
        } catch (error) {
            logger.error('Error parsing message:', error);
            this.sendError(ws, 'Invalid message format');
        }
    }

    handlePairingRequest(ws, connection, data) {
        const { sessionId, deviceId, deviceName, encryptionKey } = data;

        if (!sessionId) {
            this.sendError(ws, 'Session ID required for pairing');
            return;
        }

        // Validate session
        if (!this.sessions.has(sessionId)) {
            this.sessions.set(sessionId, {
                sessionId,
                createdAt: Date.now(),
                expiresAt: Date.now() + this.sessionTimeout,
                status: 'active',
                encryptionKey,
                participants: {}
            });
        }

        const session = this.sessions.get(sessionId);

        // Check session expiration
        if (Date.now() > session.expiresAt) {
            this.sendError(ws, 'Session expired');
            return;
        }

        // Check connection limits
        const participantCount = Object.keys(session.participants).length;
        if (participantCount >= this.maxConnectionsPerSession) {
            this.sendError(ws, 'Session full - maximum participants reached');
            return;
        }

        // Determine device type based on request pattern
        const isPC = data.deviceName && !data.deviceName.includes('Android');
        connection.type = isPC ? 'pc' : 'android';
        connection.sessionId = sessionId;

        // Add to session participants
        session.participants[connection.connectionId] = {
            deviceId,
            deviceName,
            type: connection.type,
            connectedAt: Date.now()
        };

        // Add to clients map
        if (!this.clients.has(sessionId)) {
            this.clients.set(sessionId, { pc: null, android: null, timestamp: Date.now() });
        }

        const clientData = this.clients.get(sessionId);
        clientData[connection.type] = ws;
        clientData.timestamp = Date.now();

        // Update connection info
        connection.deviceId = deviceId;
        connection.deviceName = deviceName;

        // Send pairing response
        this.sendMessage(ws, {
            type: 'pairing_response',
            status: 'success',
            sessionId: sessionId,
            participants: Object.keys(session.participants)
        });

        // Notify other participants if both devices are connected
        if (clientData.pc && clientData.android) {
            const bothConnectedMessage = {
                type: 'session_complete',
                sessionId: sessionId,
                participants: session.participants
            };

            this.sendMessage(clientData.pc, bothConnectedMessage);
            this.sendMessage(clientData.android, bothConnectedMessage);
        }

        logger.info(`Pairing successful: ${connection.type} (${deviceName}) joined session ${sessionId}`);
    }

    handlePairingResponse(ws, connection, data) {
        // Handle pairing response if needed
        logger.debug(`Pairing response from ${connection.connectionId}`);
    }

    handleVideoFrame(ws, connection, data) {
        if (connection.type !== 'android') {
            logger.warn('Non-Android client attempting to send video frame');
            return;
        }

        const session = this.clients.get(connection.sessionId);
        if (!session || !session.pc) {
            logger.warn('No PC client to forward video frame to');
            return;
        }

        // Forward video frame to PC client
        this.sendMessage(session.pc, {
            type: 'video_frame',
            data: data.data || {},
            timestamp: Date.now(),
            from: connection.connectionId
        });
    }

    handleInputEvent(ws, connection, data) {
        if (connection.type !== 'pc') {
            logger.warn('Non-PC client attempting to send input event');
            return;
        }

        const session = this.clients.get(connection.sessionId);
        if (!session || !session.android) {
            logger.warn('No Android client to forward input event to');
            return;
        }

        // Forward input event to Android client
        this.sendMessage(session.android, {
            type: 'input_event',
            data: data.data || {},
            timestamp: Date.now(),
            from: connection.connectionId
        });
    }

    handlePing(ws, connection) {
        this.sendMessage(ws, {
            type: 'pong',
            timestamp: Date.now()
        });
        connection.lastPing = Date.now();
    }

    handlePong(ws, connection) {
        connection.lastPing = Date.now();
    }

    handleDisconnectRequest(ws, connection, data) {
        logger.info(`Disconnect requested from ${connection.connectionId}`);
        this.handleDisconnection(ws, 1000, data.reason || 'Client requested disconnect');
    }

    handleQualityChange(ws, connection, data) {
        // Forward quality change to Android client
        const session = this.clients.get(connection.sessionId);
        if (!session || !session.android) {
            return;
        }

        this.sendMessage(session.android, {
            type: 'quality_change',
            quality: data.quality,
            timestamp: Date.now(),
            from: connection.connectionId
        });
    }

    handleDisconnection(ws, code, reason) {
        const connection = this.connections.get(ws);
        if (!connection) {
            return;
        }

        logger.info(`Connection ${connection.connectionId} disconnected: ${code} - ${reason}`);

        // Remove from session
        if (connection.sessionId) {
            const session = this.sessions.get(connection.sessionId);
            if (session) {
                delete session.participants[connection.connectionId];

                // Notify other participants
                const disconnectMessage = {
                    type: 'participant_disconnected',
                    connectionId: connection.connectionId,
                    deviceType: connection.type,
                    reason: reason
                };

                const clientData = this.clients.get(connection.sessionId);
                if (clientData) {
                    if (connection.type === 'pc' && clientData.android) {
                        this.sendMessage(clientData.android, disconnectMessage);
                    } else if (connection.type === 'android' && clientData.pc) {
                        this.sendMessage(clientData.pc, disconnectMessage);
                    }

                    // Remove from clients map
                    clientData[connection.type] = null;
                }
            }
        }

        // Remove from connections map
        this.connections.delete(ws);
    }

    sendMessage(ws, message) {
        if (ws.readyState === WebSocket.OPEN) {
            try {
                ws.send(JSON.stringify(message));
            } catch (error) {
                logger.error('Error sending message:', error);
            }
        }
    }

    sendError(ws, error) {
        this.sendMessage(ws, {
            type: 'error',
            error: error,
            timestamp: Date.now()
        });
    }

    startCleanup() {
        // Clean up expired sessions and inactive connections
        setInterval(() => {
            this.cleanupExpiredSessions();
            this.cleanupInactiveConnections();
        }, 60000); // Every minute

        // Heartbeat for connections
        setInterval(() => {
            this.sendHeartbeat();
        }, this.heartbeatInterval);

        logger.info('Cleanup tasks started');
    }

    cleanupExpiredSessions() {
        const now = Date.now();
        for (const [sessionId, session] of this.sessions.entries()) {
            if (now > session.expiresAt) {
                logger.info(`Session ${sessionId} expired, cleaning up`);

                // Close all connections in the session
                const clientData = this.clients.get(sessionId);
                if (clientData) {
                    if (clientData.pc) {
                        clientData.pc.close(1000, 'Session expired');
                    }
                    if (clientData.android) {
                        clientData.android.close(1000, 'Session expired');
                    }
                    this.clients.delete(sessionId);
                }

                this.sessions.delete(sessionId);
            }
        }
    }

    cleanupInactiveConnections() {
        const now = Date.now();
        const timeout = this.heartbeatInterval * 2; // 2x heartbeat interval

        for (const [ws, connection] of this.connections.entries()) {
            if (now - connection.lastPing > timeout) {
                logger.info(`Connection ${connection.connectionId} timeout, closing`);
                ws.close(1000, 'Connection timeout');
            }
        }
    }

    sendHeartbeat() {
        for (const [ws, connection] of this.connections.entries()) {
            if (ws.readyState === WebSocket.OPEN) {
                ws.ping();
            }
        }
    }

    start() {
        this.httpServer = this.app.listen(this.port, () => {
            logger.info(`ScreenFlow Relay Server started on port ${this.port}`);
            logger.info(`WebSocket server listening on port ${this.port + 1}`);
        });

        this.httpServer.on('error', (error) => {
            logger.error('HTTP server error:', error);
        });

        // Graceful shutdown
        process.on('SIGTERM', () => this.shutdown());
        process.on('SIGINT', () => this.shutdown());
    }

    shutdown() {
        logger.info('Shutting down relay server...');

        // Close all WebSocket connections
        for (const [ws, connection] of this.connections.entries()) {
            ws.close(1001, 'Server shutdown');
        }

        // Close HTTP server
        if (this.httpServer) {
            this.httpServer.close(() => {
                logger.info('HTTP server closed');
            });
        }

        // Close WebSocket server
        if (this.wss) {
            this.wss.close(() => {
                logger.info('WebSocket server closed');
            });
        }

        process.exit(0);
    }

    getStats() {
        return {
            connections: this.connections.size,
            sessions: this.sessions.size,
            clients: this.clients.size,
            uptime: process.uptime(),
            memory: process.memoryUsage()
        };
    }
}

// Start the server
if (require.main === module) {
    const server = new ScreenFlowRelayServer();
    server.start();
}

module.exports = ScreenFlowRelayServer;