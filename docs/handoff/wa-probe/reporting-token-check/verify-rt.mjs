import { readFileSync } from 'node:fs'
const PL=`${(process.env.PROTOCOL_LAYER ?? process.env.HOME + '/daixz工作空间/armada/armada-protocol/protocol-layer')}/node_modules/baileys/lib`
const { proto } = await import(`${PL}/index.js`)
const { getMessageReportingToken } = await import(`${PL}/Utils/reporting-utils.js`)
const W = (process.env.WA_PROBE_RUNS ?? decodeURIComponent(new URL('../runs', import.meta.url).pathname))
const unplain = v => {
  if (Array.isArray(v)) return v.map(unplain)
  if (v && typeof v === 'object') {
    if ('$bytes' in v) { if (!v.b64) throw new Error('字节超过 8192 未保存'); return Buffer.from(v.b64, 'base64') }
    const o = {}; for (const k of Object.keys(v)) o[k] = unplain(v[k]); return o
  }
  return v
}
// 发送方实际发出的举报令牌
const sentTok = {}
for (const l of readFileSync(`${W}/web-send-20261104084844/tx.jsonl`, 'utf8').split('\n').filter(Boolean)) {
  const n = JSON.parse(l).node
  if (n.tag !== 'message' || !(n.attrs.to || '').startsWith(process.env.RECV_PHONE ?? '0000000000')) continue
  const rep = (n.children || []).find(c => c.tag === 'reporting')
  sentTok[n.attrs.id] = rep ? rep.children.find(c => c.tag === 'reporting_token')?.hex : null
}
for (const l of readFileSync(`${W}/20261104083406/messages.jsonl`, 'utf8').split('\n').filter(Boolean)) {
  const d = JSON.parse(l)
  if (!(d.key?.id in sentTok)) continue
  const msg = proto.Message.fromObject(unplain(d.message))
  const raw = Buffer.from(proto.Message.encode(msg).finish())
  const r = await getMessageReportingToken(raw, msg, { id: d.key.id, fromMe: true, remoteJid: `${process.env.RECV_PHONE ?? '0000000000'}@s.whatsapp.net` })
  const correct = r ? Buffer.from(r.content[0].content).toString('hex') : null
  console.log(d.key.id, '实际发出:', sentTok[d.key.id] || '（没带）', ' 正确应为:', correct, ' →', !sentTok[d.key.id] ? '缺失' : sentTok[d.key.id] === correct ? '正确' : '错误（填充字节被算进去了）')
}
