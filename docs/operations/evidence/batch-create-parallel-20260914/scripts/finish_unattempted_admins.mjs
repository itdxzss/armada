import {readFile,open,unlink} from 'node:fs/promises'
import {normalizeConfig,createClients,verifyMembers} from './batch-create-groups.mjs'
const accountId=Number(process.argv[2]),no=Number(process.argv[3]),firstUnattempted=Number(process.argv[4])
if(!Number.isInteger(accountId)||!Number.isInteger(no)||![1,2].includes(firstUnattempted))throw Error('invalid arguments')
const root=`/tmp/armada-batch-parallel-20260914/${accountId}`
const lock=await open(`${root}/state.json.lock`,'wx',0o600)
try {
  const config=normalizeConfig(JSON.parse(await readFile(`${root}/config.json`,'utf8')))
  const state=JSON.parse(await readFile(`${root}/state.json`,'utf8'))
  const row=state.rows.find(r=>r.no===no)
  if(!row?.groupJid||row.stage!=='PROMOTION_REQUESTED')throw Error('expected interrupted promotion')
  const clients=createClients(config),creator=config.creators[0]
  const ledgerPath=`${root}/admin-continuation-${no}.jsonl`
  let past=[]
  try{past=(await readFile(ledgerPath,'utf8')).trim().split('\n').filter(Boolean).map(JSON.parse)}catch(e){if(e.code!=='ENOENT')throw e}
  const ledger=await open(ledgerPath,'a',0o600)
  const record=async entry=>{await ledger.writeFile(JSON.stringify({...entry,observedAt:new Date().toISOString()})+'\n');await ledger.sync()}
  try{
    let missing=verifyMembers(creator,await clients.members(creator,row.groupJid))
    if(missing.some(p=>creator.admins.indexOf(p)<firstUnattempted))throw Error('previously attempted admin still unconfirmed')
    for(const p of missing){
      const index=creator.admins.indexOf(p)
      if(past.some(r=>r.index===index))throw Error('continuation previously attempted; reconcile before any further request')
      await record({accountId,no,index,stage:'REQUESTED',basis:'earlier sequential loop stopped before this member; previous attempted admins verified present'})
      await clients.promote(creator,row.groupJid,[p])
      await record({accountId,no,index,stage:'PROTOCOL_ACK'})
    }
    missing=verifyMembers(creator,await clients.members(creator,row.groupJid))
    await record({accountId,no,stage:missing.length?'UNCONFIRMED':'VERIFIED',missingIndexes:missing.map(p=>creator.admins.indexOf(p))})
    console.log(JSON.stringify({accountId,no,verified:missing.length===0}))
  }finally{await ledger.close()}
}finally{await lock.close();await unlink(`${root}/state.json.lock`)}
