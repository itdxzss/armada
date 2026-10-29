import {readFile, open, unlink} from 'node:fs/promises'
import {execFile} from 'node:child_process'
import {promisify} from 'node:util'
import {normalizeConfig, makeState, runBatch, createClients, saveOutputs} from './batch-create-groups.mjs'
const accountId = Number(process.argv[2])
const mode = process.argv[3]
if (!Number.isInteger(accountId) || !['fill','reconcile'].includes(mode)) throw new Error('invalid runner arguments')
const root = `/tmp/armada-batch-parallel-20260914/${accountId}`
const path = `${root}/state.json`
const config = normalizeConfig(JSON.parse(await readFile(`${root}/config.json`, 'utf8')))
const lock = await open(`${path}.lock`, 'wx', 0o600)
try {
  await lock.writeFile(JSON.stringify({pid:process.pid, accountId}))
  let state
  try {state = JSON.parse(await readFile(path, 'utf8'))} catch (error) {
    if (error.code !== 'ENOENT' || mode !== 'fill') throw error
    state = makeState(config)
  }
  const save = () => saveOutputs(path, state)
  if (state.fingerprint !== makeState(config).fingerprint) throw new Error('config differs from persisted plan')
  await save()
  const clients = createClients(config)
  // 一个账号由本进程串行操作，同名预检用启动时的真实群列表；重启会重新查询。
  if (mode === 'fill') {
    const list = clients.list.bind(clients)
    const create = clients.create.bind(clients)
    let existing
    clients.list = async creator => existing ??= await list(creator)
    clients.create = async (creator, subject) => {
      const jid = await create(creator, subject)
      existing?.push({groupJid:jid, subject})
      return jid
    }
  }
  clients.control = async jid => {
    const {stdout} = await promisify(execFile)('python3', ['/tmp/armada-batch-support-android-20260914.py','control',jid],
      {timeout:15000, maxBuffer:1024*1024})
    return JSON.parse(stdout)
  }
  await runBatch(config, state, {clients, execute:mode==='fill', deferControl:mode==='fill', save,
    log:message => console.log(JSON.stringify({accountId,message}))})
  console.log(JSON.stringify({accountId,created:state.rows.filter(r=>r.groupJid).length,
    done:state.rows.filter(r=>r.stage==='DONE').length,target:50}))
} catch (error) {
  console.log(JSON.stringify({accountId,stopped:true,error:error.message}))
  process.exitCode=1
} finally {await lock.close(); await unlink(`${path}.lock`)}
