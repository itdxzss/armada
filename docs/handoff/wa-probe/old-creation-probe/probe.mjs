// 一次性探针：在本机把一个分身设备（关联设备）挂到用户主号上，
// 只读出 WhatsApp 登录成功节点 <success> 里的 creation 等属性，然后注销分身。
// 不连接任何 Armada / WB Pro 环境，不发消息，不打印密钥。
//
// 用法：
//   node probe.mjs qr                 生成二维码网页（自动打开），用手机“关联设备”扫码
//   node probe.mjs pair 9230xxxxxxx   生成 8 位配对码，手机上选“用电话号码关联”输入
//   追加 --keep 则读到结果后不注销分身（默认读到后 10 秒自动注销）
import { mkdtempSync, writeFileSync, readFileSync, appendFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { execFile } from 'node:child_process'

const PL = `${(process.env.PROTOCOL_LAYER ?? process.env.HOME + '/daixz工作空间/armada/armada-protocol/protocol-layer')}/node_modules`
const { default: makeWASocket, useMultiFileAuthState, DisconnectReason, Browsers, fetchLatestWaWebVersion, binaryNodeToString } =
  await import(`${PL}/baileys/lib/index.js`)
// 与 Web 协议相同：优先取最新 WhatsApp Web 版本，失败用协议里验证过的回退值，避免 405。
let version = [2, 3000, 1044409164]
try { const r = await fetchLatestWaWebVersion({ signal: AbortSignal.timeout(10000) }); if (r?.isLatest) version = r.version } catch {}
console.log('WhatsApp Web 版本：', version.join('.'))
const { default: pino } = await import(`${PL}/pino/pino.js`)

const mode = process.argv[2]
const phone = mode === 'pair' ? (process.argv[3] || '').replace(/\D/g, '') : ''
const keep = process.argv.includes('--keep')
// --biz 号码1,号码2：登录后只读查询这些号码的商家资料（w:biz business_profile），打印原始返回，找 member_since 字段
const bizIdx = process.argv.indexOf('--biz')
let bizList = bizIdx > 0 ? (process.argv[bizIdx + 1] || '').split(',').map(x => x.replace(/\D/g, '')).filter(Boolean) : []
// --biz-file 文件：第一列是号码（表头/非数字行跳过）。--light：每个号只查一次 business_profile v=372，间隔 1.5 秒，结果写 JSONL
const bizFileIdx = process.argv.indexOf('--biz-file')
if (bizFileIdx > 0) bizList = readFileSync(process.argv[bizFileIdx + 1], 'utf8').split('\n').map(l => l.split('\t')[0].trim()).filter(x => /^\d{8,15}$/.test(x))
const light = process.argv.includes('--light')
const outIdx = process.argv.indexOf('--out')
const outFile = outIdx > 0 ? process.argv[outIdx + 1] : 'bizscan-result.jsonl'
if (!['qr', 'pair'].includes(mode) || (mode === 'pair' && !phone)) {
  console.error('用法: node probe.mjs qr | node probe.mjs pair <主号完整号码>')
  process.exit(2)
}

const here = new URL('.', import.meta.url).pathname
const authDir = mkdtempSync(join(tmpdir(), 'creation-probe-auth-'))
const qrPage = join(here, 'qr.html')
const bj = s => new Date(Number(s) * 1000).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
let pairingRequested = false
let reported = false

function writeQr(qr) {
  writeFileSync(qrPage, `<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="15">
<title>分身扫码</title><body style="font-family:sans-serif;text-align:center;background:#fff">
<h3>WhatsApp → 设置 → 关联设备 → 关联新设备，扫下面的码（约 20 秒换一次，页面自动刷新）</h3>
<div id="q" style="display:inline-block;padding:16px;background:#fff"></div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
<script>new QRCode(document.getElementById('q'), {text: ${JSON.stringify(qr)}, width: 320, height: 320})</script>`)
}

const nodeText = n => (n?.content instanceof Uint8Array ? Buffer.from(n.content).toString('utf8') : (typeof n?.content === 'string' ? n.content : ''))
const child = (n, tag) => Array.isArray(n?.content) ? n.content.find(c => c.tag === tag) : undefined
async function queryLight(sock) {
  let i = 0
  for (const num of bizList) {
    i++
    const rec = { phone: num, at: new Date().toISOString() }
    try {
      const res = await sock.query({ tag: 'iq', attrs: { to: 's.whatsapp.net', xmlns: 'w:biz', type: 'get' },
        content: [{ tag: 'business_profile', attrs: { v: '372' }, content: [{ tag: 'profile', attrs: { jid: `${num}@s.whatsapp.net` } }] }] })
      const prof = child(child(res, 'business_profile'), 'profile')
      if (!prof) rec.result = 'empty'
      else {
        rec.result = 'business'
        const ts = nodeText(child(prof, 'member_since_ts'))
        if (ts) { rec.member_since_ts = Number(ts); rec.member_since_bj = bj(ts) }
        rec.member_since_text = nodeText(child(prof, 'member_since_text')) || null
        const bi = child(prof, 'biz_identity_info')?.attrs || {}
        rec.biz_type = bi.type || null; rec.vlevel = bi.vlevel || null; rec.has_name = !!bi.display_name
        rec.category = nodeText(child(child(prof, 'categories'), 'category')) || null
      }
    } catch (e) { rec.result = 'error'; rec.error = e?.message }
    appendFileSync(outFile, JSON.stringify(rec) + '\n')
    console.log(`[${i}/${bizList.length}] ${num.slice(0, 4)}…${num.slice(-2)} ${rec.result} ${rec.member_since_bj || ''} ${rec.member_since_text || ''}`)
    await new Promise(r => setTimeout(r, 1500))
  }
  console.log('\n===== 查询结束 =====')
}

async function queryBiz(sock) {
  // 顺带查扫码号自己（如果它是商家号，能对照它真实的注册时间）
  const self = (sock.user?.id || '').split(':')[0].split('@')[0]
  const list = [...new Set([...(self ? [self] : []), ...bizList])]
  const show = (title, res) => {
    const txt = binaryNodeToString(res)
    console.log(`\n----- ${title} -----`)
    console.log(txt.length > 4000 ? txt.slice(0, 4000) + '\n…(截断)' : txt)
    const m = txt.match(/member_since_ts[^0-9]*([0-9]{9,})/)
    if (m) console.log(`>>> member_since_ts = ${m[1]}  （北京时间 ${bj(m[1])}）`)
    const t = txt.match(/member_since_text[^>]*>([^<]*)</)
    if (t) console.log(`>>> member_since_text = ${t[1]}`)
    return txt
  }
  const usync = (phone, queryNodes, context = 'interactive') => sock.query({
    tag: 'iq', attrs: { to: 's.whatsapp.net', type: 'get', xmlns: 'usync' },
    content: [{ tag: 'usync', attrs: { context, mode: 'query', sid: sock.generateMessageTag(), last: 'true', index: '0' },
      content: [{ tag: 'query', attrs: {}, content: queryNodes },
        { tag: 'list', attrs: {}, content: [{ tag: 'user', attrs: {}, content: [{ tag: 'contact', attrs: {}, content: `+${phone}` }] }] }] }]
  })
  const bizProfile = (jid, v) => sock.query({ tag: 'iq', attrs: { to: 's.whatsapp.net', xmlns: 'w:biz', type: 'get' },
    content: [{ tag: 'business_profile', attrs: { v }, content: [{ tag: 'profile', attrs: { jid } }] }] })

  for (const num of list) {
    const tag = `${num.slice(0, 4)}…${num.slice(-2)}`
    let lid = ''
    // 1) 号码是否存在 + LID
    try {
      const txt = show(`${tag} usync contact+lid`, await usync(num, [{ tag: 'contact', attrs: {} }, { tag: 'lid', attrs: {} }]))
      lid = (txt.match(/<lid val='([^']+@lid)'/) || [])[1] || ''
    } catch (e) { console.log(`${tag} usync contact+lid 出错：`, e?.message) }
    // 2) usync 的 business 子查询（Web 端批量取商家信息的方式，单独查，失败不影响其他）
    try {
      show(`${tag} usync business`, await usync(num, [{ tag: 'business', attrs: {}, content: [{ tag: 'verified_name', attrs: {} }, { tag: 'profile', attrs: { v: '372' } }] }]))
    } catch (e) { console.log(`${tag} usync business 出错：`, e?.message) }
    // 3) w:biz business_profile，分别用手机号 jid 和 LID 查
    for (const jid of [`${num}@s.whatsapp.net`, lid].filter(Boolean)) {
      for (const v of ['244', '372']) {
        try { show(`${tag} business_profile v=${v} jid=${jid.endsWith('@lid') ? 'LID' : '手机号'}`, await bizProfile(jid, v)) }
        catch (e) { console.log(`${tag} business_profile v=${v} 出错：`, e?.message) }
      }
    }
  }
  console.log('\n===== 查询结束 =====')
}

async function start() {
  const { state, saveCreds } = await useMultiFileAuthState(authDir)
  const sock = makeWASocket({
    version,
    auth: state,
    logger: pino({ level: 'silent' }),
    browser: Browsers.macOS('Chrome'),
    syncFullHistory: false,
    markOnlineOnConnect: false
  })
  sock.ev.on('creds.update', saveCreds)

  sock.ws.on('CB:success', node => {
    if (reported) return  // 断线重连再次登录时不重复查询
    const a = node.attrs || {}
    const adv = state.creds?.account?.details
    console.log('\n===== 登录成功节点 <success> 属性 =====')
    console.log(JSON.stringify(a))
    if (a.creation) console.log(`creation = ${a.creation}  （北京时间 ${bj(a.creation)}）`)
    else console.log('creation 字段：没有')
    if (a.t) console.log(`t（服务器当前时间）= ${a.t}  （北京时间 ${bj(a.t)}）`)
    console.log(`本机当前时间（北京）：${new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })}`)
    console.log(`分身设备号：${state.creds?.me?.id ? state.creds.me.id.replace(/^\d+(?=\d{2})/, m => m.slice(0, 3) + '…') : '未知'}`)
    console.log('=======================================\n')
    reported = true
    const logoutNow = async () => {
      try { await sock.logout() } catch (e) { console.log('注销请求出错，可在手机上手动移除：', e?.message) }
      setTimeout(() => process.exit(0), 2000)
    }
    if (bizList.length && light) setTimeout(async () => { await queryLight(sock); if (!keep) { console.log('查完，注销分身……'); await logoutNow() } }, 3000)
    else if (bizList.length) setTimeout(() => queryBiz(sock), 3000)
    if (!keep) {
      const wait = light ? 30000 + bizList.length * 4000 : 20000 + (bizList.length + 1) * 9000
      console.log(`${wait / 1000} 秒后注销这个分身（手机“关联设备”里会消失）……`)
      setTimeout(async () => {
        try { await sock.logout() } catch (e) { console.log('注销请求出错，可在手机上手动移除：', e?.message) }
        setTimeout(() => process.exit(0), 2000)
      }, wait)
    }
  })

  sock.ev.on('connection.update', async u => {
    if (u.qr && mode === 'qr') {
      writeQr(u.qr)
      if (!start.opened && !process.env.NO_OPEN) { start.opened = true; execFile('open', [qrPage]) }
      console.log('二维码已更新：', qrPage)
    }
    if (mode === 'pair' && !pairingRequested && u.qr && !state.creds.registered) {
      pairingRequested = true
      const code = await sock.requestPairingCode(phone)
      console.log(`\n配对码：${code}\n手机 WhatsApp → 关联设备 → 关联新设备 → 改用电话号码关联，输入上面的码\n`)
    }
    if (u.connection === 'close') {
      const code = u.lastDisconnect?.error?.output?.statusCode
      if (code === DisconnectReason.restartRequired) { console.log('配对完成，重新连接以登录……'); start(); return }
      if (code === DisconnectReason.loggedOut) { console.log(reported ? '分身已注销。' : '被登出（401）。'); process.exit(0) }
      console.log('连接关闭，状态码', code, reported ? '' : '— 还没拿到 success 节点')
      if (!reported) setTimeout(start, 3000)
    }
  })
}
await start()
