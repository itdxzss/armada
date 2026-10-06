"""用 V211/V212 与既有模型离线生成文档，禁止连接共享数据库。"""
from pathlib import Path
import csv
import os
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
wiki = root / '.harness/wiki/数据模型.md'
content = wiki.read_text()
sql = (root / 'armada-api/src/main/resources/db/migration/V211__new_group_creator_deletion.sql').read_text()
sections = list(re.finditer(r'### pull_task（.*?(?=\n### |\Z)', content, re.S))
current = sections[-1]
columns, indexes = [], []
for line in current.group().splitlines():
    fields = [s.strip() for s in line.split('|')[1:-1]]
    if len(fields) == 5 and fields[0] != 'Column' and not fields[0].startswith('-'):
        name, typ, nullable, default, description = fields
        if name == 'is_creator_delete_after_takeover':
            continue
        columns.append(['armada', 'pull_task', len(columns) + 1, name, typ, nullable,
                        '__NULL__' if default in ('-', 'NULL', 'AUTO_INCREMENT') else default,
                        '', 'auto_increment' if default == 'AUTO_INCREMENT' else '', description])
    elif len(fields) == 3 and fields[2] in ('INDEX', 'UNIQUE', 'PRIMARY'):
        name, names, kind = fields
        for seq, col in enumerate(names.split(','), 1):
            indexes.append(['armada','pull_task',name,'1' if kind == 'INDEX' else '0',seq,col.strip(),'BTREE'])
columns.append(['armada','pull_task',len(columns)+1,'is_creator_delete_after_takeover','tinyint(1)','NO','0','','',
                '管理员接管后永久注销建群账号:0关闭 1开启;启动后冻结'])
tables=[['armada','pull_task','拉群任务配置']]
# 复用生成器更新账号身份派生索引，保留原模型其他列与注释。
account = re.search(r'### account（.*?(?=\n### |\Z)', content, re.S)
account_pos = 0
tables.append(['armada','account','账号身份主表'])
for line in account.group().splitlines():
    fields = [item.strip() for item in line.split('|')[1:-1]]
    if len(fields) == 5 and fields[0] != 'Column' and not fields[0].startswith('-'):
        name, typ, nullable, default, description = fields
        if name == 'creator_deletion_identity_phone': continue
        account_pos += 1
        columns.append(['armada','account',account_pos,name,typ,nullable,
                        '__NULL__' if default in ('-', 'NULL', 'AUTO_INCREMENT') else default,
                        '', 'auto_increment' if default == 'AUTO_INCREMENT' else '', description])
    elif len(fields) == 3 and fields[2] in ('INDEX','UNIQUE','PRIMARY'):
        name, names, kind = fields
        if name == 'idx_account_creator_deletion_identity': continue
        for seq, col in enumerate(names.split(','),1):
            indexes.append(['armada','account',name,'1' if kind == 'INDEX' else '0',seq,col.strip(),'BTREE'])
columns.append(['armada','account',account_pos+1,'creator_deletion_identity_phone','varchar(32)',
                'YES','（生成列）','','STORED GENERATED','永久注销使用的规范化身份索引，不包含授权材料'])
for seq, col in enumerate(['creator_deletion_identity_phone','id'],1):
    indexes.append(['armada','account','idx_account_creator_deletion_identity','1',seq,col,'BTREE'])

for match in re.finditer(r'CREATE TABLE IF NOT EXISTS (\w+) \((.*?)\) ENGINE=', sql, re.S):
    table, body = match.groups()
    tables.append(['armada',table,'建群账号一次性占用生命周期' if table.startswith('account_') else '建群账号注销及实时验证账本'])
    pos=0
    for line in body.splitlines():
        field=re.match(r"\s*(\w+)\s+(BIGINT|INT|TINYINT|TEXT|(?:VAR)?CHAR\(\d+\))(.*?)COMMENT '([^']*)'",line)
        if field:
            name, typ, attrs, comment=field.groups();pos+=1
            default=re.search(r'DEFAULT\s+(\w+)',attrs)
            columns.append(['armada',table,pos,name,typ.lower(),'NO' if 'NOT NULL' in attrs else 'YES',
                            default.group(1) if default else '__NULL__','','auto_increment' if 'AUTO_INCREMENT' in attrs else '',comment])
            if 'PRIMARY KEY' in attrs: indexes.append(['armada',table,'PRIMARY','0',1,name,'BTREE'])
        index=re.match(r'\s*(UNIQUE KEY|KEY)\s+(\w+)\s*\(([^)]*)\)',line)
        if index:
            kind,name,cols=index.groups()
            for seq,col in enumerate(cols.split(','),1): indexes.append(['armada',table,name,'0' if kind=='UNIQUE KEY' else '1',seq,col.strip(),'BTREE'])
with tempfile.TemporaryDirectory(prefix='armada-creator-deletion-model-') as temp:
    env=os.environ.copy()
    for key,rows in [('COLS',columns),('IDX',indexes),('TBL',tables)]:
        path=Path(temp)/(key+'.tsv')
        with path.open('w') as stream:csv.writer(stream,delimiter='\t',lineterminator='\n').writerows(rows)
        env['ARMADA_MODEL_'+key]=str(path)
    output=Path(temp)/'model.md';env['ARMADA_MODEL_OUT']=str(output)
    subprocess.run(['python3',str(root/'.harness/wiki/gen_datamodel.py')],env=env,check=True)
    generated=output.read_text()
    task=re.search(r'### pull_task（.*?(?=\n### |\Z)',generated,re.S).group().rstrip()+'\n\n'
    content=content[:current.start()]+task+content[current.end():]
    for table in ['account','account_creator_deletion','pull_task_creator_deletion']:
        pattern=rf'### {table}（.*?(?=\n### |\Z)'
        section=re.search(pattern,generated,re.S).group().rstrip()+'\n\n'
        content=re.sub(pattern,lambda _: section,content,flags=re.S) if re.search(pattern,content,re.S) else content.rstrip()+'\n\n'+section
    wiki.write_text(content.rstrip()+"\n")
