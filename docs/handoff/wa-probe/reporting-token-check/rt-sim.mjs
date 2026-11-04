const PL=`${(process.env.PROTOCOL_LAYER ?? process.env.HOME + '/daixz工作空间/armada/armada-protocol/protocol-layer')}/node_modules/baileys/lib`
const { generateWAMessageContent, encodeWAMessage, proto } = await import(`${PL}/index.js`)
const { getMessageReportingToken } = await import(`${PL}/Utils/reporting-utils.js`)
const key = { id: '3EB0SIM', fromMe: true, remoteJid: `${process.env.RECV_PHONE ?? '0000000000'}@s.whatsapp.net` }
const m = await generateWAMessageContent({ text: 'hi' }, {})
const good = Buffer.from((await getMessageReportingToken(Buffer.from(proto.Message.encode(m).finish()), m, key)).content[0].content).toString('hex')
const stat = { 没带: 0, 带且正确: 0, 带但错误: 0 }, byPad = {}
const rawLen = proto.Message.encode(m).finish().length
for (let i = 0; i < 2000; i++) {
  const enc = encodeWAMessage(m); const pad = enc.length - rawLen
  const r = await getMessageReportingToken(enc, m, key)
  const k = !r ? '没带' : Buffer.from(r.content[0].content).toString('hex') === good ? '带且正确' : '带但错误'
  stat[k]++; (byPad[pad] ||= new Set()).add(k)
}
console.log('2000 次：', stat)
console.log('按填充长度：', Object.entries(byPad).map(([p, s]) => `${p}:${[...s].join('/')}`).join('  '))
