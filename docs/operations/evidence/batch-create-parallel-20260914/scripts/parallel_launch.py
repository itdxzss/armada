import importlib.util
import json
import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

spec = importlib.util.spec_from_file_location('support', '/tmp/armada-batch-support-android-20260914.py')
s = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)
root = Path('/tmp/armada-batch-parallel-20260914')
selection = json.loads((root / 'selection.json').read_text())
mode = sys.argv[1]
assert mode in ('fill','reconcile')
chosen = [int(v) for v in sys.argv[2:]] or selection['creatorIds']
assert len(chosen)==len(set(chosen)) and set(chosen)<=set(selection['creatorIds'])
s.IDS = selection['creatorIds'] + selection['memberIds']
accounts = s.accounts(require_online=False)
past = [json.loads(line) for line in (root/'contacts.jsonl').read_text().splitlines()]
for actor in s.IDS:
    entries = [r for r in past if r['actor']==actor]
    assert entries and entries[-1]['status']=='SUCCESS', 'mutual contacts incomplete'
env = {**os.environ,'PROTOCOL_API_KEY':s.db.env.get('PROTOCOL_ANDROID_API_KEY','')}

def run(account_id):
    status = s.request('/ws/v1/auth/status/'+accounts[account_id]['ws_phone'].removesuffix('@s.whatsapp.net'))
    if status.get('Code') != 0:
        return {'accountId':account_id,'started':False,'reason':'creator_offline'}
    fd=os.open(root/str(account_id)/'run.log',os.O_WRONLY|os.O_APPEND|os.O_CREAT,0o600)
    with os.fdopen(fd,'a') as logfile:
        process=subprocess.Popen(['node',str(root/'runner.mjs'),str(account_id),mode],env=env,
            stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        print(json.dumps({'accountId':account_id,'started':True,'pid':process.pid}),flush=True)
        for line in process.stdout:
            logfile.write(line);logfile.flush()
            print(line,end='',flush=True)
        code=process.wait()
    return {'accountId':account_id,'exitCode':code}

with ThreadPoolExecutor(max_workers=10) as pool:
    futures=[pool.submit(run,i) for i in chosen]
    for future in as_completed(futures):
        try: result=future.result()
        except Exception as error: result={'launcherErrorType':type(error).__name__}
        print(json.dumps({'workerResult':result}),flush=True)
