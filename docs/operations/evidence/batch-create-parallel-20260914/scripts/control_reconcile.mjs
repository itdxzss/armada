import {readFile,open,unlink} from 'node:fs/promises'
import {execFile} from 'node:child_process'
import {promisify} from 'node:util'
import {normalizeConfig,makeState,createClients,verifyMembers,controlConverged,saveOutputs} from './batch-create-groups.mjs'
const accountId=Number(process.argv[2])
if(!Number.isInteger(accountId))throw Error('invalid account')
const root=`/tmp/armada-batch-parallel-20260914/${accountId}`,path=`${root}/state.json`
const lock=await open(`${path}.lock`,'wx',0o600)
try {
  const config=normalizeConfig(JSON.parse(await readFile(`${root}/config.json`,'utf8')))
  const state=JSON.parse(await readFile(path,'utf8'))
  if(state.fingerprint!==makeState(config).fingerprint)throw Error('config mismatch')
  const creator=config.creators[0],clients=createClients(config)
  const control=async jid=>JSON.parse((await promisify(execFile)('python3',['/tmp/armada-batch-support-android-20260914.py','control',jid],{timeout:15000,maxBuffer:1024*1024})).stdout)
  for(const row of state.rows.filter(r=>r.protocolVerifiedAt && r.stage!=='DONE')){
    try{
      const snapshot=await control(row.groupJid)
      row.controlObservedAt=new Date().toISOString();row.controlGroupId=snapshot?.id??null
      if(!controlConverged(creator,row,snapshot))continue
      if(verifyMembers(creator,await clients.members(creator,row.groupJid)).length)throw Error('specified admins not confirmed')
      row.inviteUrl=await clients.link(creator,row.groupJid)
      row.protocolVerifiedAt=new Date().toISOString()
      const after=await control(row.groupJid)
      row.controlObservedAt=new Date().toISOString();row.controlGroupId=after?.id??null
      if(controlConverged(creator,row,after)){
        row.stage='DONE';row.completedAt=new Date().toISOString();delete row.error;delete row.reconcileError
        await saveOutputs(path,state)
      }
    }catch(error){row.reconcileError=error.message;row.reconcileFailedAt=new Date().toISOString()}
  }
  await saveOutputs(path,state)
  console.log(JSON.stringify({accountId,created:state.rows.filter(r=>r.groupJid).length,done:state.rows.filter(r=>r.stage==='DONE').length}))
}finally{await lock.close();await unlink(`${path}.lock`)}
