'use strict';

const express = require('express');
const http = require('http');
const path = require('path');
const crypto = require('crypto');
const WebSocket = require('ws');

const PORT = Number(process.env.PORT || 3000);
const DASHBOARD_TOKEN = process.env.DASHBOARD_TOKEN || '';
const DEVICE_TOKEN = process.env.DEVICE_TOKEN || '';
if (!DASHBOARD_TOKEN || !DEVICE_TOKEN) {
  console.warn('Vani Bobo: set DASHBOARD_TOKEN and DEVICE_TOKEN environment variables before exposing this server.');
}

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ noServer: true, maxPayload: 1024 * 1024 });
const devices = new Map();
const dashboards = new Set();

app.disable('x-powered-by');
app.use(express.json({ limit: '16kb' }));
app.use(express.static(path.join(__dirname, 'public'), { index: 'index.html', etag: true }));

function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || !a || !b) return false;
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}
function authDashboard(req, res, next) {
  const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  if (!safeEqual(token, DASHBOARD_TOKEN)) return res.status(401).json({ error: 'Authentication required' });
  next();
}
function sendJson(ws, data) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(data));
}
function broadcast(data) {
  for (const ws of dashboards) sendJson(ws, data);
}
function publicDevices() {
  return [...devices.entries()].map(([id, entry]) => ({
    id, status: entry.status || {}, connectedAt: entry.connectedAt
  }));
}

server.on('upgrade', (req, socket, head) => {
  let url;
  try { url = new URL(req.url, `http://${req.headers.host || 'localhost'}`); }
  catch { socket.destroy(); return; }
  const role = url.searchParams.get('role');
  const token = url.searchParams.get('token');
  if (role === 'dashboard' && safeEqual(token, DASHBOARD_TOKEN)) {
    wss.handleUpgrade(req, socket, head, ws => wss.emit('connection', ws, req, { role, id: null }));
    return;
  }
  if (role === 'device' && safeEqual(token, DEVICE_TOKEN)) {
    const id = (url.searchParams.get('id') || '').trim();
    if (!/^[a-zA-Z0-9_-]{1,48}$/.test(id)) { socket.destroy(); return; }
    wss.handleUpgrade(req, socket, head, ws => wss.emit('connection', ws, req, { role, id }));
    return;
  }
  socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
  socket.destroy();
});

wss.on('connection', (ws, req, identity) => {
  if (identity.role === 'dashboard') {
    dashboards.add(ws);
    sendJson(ws, { type: 'snapshot', devices: publicDevices() });
    ws.on('close', () => dashboards.delete(ws));
    ws.on('error', () => dashboards.delete(ws));
    return;
  }

  const { id } = identity;
  const old = devices.get(id);
  if (old?.ws && old.ws !== ws) old.ws.close(4001, 'Replaced by a newer connection');
  const entry = { ws, status: { online: true }, connectedAt: new Date().toISOString() };
  devices.set(id, entry);
  broadcast({ type: 'device_status', id, online: true, status: entry.status, connectedAt: entry.connectedAt });

  ws.on('message', raw => {
    let message;
    try { message = JSON.parse(raw.toString()); } catch { return; }
    if (message.type === 'status') {
      entry.status = {
        online: true,
        batteryPercent: Number.isFinite(message.batteryPercent) ? Math.max(0, Math.min(100, message.batteryPercent)) : null,
        charging: Boolean(message.charging),
        androidVersion: String(message.androidVersion || '').slice(0, 40),
        model: String(message.model || '').slice(0, 80),
        lastSeen: new Date().toISOString()
      };
      broadcast({ type: 'device_status', id, online: true, status: entry.status, connectedAt: entry.connectedAt });
    } else if (message.type === 'camera_frame' && typeof message.jpeg === 'string' && message.jpeg.length < 900_000) {
      broadcast({ type: 'camera_frame', id, jpeg: message.jpeg, at: Number(message.at) || Date.now() });
    } else if (message.type === 'result') {
      broadcast({ type: 'result', id, action: String(message.action || '').slice(0, 40), ok: Boolean(message.ok), message: String(message.message || '').slice(0, 300), at: new Date().toISOString() });
    }
  });
  ws.on('close', () => {
    if (devices.get(id)?.ws === ws) {
      devices.delete(id);
      broadcast({ type: 'device_status', id, online: false, status: {}, connectedAt: null });
    }
  });
  ws.on('error', () => {});
});

app.get('/api/health', (_req, res) => res.json({ app: 'Vani Bobo', status: 'ok' }));
app.get('/api/devices', authDashboard, (_req, res) => res.json({ devices: publicDevices() }));
app.post('/api/command', authDashboard, (req, res) => {
  const { id, action } = req.body || {};
  const allowed = new Set(['status', 'ring', 'stop_ring', 'open_settings', 'camera_start', 'camera_stop']);
  if (typeof id !== 'string' || !allowed.has(action)) return res.status(400).json({ error: 'Invalid device or unsupported action' });
  const entry = devices.get(id);
  if (!entry || entry.ws.readyState !== WebSocket.OPEN) return res.status(404).json({ error: 'Device is offline' });
  sendJson(entry.ws, { action, requestId: crypto.randomUUID() });
  res.json({ success: true, message: `Sent ${action} to ${id}` });
});

server.listen(PORT, '0.0.0.0', () => console.log(`Vani Bobo listening on port ${PORT}`));
