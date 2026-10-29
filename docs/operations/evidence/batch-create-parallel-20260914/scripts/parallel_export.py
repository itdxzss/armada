import csv
import io
import json
import os
import subprocess
import tarfile
from pathlib import Path

root = Path('/tmp/armada-batch-parallel-20260914')
out = root / 'export'
out.mkdir(mode=0o700, exist_ok=True)
selection = json.loads((root / 'selection.json').read_text())
audit = json.loads(subprocess.check_output(['python3', str(root / 'parallel_audit.py')], text=True))
status = json.loads(subprocess.check_output(['python3', str(root / 'parallel_status.py')], text=True))
assert not any((root / str(a) / 'state.json.lock').exists() for a in selection['creatorIds']), 'workers still hold account locks'
assert len({r['groupJid'] for r in audit['rows']}) == audit['created']

def write(name, text):
    path = out / name
    path.write_text(text)
    path.chmod(0o600)

write('audit.json', json.dumps(audit, indent=2, ensure_ascii=False) + '\n')
write('status.json', json.dumps(status, indent=2, ensure_ascii=False) + '\n')
verified = [r for r in audit['rows'] if r['protocolVerifiedAt'] and r['inviteUrl']]
ready = [r for r in verified if r['stage'] == 'DONE' and r['matched']]
for name, rows in [('protocol-group-links.txt', verified), ('group-links.txt', ready)]:
    assert len({r['inviteUrl'] for r in rows}) == len(rows), 'duplicate invite URL'
    write(name, ''.join(r['inviteUrl'] + '\n' for r in rows))
buf = io.StringIO()
columns = ['creatorAccountId', 'number', 'subject', 'groupJid', 'inviteUrl', 'stage',
           'controlGroupId', 'memberCount', 'specifiedAdminsPresent', 'inviteMatches', 'banned',
           'protocolVerifiedAt', 'matched']
writer = csv.DictWriter(buf, fieldnames=columns, extrasaction='ignore')
writer.writeheader()
writer.writerows(audit['rows'])
write('groups.csv', buf.getvalue())
summary = {'observedAt': audit['observedAt'], 'target': 500, 'created': audit['created'],
           'protocolVerified': len(verified), 'controlRegistered': audit['registered'],
           'controlMatched': audit['matched'], 'readyLinks': len(ready),
           'pausedFor429': [r['accountId'] for r in status['accounts'] if r.get('issue', {}).get('stage') == 'CREATE_REJECTED']}
write('summary.json', json.dumps(summary, indent=2, ensure_ascii=False) + '\n')
archive = root / 'evidence.tar.gz'
with tarfile.open(archive, 'w:gz') as tar:
    for name in ['selection.json', 'contacts.jsonl']:
        tar.add(root / name, arcname=name)
    for p in out.iterdir():
        tar.add(p, arcname=p.name)
    for account in selection['creatorIds']:
        folder = root / str(account)
        for name in ['state.json', 'state.json.csv', 'state.json.links.txt', 'run.log']:
            tar.add(folder / name, arcname=f'accounts/{account}/{name}')
        for p in folder.glob('admin-continuation-*.jsonl'):
            tar.add(p, arcname=f'accounts/{account}/{p.name}')
archive.chmod(0o600)
print(json.dumps(summary, ensure_ascii=False))
