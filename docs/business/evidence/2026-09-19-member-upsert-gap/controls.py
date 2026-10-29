import stress, json,re
from probe import BASE
full=stress.UPSERT
cols=re.search(r'INSERT INTO wa_group_participant\s*\((.*?)\)\s*VALUES',full,re.S).group(1).split(',')
p=full.index('VALUES')+len('VALUES');a=full.index('(',p);depth=0;quote=False
parts=[];start=a+1
for i in range(a,len(full)):
 c=full[i]
 if c=="'":quote=not quote
 if quote:continue
 if c=='(':depth+=1
 elif c==')':
  depth-=1
  if depth==0:parts.append(full[start:i].strip());break
 elif c==',' and depth==1:parts.append(full[start:i].strip());start=i+1
values={c.strip():v for c,v in zip(cols,parts)};assert len(cols)==len(parts)
sets=full.split('ON DUPLICATE KEY UPDATE',1)[1].strip().removesuffix(';')
sets=re.sub(r'VALUES\((\w+)\)',lambda m:values[m.group(1)],sets)
primary='UPDATE wa_group_participant SET '+sets+' WHERE tenant_id=1 AND id=11;'
(BASE/'primary-update-control.sql').write_text(primary)
no_lid=full.replace('lid_jid = COALESCE(lid_jid, VALUES(lid_jid))','lid_jid = lid_jid')
assert no_lid!=full
minimal="INSERT INTO wa_group_participant(tenant_id,group_id,pn_jid,lid_jid,phone,created_at,updated_at) VALUES(1,20,NULL,'213@lid','10001',1,1) ON DUPLICATE KEY UPDATE lid_jid=COALESCE(lid_jid,VALUES(lid_jid));"
(BASE/'minimal-upsert.sql').write_text(minimal)
results=[]
for label,sql,iso in [('no_lid_assignment',no_lid,'REPEATABLE READ'),('primary_update',primary,'REPEATABLE READ'),('primary_update',primary,'READ COMMITTED'),('minimal_lid_fill',minimal,'REPEATABLE READ')]:
 print('CONTROL',label,iso,flush=True)
 stress.UPSERT=sql
 stress.DB_PREFIX=label+'_'
 d=stress.two('pn',iso);d['variant']=label;results.append(d)
 (BASE/'control-summary.json').write_text(json.dumps(results,indent=2))
