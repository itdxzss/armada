from pathlib import Path
import subprocess
import xml.etree.ElementTree as E
base=Path(__file__).resolve().parent
repo=Path('/Users/daishuaishuai/IdeaProjects/armada/armada-api')
classpath=None
for report in (repo/'target/surefire-reports').glob('TEST-*.xml'):
    for prop in E.parse(report).findall('./properties/property'):
        if prop.attrib.get('name')=='java.class.path': classpath=prop.attrib['value'];break
    if classpath: break
if not classpath: raise SystemExit('No cached test classpath')
jdk=subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip()+'/bin/'
sources=[
'src/main/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImpl.java',
'src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java',
'src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java',
'src/main/java/com/armada/platform/protocol/exception/ProtocolAccountCommandRejectedException.java',
]
agent=next(Path('/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent').glob('*/byte-buddy-agent-*.jar'))
commands=[
[jdk+'javac','-cp',classpath,'-d',str(base)]+[str(repo/s) for s in sources]+[str(base/'LegacyBatchOfflineProbe.java')],
[jdk+'java','-javaagent:'+str(agent),'-cp',str(base)+':'+str(repo/'src/main/resources')+':'+classpath,'com.armada.account.service.impl.LegacyBatchOfflineProbe']]
output=['Reproduction: python3 '+str(base/'run-probe.py'),'Current production source compiled: '+', '.join(sources), 'Runtime source resources precede target classes: true; actual MyBatis Mapper + tenant plugin + Spring DataSourceTransactionManager; synthetic H2 only']
for cmd in commands:
    result=subprocess.run(cmd,text=True,capture_output=True)
    output.extend([result.stdout,result.stderr,'exit_code='+str(result.returncode)])
    print(result.stdout,end='');print(result.stderr,end='')
    if result.returncode: (base/'result.txt').write_text('\n'.join(output));raise SystemExit(result.returncode)
(base/'result.txt').write_text('\n'.join(output))
