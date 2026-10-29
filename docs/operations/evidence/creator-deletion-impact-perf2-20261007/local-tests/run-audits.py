#!/usr/bin/env python3
"""Run read-only source audits in an isolated /private/tmp build directory.
Requires existing Java 17 and Armada target classes/test classes + Surefire XML.
Does not run Maven, install dependencies, access network, or write repository files.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--repo', type=Path, default=Path('/Users/daishuaishuai/IdeaProjects/armada'))
parser.add_argument('--output', type=Path, help='Optional new/empty output directory under /private/tmp')
args = parser.parse_args()
repo = args.repo.resolve()
module = repo / 'armada-api'
sources_dir = Path(__file__).resolve().parent
output = args.output.resolve() if args.output else Path(tempfile.mkdtemp(prefix='creator-backend-audits-', dir='/private/tmp'))
if not output.is_relative_to(Path('/private/tmp')):
    raise SystemExit('--output must be under /private/tmp')
output.mkdir(parents=True, exist_ok=True)
classes = output / 'classes'
classes.mkdir(exist_ok=True)
reports = module / 'target/surefire-reports'
preferred = reports / 'TEST-com.armada.account.service.AccountCreatorDeletionServiceInMemoryTest.xml'
xmls = [preferred] if preferred.exists() else sorted(reports.glob('TEST-*.xml'))
deps = None
for xml in xmls:
    values = {x.get('name'): x.get('value') for x in ET.parse(xml).getroot().findall('./properties/property')}
    if values.get('java.class.path'):
        deps = values['java.class.path']
        print('Classpath source:', xml)
        break
if not deps:
    raise SystemExit('Existing Surefire java.class.path not found; no dependencies installed or build attempted')
java_home = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
cp = ':'.join([str(module/'src/main/resources'), str(classes), str(module/'target/test-classes'), str(module/'target/classes'), deps])
(output/'classpath.txt').write_text(cp)
sources = [sources_dir/'BackendAudit.java', sources_dir/'OutboxMixedTransactionAudit.java']
# Compile audited services from current worktree; supporting compiled project/test classes are read only.
sources += [module/'src/main/java/com/armada/account/service/impl/AccountCreatorDeletionServiceImpl.java', module/'src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java', module/'src/main/java/com/armada/platform/protocol/exception/ProtocolAccountCommandRejectedException.java']
subprocess.run([str(java_home/'bin/javac'), '-cp', cp, '-d', str(classes), *map(str, sources)], check=True, cwd=repo)
total = 0
for main, logname, expected in [('BackendAudit', 'result.log', 15), ('OutboxMixedTransactionAudit', 'outbox-mixed-result.log', 21)]:
    proc = subprocess.run([str(java_home/'bin/java'), '-cp', cp, 'com.armada.account.service.'+main], cwd=repo, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (output/logname).write_text(proc.stdout)
    print(proc.stdout, end='')
    if proc.returncode:
        raise SystemExit(proc.returncode)
    actual = sum(line.startswith('PASS ') for line in proc.stdout.splitlines())
    if actual != expected or 'AUDIT_COMPLETED:' not in proc.stdout:
        raise SystemExit(f'Unexpected assertions in {main}: expected {expected}, saw {actual}')
    total += actual
print(f'COMPLETED: {total} assertions (15 candidate/dependency + 21 mixed-outbox transaction). Output: {output}')
