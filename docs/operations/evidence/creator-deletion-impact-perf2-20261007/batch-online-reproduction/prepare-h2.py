from pathlib import Path
import re,xml.etree.ElementTree as ET
root=Path('/Users/daishuaishuai/IdeaProjects/armada/armada-api')
out=Path('/private/tmp/armada-batch-proxy-audit')
# Refresh dependency classpath from local cached Surefire report, never download dependencies.
r=ET.parse(root/'target/surefire-reports/TEST-com.armada.account.service.impl.AccountBatchLifecycleTransactionH2Test.xml').getroot()
(out/'classpath.txt').write_text(next(x.attrib['value'] for x in r.find('properties') if x.attrib.get('name')=='java.class.path'))
original=(root/'src/main/resources/mapper/account/AccountStateMapper.xml').read_text()
fields={'truth_ip':'truthIp','proxy_country':'proxyCountry','proxy_source':'proxySource','updated_at':'updatedAt'}
parts=['  <update id="updateProxySnapshotsInternal">','    UPDATE account_state SET']
for ix,(column,prop) in enumerate(fields.items()):
    parts.append(f'      {column} = CASE account_id <foreach collection="rows" item="row"> WHEN #{{row.accountId}} THEN #{{row.{prop}}} </foreach> END'+(',' if ix<3 else ''))
parts.extend(['    WHERE account_id IN <foreach collection="rows" item="row" open="(" separator="," close=")">#{row.accountId}</foreach>','  </update>'])
pattern=r'  <update id="updateProxySnapshotsInternal">.*?  </update>'
adapted,n=re.subn(pattern,'\n'.join(parts),original,flags=re.S)
assert n==1
res=out/'resources/audit';res.mkdir(parents=True,exist_ok=True)
(res/'AccountStateMapper.xml').write_text(adapted)
(out/'snapshot-mapper-original.xml').write_text(re.search(pattern,original,re.S).group(0))
(out/'snapshot-mapper-h2.xml').write_text('\n'.join(parts))
