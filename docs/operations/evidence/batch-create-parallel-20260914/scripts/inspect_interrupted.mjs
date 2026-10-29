import {readFile} from 'node:fs/promises'
import {normalizeConfig,createClients,verifyMembers} from './batch-create-groups.mjs'
for (const input of process.argv.slice(2)) {
  const accountId=Number(input)
  const root=`/tmp/armada-batch-parallel-20260914/${accountId}`
  const config=normalizeConfig(JSON.parse(await readFile(`${root}/config.json`,'utf8')))
  const state=JSON.parse(await readFile(`${root}/state.json`,'utf8'))
  const clients=createClients(config),creator=config.creators[0]
  for (const row of state.rows.filter(r=>r.error && r.groupJid)) {
    try {
      const members=await clients.members(creator,row.groupJid)
      const missing=verifyMembers(creator,members)
      console.log(JSON.stringify({accountId,no:row.no,groupJid:row.groupJid,stage:row.stage,
        memberCount:members.length,missingAdminIndexes:missing.map(p=>creator.admins.indexOf(p))}))
    } catch(error) {console.log(JSON.stringify({accountId,no:row.no,error:error.message}))}
  }
}
