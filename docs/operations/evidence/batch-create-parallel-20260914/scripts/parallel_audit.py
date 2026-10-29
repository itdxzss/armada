import importlib.util
import json
import re
from datetime import datetime, timezone
from pathlib import Path
spec=importlib.util.spec_from_file_location('support','/tmp/armada-batch-support-android-20260914.py')
s=importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)
root=Path('/tmp/armada-batch-parallel-20260914')
selection=json.loads((root/'selection.json').read_text())
groups=[]
for account_id in selection['creatorIds']:
    path=root/str(account_id)/'state.json'
    if path.exists():
        for row in json.loads(path.read_text())['rows']:
            if row.get('groupJid'):
                groups.append({**row,'creatorAccountId':account_id})
assert all(re.fullmatch(r'[0-9]+(?:-[0-9]+)?@g\.us',row['groupJid']) for row in groups)
admin_ids=','.join(map(str,selection['adminIds']))
joined=','.join("'"+row['groupJid']+"'" for row in groups) or "'0@g.us'"
data=s.records(f'''SELECT g.group_jid,l.id,p.member_count AS reported_member_count,p.banned,
  CONCAT('https://chat.whatsapp.com/',i.invite_code) AS invite_url,
  (SELECT COUNT(*) FROM wa_group_participant m WHERE m.tenant_id=g.tenant_id AND m.group_id=g.id AND m.presence_status=1) AS member_count,
  (SELECT COUNT(*) FROM wa_group_participant m WHERE m.tenant_id=g.tenant_id AND m.group_id=g.id AND m.presence_status=1 AND m.role IN (2,3)) AS admin_count,
  (SELECT COUNT(DISTINCT a.id) FROM wa_group_participant m JOIN account a ON a.tenant_id=m.tenant_id
     AND (a.ws_phone=m.phone OR CONCAT(a.ws_phone,'@s.whatsapp.net')=m.pn_jid)
   WHERE m.tenant_id=g.tenant_id AND m.group_id=g.id AND m.presence_status=1 AND m.role IN (2,3)
     AND a.id IN ({admin_ids}) AND a.deleted_at IS NULL AND a.is_active=1) AS matched_admins
  FROM wa_group g JOIN group_link l ON l.tenant_id=g.tenant_id AND l.group_id=g.id AND l.deleted_at IS NULL
  JOIN wa_group_profile p ON p.tenant_id=g.tenant_id AND p.group_id=g.id
  LEFT JOIN wa_group_invite i ON i.tenant_id=p.tenant_id AND i.id=p.current_invite_id AND i.deleted_at IS NULL
  WHERE g.tenant_id=1 AND g.group_jid IN ({joined}) AND g.deleted_at IS NULL;''')
assert len({r['group_jid'] for r in data})==len(data),'duplicate control group'
observed={r['group_jid']:r for r in data}
results=[]
for row in groups:
    control=observed.get(row['groupJid'])
    number=lambda key:None if not control or control[key]=='NULL' else int(control[key])
    result={'creatorAccountId':row['creatorAccountId'],'number':row['no'],'subject':row['subject'],'groupJid':row['groupJid'],
            'stage':row['stage'],'protocolVerifiedAt':row.get('protocolVerifiedAt'),'controlGroupId':number('id'),
            'memberCount':number('member_count'),'adminCount':number('admin_count'),
            'specifiedAdminsPresent':number('matched_admins')==3,
            'inviteMatches':bool(control and control['invite_url']==row.get('inviteUrl')),
            'banned':None if number('banned') is None else number('banned')!=0,'inviteUrl':row.get('inviteUrl')}
    result['matched']=bool(result['protocolVerifiedAt'] and result['memberCount']==15 and result['specifiedAdminsPresent']
                           and result['inviteMatches'] and result['banned'] is False)
    results.append(result)
print(json.dumps({'observedAt':datetime.now(timezone.utc).isoformat(),'target':500,'created':len(groups),
                  'registered':len(data),'matched':sum(r['matched'] for r in results),'rows':results},indent=2))
