from pathlib import Path
import subprocess
import xml.etree.ElementTree as E
base=Path(__file__).resolve().parent
repo=Path('/Users/daishuaishuai/IdeaProjects/armada/armada-api')
classpath=None
for report in (repo/'target/surefire-reports').glob('TEST-*.xml'):
    for prop in E.parse(report).findall('./properties/property'):
        if prop.attrib.get('name')=='java.class.path':
            classpath=prop.attrib['value']
            break
    if classpath: break
if not classpath: raise SystemExit('No cached test classpath in local surefire reports')
jdk=subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip()+'/bin/'
commands=[
    [jdk+'javac','-cp',classpath,'-d',str(base),str(base/'CreatorDeletionManagementProbe.java')],
    [jdk+'java','-cp',str(base)+':'+classpath,'CreatorDeletionManagementProbe'],
]
output=['Reproduction: python3 '+str(base/'run-probe.py'), 'Classpath: cached local surefire java.class.path; no dependency download', 'Inputs: current source XML loaded via FileSystemResource; synthetic in-memory H2 only']
for command in commands:
    result=subprocess.run(command,text=True,capture_output=True)
    output.extend([result.stdout,result.stderr,'exit_code='+str(result.returncode)])
    print(result.stdout,end='');print(result.stderr,end='')
    if result.returncode:
        (base/'result.txt').write_text('\n'.join(output));raise SystemExit(result.returncode)
(base/'result.txt').write_text('\n'.join(output))
