import subprocess,json,hashlib,hmac,secrets,concurrent.futures,pathlib,datetime

ROOT=pathlib.Path('/Users/daishuaishuai/IdeaProjects')
OUT=ROOT/'armada/docs/operations/evidence/new-group-creator-deletion-perf2-20261006-01a11087/runtime'
nonce=secrets.token_hex(32)
def read(target):
    kind,host,key,container,secret=target
    script='''import subprocess,json,hashlib,hmac,datetime,re,pathlib
kind=KIND
container=CONTAINER
d=json.loads(subprocess.check_output(['docker','inspect',container]))[0]
env=dict(x.split('=',1) for x in d['Config']['Env'] if '=' in x)
s=env.get(SECRET,'')
o={'kind':kind,'observedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'container':container,'image':d['Image'],'created':d['Created'],'startedAt':d['State']['StartedAt'],'status':d['State']['Status'],'restartCount':d['RestartCount'],'composeProject':d['Config'].get('Labels',{}).get('com.docker.compose.project'),'secretPresent':bool(s),'secretMin32Bytes':len(s.encode())>=32,'_compare':hmac.new(s.encode(),NONCE.encode(),hashlib.sha256).hexdigest() if s else None}
def run(cmd):
 p=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True); return p.stdout.strip() if p.returncode==0 else None
if kind=='backend':
 u=env.get('DB_URL',''); o['databaseSchema']=re.search(r'/([^/?]+)(?:\\?|$)',u.split('://',1)[-1]).group(1) if u else None
 o['androidBaseUrl']=env.get('ANDROID_BASE_URL')
 o['runtimeArtifactSha256']=run(['docker','exec',container,'sha256sum','/app/app.jar'])
 o['frontendIndexSha256']=run(['docker','exec','armada-nginx','sha256sum','/usr/share/nginx/html/saas/index.html'])
 o['queryTools']={x:run(['sh','-c','command -v '+x]) for x in ['mysql','mariadb','go','python3','docker']}
else:
 o['runtimeArtifactSha256']=run(['docker','exec',container,'sha256sum','/app/whatsapp-server'])
 cfgs=list(pathlib.Path('/home/ec2-user/whatsapp-android-zhuan/deploy/configs').glob('*'))
 o['configFiles']=[p.name for p in cfgs if p.is_file()]
 for p in cfgs:
  if p.is_file():
   text=p.read_text(errors='replace')
   names=re.findall(r'(?i)(?:dbname|database|db_name|schema|name)\\s*[:=]\\s*["\\\']?([A-Za-z0-9_]+)',text)
   if names:o.setdefault('databaseNamesInConfig',{})[p.name]=[x for x in names if 'whatsapp' in x or 'armada' in x]
print(json.dumps(o))
'''
    for token,value in [('KIND',kind),('CONTAINER',container),('SECRET',secret),('NONCE',nonce)]:script=script.replace(token,repr(value))
    p=subprocess.run(['ssh','-i',str(ROOT/'测试pem'/key),'-o','BatchMode=yes','-o','ConnectTimeout=8','ec2-user@'+host,'python3 -'],input=script,text=True,capture_output=True,timeout=45)
    if p.returncode: raise RuntimeError(kind+' SSH preflight failed: '+str(p.returncode))
    return json.loads(p.stdout)
targets=[('backend','3.110.124.52','armada-perf.pem','armada-backend','ARMADA_PROTOCOL_ACCOUNT_DELETION_TASK_SECRET'),('android','3.111.245.182','android-protocol.pem','whatsapp-android-zhuan','WA_ACCOUNT_DELETE_TASK_SECRET')]
with concurrent.futures.ThreadPoolExecutor(2) as pool:results=list(pool.map(read,targets))
match=all(r['secretPresent'] for r in results) and results[0]['_compare']==results[1]['_compare']
for r in results:r.pop('_compare',None)
result={'observedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'perf2','secretMatch':match,'runtime':results}
(OUT/'live-preflight.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps(result,ensure_ascii=False,indent=2))
