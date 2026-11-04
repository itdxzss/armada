// 接收探针：把一个个人号以“关联设备（分身）”身份挂到本机，原样记录这个号收到的一切，
// 用来看真手机首次给它发消息时到底带了什么（举报令牌、隐私令牌、正在输入、链接预览结构……）。
// 只收不发：不主动发任何消息；Baileys 会照常回送达回执（真设备也会回）。
// 不连接任何 Armada / WB Pro 环境。记录只存在本机 runs/ 目录，不进仓库。
//
// 用法：
//   node recv-probe.mjs                 生成二维码网页（自动打开），接收号手机 WhatsApp → 关联设备 → 扫码
//   node recv-probe.mjs --minutes 60    最长运行分钟数（默认 90），到时自动注销分身并出报告
//   node recv-probe.mjs --keep          结束时不注销分身（默认结束时注销）
//   node recv-probe.mjs report <目录>   对已有记录重新生成报告
// 结束：Ctrl+C 或 kill（SIGTERM），都会先注销分身、再写 report.md。
import { mkdirSync, mkdtempSync, writeFileSync, appendFileSync, readFileSync, rmSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { execFile } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = fileURLToPath(new URL('.', import.meta.url))  // 必须解码，路径里有中文
const args = process.argv.slice(2)

// ---------- 通用工具 ----------
const bjMs = ms => new Date(ms).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) +
  '.' + String(ms % 1000).padStart(3, '0')
const bjSec = s => bjMs(Number(s) * 1000)
// 号码打码：前 4 位…后 2 位；LID 同样处理
const maskUser = u => (u && u.length > 6 ? `${u.slice(0, 4)}…${u.slice(-2)}` : u || '')
const maskJid = jid => {
  if (!jid || typeof jid !== 'string') return jid
  const [user, server] = jid.split('@')
  const [num, dev] = user.split(':')
  return `${maskUser(num)}${dev !== undefined ? ':' + dev : ''}${server ? '@' + server : ''}`
}
const userOf = jid => (jid || '').split('@')[0].split(':')[0]
const isBytes = v => v instanceof Uint8Array || (v && v.type === 'Buffer' && Array.isArray(v.data))
const toBuf = v => (v instanceof Uint8Array ? Buffer.from(v) : Buffer.from(v.data))
// 这些子节点很小，全文十六进制保存；其余二进制只存长度和前 16 字节
const FULL_HEX_TAGS = new Set(['tctoken', 'reporting_token', 'token', 'trusted_contact', 'device-identity'])

// 二进制节点 → 可 JSON 的结构
function serNode(node, parentTag = '') {
  if (!node || typeof node !== 'object' || !node.tag) return node
  const out = { tag: node.tag, attrs: { ...(node.attrs || {}) } }
  const c = node.content
  if (Array.isArray(c)) out.children = c.map(x => serNode(x, node.tag))
  else if (isBytes(c)) {
    const b = toBuf(c)
    out.bytes = b.length
    if (FULL_HEX_TAGS.has(node.tag) || FULL_HEX_TAGS.has(parentTag)) out.hex = b.toString('hex')
    else out.head = b.subarray(0, 16).toString('hex')
    // 很多文本字段（如 member_since_ts）也是字节，顺手给出可打印文本
    const s = b.toString('utf8')
    if (b.length <= 200 && /^[\x20-\x7e]*$/.test(s)) out.text = s
  } else if (typeof c === 'string') out.text = c
  return out
}

// protobuf 解出来的消息 → 普通 JSON（字节写成长度+base64，Long 写成字符串）
function plain(v, depth = 0) {
  if (v === null || v === undefined) return undefined
  if (depth > 25) return '[深度截断]'
  if (isBytes(v)) { const b = toBuf(v); return { $bytes: b.length, b64: b.length <= 8192 ? b.toString('base64') : undefined } }
  if (typeof v === 'object' && 'low' in v && 'high' in v && 'unsigned' in v) {
    return BigInt.asUintN(64, (BigInt(v.high >>> 0) << 32n) | BigInt(v.low >>> 0)).toString()
  }
  if (Array.isArray(v)) return v.map(x => plain(x, depth + 1))
  if (typeof v === 'object') {
    const o = {}
    for (const k of Object.keys(v)) { const p = plain(v[k], depth + 1); if (p !== undefined) o[k] = p }
    return o
  }
  return v
}

// 结构轮廓：只列字段名和类型/长度，不带内容（报告里用）
function shape(v, depth = 0) {
  if (v === null || v === undefined) return undefined
  if (v && typeof v === 'object' && '$bytes' in v) return `bytes(${v.$bytes})`
  if (Array.isArray(v)) return v.length ? [shape(v[0], depth + 1), `…共${v.length}项`] : []
  if (typeof v === 'object') { const o = {}; for (const k of Object.keys(v)) o[k] = shape(v[k], depth + 1); return o }
  if (typeof v === 'string') return `string(${v.length})`
  return typeof v
}

// ---------- 报告 ----------
function readJsonl(f) {
  if (!existsSync(f)) return []
  return readFileSync(f, 'utf8').split('\n').filter(Boolean).map(l => { try { return JSON.parse(l) } catch { return null } }).filter(Boolean)
}
const kid = (n, tag) => (n?.children || []).find(c => c.tag === tag)
const kidsDeep = (n, tag, acc = []) => { for (const c of n?.children || []) { if (c.tag === tag) acc.push(c); kidsDeep(c, tag, acc) } return acc }

function buildReport(dir) {
  const frames = readJsonl(join(dir, 'frames.jsonl'))
  const msgs = readJsonl(join(dir, 'messages.jsonl'))
  const meta = existsSync(join(dir, 'meta.json')) ? JSON.parse(readFileSync(join(dir, 'meta.json'), 'utf8')) : {}
  const selfUsers = new Set([userOf(meta.selfPn), userOf(meta.selfLid)].filter(Boolean))

  // LID ↔ 手机号映射（从带 sender_pn / sender_lid 的帧里收集）
  const lidToPn = {}
  for (const f of frames) {
    const a = f.node.attrs || {}
    if (a.from?.endsWith('@lid') && a.sender_pn) lidToPn[userOf(a.from)] = userOf(a.sender_pn)
    if (a.from?.endsWith('@s.whatsapp.net') && a.sender_lid) lidToPn[userOf(a.sender_lid)] = userOf(a.from)
  }
  const senderKey = jid => { const u = userOf(jid); return lidToPn[u] || u }

  // 按发送方分组（排除自己、群、广播、服务器）
  const per = {}
  const get = k => (per[k] ||= { events: [], messages: [] })
  for (const f of frames) {
    const n = f.node, a = n.attrs || {}
    if (!['message', 'chatstate', 'notification', 'presence', 'call'].includes(n.tag)) continue
    const from = a.from || ''
    if (!from || from.endsWith('@g.us') || from.endsWith('@broadcast') || from.endsWith('@newsletter') || !from.includes('@') || from.startsWith('@') || from === 's.whatsapp.net') continue
    const k = a.sender_pn ? userOf(a.sender_pn) : senderKey(a.participant || from)
    if (selfUsers.has(k) || selfUsers.has(userOf(from))) continue
    get(k).events.push(f)
  }
  for (const m of msgs) {
    if (m.key?.fromMe) continue
    const rj = m.key?.remoteJid || ''
    if (rj.endsWith('@g.us') || rj.endsWith('@broadcast') || rj.endsWith('@newsletter')) continue
    const pn = [m.key?.remoteJidAlt, rj].find(j => j?.endsWith('@s.whatsapp.net'))
    const k = pn ? userOf(pn) : senderKey(rj)
    if (selfUsers.has(k)) continue
    get(k).messages.push(m)
  }

  const L = []
  L.push(`# 接收探针报告`, '')
  L.push(`- 记录目录：\`${dir}\``)
  L.push(`- 接收号（分身所挂的号）：${maskUser(userOf(meta.selfPn))}${meta.selfLid ? `（LID ${maskUser(userOf(meta.selfLid))}）` : ''}`)
  if (meta.startedAt) L.push(`- 开始：${bjMs(meta.startedAt)}　结束：${meta.endedAt ? bjMs(meta.endedAt) : '（未正常结束）'}`)
  L.push(`- 收到帧 ${frames.length} 个，解密消息 ${msgs.length} 条，涉及发送方 ${Object.keys(per).length} 个（已排除自己、群、频道）`, '')
  L.push('说明：接收端只能看到服务器转给我们的东西；发送方只发给服务器、服务器不转发的部分（如 usync、presence 订阅、签发令牌的 IQ 本身）看不到。', '')

  for (const [k, p] of Object.entries(per)) {
    L.push(`## 发送方 ${maskUser(k)}`, '')
    L.push('### 时间线（北京时间，毫秒）', '')
    L.push('| 时间 | 收到什么 | 要点 |', '|---|---|---|')
    let firstMsgT = null
    for (const f of p.events) {
      const n = f.node, a = n.attrs || {}
      let what = n.tag, note = ''
      if (n.tag === 'chatstate') {
        const st = (n.children || [])[0]?.tag || '?'
        what = `正在输入状态：${st}`
        if (st === 'composing' && kid(n.children?.[0], 'media')) note = '（录音）'
      } else if (n.tag === 'message') {
        if (firstMsgT === null) firstMsgT = f.t
        const encs = (n.children || []).filter(c => c.tag === 'enc')
        const childTags = (n.children || []).map(c => c.tag)
        what = `消息 type=${a.type || ''}${a.category ? ' category=' + a.category : ''}`
        note = [
          `外层属性：${Object.keys(a).filter(x => !['from', 'id', 't', 'notify', 'sender_pn', 'sender_lid', 'participant'].includes(x)).map(x => `${x}=${a[x]}`).join(' ') || '无'}`,
          `子节点：${childTags.join(', ')}`,
          `enc：${encs.map(e => `${e.attrs.type}/v${e.attrs.v}${e.attrs.mediatype ? '/' + e.attrs.mediatype : ''}(${e.bytes}B)`).join(' ')}`,
          `举报令牌：${kidsDeep(n, 'reporting_token').length ? '有' : '无'}`,
          `隐私令牌 tctoken：${kidsDeep(n, 'tctoken').length ? '有' : '无'}`
        ].join('；')
      } else if (n.tag === 'notification') {
        what = `通知 type=${a.type || ''}`
        if (a.type === 'privacy_token') {
          const toks = kidsDeep(n, 'token')
          note = `对方给我们签发隐私令牌：${toks.map(t => `type=${t.attrs.type || ''} t=${t.attrs.t ? bjSec(t.attrs.t) : ''} ${t.bytes || 0}B`).join('，') || '（无 token 子节点）'}`
        } else note = `子节点：${(n.children || []).map(c => c.tag).join(', ')}`
      } else if (n.tag === 'presence') {
        what = `在线状态 type=${a.type || 'available'}`
      }
      const rel = firstMsgT !== null && n.tag !== 'message' ? `（首条消息后 ${((f.t - firstMsgT) / 1000).toFixed(3)} 秒）` : ''
      L.push(`| ${bjMs(f.t)} | ${what} | ${note}${rel} |`)
    }
    L.push('')
    // “正在输入”到消息到达的间隔
    const msgsT = p.events.filter(f => f.node.tag === 'message').map(f => f.t)
    const composingT = p.events.filter(f => f.node.tag === 'chatstate' && f.node.children?.[0]?.tag === 'composing').map(f => f.t)
    if (msgsT.length) {
      const gaps = msgsT.map(t => { const before = composingT.filter(c => c <= t); return before.length ? ((t - before[before.length - 1]) / 1000).toFixed(3) : null })
      L.push(`- 每条消息前最近一次“正在输入”到消息到达的间隔（秒）：${gaps.map(g => g ?? '没有正在输入').join('，')}`, '')
    }
    L.push('### 解密后的消息结构（只列字段和类型/长度）', '')
    for (const m of p.messages) {
      L.push(`- ${bjMs(m._recvAt)}　id ${m.key?.id}　类型 ${Object.keys(m.message || {}).join(', ')}`)
      L.push('```json', JSON.stringify(shape(m.message), null, 2), '```')
      const ext = m.message?.extendedTextMessage
      if (ext) {
        L.push(`  - 链接预览：matchedText=${ext.matchedText ? '有' : '无'}，title=${ext.title ? '有' : '无'}，description=${ext.description ? '有' : '无'}，jpegThumbnail=${ext.jpegThumbnail ? ext.jpegThumbnail.$bytes + 'B' : '无'}，thumbnailDirectPath(高清缩略图上传)=${ext.thumbnailDirectPath ? '有' : '无'}，previewType=${ext.previewType ?? '无'}`)
      }
      L.push(`  - messageContextInfo.messageSecret（算举报令牌要用）：${m.message?.messageContextInfo?.messageSecret ? '有' : '无'}`, '')
    }
  }
  writeFileSync(join(dir, 'report.md'), L.join('\n'))
  return join(dir, 'report.md')
}

if (args[0] === 'report') {
  console.log('报告已写入：', buildReport(args[1]))
  process.exit(0)
}

// ---------- 运行 ----------
const PL = `${(process.env.PROTOCOL_LAYER ?? process.env.HOME + '/daixz工作空间/armada/armada-protocol/protocol-layer')}/node_modules`
const { default: makeWASocket, useMultiFileAuthState, DisconnectReason, Browsers, fetchLatestWaWebVersion } =
  await import(`${PL}/baileys/lib/index.js`)
const { default: pino } = await import(`${PL}/pino/pino.js`)
let version = [2, 3000, 1044409164]
try { const r = await fetchLatestWaWebVersion({ signal: AbortSignal.timeout(10000) }); if (r?.isLatest) version = r.version } catch {}

const minIdx = args.indexOf('--minutes')
const minutes = minIdx >= 0 ? Number(args[minIdx + 1]) : 90
const keep = args.includes('--keep')
const stamp = new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).replace(/[ :]/g, '').replace(/-/g, '')
const runDir = join(here, 'runs', stamp)
mkdirSync(join(runDir, 'thumbs'), { recursive: true })
const authDir = mkdtempSync(join(tmpdir(), 'recv-probe-auth-'))
const qrPage = join(here, 'qr.html')
const meta = { startedAt: Date.now(), waWebVersion: version.join('.'), minutes, selfPn: null, selfLid: null, endedAt: null }
const saveMeta = () => writeFileSync(join(runDir, 'meta.json'), JSON.stringify(meta, null, 2))
saveMeta()
console.log(`WhatsApp Web 版本 ${meta.waWebVersion}；记录目录：${runDir}；最长 ${minutes} 分钟`)

const baileysLog = pino({ level: 'debug' }, pino.destination({ dest: join(runDir, 'baileys-debug.log'), sync: false }))
const LIVE_TAGS = new Set(['message', 'chatstate', 'notification', 'presence', 'call', 'receipt'])
let sock = null
let loggedIn = false
let ending = false

function writeQr(qr) {
  writeFileSync(qrPage, `<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="15">
<title>接收号扫码</title><body style="font-family:sans-serif;text-align:center;background:#fff;color:#111">
<h3>接收号手机：WhatsApp → 设置 → 已关联的设备 → 关联新设备，扫下面的码（约 20 秒换一次，页面自动刷新）</h3>
<div id="q" style="display:inline-block;padding:16px;background:#fff"></div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
<script>new QRCode(document.getElementById('q'), {text: ${JSON.stringify(qr)}, width: 320, height: 320})</script>`)
}

function liveLine(node, t) {
  const a = node.attrs || {}
  const from = maskJid(a.from)
  let s = `[${bjMs(t)}] ${node.tag}`
  if (node.tag === 'chatstate') s += ` ${(node.content || [])[0]?.tag || ''} 来自 ${from}`
  else if (node.tag === 'message') {
    const tags = Array.isArray(node.content) ? node.content.map(c => c.tag) : []
    const has = tag => JSON.stringify(serNode(node)).includes(`"tag":"${tag}"`)
    s += ` type=${a.type} 来自 ${from}${a.sender_pn ? ' (' + maskJid(a.sender_pn) + ')' : ''}${a.category ? ' category=' + a.category : ''} 子节点[${tags.join(',')}] 举报令牌:${has('reporting_token') ? '有' : '无'} tctoken:${has('tctoken') ? '有' : '无'}`
  } else if (node.tag === 'notification') s += ` type=${a.type} 来自 ${from}${a.type === 'privacy_token' ? '  ← 对方签发隐私令牌' : ''}`
  else if (node.tag === 'receipt') s += ` type=${a.type || 'delivery'} 来自 ${from}`
  else s += ` 来自 ${from}`
  return s
}

async function finish(reason) {
  if (ending) return
  ending = true
  console.log(`\n结束（${reason}）……`)
  meta.endedAt = Date.now(); saveMeta()
  if (sock && loggedIn && !keep) {
    try { await sock.logout(); console.log('已注销分身（接收号手机“已关联的设备”里会消失）') }
    catch (e) { console.log('注销请求出错，请在接收号手机“已关联的设备”里手动退出：', e?.message) }
  }
  try { console.log('报告：', buildReport(runDir)) } catch (e) { console.log('生成报告出错：', e?.message) }
  try { rmSync(authDir, { recursive: true, force: true }) } catch {}
  setTimeout(() => process.exit(0), 1500)
}
process.on('SIGINT', () => finish('Ctrl+C'))
process.on('SIGTERM', () => finish('收到停止信号'))
setTimeout(() => finish(`到达 ${minutes} 分钟上限`), minutes * 60 * 1000)

async function start() {
  const { state, saveCreds } = await useMultiFileAuthState(authDir)
  sock = makeWASocket({
    version, auth: state, logger: baileysLog,
    browser: Browsers.macOS('Chrome'),
    // 不能关掉历史同步：2026-11-04 关掉后主手机 2 秒内就把分身移除了（device_removed）。
    // 历史消息只在内存里解密，不写进 messages.jsonl（只记 type=notify 的实时消息）。
    syncFullHistory: false, markOnlineOnConnect: false
  })
  sock.ev.on('creds.update', saveCreds)

  // 收到的每一帧原样记录
  sock.ws.on('frame', frame => {
    if (!frame || frame instanceof Uint8Array || !frame.tag) return
    const t = Date.now()
    appendFileSync(join(runDir, 'frames.jsonl'), JSON.stringify({ t, dir: 'in', node: serNode(frame) }) + '\n')
    if (LIVE_TAGS.has(frame.tag) && loggedIn) console.log(liveLine(frame, t))
  })
  // 我们这边发出去的帧（回执、ack 等），用来确认探针没有发消息
  sock.ws.on('traffic:tx', node => {
    if (!node || typeof node !== 'object' || !node.tag) return
    appendFileSync(join(runDir, 'tx.jsonl'), JSON.stringify({ t: Date.now(), dir: 'out', node: serNode(node) }) + '\n')
    if (node.tag === 'message') console.log(`⚠️ 探针发出了一条 message 帧（不应发生）：${JSON.stringify(serNode(node).attrs)}`)
  })

  sock.ws.on('CB:success', node => {
    loggedIn = true
    meta.selfPn = state.creds?.me?.id || null
    meta.selfLid = state.creds?.me?.lid || null
    meta.successAttrs = node.attrs
    saveMeta()
    console.log(`\n已上线。接收号 ${maskJid(meta.selfPn)}。现在可以用发送手机给它发消息了；结束按 Ctrl+C。\n`)
  })

  // 解密后的实时消息
  sock.ev.on('messages.upsert', ({ messages, type }) => {
    if (type !== 'notify') return
    for (const m of messages) {
      const rec = { _recvAt: Date.now(), ...plain(m) }
      appendFileSync(join(runDir, 'messages.jsonl'), JSON.stringify(rec) + '\n')
      const thumb = m.message?.extendedTextMessage?.jpegThumbnail || m.message?.imageMessage?.jpegThumbnail
      if (thumb && isBytes(thumb)) writeFileSync(join(runDir, 'thumbs', `${m.key.id}.jpg`), toBuf(thumb))
      if (m.key?.fromMe) continue
      const kinds = Object.keys(m.message || {}).filter(k => k !== 'messageContextInfo')
      const text = m.message?.conversation ?? m.message?.extendedTextMessage?.text ?? ''
      console.log(`    ↳ 解密：${kinds.join(',')}  文字${text.length}字${m.message?.extendedTextMessage?.matchedText ? '  带链接预览' : ''}`)
    }
  })

  sock.ev.on('connection.update', u => {
    if (u.qr) {
      writeQr(u.qr)
      if (!start.opened && !process.env.NO_OPEN) { start.opened = true; execFile('open', [qrPage]) }
      console.log('二维码已更新：', qrPage)
    }
    if (u.connection === 'close') {
      const code = u.lastDisconnect?.error?.output?.statusCode
      if (ending) return
      if (code === DisconnectReason.loggedOut) { console.log('分身被登出（401）。'); finish('被登出'); return }
      console.log('连接关闭，状态码', code, '，3 秒后重连')
      setTimeout(start, code === DisconnectReason.restartRequired ? 0 : 3000)
    }
  })
}
await start()
