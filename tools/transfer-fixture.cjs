'use strict';
// 仅供本机验收。启动：node tools/transfer-fixture.cjs；停止：Ctrl+C。
const http = require('node:http');
const net = require('node:net');
const { createHash, randomBytes } = require('node:crypto');
const { once } = require('node:events');

const HOST = '127.0.0.1', GUEST = '10.0.2.2', ORIGIN = 38881, PROXY = 38882;
const authority = `${GUEST}:${ORIGIN}`;
const node = 'FixtureHTTP';
const selector = `AND,((IP-CIDR,${GUEST}/32),(DST-PORT,${ORIGIN}),(SRC-PORT,41000-44999)),PROXY`;
// 两个源端口区间为同一应用提供可复现的规则对照；应用自己的 UID 规则应优先于此处。
const profile = `proxies:
  - name: ${node}
    type: http
    server: ${GUEST}
    port: ${PROXY}
proxy-groups:
  - name: PROXY
    type: select
    proxies:
      - ${node}
rules:
  - ${selector}
  - MATCH,DIRECT
`;
const upload = Buffer.alloc(4 * 1024 * 1024);
for (let i = 0; i < upload.length; i++) upload[i] = (i * 17 + (i >>> 9)) & 255;
const download = Buffer.alloc(128 * 1024);
for (let i = 0; i < download.length; i++) download[i] = (i * 31 + (i >>> 8)) & 255;
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const uploadHash = sha(upload), downloadHash = sha(download);
const newId = () => randomBytes(12).toString('hex');
const log = event => console.log(JSON.stringify({ time: new Date().toISOString(), ...event }));

async function start() {
  // 键是代理真实上游 socket 的本地端口，不信任客户端自报的路由标记。
  const tunnels = new Map();
  const sockets = new Set();
  const origin = http.createServer({ maxHeaderSize: 8192 }, (req, res) => {
    const port = req.socket.remotePort;
    const tunnel = tunnels.get(port) || null;
    const id = req.headers['x-fixture-id'] ?? newId();
    const path = ['/upload', '/download', '/profile.yaml'].includes(req.url) ? req.url : null;
    const reply = (status, body, type, extra = {}) => {
      const validId = typeof id === 'string' && /^[0-9a-f]{24}$/.test(id);
      log({ event: 'request', id: validId ? id : null, tunnel, port, path,
        method: req.method, status, ...extra });
      res.writeHead(status, { 'Content-Type': type, 'Content-Length': Buffer.byteLength(body),
        'Cache-Control': 'no-store', Connection: 'close', 'X-Fixture-Id': validId ? id : '-',
        'X-Fixture-Connect': tunnel || '-' });
      res.end(body);
    };
    const reject = code => reply(code, '', 'text/plain');
    if (![authority, `${HOST}:${ORIGIN}`].includes(req.headers.host)) return reject(403);
    if (typeof id !== 'string' || !/^[0-9a-f]{24}$/.test(id)) return reject(400);
    if (!path) return reject(404);
    if (path === '/profile.yaml' && req.method === 'GET') return reply(200, profile, 'text/yaml; charset=utf-8');
    if (path === '/download' && req.method === 'GET') {
      return reply(200, download, 'application/octet-stream', { bytes: download.length, sha256: downloadHash });
    }
    if (path !== '/upload' || req.method !== 'POST') return reject(405);
    if (req.headers['content-length'] !== String(upload.length)) return reject(413);
    const hash = createHash('sha256');
    let received = 0;
    req.on('data', chunk => { received += chunk.length; hash.update(chunk); });
    req.on('end', () => {
      const sha256 = hash.digest('hex');
      if (received !== upload.length || sha256 !== uploadHash) return reject(422);
      reply(200, JSON.stringify({ received, sha256 }), 'application/json', { bytes: received, sha256 });
    });
    req.on('error', () => res.destroy());
  });
  const proxy = http.createServer({ maxHeaderSize: 8192 }, (_req, res) => {
    log({ event: 'proxy-reject', status: 405 });
    res.writeHead(405, { Allow: 'CONNECT', Connection: 'close' }).end();
  });
  proxy.on('connect', (req, client, head) => {
    if (req.url !== authority) {
      log({ event: 'connect-reject', status: 403 });
      return client.end('HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
    }
    const tunnel = newId();
    // 不解析用户提供的地址或做 DNS 查询；唯一可能的目标是本进程已绑定的 origin。
    const upstream = net.connect(ORIGIN, HOST);
    let port;
    const destroy = () => { client.destroy(); upstream.destroy(); };
    client.on('error', destroy).on('close', () => upstream.destroy());
    upstream.on('error', destroy).on('close', () => {
      if (tunnels.get(port) === tunnel) tunnels.delete(port);
    });
    client.setTimeout(15000, destroy);
    upstream.setTimeout(15000, destroy);
    upstream.once('connect', () => {
      port = upstream.localPort;
      tunnels.set(port, tunnel);
      log({ event: 'connect', tunnel, port, authority });
      client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (head.length) upstream.write(head);
      client.pipe(upstream).pipe(client);
    });
  });
  const servers = [origin, proxy];
  const close = async () => {
    const closing = servers.filter(s => s.listening).map(s => new Promise(resolve => s.close(resolve)));
    for (const socket of sockets) socket.destroy();
    await Promise.all(closing);
  };
  try {
    for (const [i, server] of servers.entries()) {
      server.maxConnections = 32;
      server.setTimeout(15000, socket => socket.destroy());
      server.on('connection', socket => {
        sockets.add(socket);
        socket.on('close', () => sockets.delete(socket));
      });
      server.listen({ host: HOST, port: [ORIGIN, PROXY][i], exclusive: true });
      await once(server, 'listening');
    }
  } catch (error) { await close(); throw error; }
  return close;
}

module.exports = { HOST, GUEST, ORIGIN, PROXY, authority, node, selector, profile, upload, download, sha, newId, start };
if (require.main === module) {
  start().then(close => {
    log({ event: 'ready', bind: HOST, origin: ORIGIN, proxy: PROXY, profile: `http://${authority}/profile.yaml` });
    process.once('SIGINT', close);
    process.once('SIGTERM', close);
  }).catch(e => { console.error(e.message); process.exitCode = 1; });
}
