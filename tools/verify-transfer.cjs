'use strict';
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const { randomInt } = require('node:crypto');
const { join } = require('node:path');
const { HOST, GUEST, ORIGIN, PROXY, authority, node, selector, profile, upload, download, sha, newId, start } = require('./transfer-fixture.cjs');

const probe = 'com.roottun.probe', manager = 'com.roottun.clash';
const runtime = 'files/clash/mihomo/config.yaml', log = 'files/clash/logs/app.log';
const usage = `主机自测（不使用 adb）：node tools/verify-transfer.cjs host
设备验收：node tools/verify-transfer.cjs rules|uid-proxy|uid-direct [adb序列号]
1. 另开终端启动 node tools/transfer-fixture.cjs。MuMu 已手动连接 adb；安装主应用、probe-debug.apk 和 probe-debug-androidTest.apk；两应用均须为可 run-as 的调试包。
2. 仅在独立测试配置中手动加载 http://${authority}/profile.yaml 并启动 TUN。不要替换现存订阅。
3. rules：探针按订阅规则；uid-proxy / uid-direct：手动设置探针 UID 强制代理 / 内核 DIRECT 后再分别运行。
三个模式都测试上下行及反向规则对照。设备侧不安装、不启动服务、不写配置，仅按 UID 发送固定测试字节。
若 MuMu 不支持该 NAT 别名、缺少工具/日志或未命中内核，返回非零，不退回设备回环或 root。`;
const quote = s => `'${s.replace(/'/g, `'\\''`)}'`;
const escapeRe = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

function request(path, id) {
  const post = path === '/upload';
  const head = `${post ? 'POST' : 'GET'} ${path} HTTP/1.1\r\nHost: ${authority}\r\nConnection: close\r\nX-Fixture-Id: ${id}\r\n`
    + (post ? `Content-Type: application/octet-stream\r\nContent-Length: ${upload.length}\r\n` : '') + '\r\n';
  return post ? Buffer.concat([Buffer.from(head), upload]) : Buffer.from(head);
}

function checkedResponse(raw, path, id, route) {
  const split = raw.indexOf('\r\n\r\n');
  assert(split > 0 && split < 8192, '未收到完整 HTTP 头');
  const lines = raw.subarray(0, split).toString('latin1').split('\r\n');
  assert.match(lines.shift(), /^HTTP\/1\.[01] 200(?: |$)/, 'origin 未返回 200');
  const headers = Object.fromEntries(lines.map(s => {
    const colon = s.indexOf(':');
    return [s.slice(0, colon).toLowerCase(), s.slice(colon + 1).trim()];
  }));
  const body = raw.subarray(split + 4), tunnel = headers['x-fixture-connect'];
  assert.equal(headers['x-fixture-id'], id, '不是本次请求的夹具回执');
  assert.equal(headers['content-length'], String(body.length), '响应未完整接收');
  assert.match(tunnel || '', route === 'proxy' ? /^[0-9a-f]{24}$/ : /^-$/, `夹具实际 CONNECT=${tunnel}，预期 ${route}`);
  const bytes = path === '/upload' ? upload : download;
  const sha256 = sha(bytes);
  if (path === '/upload') assert.deepEqual(JSON.parse(body), { received: bytes.length, sha256 });
  else assert.equal(sha(body), sha256, '下载内容 SHA256 不符');
  return { id, path, route, tunnel, bytes: bytes.length, sha256 };
}

async function hostTest() {
  const net = require('node:net');
  const close = await start();
  async function exchange(port, bytes) {
    const sock = net.connect(port, HOST), chunks = [];
    sock.setTimeout(6000, () => sock.destroy(new Error('主机自测超时')));
    sock.write(bytes);
    try { for await (const chunk of sock) chunks.push(chunk); }
    finally { sock.destroy(); }
    return Buffer.concat(chunks);
  }
  const connect = target => Buffer.from(`CONNECT ${target} HTTP/1.1\r\nHost: ${target}\r\n\r\n`);
  try {
    const yaml = await exchange(ORIGIN, request('/profile.yaml', newId()));
    assert.equal(yaml.subarray(yaml.indexOf('\r\n\r\n') + 4).toString(), profile);
    for (const route of ['direct', 'proxy']) for (const path of ['/upload', '/download']) {
      const id = newId(), data = request(path, id);
      let raw = await exchange(route === 'proxy' ? PROXY : ORIGIN,
        route === 'proxy' ? Buffer.concat([connect(authority), data]) : data);
      if (route === 'proxy') {
        assert.match(raw.subarray(0, 40).toString(), /^HTTP\/1.1 200 Connection Established\r\n\r\n/);
        raw = raw.subarray(raw.indexOf('\r\n\r\n') + 4);
      }
      checkedResponse(raw, path, id, route);
    }
    const bad = request('/upload', newId());
    bad[bad.length - 1] ^= 1;
    assert.match((await exchange(ORIGIN, bad)).toString(), /^HTTP\/1.1 422 /);
    for (const target of [`${GUEST}:${PROXY}`, `${HOST}:${ORIGIN}`, `localhost:${ORIGIN}`]) {
      assert.match((await exchange(PROXY, connect(target))).toString(), /^HTTP\/1.1 403 /);
    }
    assert.match((await exchange(PROXY, request('/download', newId()))).toString(), /^HTTP\/1.1 405 /);
    console.log('主机自测通过：上下行、CONNECT 关联、配置及拒绝转发；不代表设备验收。');
  } finally { await close(); }
}

function device(mode, serial) {
  const adb = process.env.ADB || (process.platform === 'win32' ? join(__dirname, '../android-sdk/platform-tools/adb.exe') : 'adb');
  function shell(pkg, script, input) {
    const result = spawnSync(adb, ['-s', serial, 'shell', '-T', `run-as ${pkg} sh -c ${quote(script)}`], {
      input: input || Buffer.alloc(0), timeout: 20000, maxBuffer: 512 * 1024, windowsHide: true,
    });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(`run-as ${pkg} 退出 ${result.status}；${result.stderr.toString().trim().slice(0, 300)}`);
    return result.stdout;
  }
  const managerRead = cmd => shell(manager, cmd).toString().trim();
  const uid = Number(shell(probe, 'id -u').toString().trim());
  assert(Number.isSafeInteger(uid) && uid % 100000 >= 10000, '未取得探针应用 UID');
  assert.notEqual(uid, Number(managerRead('id -u')), '探针不能是管理应用自身的绕行 UID');
  // 不导出运行配置/订阅，只在设备端比较合成配置里特有的固定规则和测试节点。
  managerRead(`grep -Fqx ${quote(`  - ${selector}`)} ${runtime} && grep -Fqx '  - name: ${node}' ${runtime} || { echo '未激活测试配置或无权读取' >&2; exit 1; }`);
  // run-as validates identity and reads evidence; exercise the actual package process for socket attribution.
  const used = new Set();
  for (const base of ['direct', 'proxy']) for (const path of ['/upload', '/download']) {
    const route = mode === 'rules' ? base : mode.slice(4);
    const id = newId();
    let source;
    do { source = randomInt(4000) + (base === 'proxy' ? 41000 : 45000); } while (used.has(source));
    used.add(source);
    // 固定源端口将本次读写与全新的 MIHOMO/TCP 匹配记录关联；不依赖设备和主机时钟同步。
    const before = Number(managerRead(`wc -c < ${log}`));
    assert(Number.isSafeInteger(before) && before >= 0, '不能定位内核日志');
    const command = `am instrument -w -r -e source ${source} -e path ${path} -e requestId ${id} ${probe}.test/${probe}.TransferInstrumentation`;
    const attempt = spawnSync(adb, ['-s', serial, 'shell', '-T', command], { encoding: 'utf8', timeout: 30000, maxBuffer: 512 * 1024, windowsHide: true });
    if (attempt.error) throw attempt.error;
    const reply = attempt.stdout || '';
    assert.match(reply, /INSTRUMENTATION_CODE: -1/, (reply.match(/INSTRUMENTATION_RESULT: failure=([^\n]+)/) || [])[1] || '探针未启动或传输未完成');
    assert.match(reply, new RegExp(`INSTRUMENTATION_RESULT: uid=${uid}\\b`));
    const encoded = reply.match(/INSTRUMENTATION_RESULT: response64=([A-Za-z0-9+/=]+)/);
    assert(encoded, '缺少应用内 socket 响应');
    const raw = Buffer.from(encoded[1], 'base64');
    const receipt = checkedResponse(raw, path, id, route);
    // 仅传回本次源端口和夹具地址的 TCP 行，不导出其它连接或完整日志。日志被轮换时保守失败。
    const hits = managerRead(`tail -c +${before + 1} ${log} | grep -F 'MIHOMO:' | grep -F '[TCP]' | grep -F ':${source}' | grep -F -- '--> ${authority} ' | head -n 6`);
    const sourceHit = new RegExp(`\\[TCP\\] [^\\s]+:${source}(?:\\([^)]*\\))? --> ${escapeRe(authority)} match (.*?) using (.+?)(?:"|$)`, 'i');
    const matches = hits.split('\n').map(line => line.match(sourceHit)).filter(Boolean);
    const policy = route === 'direct' ? /^DIRECT$/ : /^(?:FixtureHTTP|[^\s\[\]"]+\[FixtureHTTP\])$/;
    const hit = matches.find(([, rule, outbound]) => {
      if (!policy.test(outbound)) return false;
      if (mode === 'rules') return base === 'direct' ? /^Match$/i.test(rule)
        : /^AND\(/i.test(rule) && /\(Src-?Port,41000-44999\)/i.test(rule);
      // 兼容单 UID 规则与应用生成的 TUN + UID + 包名组合规则；订阅的 AND 不能充当 UID 命中。
      const plainUid = new RegExp(`^Uid\\(${uid}\\)$`, 'i');
      return plainUid.test(rule) || (/^AND\(/i.test(rule) && new RegExp(`\\(Uid,${uid}\\)`, 'i').test(rule)
        && /\(In-?Type,Tun\)/i.test(rule) && /Process-?Name-?Regex/i.test(rule)
        && rule.replaceAll('\\', '').includes(probe));
    });
    console.log(JSON.stringify({ mode, pkg: probe, uid, source, defaultRoute: base, ...receipt,
      kernel: hit ? hit[0] : null, observed: hit ? undefined : matches.map(m => m[0]) }));
    assert(hit, `收到 origin 回执，但没有本次源端口 ${source}、目标、规则、出站全部吻合的内核记录；不算通过`);
  }
  console.log(`设备阶段通过：${mode}。完整验收需在对应设置下分别通过三个阶段。`);
}

async function main() {
  const [mode, serial = '127.0.0.1:16384', ...rest] = process.argv.slice(2);
  if (!mode || mode === '--help') return console.log(usage);
  if (mode === 'host' && process.argv.length === 3) return hostTest();
  if (!['rules', 'uid-proxy', 'uid-direct'].includes(mode) || rest.length || !/^[A-Za-z0-9.:_-]+$/.test(serial)) throw new Error(usage);
  device(mode, serial);
}
main().catch(e => { console.error(`未通过：${e.message}`); process.exitCode = 1; });
