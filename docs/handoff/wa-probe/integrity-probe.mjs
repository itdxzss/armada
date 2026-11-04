// 账号权重探针（2026-11-04）：扫码挂一个分身，只读查询一批号码的“信誉/完整性”信号，然后注销分身。
// 查询来自 iOS 26.34.72 的持久查询表（whatsapp-ios-mex_client-persist.json）和 zhuan safemex.go 里现成的 Android 编号。
// 不发消息、不加联系人、不改任何设置；不连 Armada / WB Pro / perf2。
//
// 用法：
//   node integrity-probe.mjs --targets <文件|号码1,号码2> [--gap 3] [--max 80] [--probe-n 2] [--hold] [--minutes 120] [--keep]
//     --hold：试探完先不批量，等记录目录里出现 go 文件再开始；开始时重新读一遍 --targets 文件（名单可以在试探期间再补）
//     文件每行：号码<TAB>标签（标签可省，如 banned / sent>200 / sent<=10 / control）；非数字开头的行跳过
//   node integrity-probe.mjs --dry       不登录，只打印将要发的查询，用来核对
//
// 流程：
//   1. 扫码上线（二维码页 qr-integrity.html，不和其他探针共用）
//   2. 试探：对“扫码号自己 + 名单第 1 个号”把每个查询 × {手机号jid, LID} 都试一遍，原样记录
//   3. 只用第 2 步里真正拿到数据的组合，逐个查名单里的号（默认间隔 3 秒）
//   4. 写结果、注销分身、删掉本机登录凭据
// 记录目录 runs/integrity-<北京时间>/：raw.jsonl（每次查询的原始返回）、summary.tsv、tx.jsonl（确认没发消息）
// 记录里有完整号码，只放本机，不要拷进仓库。
import { mkdirSync, mkdtempSync, writeFileSync, appendFileSync, readFileSync, rmSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { execFile } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = fileURLToPath(new URL('.', import.meta.url))  // 必须解码，路径里有中文
const args = process.argv.slice(2)
const arg = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d }
const dry = args.includes('--dry')
const keep = args.includes('--keep')
const gapMs = Number(arg('--gap', '3')) * 1000
const maxTargets = Number(arg('--max', '80'))
const probeN = Number(arg('--probe-n', '2'))
const hold = args.includes('--hold')
const minutes = Number(arg('--minutes', '120'))

// ---------- 查询定义 ----------
// 每个查询可能有多个编号：iOS 编号来自 iOS 包；android-zhuan 是 zhuan safemex.go 里的编号（不知道对应 iOS 哪个查询，一起试）。
const QUERIES = [
  { name: 'IntegrityAIInputsQuery', ids: [['ios', '26576471731949397']], note: 'reputation_score / is_country_mismatch' },
  { name: 'StartChatContextIntegrityQuery', ids: [['ios', '26038899055732942']], note: 'is_new_account / join_date_ms / is_suspicious_start_chat' },
  { name: 'IntegrityQuery', ids: [['ios', '24966207873007786']], note: 'is_banned / is_suspicious / join_date_ms / trust_tier / integrity_tags' },
  { name: 'ContactsIntegrityQuery', ids: [['ios', '25377013171916372']], note: 'contacts_integrity_state' },
  { name: 'zhuan.SendIqMex', ids: [['android-zhuan', '24897449816559062']], note: 'zhuan 注释“查询商业版账号权重”' },
  { name: 'zhuan.SendContactIntegrityQuery', ids: [['android-zhuan', '8370845569632382']], note: 'zhuan 注释“查询账号权重”' }
]
// --queries 名1,名2：只用这些查询（试探和批量都只发这些），减少请求量避免限速
const onlyQ = (arg('--queries', '') || '').split(',').map(x => x.trim()).filter(Boolean)
if (onlyQ.length) { for (let i = QUERIES.length - 1; i >= 0; i--) if (!onlyQ.includes(QUERIES[i].name)) QUERIES.splice(i, 1) }
const variablesFor = jid => ({ input: { query_input: [{ jid }], telemetry: { context: 'INTERACTIVE' } } })

// ---------- 名单 ----------
function loadTargets() {
  const t = arg('--targets', '')
  if (!t) return []
  const lines = existsSync(t) ? readFileSync(t, 'utf8').split('\n') : t.split(',')
  const out = []
  for (const line of lines) {
    const [p, label = ''] = line.split('\t').map(s => s.trim())
    const phone = (p || '').replace(/\D/g, '')
    if (/^\d{8,15}$/.test(phone) && !out.some(x => x.phone === phone)) out.push({ phone, label })
  }
  return out.slice(0, maxTargets)
}
let targets = loadTargets()
const mask = s => { const d = String(s || '').split('@')[0].split(':')[0]; return d.length > 6 ? `${d.slice(0, 4)}…${d.slice(-2)}` : d }
const bj = ms => new Date(ms).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })

if (dry) {
  console.log(`名单 ${targets.length} 个号（只显示前 5 个）：`, targets.slice(0, 5).map(x => `${mask(x.phone)}${x.label ? '(' + x.label + ')' : ''}`).join(' '))
  for (const q of QUERIES) for (const [src, id] of q.ids) {
    console.log(`<iq xmlns="w:mex" type="get" to="s.whatsapp.net"><query query_id="${id}">${JSON.stringify({ variables: variablesFor('<号码>@s.whatsapp.net') })}</query></iq>   ← ${q.name} [${src}] ${q.note}`)
  }
  process.exit(0)
}
if (!targets.length) { console.error('用法: node integrity-probe.mjs --targets <文件|号码1,号码2> [--gap 3] [--max 80] [--keep]   或 --dry'); process.exit(2) }

// ---------- 结果解析 ----------
const childOf = (n, tag) => Array.isArray(n?.content) ? n.content.find(c => c.tag === tag) : undefined
const bytesOf = n => n?.content instanceof Uint8Array ? Buffer.from(n.content) : (typeof n?.content === 'string' ? Buffer.from(n.content) : null)
function deepFindNode(n, pred) {
  if (!n || typeof n !== 'object') return null
  if (pred(n)) return n
  if (Array.isArray(n.content)) for (const c of n.content) { const r = deepFindNode(c, pred); if (r) return r }
  return null
}
const FIELDS = ['status', 'reputation_score', 'is_country_mismatch', 'is_banned', 'is_suspicious', 'is_new_account', 'is_suspicious_start_chat',
  'join_date_ms', 'trust_tier', 'phone_country_code', 'is_mv_subscribed', 'contacts_integrity_state', 'integrity_tags']
function pickFields(json) {
  const users = json?.data?.xwa2_fetch_wa_users
  const u = Array.isArray(users) ? users[0] : null
  const info = u?.integrity_signals_info || u?.common_integrity_signals_info || null
  if (!info) return null
  const flat = { ...info, ...(info.integrity_features || {}) }
  const out = {}
  for (const k of FIELDS) if (flat[k] !== undefined && flat[k] !== null) out[k] = flat[k]
  if (out.join_date_ms) out.join_date_bj = bj(Number(out.join_date_ms))
  if (out.__typename === undefined && info.__typename) out.typename = info.__typename
  return out
}

// ---------- 运行 ----------
const PL = `${(process.env.PROTOCOL_LAYER ?? process.env.HOME + '/daixz工作空间/armada/armada-protocol/protocol-layer')}/node_modules`
const { default: makeWASocket, useMultiFileAuthState, DisconnectReason, Browsers, fetchLatestWaWebVersion } =
  await import(`${PL}/baileys/lib/index.js`)
const { default: pino } = await import(`${PL}/pino/pino.js`)
let version = [2, 3000, 1044409164]
try { const r = await fetchLatestWaWebVersion({ signal: AbortSignal.timeout(10000) }); if (r?.isLatest) version = r.version } catch {}

const stamp = new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).replace(/[ :]/g, '').replace(/-/g, '')
const runDir = join(here, 'runs', `integrity-${stamp}`)
mkdirSync(runDir, { recursive: true })
const authDir = mkdtempSync(join(tmpdir(), 'integrity-probe-auth-'))
const qrPage = join(here, 'qr-integrity.html')
const meta = { startedAt: Date.now(), waWebVersion: version.join('.'), targets: targets.length, gapMs, selfPn: null, selfLid: null, workingCombos: [], endedAt: null }
const saveMeta = () => writeFileSync(join(runDir, 'meta.json'), JSON.stringify(meta, null, 2))
saveMeta()
console.log(`WhatsApp Web 版本 ${meta.waWebVersion}；名单 ${targets.length} 个号；记录目录：${runDir}`)
const baileysLog = pino({ level: 'debug' }, pino.destination({ dest: join(runDir, 'baileys-debug.log'), sync: false }))

let sock = null
let loggedIn = false
let started = false
let ending = false
const sleep = ms => new Promise(r => setTimeout(r, ms))

function writeQr(qr) {
  writeFileSync(qrPage, `<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="15">
<title>权重探针扫码</title><body style="font-family:sans-serif;text-align:center;background:#fff;color:#111">
<h3>手机 WhatsApp → 设置 → 已关联的设备 → 关联新设备，扫下面的码（约 20 秒换一次，页面自动刷新）</h3>
<div id="q" style="display:inline-block;padding:16px;background:#fff"></div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
<script>new QRCode(document.getElementById('q'), {text: ${JSON.stringify(qr)}, width: 320, height: 320})</script>`)
}

async function lookupLid(phone) {
  try {
    const res = await sock.query({
      tag: 'iq', attrs: { to: 's.whatsapp.net', type: 'get', xmlns: 'usync' },
      content: [{ tag: 'usync', attrs: { context: 'interactive', mode: 'query', sid: sock.generateMessageTag(), last: 'true', index: '0' },
        content: [{ tag: 'query', attrs: {}, content: [{ tag: 'contact', attrs: {} }, { tag: 'lid', attrs: {} }] },
          { tag: 'list', attrs: {}, content: [{ tag: 'user', attrs: {}, content: [{ tag: 'contact', attrs: {}, content: `+${phone}` }] }] }] }]
    })
    const contact = deepFindNode(res, n => n.tag === 'contact' && n.attrs?.type)
    const lid = deepFindNode(res, n => n.tag === 'lid' && n.attrs?.val)
    return { onWhatsApp: contact?.attrs?.type === 'in', lid: lid?.attrs?.val || null }
  } catch (e) { return { onWhatsApp: null, lid: null, error: e?.message } }
}

async function mex(queryId, jid) {
  const t = Date.now()
  const rec = { t, queryId, jid }
  try {
    const res = await sock.query({
      tag: 'iq', attrs: { id: sock.generateMessageTag(), type: 'get', to: 's.whatsapp.net', xmlns: 'w:mex' },
      content: [{ tag: 'query', attrs: { query_id: queryId }, content: Buffer.from(JSON.stringify({ variables: variablesFor(jid) }), 'utf-8') }]
    }, 15000)
    const result = childOf(res, 'result')
    const buf = bytesOf(result)
    rec.ms = Date.now() - t
    rec.format = result?.attrs?.format || null
    if (!buf || !buf.length) { rec.kind = 'empty'; return rec }
    try {
      const json = JSON.parse(buf.toString('utf8'))
      rec.kind = json.errors?.length ? 'graphql_error' : 'json'
      rec.json = json
      rec.fields = pickFields(json)
    } catch { rec.kind = 'binary'; rec.hex = buf.toString('hex') }  // 可能是 Argo 编码，事后用 tools/argo-decode 解
  } catch (e) {
    rec.ms = Date.now() - t
    rec.kind = 'iq_error'
    rec.error = { message: e?.message, code: e?.output?.statusCode ?? e?.data?.attrs?.code ?? null, data: e?.data ? JSON.stringify(e.data).slice(0, 2000) : null }
  }
  return rec
}
const oneLine = r => r.kind === 'json' ? (r.fields ? JSON.stringify(r.fields) : '有返回但没有 integrity 字段：' + JSON.stringify(r.json).slice(0, 200))
  : r.kind === 'graphql_error' ? `GraphQL 错误：${r.json.errors.map(x => x.message + (x.extensions?.error_code ? `(${x.extensions.error_code})` : '')).join('; ')}`
  : r.kind === 'iq_error' ? `IQ 错误：${r.error.code ?? ''} ${r.error.message}` : r.kind === 'binary' ? `二进制返回 ${r.hex.length / 2} 字节（Argo？）` : r.kind

async function run() {
  const self = (meta.selfPn || '').split(':')[0].split('@')[0]
  // ---- 第 2 步：试探 ----
  console.log(`\n===== 试探：每个查询 × {手机号, LID}，对象 = 扫码号自己 + 名单前 ${probeN} 个 =====`)
  const probeTargets = [...new Set([self, ...targets.slice(0, probeN).map(x => x.phone)].filter(Boolean))]
  const works = new Map()  // key = queryId|jidType
  for (const phone of probeTargets) {
    const { onWhatsApp, lid, error } = await lookupLid(phone)
    console.log(`\n${mask(phone)}${phone === self ? '（扫码号自己）' : ''}  usync: 在WhatsApp=${onWhatsApp} LID=${lid ? '有' : '无'}${error ? ' 错误 ' + error : ''}`)
    for (const q of QUERIES) for (const [src, id] of q.ids) {
      for (const [jidType, jid] of [['pn', `${phone}@s.whatsapp.net`], ...(args.includes('--pn-only') ? [] : [['lid', lid]])]) {
        if (!jid) continue
        const r = await mex(id, jid)
        appendFileSync(join(runDir, 'raw.jsonl'), JSON.stringify({ phase: 'probe', phone, self: phone === self, query: q.name, src, jidType, ...r }) + '\n')
        console.log(`  ${q.name} [${src}] jid=${jidType}: ${oneLine(r)}`)
        if (r.kind === 'json' && r.fields) works.set(`${id}|${jidType}`, { name: q.name, id, src, jidType })
        await sleep(1500)
      }
    }
  }
  meta.workingCombos = [...works.values()]
  saveMeta()
  if (!works.size) {
    console.log('\n没有任何查询组合拿到 integrity 字段，不继续批量查。原始返回在 raw.jsonl，拿去分析变量/编号。')
    return
  }
  // 同一个查询手机号和 LID 都能用时只用手机号，减少请求
  const combos = []
  for (const c of works.values()) if (!(c.jidType === 'lid' && works.has(`${c.id}|pn`))) combos.push(c)
  console.log(`\n能用的组合：${combos.map(c => `${c.name}[${c.jidType}]`).join('、')}`)

  // ---- 第 3 步：批量 ----
  if (hold) {
    console.log(`\n--hold：等待 ${join(runDir, 'go')} 出现后开始批量（分身保持在线）……`)
    while (!ending && !existsSync(join(runDir, 'go'))) await sleep(2000)
    if (ending) return
    targets = loadTargets()
    meta.targets = targets.length; saveMeta()
  }
  console.log(`\n===== 批量查 ${targets.length} 个号，间隔 ${gapMs / 1000} 秒 =====`)
  const cols = ['phone', 'label', 'on_whatsapp', 'has_lid', ...combos.map(c => c.name + '.' + c.jidType)]
  writeFileSync(join(runDir, 'summary.tsv'), cols.join('\t') + '\n')
  let i = 0
  for (const t of targets) {
    if (ending) break
    i++
    const needLid = combos.some(c => c.jidType === 'lid')
    const u = await lookupLid(t.phone)
    const row = [t.phone, t.label, String(u.onWhatsApp), u.lid ? 'y' : 'n']
    const parts = []
    for (const c of combos) {
      const jid = c.jidType === 'pn' ? `${t.phone}@s.whatsapp.net` : u.lid
      if (!jid) { row.push('no-lid'); continue }
      await sleep(gapMs)
      const r = await mex(c.id, jid)
      appendFileSync(join(runDir, 'raw.jsonl'), JSON.stringify({ phase: 'batch', phone: t.phone, label: t.label, query: c.name, src: c.src, jidType: c.jidType, ...r }) + '\n')
      row.push(r.fields ? JSON.stringify(r.fields) : oneLine(r))
      parts.push(`${c.name.replace('Query', '')}=${r.fields ? JSON.stringify(r.fields) : oneLine(r)}`)
    }
    appendFileSync(join(runDir, 'summary.tsv'), row.join('\t') + '\n')
    console.log(`[${i}/${targets.length}] ${mask(t.phone)} ${t.label} 在WA=${u.onWhatsApp}${needLid && !u.lid ? ' 无LID' : ''}  ${parts.join('  ')}`)
    await sleep(gapMs)
  }
  console.log('\n===== 查询结束 =====')
}

async function finish(reason) {
  if (ending) return
  ending = true
  console.log(`\n结束（${reason}）……`)
  meta.endedAt = Date.now(); saveMeta()
  if (sock && loggedIn && !keep) {
    try { await sock.logout(); console.log('已注销分身（手机“已关联的设备”里会消失）') }
    catch (e) { console.log('注销请求出错，请在手机“已关联的设备”里手动退出：', e?.message) }
  }
  try { rmSync(authDir, { recursive: true, force: true }) } catch {}
  console.log('记录目录：', runDir)
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
    // 不能关掉历史同步：2026-11-04 recv-probe 关掉后主手机 2 秒内就把分身移除了（device_removed）。
    syncFullHistory: false, markOnlineOnConnect: false
  })
  sock.ev.on('creds.update', saveCreds)
  // 我们发出去的帧，确认没有发消息
  sock.ws.on('traffic:tx', node => {
    if (!node || typeof node !== 'object' || !node.tag) return
    appendFileSync(join(runDir, 'tx.jsonl'), JSON.stringify({ t: Date.now(), tag: node.tag, attrs: node.attrs }) + '\n')
    if (node.tag === 'message') console.log(`⚠️ 探针发出了一条 message 帧（不应发生）：${JSON.stringify(node.attrs)}`)
  })
  sock.ws.on('CB:success', node => {
    loggedIn = true
    meta.selfPn = state.creds?.me?.id || null
    meta.selfLid = state.creds?.me?.lid || null
    meta.successAttrs = node.attrs
    saveMeta()
    if (started) return  // 断线重连不重复跑
    started = true
    console.log(`\n已上线（扫码号 ${mask(meta.selfPn)}），8 秒后开始查询……`)
    setTimeout(async () => {
      try { await run() } catch (e) { console.log('查询过程出错：', e?.message) }
      if (!keep) finish('查询完成')
      else console.log('--keep：不注销，结束按 Ctrl+C')
    }, 8000)
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
