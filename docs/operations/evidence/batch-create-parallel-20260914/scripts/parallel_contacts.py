import importlib.util
import json
import os
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

spec = importlib.util.spec_from_file_location('support', '/tmp/armada-batch-support-android-20260914.py')
s = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)
root = Path('/tmp/armada-batch-parallel-20260914')
selection = json.loads((root / 'selection.json').read_text())
creators, members = selection['creatorIds'], selection['memberIds']
s.IDS = creators + members
accounts = s.accounts(require_online=False)
ledger = root / 'contacts.jsonl'
past = [json.loads(line) for line in ledger.read_text().splitlines()] if ledger.exists() else []
lock = threading.Lock()

def record(row):
    with lock:
        fd = os.open(ledger, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        with os.fdopen(fd, 'a') as f:
            f.write(json.dumps(row) + '\n')
            f.flush()
            os.fsync(f.fileno())
        print(json.dumps(row), flush=True)

def prepare(actor):
    targets = members if actor in creators else creators
    previous = [r for r in past if r['actor'] == actor]
    if previous:
        assert previous[-1]['targets'] == targets, 'contact target plan changed'
        if previous[-1]['status'] == 'SUCCESS':
            return True
        assert previous[-1]['status'] == 'NOT_STARTED', 'unknown contact result; cannot replay'
    phone = accounts[actor]['ws_phone'].removesuffix('@s.whatsapp.net')
    ready = s.request('/ws/v1/auth/status/' + phone)
    base = {'actor': actor, 'targets': targets, 'at': time.time()}
    if ready.get('Code') != 0:
        record({**base, 'status': 'NOT_STARTED', 'reason': 'actor_offline'})
        return False
    record({**base, 'status': 'REQUESTED'})
    body = {'Numbers': [accounts[i]['ws_phone'].removesuffix('@s.whatsapp.net').lstrip('+') for i in targets]}
    request = urllib.request.Request(s.db.env['PROTOCOL_ANDROID_BASE_URL'].rstrip('/') + '/ws/v1/contacts/add/' + phone,
        data=json.dumps(body).encode(), headers={'content-type': 'application/json', 'x-api-key': s.db.env.get('PROTOCOL_ANDROID_API_KEY', '')})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            data = json.load(response)
        assert data.get('Code') == 0, 'nonzero contact response'
        assert isinstance(data.get('Data'), list) and len(data['Data']) == len(targets), 'partial contact response'
    except Exception as error:
        record({**base, 'status': 'UNCONFIRMED', 'errorType': type(error).__name__})
        return False
    record({**base, 'status': 'SUCCESS', 'businessCode': data['Code'], 'returnedContacts': len(data['Data'])})
    return True

results = list(ThreadPoolExecutor(max_workers=10).map(prepare, creators + members))
print(json.dumps({'contactActors': len(results), 'successfulActors': sum(results), 'confirmedDirections':
    sum((len(members) if actor in creators else len(creators)) for actor, ok in zip(creators + members, results) if ok)}), flush=True)
raise SystemExit(0 if all(results) else 1)
