import json
from datetime import datetime, timezone
from pathlib import Path
root=Path('/tmp/armada-batch-parallel-20260914')
selection=json.loads((root/'selection.json').read_text())
rows=[]
for account_id in selection['creatorIds']:
    path=root/str(account_id)/'state.json'
    state=json.loads(path.read_text()) if path.exists() else {'rows':[]}
    groups=state['rows']
    row={'accountId':account_id,'created':sum(bool(x.get('groupJid')) for x in groups),
         'protocolVerified':sum(bool(x.get('protocolVerifiedAt')) for x in groups),
         'done':sum(x['stage']=='DONE' for x in groups),'running':(path.parent/'state.json.lock').exists()}
    stopped=[x for x in groups if x.get('error') or x['stage']=='CREATE_REJECTED']
    if stopped:row['issue']={k:stopped[-1].get(k) for k in ['no','stage','error','rejection','groupJid']}
    rows.append(row)
print(json.dumps({'observedAt':datetime.now(timezone.utc).isoformat(),'target':500,
                  'created':sum(x['created'] for x in rows),'protocolVerified':sum(x['protocolVerified'] for x in rows),
                  'done':sum(x['done'] for x in rows),'running':sum(x['running'] for x in rows),'accounts':rows},indent=2))
