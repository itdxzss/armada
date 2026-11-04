// 本机复现 Web 协议（armada-protocol）的私聊发送，用来和真手机、zhuan 对比“我们 Web 协议发出去的是什么”。
// 与线上一致的部分：
//   - 同一份 Baileys（armada-protocol/protocol-layer/node_modules/baileys，7.0.0-rc13 + 补丁）
//   - 同样的 socket 参数（socket-factory.ts / config.ts 默认值：不标记在线、不拉全量历史、浏览器 Mac OS/Chrome/14.4.1）
//   - 同样的组包与发送步骤（worker-consumer.ts generateHyperlinkPrivateMessage → relayMessage）：
//       TEXT：generateWAMessage(jid, { text }) → relayMessage
//       LINK_CARD：dist 里编译好的 buildLinkCardContent → generateWAMessage → relayMessage
//   - 发送前不发“正在输入”（线上私聊链路也不发）
// 不一样的部分：不走 Kafka/Redis/代理，直接本机网络；账号是扫码临时挂上的分身。
//
// 用法：node web-send.mjs --to <对方号码> [--send] [--gap 60] [--keep]
//   不带 --send 只登录、不发消息（演练）。带 --send：登录 10 秒后发 "hi"，隔 --gap 秒发苹果链接卡片，再等 20 秒注销退出。
// 记录：runs/web-send-<北京时间>/tx.jsonl（我们发出的每一帧，含令牌全文十六进制）、rx.jsonl（收到的帧）、sent.jsonl（发出的消息内容）
import { mkdirSync, mkdtempSync, writeFileSync, appendFileSync, readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { execFile } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = fileURLToPath(new URL('.', import.meta.url))
const PLROOT = (process.env.PROTOCOL_LAYER ?? process.env.HOME + '/daixz工作空间/armada/armada-protocol/protocol-layer')
const PL = `${PLROOT}/node_modules`
const { default: makeWASocket, useMultiFileAuthState, DisconnectReason, fetchLatestWaWebVersion, generateWAMessage, proto } =
  await import(`${PL}/baileys/lib/index.js`)
const { default: pino } = await import(`${PL}/pino/pino.js`)
const { buildLinkCardContent } = await import(`${PLROOT}/dist/protocol-layer/src/messages/card-content.js`)

const args = process.argv.slice(2)
const arg = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d }
const to = (arg('--to', '') || '').replace(/\D/g, '')
const doSend = args.includes('--send')
const gap = Number(arg('--gap', '60'))
const keep = args.includes('--keep')
if (!to) { console.error('用法：node web-send.mjs --to <对方号码> [--send]'); process.exit(2) }
const jid = `${to}@s.whatsapp.net`

const LINK = {
  url: 'https://www.apple.com/iphone/',
  title: 'iPhone',
  description: 'Designed for Apple Intelligence. Discover iPhone Duo, iPhone 18 Pro, iPhone Air, iPhone 17, and iPhone 17e.',
  thumbnail: { base64: readFileSync(join(here, 'assets', 'apple-iphone-og-800.jpg')).toString('base64') }
}

const bjMs = ms => new Date(ms).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) + '.' + String(ms % 1000).padStart(3, '0')
const mask = s => String(s).replace(/(\d{4})\d{4,}(\d{2})/g, '$1…$2')
const isBytes = v => v instanceof Uint8Array || (v && v.type === 'Buffer' && Array.isArray(v.data))
const toBuf = v => (v instanceof Uint8Array ? Buffer.from(v) : Buffer.from(v.data))
const FULL_HEX_TAGS = new Set(['tctoken', 'reporting_token', 'token', 'device-identity'])
function serNode(node, parentTag = '') {
  if (!node || typeof node !== 'object' || !node.tag) return node
  const out = { tag: node.tag, attrs: { ...(node.attrs || {}) } }
  const c = node.content
  if (Array.isArray(c)) out.children = c.map(x => serNode(x, node.tag))
  else if (isBytes(c)) {
    const b = toBuf(c); out.bytes = b.length
    if (FULL_HEX_TAGS.has(node.tag) || FULL_HEX_TAGS.has(parentTag)) out.hex = b.toString('hex')
    else out.head = b.subarray(0, 16).toString('hex')
  } else if (typeof c === 'string') out.text = c
  return out
}
function plain(v, d = 0) {
  if (v === null || v === undefined) return undefined
  if (d > 25) return '[深度截断]'
  if (isBytes(v)) return { $bytes: toBuf(v).length }
  if (typeof v === 'object' && 'low' in v && 'high' in v && 'unsigned' in v) return BigInt.asUintN(64, (BigInt(v.high >>> 0) << 32n) | BigInt(v.low >>> 0)).toString()
  if (Array.isArray(v)) return v.map(x => plain(x, d + 1))
  if (typeof v === 'object') { const o = {}; for (const k of Object.keys(v)) { const p = plain(v[k], d + 1); if (p !== undefined) o[k] = p } return o }
  return v
}

let version = [2, 3000, 1044409164]
try { const r = await fetchLatestWaWebVersion({ signal: AbortSignal.timeout(10000) }); if (r?.isLatest) version = r.version } catch {}
const stamp = new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).replace(/[ :]/g, '').replace(/-/g, '')
const runDir = join(here, 'runs', `web-send-${stamp}`)
mkdirSync(runDir, { recursive: true })
const authDir = mkdtempSync(join(tmpdir(), 'web-send-auth-'))
const qrPage = join(here, 'qr-sender.html')
const log = pino({ level: 'debug' }, pino.destination({ dest: join(runDir, 'baileys-debug.log'), sync: false }))
console.log(`Web 版本 ${version.join('.')}；目标 ${mask(to)}；${doSend ? '会发送 2 条' : '演练：不发送'}；记录 ${runDir}`)

let sock, loggedIn = false, sentStarted = false, ending = false
const sentIds = new Set()

function writeQr(qr) {
  writeFileSync(qrPage, `<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="15">
<title>发送方扫码</title><body style="font-family:sans-serif;text-align:center;background:#fff;color:#111">
<h3>【发送方 B 手机】WhatsApp → 设置 → 已关联的设备 → 关联新设备，扫下面的码</h3>
<div id="q" style="display:inline-block;padding:16px;background:#fff"></div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
<script>new QRCode(document.getElementById('q'), {text: ${JSON.stringify(qr)}, width: 320, height: 320})</script>`)
}

async function finish(reason) {
  if (ending) return
  ending = true
  console.log(`结束（${reason}）`)
  if (sock && loggedIn && !keep) { try { await sock.logout(); console.log('已注销分身') } catch (e) { console.log('注销出错，请在 B 手机“已关联的设备”里手动退出：', e?.message) } }
  try { rmSync(authDir, { recursive: true, force: true }) } catch {}
  setTimeout(() => process.exit(0), 1500)
}
process.on('SIGINT', () => finish('Ctrl+C'))
process.on('SIGTERM', () => finish('停止信号'))
setTimeout(() => finish('超过 15 分钟'), 15 * 60 * 1000)

async function sendOne(kind) {
  const t0 = Date.now()
  const content = kind === 'TEXT'
    ? { text: 'hi' }
    : await buildLinkCardContent({ text: LINK.url, card: LINK, sock })
  const msg = await generateWAMessage(jid, content, { userJid: sock.user?.id ?? '', upload: sock.waUploadToServer })
  sentIds.add(msg.key.id)
  appendFileSync(join(runDir, 'sent.jsonl'), JSON.stringify({ t: Date.now(), kind, id: msg.key.id, message: plain(msg.message) }) + '\n')
  console.log(`[${bjMs(Date.now())}] 准备好 ${kind}（${Date.now() - t0}ms），relayMessage……`)
  await sock.relayMessage(jid, msg.message, { messageId: msg.key.id })
  console.log(`[${bjMs(Date.now())}] 已交给 relayMessage：${kind} id=${msg.key.id}`)
}

async function runSends() {
  if (sentStarted || !doSend) return
  sentStarted = true
  await new Promise(r => setTimeout(r, 10000))
  try { await sendOne('TEXT') } catch (e) { console.log('发送 hi 出错：', e?.message) }
  console.log(`等 ${gap} 秒再发链接……`)
  await new Promise(r => setTimeout(r, gap * 1000))
  try { await sendOne('LINK_CARD') } catch (e) { console.log('发送链接出错：', e?.message) }
  await new Promise(r => setTimeout(r, 20000))
  finish('发送完成')
}

async function start() {
  const { state, saveCreds } = await useMultiFileAuthState(authDir)
  sock = makeWASocket({
    version, auth: state, logger: log,
    markOnlineOnConnect: false, syncFullHistory: false, fireInitQueries: false, emitOwnEvents: false,
    shouldSyncHistoryMessage: ({ syncType }) => syncType !== proto.HistorySync.HistorySyncType.FULL,
    browser: ['Mac OS', 'Chrome', '14.4.1']
  })
  sock.ev.on('creds.update', saveCreds)
  sock.ws.on('traffic:tx', node => {
    if (!node || typeof node !== 'object' || !node.tag) return
    const t = Date.now()
    appendFileSync(join(runDir, 'tx.jsonl'), JSON.stringify({ t, node: serNode(node) }) + '\n')
    if (!loggedIn) return
    const a = node.attrs || {}
    const interesting = node.tag === 'message' || node.tag === 'chatstate' || node.tag === 'presence' ||
      (node.tag === 'iq' && ['privacy', 'usync', 'encrypt'].includes(a.xmlns)) || JSON.stringify(serNode(node)).includes('"tag":"tokens"')
    if (interesting) {
      const kids = Array.isArray(node.content) ? node.content.map(c => c.tag).join(',') : ''
      console.log(`[${bjMs(t)}] 发出 ${node.tag}${a.type ? ' type=' + a.type : ''}${a.xmlns ? ' xmlns=' + a.xmlns : ''}${a.to ? ' to=' + mask(a.to) : ''} [${kids}]`)
    }
  })
  sock.ws.on('frame', frame => {
    if (!frame || frame instanceof Uint8Array || !frame.tag) return
    const t = Date.now()
    appendFileSync(join(runDir, 'rx.jsonl'), JSON.stringify({ t, node: serNode(frame) }) + '\n')
    const a = frame.attrs || {}
    if (loggedIn && (frame.tag === 'ack' || frame.tag === 'receipt') && sentIds.has(a.id)) {
      console.log(`[${bjMs(t)}] 收到 ${frame.tag}${a.class ? ' class=' + a.class : ''}${a.type ? ' type=' + a.type : ''}${a.error ? ' error=' + a.error : ''} id=${a.id}`)
    }
  })
  sock.ws.on('CB:success', () => {
    loggedIn = true
    console.log(`已上线：发送方 ${mask(state.creds?.me?.id || '')}`)
    runSends()
  })
  sock.ev.on('connection.update', u => {
    if (u.qr) {
      writeQr(u.qr)
      if (!start.opened && !process.env.NO_OPEN) { start.opened = true; execFile('open', [qrPage]) }
      console.log('发送方二维码已更新：', qrPage)
    }
    if (u.connection === 'close') {
      const code = u.lastDisconnect?.error?.output?.statusCode
      if (ending) return
      if (code === DisconnectReason.loggedOut) { console.log('被登出（401）'); finish('被登出'); return }
      console.log('连接关闭，状态码', code, '，重连')
      setTimeout(start, code === DisconnectReason.restartRequired ? 0 : 3000)
    }
  })
}
await start()
