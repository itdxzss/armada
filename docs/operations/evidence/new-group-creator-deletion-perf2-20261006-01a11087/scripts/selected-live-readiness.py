import datetime
import json
import pathlib
import subprocess
import sys

BASE = pathlib.Path(__file__).resolve().parent.parent
helper = BASE / 'scripts/perf2-stuck-task-read.py'
query = BASE / 'queries/selected-live-readiness-identities.sql'
result = subprocess.run([sys.executable, str(helper), str(query)], capture_output=True, text=True, timeout=120)
if result.returncode:
    raise SystemExit('READ_ONLY_IDENTITY_QUERY_FAILED')
accounts = [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
expected = {1720, 1737, 2187, 1729, 1709, 2774}
if {a['accountId'] for a in accounts} != expected or any(a['tenantId'] != 1 for a in accounts):
    raise SystemExit('IDENTITY_SCOPE_MISMATCH')

remote = r'''
import concurrent.futures,datetime,hashlib,json,re,subprocess,time,urllib.request,urllib.error,urllib.parse
accounts=__ACCOUNTS__
env=json.loads(subprocess.check_output(['docker','inspect','armada-backend']))[0]['Config']['Env']
env=dict(x.split('=',1) for x in env if '=' in x)
web=env.get('PROTOCOL_WEB_BASE_URL') or env.get('ARMADA_PROTOCOL_BASE_URL') or env.get('PROTOCOL_BASE_URL')
android=env.get('PROTOCOL_ANDROID_BASE_URL')
if web!='http://172.31.8.217:8080' or android!='http://172.31.40.84:8001':raise SystemExit('PROTOCOL_TARGET_MISMATCH')
key=env.get('PROTOCOL_WEB_API_KEY') or env.get('ARMADA_PROTOCOL_API_KEY') or env.get('PROTOCOL_API_KEY')
android_key=env.get('PROTOCOL_ANDROID_API_KEY')
roles={1720:'permanent_deletion_creator',1737:'control_creator',2187:'preferred_web_manager',1729:'backup_android_manager',1709:'puller',2774:'test_member'}
def safe_text(value,a):
 if value is None:return None
 s=str(value)
 for v in [a.get('phone'),a.get('protocolAccountId')]:
  if v:s=s.replace(str(v),'[identity]')
 return re.sub(r'[+]?[0-9](?:[ .()-]*[0-9]){6,}','[redacted]',s)[:500]
def read(a):
 is_android=a['protocol']=='ANDROID'
 identity=a['phone'] if is_android else a['protocolAccountId']
 out={k:a[k] for k in ['accountId','tenantId','protocol','deviceOs','dbLoginState','dbDesiredLoginState','backendPrimaryAndroidShape']}
 out.update({'role':roles[a['accountId']],'identitySha256':hashlib.sha256(a['phone'].strip().encode()).hexdigest(),
  'queriedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'online':None,
  'method':'GET','endpointTemplate':'/ws/v1/auth/status/:phone' if is_android else '/v1/accounts/:protocolAccountId/status'})
 url=android+'/ws/v1/auth/status/'+urllib.parse.quote(identity,safe='') if is_android else web+'/v1/accounts/'+urllib.parse.quote(identity,safe='')+'/status'
 headers={'Accept':'application/json'}
 auth=android_key if is_android else key
 if auth:headers['x-api-key']=auth
 request=urllib.request.Request(url,headers=headers,method='GET')
 start=time.monotonic()
 try:
  try:
   response=urllib.request.urlopen(request,timeout=15)
  except urllib.error.HTTPError as e:response=e
  out['httpStatus']=response.code
  body=json.load(response)
  if is_android:
   out['code']=body.get('Code');out['online']=response.code==200 and body.get('Code')==0
   out['message']=safe_text(body.get('Msg'),a)
   out['primaryDeviceRuntimeVerified']=False
   out['primaryDeviceRuntimeLimit']='Status response does not expose platform or both DeviceJid.Device values; DB shape alone is insufficient.'
  else:
   out['code']=body.get('code');out['state']=safe_text(body.get('state'),a)
   out['routeIdentityMatched']=body.get('accountId')==identity
   out['online']=response.code==200 and body.get('state')=='ONLINE' and out['routeIdentityMatched']
   out['reportedAt']=body.get('reportedAt');out['lastStateSyncTime']=body.get('lastStateSyncTime')
   out['stateSource']=safe_text(body.get('stateSource'),a)
   evidence=body.get('evidence')
   if isinstance(evidence,dict):
    out['evidence']={k:evidence[k] for k in ['wsOpen','connectionField','ageMs','keepAliveIntervalMs','keepAliveJitterMinMs','keepAliveJitterMaxMs','slotReleased','updatedAt'] if k in evidence}
    if 'reason' in evidence:out['evidence']['reason']=safe_text(evidence['reason'],a)
   if body.get('message'):out['message']=safe_text(body['message'],a)
 except Exception as e:
  out['errorType']=type(e).__name__
 out['elapsedMs']=round((time.monotonic()-start)*1000)
 return out
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:results=list(executor.map(read,accounts))
print(json.dumps({'environment':'perf2','databaseSchema':'armada_perf','queryOriginHost':'3.110.124.52',
 'observedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'queries':results,
 'scope':'Read-only status checks only; no online, deletion, group, contact or message actions.'}))
'''.replace('__ACCOUNTS__', repr(accounts))
ssh = ['ssh', '-F', '/dev/null', '-i', '/Users/daishuaishuai/IdeaProjects/测试pem/armada-perf.pem',
       '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', 'ec2-user@3.110.124.52', 'python3 -']
result = subprocess.run(ssh, input=remote, capture_output=True, text=True, timeout=60)
if result.returncode:
    raise SystemExit('PROTOCOL_READ_ONLY_QUERY_FAILED')
safe = json.loads(result.stdout)
(BASE / 'runtime/selected-live-readiness.json').write_text(json.dumps(safe, ensure_ascii=False, indent=2)+'\n')
print(json.dumps(safe, ensure_ascii=False, indent=2))
